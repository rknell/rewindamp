#!/usr/bin/env python3
"""Isolated synthetic podcast fixture; no live feeds, credentials or media mounts."""
import argparse
from pathlib import Path
import sys
server_root=Path(__file__).resolve().parents[1]/'server'
if not server_root.is_dir():server_root=Path(__file__).resolve().parents[2]/'rewindamp-server'
if not server_root.is_dir():server_root=Path(__file__).resolve().parent/'fixtures'
sys.path.insert(0,str(server_root))
import server as fixture_server
from server import Archive, Server

# Never resolve external hosts from synthetic fixtures.
fixture_server.public_address=lambda url:(None,None)
parser=argparse.ArgumentParser();parser.add_argument('directory',type=Path);parser.add_argument('--port',type=int,default=18097);parser.add_argument('--password-fixture',action='store_true');parser.add_argument('--queue-fixture',action='store_true');args=parser.parse_args()
config=args.directory/'config';config.mkdir(parents=True,exist_ok=True)
(config/'owner-token').write_text('isolated-podcast-fixture-token-00000001')
archive=Archive(config,args.directory/'podcasts')
if args.password_fixture and not archive.password_set():archive.set_password('isolated memorable fixture password')
feed_url='https://example.com/fixture.rss'
feed=archive.subscribe('Synthetic Signals',feed_url,'all')
if args.password_fixture:
    archive.create_account('listener','synthetic listener password')
    account=archive.rows("SELECT id FROM accounts WHERE username='listener'")[0]['id']
    archive.subscribe('Synthetic Signals',feed_url,'manual',account_id=account)
archive.import_feed(feed,b'<rss version="2.0"><channel><title>Synthetic Signals</title><item><title>Podcast Test Signal</title><guid>synthetic-1</guid><pubDate>Tue, 27 Oct 2026 00:00:00 GMT</pubDate><enclosure url="https://example.com/signal.mp3" type="audio/mpeg"/></item></channel></rss>')
id=archive.episodes()[0]['id'];destination=archive.media/feed;destination.mkdir(exist_ok=True)
import shutil
shutil.copyfile(args.directory/'signal.mp3',destination/(id+'.mp3'))
with archive.lock,archive.db:archive.db.execute('UPDATE episodes SET file=?,bytes=?,duration=90,eligible=1 WHERE id=?',(feed+'/'+id+'.mp3',(destination/(id+'.mp3')).stat().st_size,id))
# Catalogue and subscription validation are local fixtures; tests never call Apple or a live RSS feed.
def catalogue(term,account_id=1):
    if term=='empty fixture':return []
    if term=='unavailable fixture':raise fixture_server.CatalogueUnavailable()
    subscriptions={row['url']:row['id'] for row in archive.feeds(account_id)}
    return [dict(id=key,title=title,author='Test Publisher',genre='Synthetic tests',feed_url=url,apple_url='',
                 subscribed=url in subscriptions,feed_id=subscriptions.get(url))
            for key,title,url in [
                ('fixture-discovery','Synthetic Discovery Show','https://example.com/discovery.rss'),
                ('fixture-existing','Synthetic Signals',feed_url),
            ]]
archive.search_podcasts=catalogue
if args.queue_fixture:
    import email.utils
    second=archive.subscribe('Another Synthetic Show','https://example.com/another.rss','all')
    with archive.lock,archive.db:archive.db.execute('DELETE FROM episodes')
    for show,items in [(feed,[('Queue old',1),('Queue played',2),('Queue newest',4),('Queue unavailable',5)]),(second,[('Queue middle',3)])]:
        body='<rss><channel><title>'+('Synthetic Signals' if show==feed else 'Another Synthetic Show')+'</title>'
        for title,day in items:
            date=email.utils.formatdate(1792800000+day*86400,usegmt=True)
            body+=f'<item><title>{title}</title><guid>{title}</guid><pubDate>{date}</pubDate><enclosure url="https://example.com/{day}.mp3" type="audio/mpeg"/></item>'
        archive.import_feed(show,(body+'</channel></rss>').encode())
    for e in archive.episodes():
        if e['title']=='Queue unavailable':continue
        folder=archive.media/e['feed_id'];folder.mkdir(exist_ok=True);file=e['feed_id']+'/'+e['id']+'.mp3';shutil.copyfile(args.directory/'signal.mp3',archive.media/file)
        with archive.lock,archive.db:archive.db.execute('UPDATE episodes SET file=?,bytes=?,duration=90 WHERE id=?',(file,(archive.media/file).stat().st_size,e['id']))
Server(('127.0.0.1',args.port),archive).serve_forever()
