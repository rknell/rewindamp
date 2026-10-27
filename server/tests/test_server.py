import contextlib
import datetime as dt
import email.utils
import http.client
import io
import json
from pathlib import Path
import socket
import sys
import tarfile
import tempfile
import threading
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from server import Archive, Server, parse_feed, public_address, years_ago

NOW=dt.datetime(2026,10,27,tzinfo=dt.timezone.utc).timestamp()
def rss(*items):
    result='<rss version="2.0"><channel><title>Fixture podcast</title>'
    for guid,published in items:
        date='' if published is None else '<pubDate>'+email.utils.format_datetime(dt.datetime.fromtimestamp(published,dt.timezone.utc))+'</pubDate>'
        result+=f'<item><title>Episode {guid}</title><guid>{guid}</guid>{date}<enclosure url="https://media.example.com/{guid}.mp3" type="audio/mpeg"/></item>'
    return (result+'</channel></rss>').encode()

class Response(io.BytesIO):
    def __init__(self,data=b'audio',headers=None):super().__init__(data);self.headers=headers or {'Content-Type':'audio/mpeg','Content-Length':str(len(data))}
    def getheader(self,key):return self.headers.get(key)

class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.now=NOW
        self.archive=Archive(Path(self.tmp.name)/'config',Path(self.tmp.name)/'media',clock=lambda:self.now)
        self.addCleanup(lambda:self.archive.close())
        self.public=patch('server.public_address',return_value=(None,None));self.public.start();self.addCleanup(self.public.stop)
    def feed(self,mode='all',years=0):return self.archive.subscribe('Fixture','https://example.com/feed',mode,years)
    def episode(self):
        feed=self.feed();self.archive.import_feed(feed,rss(('stable',NOW-100)));return self.archive.episodes()[0]['id']
    def event(self,id,event_id='event-00001',**values):
        return self.archive.progress(id,dict(event_id=event_id,**{'observed':self.now,**values}))
    def test_rolling_years_and_missing_dates(self):
        f=self.feed('years',2);self.archive.import_feed(f,rss(('recent',NOW-100),('old',years_ago(NOW,2)-1),('edge',years_ago(NOW,2)),('undated',None),('future',NOW+100)))
        rows={r['title']:r for r in self.archive.episodes()}
        self.assertEqual({k for k,v in rows.items() if v['eligible']},{'Episode recent','Episode edge'})
        self.now+=86400;self.archive.import_feed(f,rss(('recent',NOW-100)))
        self.assertFalse(next(e for e in self.archive.episodes() if e['title']=='Episode edge')['eligible'])
    def test_leap_day_cutoff(self):
        leap=dt.datetime(2024,2,29,tzinfo=dt.timezone.utc).timestamp()
        self.assertEqual(dt.datetime.fromtimestamp(years_ago(leap,1),dt.timezone.utc).date(),dt.date(2023,2,28))
    def test_future_baseline_survives_refresh_restart_and_backfill(self):
        f=self.feed('future');self.archive.import_feed(f,rss(('old',NOW-100)))
        self.assertFalse(self.archive.episodes()[0]['eligible'])
        self.now+=200;self.archive.import_feed(f,rss(('old',NOW-100),('new',NOW+100),('backfill',NOW-50)))
        self.assertEqual([e['title'] for e in self.archive.episodes() if e['eligible']],['Episode new'])
        self.archive.seed=lambda:None
        with self.archive.lock,self.archive.db:self.archive.db.execute("UPDATE feeds SET title='Changed'")
        self.archive.import_feed(f,rss(('new',NOW+100)))
        self.assertEqual(len(self.archive.episodes()),3)
        self.assertEqual(self.archive.rows('SELECT baseline FROM feeds')[0]['baseline'],NOW)
    def test_failed_first_fetch_does_not_baseline(self):
        f=self.feed('future')
        @contextlib.contextmanager
        def broken(url):raise OSError('offline');yield
        self.archive.fetch=broken;self.assertIsNone(self.archive.refresh(f))
        self.assertIsNone(self.archive.rows('SELECT baseline FROM feeds')[0]['baseline'])
        self.now+=500;self.archive.import_feed(f,rss(('newduringoutage',NOW+100)))
        self.assertFalse(self.archive.episodes()[0]['eligible'])
    def test_mode_change_reuses_history_and_identity(self):
        f=self.feed('future');self.archive.import_feed(f,rss(('old',NOW-10)));id=self.archive.episodes()[0]['id'];self.event(id,action='played')
        self.feed('all');self.assertEqual(self.archive.episodes()[0]['id'],id);self.assertTrue(self.archive.episodes()[0]['eligible']);self.assertEqual(self.archive.episodes()[0]['state'],'played')
    def test_guid_and_enclosure_aliases_keep_history(self):
        id=self.episode();self.event(id,action='played');f=self.archive.rows('SELECT id FROM feeds')[0]['id']
        self.archive.import_feed(f,rss(('changed',NOW-100)).replace(b'changed.mp3',b'stable.mp3'))
        self.assertEqual(len(self.archive.episodes()),1);self.assertEqual(self.archive.episodes()[0]['state'],'played')
        self.archive.import_feed(f,rss(('changed',NOW-100)).replace(b'changed.mp3',b'new-location.mp3'))
        self.assertEqual(len(self.archive.episodes()),1)
    def test_completion_sticky_idempotent_out_of_order_and_reset(self):
        id=self.episode();self.event(id,position=100,action='progress')
        self.event(id,'event-00002',position=500,action='played')
        self.now+=10;self.event(id,'event-00003',position=2,action='progress')
        self.assertEqual(self.archive.episodes()[0]['state'],'played');self.assertEqual(self.archive.episodes(unplayed=True),[])
        result=self.event(id,'event-00004',action='reset');self.assertEqual(result['epoch'],1)
        self.event(id,'event-00005',epoch=0,position=900,action='played')
        self.assertEqual(self.archive.episodes()[0]['state'],'unplayed')
        self.event(id,'event-00006',epoch=1,position=60)
        self.event(id,'event-00006',epoch=1,position=200)
        self.assertEqual(self.archive.episodes()[0]['position'],60)
    def test_backward_seek_allowed_but_older_position_is_ignored(self):
        id=self.episode();self.event(id,position=1000);self.now+=10;self.event(id,'event-00002',position=500)
        self.event(id,'event-00003',position=10,observed=NOW-10)
        self.assertEqual(self.archive.episodes()[0]['position'],500)
    def test_atomic_download_and_disk_ceiling(self):
        id=self.episode()
        @contextlib.contextmanager
        def fetch(url):yield Response(b'audio bytes')
        self.archive.fetch=fetch;self.archive.max_bytes=1;self.archive.download(id)
        self.assertFalse(self.archive.episodes()[0]['downloaded']);self.assertFalse(list(self.archive.media.glob('*/*.part')))
        self.archive.max_bytes=100;self.archive.download(id)
        self.assertTrue(self.archive.episodes()[0]['downloaded']);self.assertEqual(self.archive.episodes()[0]['bytes'],11)
    def test_truncated_download_and_non_audio_are_not_published(self):
        id=self.episode()
        for headers in ({'Content-Type':'text/html'},{'Content-Type':'audio/mpeg','Content-Length':'500'}):
            @contextlib.contextmanager
            def fetch(url):yield Response(b'bad',headers)
            self.archive.fetch=fetch;self.archive.download(id);self.assertFalse(self.archive.episodes()[0]['downloaded'])
    def test_full_backup_contains_media_and_restorable_history(self):
        id=self.episode();self.event(id,action='played')
        @contextlib.contextmanager
        def fetch(url):yield Response(b'fixture audio')
        self.archive.fetch=fetch;self.archive.download(id)
        backup=self.archive.backup(True,Path(self.tmp.name)/'backup')
        self.assertEqual(list(backup.parent.iterdir()),[backup])
        restored=Path(self.tmp.name)/'restore'
        with tarfile.open(backup) as tar:tar.extractall(restored,filter='data')
        archive=Archive(restored/'config',restored/'podcasts',clock=lambda:self.now)
        try:self.assertEqual(archive.episodes()[0]['state'],'played');self.assertEqual(next((restored/'podcasts').glob('*/*.mp3')).read_bytes(),b'fixture audio')
        finally:archive.close()
    def test_manual_request_survives_policy_refresh(self):
        f=self.feed('manual');self.archive.import_feed(f,rss(('old',NOW-100)))
        id=self.archive.episodes()[0]['id']
        with self.archive.lock,self.archive.db:self.archive.db.execute('UPDATE episodes SET requested=1,eligible=1 WHERE id=?',(id,))
        self.now+=3600;self.archive.import_feed(f,rss(('old',NOW-100)))
        self.assertTrue(self.archive.episodes()[0]['eligible'])
    def test_api_uses_json_booleans_and_mark_keeps_resume(self):
        id=self.episode();row=self.archive.episodes()[0]
        self.assertIs(row['downloaded'],False);self.assertIs(row['eligible'],True)
        self.event(id,position=120)
        self.event(id,'event-00002',action='played')
        self.assertEqual(self.archive.episodes()[0]['position'],120)
    def test_bad_xml_rejected(self):
        for raw in (b'<!DOCTYPE rss [<!ENTITY x "yes">]><rss/>',b'not XML'):
            with self.assertRaises(Exception):parse_feed(raw)
    def test_feed_validation_does_not_erase_previous_state(self):
        f=self.feed('future');self.archive.import_feed(f,rss(('first',NOW-100)))
        with self.assertRaises(ValueError):self.archive.import_feed(f,b'<html/>')
        self.assertEqual(len(self.archive.episodes()),1)

class NetworkTests(unittest.TestCase):
    def test_ssrf_blocks_private_and_mixed_dns(self):
        for ip in ('127.0.0.1','10.0.0.1','169.254.169.254','::1','192.168.1.2'):
            with patch('socket.getaddrinfo',return_value=[(socket.AF_INET,socket.SOCK_STREAM,6,'',(ip,80))]):
                with self.assertRaises(ValueError):public_address('http://feed.example.com/rss')
        for url in ('file:///etc/passwd','http://user:pass@example.com/rss','http://example.com:22/rss'):
            with self.assertRaises(ValueError):public_address(url)

class HTTPTests(unittest.TestCase):
    episode = ArchiveTests.episode
    feed = ArchiveTests.feed
    def setUp(self):
        ArchiveTests.setUp(self);self.server=Server(('127.0.0.1',0),self.archive)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start();self.addCleanup(self.stop)
    def stop(self):self.server.shutdown();self.server.server_close();self.thread.join()
    def request(self,path,headers=None,method='GET',body=None):
        c=http.client.HTTPConnection('127.0.0.1',self.server.server_port)
        c.request(method,path,body=body,headers=headers or {});r=c.getresponse();data=r.read();headers=dict(r.getheaders());status=r.status;c.close();return status,headers,data
    def test_auth_and_public_health(self):
        self.assertEqual(self.request('/health')[0],200)
        self.assertEqual(self.request('/api/feeds')[0],401)
        self.assertEqual(self.request('/api/feeds?token='+self.archive.token)[0],401)
        self.assertEqual(self.request('/api/feeds',{'Authorization':'Bearer '+self.archive.token})[0],200)
    def test_seek_ranges_head_and_unauthorized_media(self):
        id=self.episode()
        @contextlib.contextmanager
        def fetch(url):yield Response(b'0123456789')
        self.archive.fetch=fetch;self.archive.download(id);path='/api/episodes/'+id+'/audio';auth={'Authorization':'Bearer '+self.archive.token}
        self.assertEqual(self.request(path)[0],401)
        status,headers,body=self.request(path,{**auth,'Range':'bytes=2-5'})
        self.assertEqual((status,body),(206,b'2345'));self.assertEqual(headers['Content-Range'],'bytes 2-5/10')
        self.assertEqual(self.request(path,{**auth,'Range':'bytes=-3'})[2],b'789')
        self.assertEqual(self.request(path,{**auth,'Range':'bytes=90-'})[0],416)
        self.assertEqual(self.request(path,auth,'HEAD')[2],b'')
    def test_reject_cross_origin_mutation(self):
        status,_,_=self.request('/api/feeds',{'Authorization':'Bearer '+self.archive.token,'Origin':'https://evil.example','Content-Type':'application/json'},'POST','{}')
        self.assertEqual(status,403)

if __name__=='__main__':unittest.main()
