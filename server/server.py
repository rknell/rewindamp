#!/usr/bin/env python3
"""Rewindamp's independent, single-owner podcast archive. Python standard library only."""
from __future__ import annotations
import argparse
import getpass
import calendar
import contextlib
import datetime as dt
import email.utils
import hashlib
import hmac
import http.client
import ipaddress
import json
import logging
import mimetypes
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import sqlite3
import ssl
import tarfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, urljoin, parse_qs, urlencode
import xml.etree.ElementTree as ET

LOG = logging.getLogger('rewindamp')
UTC = dt.timezone.utc
ITUNES = '{http://www.itunes.com/dtds/podcast-1.0.dtd}'
SEEDS = [
    ('99% Invisible', 'https://feeds.simplecast.com/BqbsxVfO', 'years', 2),
    ('Swindled', 'https://audioboom.com/channels/5170514.rss', 'years', 1),
    ('Smosh Reads Reddit Stories', 'https://rss.amperwave.net/v2/feed/audacynetwork/9c990b304783c10b9f9e1e16a0ac191d', 'future', 0),
    ('The Louis Theroux Podcast', 'https://feeds.megaphone.fm/GLT1948741183', 'future', 0),
]

def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()[:32]

def years_ago(now, years):
    value = dt.datetime.fromtimestamp(now, UTC)
    year = value.year - years
    return value.replace(year=year, day=min(value.day, calendar.monthrange(year, value.month)[1])).timestamp()

def public_address(url):
    p = urlsplit(url)
    if p.scheme not in ('http', 'https') or not p.hostname or p.username or p.password or p.fragment or p.port not in (None, 80, 443):
        raise ValueError('Only public HTTP(S) feed and media URLs are supported')
    addresses = socket.getaddrinfo(p.hostname, p.port or (443 if p.scheme == 'https' else 80), type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(a[4][0]).is_global for a in addresses):
        raise ValueError('Private, loopback and special-use feed/media addresses are blocked')
    return p, addresses[0]

class PinnedHTTP(http.client.HTTPConnection):
    def __init__(self, host, port, address, secure):
        super().__init__(host, port, timeout=45)
        self.address, self.secure = address, secure
    def connect(self):
        family, kind, proto, _, address = self.address
        self.sock = socket.socket(family, kind, proto)
        self.sock.settimeout(self.timeout)
        self.sock.connect(address)
        if self.secure:
            self.sock = ssl.create_default_context().wrap_socket(self.sock, server_hostname=self.host)

@contextlib.contextmanager
def remote(url):
    """Resolve and pin a public IP at every redirect; no ambient cookies, proxy or credentials."""
    for _ in range(8):
        p, address = public_address(url)
        connection = PinnedHTTP(p.hostname, p.port or (443 if p.scheme == 'https' else 80), address, p.scheme == 'https')
        try:
            connection.request('GET', (p.path or '/') + ('?' + p.query if p.query else ''), headers={'User-Agent':'RewindampServer/0.1', 'Accept-Encoding':'identity'})
            response = connection.getresponse()
            if response.status in (301,302,303,307,308):
                target = urljoin(url, response.getheader('Location', ''))
                if p.scheme == 'https' and urlsplit(target).scheme != 'https':
                    raise ValueError('HTTPS downgrade blocked')
                url = target
                continue
            if response.status != 200:
                raise ValueError(f'Upstream HTTP {response.status}')
            yield response
            return
        finally:
            connection.close()
    raise ValueError('Too many upstream redirects')

def parse_feed(raw):
    if len(raw) > 16*1024*1024 or re.search(br'<!\s*(DOCTYPE|ENTITY)', raw, re.I):
        raise ValueError('Feed is too large or contains XML declarations that are not allowed')
    root = ET.fromstring(raw)
    channel = root.find('channel')
    if channel is None:
        raise ValueError('An RSS 2.0 channel is required')
    title = (channel.findtext('title') or '').strip()
    episodes = []
    for item in channel.findall('item'):
        enclosure = item.find('enclosure')
        if enclosure is None or not enclosure.get('url'):
            continue
        mime = enclosure.get('type','').lower()
        if mime and not mime.startswith('audio/') and mime != 'application/octet-stream':
            continue
        url = enclosure.get('url').strip()
        p = urlsplit(url)
        if p.scheme not in ('http','https') or not p.hostname or p.username or p.password:
            continue
        published = None
        try:
            value = email.utils.parsedate_to_datetime(item.findtext('pubDate') or '')
            published = value.replace(tzinfo=value.tzinfo or UTC).timestamp()
        except (ValueError,TypeError,OverflowError):
            pass
        duration = 0
        try:
            for part in (item.findtext(ITUNES+'duration') or '0').split(':'):
                duration = duration * 60 + float(part)
        except ValueError:
            duration = 0
        episodes.append(dict(guid=(item.findtext('guid') or url).strip(), enclosure=url,
            title=(item.findtext('title') or 'Untitled episode').strip(), published=published,
            duration=max(0,duration), description=(item.findtext('description') or '')[:20000]))
    return title, episodes

SCHEMA = '''
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS feeds(
 id TEXT PRIMARY KEY, title TEXT NOT NULL, url TEXT NOT NULL UNIQUE,
 mode TEXT NOT NULL, years INTEGER NOT NULL DEFAULT 0, since REAL,
 subscribed REAL NOT NULL, baseline REAL, last_refresh REAL, error TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS episodes(
 id TEXT PRIMARY KEY, feed_id TEXT NOT NULL REFERENCES feeds(id), title TEXT NOT NULL,
 guid TEXT NOT NULL, enclosure TEXT NOT NULL, published REAL, duration REAL NOT NULL,
 description TEXT NOT NULL, first_seen REAL NOT NULL, eligible INTEGER NOT NULL DEFAULT 0,
 requested INTEGER NOT NULL DEFAULT 0,
 file TEXT, bytes INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0,
 retry_at REAL NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '', UNIQUE(feed_id,guid));
CREATE TABLE IF NOT EXISTS aliases(feed_id TEXT NOT NULL, alias TEXT NOT NULL, episode_id TEXT NOT NULL,
 PRIMARY KEY(feed_id,alias));
CREATE TABLE IF NOT EXISTS progress(
 episode_id TEXT PRIMARY KEY REFERENCES episodes(id), position REAL NOT NULL DEFAULT 0,
 state TEXT NOT NULL DEFAULT 'unplayed', epoch INTEGER NOT NULL DEFAULT 0,
 observed REAL NOT NULL DEFAULT 0, updated REAL NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, episode_id TEXT NOT NULL, received REAL NOT NULL);
CREATE INDEX IF NOT EXISTS episode_feed_date ON episodes(feed_id,published);
CREATE TABLE IF NOT EXISTS auth_settings(id INTEGER PRIMARY KEY CHECK(id=1), salt TEXT NOT NULL, password_hash TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS auth_sessions(token_hash TEXT PRIMARY KEY, expires REAL NOT NULL);

'''

class CatalogueUnavailable(Exception): pass
class CatalogueRateLimited(Exception): pass

class Archive:
    def __init__(self, config, media, fetch=remote, clock=time.time):
        self.config, self.media = Path(config), Path(media)
        self.config.mkdir(parents=True, exist_ok=True)
        self.media.mkdir(parents=True, exist_ok=True)
        self.fetch, self.clock = fetch, clock
        self.lock = threading.RLock()
        self.worker_lock = threading.Lock()
        self.catalogue_lock = threading.Lock()
        self.catalogue_cache = {}
        self.catalogue_calls = []
        self.auth_lock = threading.RLock()
        self.login_attempts = []
        self.db = sqlite3.connect(self.config/'library.sqlite3', check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        if 'requested' not in {r[1] for r in self.db.execute('PRAGMA table_info(episodes)')}:
            self.db.execute('ALTER TABLE episodes ADD COLUMN requested INTEGER NOT NULL DEFAULT 0')
            self.db.commit()
        self.db.execute('PRAGMA foreign_keys=ON')
        self.max_bytes = int(os.getenv('REWINDAMP_MAX_ARCHIVE_BYTES', str(50*1024**3)))
        self.max_episode_bytes = int(os.getenv('REWINDAMP_MAX_EPISODE_BYTES', str(1024**3)))
        token_file = self.config/'owner-token'
        if not token_file.exists():
            with open(token_file, 'x', opener=lambda p,flags: os.open(p,flags,0o600)) as f:
                f.write(secrets.token_urlsafe(36))
        self.token = token_file.read_text().strip()
        if len(self.token) < 32:
            raise ValueError('Owner token must be at least 32 characters')

    def rows(self, sql, args=()):
        with self.lock:
            return [dict(r) for r in self.db.execute(sql,args)]

    def password_set(self):
        return bool(self.rows('SELECT id FROM auth_settings WHERE id=1'))

    @staticmethod
    def password_hash(password, salt):
        return hashlib.scrypt(password.encode('utf-8'),salt=bytes.fromhex(salt),n=32768,r=8,p=1,maxmem=64*1024**2,dklen=32).hex()

    def check_password(self, password):
        if not isinstance(password,str) or len(password)>256: return False
        rows = self.rows('SELECT * FROM auth_settings WHERE id=1')
        salt = rows[0]['salt'] if rows else '00'*32
        expected = rows[0]['password_hash'] if rows else '00'*32
        return hmac.compare_digest(self.password_hash(password,salt),expected) and bool(rows)

    def authenticated(self, token):
        if not isinstance(token,str) or not token or len(token)>256: return False
        if not self.password_set(): return hmac.compare_digest(token.encode(),self.token.encode())
        return bool(self.rows('SELECT token_hash FROM auth_sessions WHERE token_hash=? AND expires>?',(hashlib.sha256(token.encode()).hexdigest(),self.clock())))

    def session(self):
        token = secrets.token_urlsafe(36)
        with self.lock,self.db:
            self.db.execute('DELETE FROM auth_sessions WHERE expires<=?',(self.clock(),))
            self.db.execute('INSERT INTO auth_sessions VALUES(?,?)',(hashlib.sha256(token.encode()).hexdigest(),self.clock()+30*86400))
        return token

    def verify_password(self,password):
        with self.auth_lock:
            now=self.clock();self.login_attempts=[stamp for stamp in self.login_attempts if now-stamp<60]
            if len(self.login_attempts)>=10: raise CatalogueRateLimited()
            self.login_attempts.append(now)
            return self.check_password(password)

    def login(self,password):
        with self.auth_lock:
            if not self.verify_password(password): return None
            return self.session()

    def set_password(self,password):
        if not isinstance(password,str) or not 10<=len(password)<=256: raise ValueError('Choose a password with 10–256 characters')
        salt=secrets.token_hex(32);hashed=self.password_hash(password,salt)
        with self.auth_lock,self.lock,self.db:
            self.db.execute('INSERT INTO auth_settings VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET salt=excluded.salt,password_hash=excluded.password_hash',(salt,hashed))
            self.db.execute('DELETE FROM auth_sessions')
        return self.session()

    def logout(self,token):
        with self.lock,self.db:self.db.execute('DELETE FROM auth_sessions WHERE token_hash=?',(hashlib.sha256(token.encode()).hexdigest(),))

    def subscribe(self, title, url, mode='future', years=0, since=None, only_if_new=False):
        public_address(url)
        if mode not in ('all','future','years','since','manual'):
            raise ValueError('Invalid subscription mode')
        years = int(years)
        if mode == 'years' and years not in range(1,11):
            raise ValueError('Years must be 1–10')
        if mode == 'since' and (since is None or not isinstance(since,(int,float))):
            raise ValueError('A publication cutoff timestamp is required')
        feed_id = digest(url)
        with self.lock, self.db:
            existing = self.rows('SELECT id FROM feeds WHERE url=?',(url,))
            if existing and only_if_new: return existing[0]['id']
            self.db.execute('INSERT INTO feeds(id,title,url,mode,years,since,subscribed) VALUES(?,?,?,?,?,?,?) ON CONFLICT(url) DO UPDATE SET mode=excluded.mode,years=excluded.years,since=excluded.since',
                (feed_id,str(title)[:300],url,mode,years,since,self.clock()))
            self.reevaluate(feed_id)
        return feed_id

    def search_podcasts(self, term):
        term = ' '.join(str(term).split())
        if not 2 <= len(term) <= 120:
            raise ValueError('Search must contain 2–120 characters')
        key = term.casefold()
        with self.catalogue_lock:
            now = self.clock()
            cached = self.catalogue_cache.get(key)
            if cached and now-cached[0] < 600:
                items = cached[1]
            else:
                self.catalogue_calls = [stamp for stamp in self.catalogue_calls if now-stamp < 60]
                if len(self.catalogue_calls) >= 15: raise CatalogueRateLimited()
                self.catalogue_calls.append(now)
                # Only the search term goes to Apple. Owner tokens and listening data never do.
                url = 'https://itunes.apple.com/search?' + urlencode(dict(term=term,media='podcast',entity='podcast',country='AU',limit=10))
                try:
                    with self.fetch(url) as response: raw = response.read(2*1024*1024+1)
                    if len(raw) > 2*1024*1024: raise ValueError('Oversized catalogue response')
                    payload = json.loads(raw)
                    if not isinstance(payload,dict) or not isinstance(payload.get('results'),list): raise ValueError('Invalid catalogue response')
                    items, seen = [], set()
                    for result in payload['results'][:50]:
                        if not isinstance(result,dict) or result.get('kind') != 'podcast': continue
                        feed_url = result.get('feedUrl')
                        if not isinstance(feed_url,str) or len(feed_url)>4000: continue
                        parsed = urlsplit(feed_url)
                        if parsed.scheme not in ('http','https') or not parsed.hostname or parsed.username or parsed.password or parsed.fragment or parsed.port not in (None,80,443): continue
                        if feed_url in seen: continue
                        seen.add(feed_url)
                        link = result.get('collectionViewUrl') or result.get('trackViewUrl') or ''
                        if not isinstance(link,str) or urlsplit(link).scheme != 'https' or urlsplit(link).hostname not in ('podcasts.apple.com','itunes.apple.com'): link = ''
                        items.append(dict(id=str(result.get('collectionId') or result.get('trackId') or ''),
                            title=str(result.get('collectionName') or result.get('trackName') or 'Untitled podcast')[:300],
                            author=str(result.get('artistName') or '')[:300],feed_url=feed_url,
                            genre=str(result.get('primaryGenreName') or '')[:100],apple_url=link))
                        if len(items) == 10: break
                    if len(self.catalogue_cache)>=64: self.catalogue_cache.pop(next(iter(self.catalogue_cache)))
                    self.catalogue_cache[key] = (now,items)
                except Exception as exc:
                    LOG.warning('Podcast catalogue search failed (%s)',type(exc).__name__)
                    raise CatalogueUnavailable() from None
            subscribed = {row['url']:row['id'] for row in self.rows('SELECT id,url FROM feeds')}
            return [dict(item,subscribed=item['feed_url'] in subscribed,feed_id=subscribed.get(item['feed_url'])) for item in items]

    def seed(self):
        for title,url,mode,years in SEEDS:
            if not self.rows('SELECT id FROM feeds WHERE url=?',(url,)):
                self.subscribe(title,url,mode,years)

    def eligible(self, feed, episode, now):
        if feed['mode'] == 'all': return True
        if feed['mode'] == 'manual': return False
        published = episode['published']
        if published is None: return False
        if feed['mode'] == 'future':
            return feed['baseline'] is not None and episode['first_seen'] > feed['baseline'] and published > feed['baseline'] and published <= now
        cutoff = years_ago(now,feed['years']) if feed['mode']=='years' else feed['since']
        return cutoff <= published <= now

    def reevaluate(self, feed_id):
        feed = self.rows('SELECT * FROM feeds WHERE id=?',(feed_id,))[0]
        now = self.clock()
        for episode in self.rows('SELECT * FROM episodes WHERE feed_id=?',(feed_id,)):
            self.db.execute('UPDATE episodes SET eligible=? WHERE id=?',(int(episode['requested'] or self.eligible(feed,episode,now)),episode['id']))

    def import_feed(self, feed_id, raw):
        title, entries = parse_feed(raw)
        now = self.clock()
        with self.lock, self.db:
            feed = self.rows('SELECT * FROM feeds WHERE id=?',(feed_id,))[0]
            for item in entries:
                # Publisher GUID takes precedence; enclosure aliases reconcile GUID changes.
                aliases = ['guid:'+item['guid'], 'url:'+item['enclosure']]
                found = []
                for alias in aliases:
                    found = self.rows('SELECT episode_id FROM aliases WHERE feed_id=? AND alias=?',(feed_id,alias))
                    if found: break
                episode_id = found[0]['episode_id'] if found else digest(feed_id+'\n'+item['guid'])
                self.db.execute('INSERT INTO episodes(id,feed_id,title,guid,enclosure,published,duration,description,first_seen) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title,enclosure=excluded.enclosure,published=excluded.published,duration=excluded.duration,description=excluded.description',
                    (episode_id,feed_id,item['title'],item['guid'],item['enclosure'],item['published'],item['duration'],item['description'],now))
                for alias in aliases:
                    self.db.execute('INSERT OR IGNORE INTO aliases VALUES(?,?,?)',(feed_id,alias,episode_id))
            # Baseline only after the entire first successful import, not on subscription or a failed fetch.
            self.db.execute('UPDATE feeds SET title=?,baseline=COALESCE(baseline,?),last_refresh=?,error=? WHERE id=?',(title or feed['title'],now,now,'',feed_id))
            self.reevaluate(feed_id)
        return len(entries)

    def refresh(self, feed_id):
        feed = self.rows('SELECT * FROM feeds WHERE id=?',(feed_id,))[0]
        try:
            with self.fetch(feed['url']) as response:
                raw = response.read(16*1024*1024+1)
            return self.import_feed(feed_id,raw)
        except Exception as exc:
            # Do not log upstream URLs: private feed paths/query strings may be credentials.
            LOG.warning('Feed %s refresh failed (%s)',feed_id,type(exc).__name__)
            with self.lock, self.db:
                self.db.execute('UPDATE feeds SET error=?,last_refresh=? WHERE id=?',('Feed refresh failed; check the RSS URL or retry later',self.clock(),feed_id))
            return None

    def episodes(self, feed_id=None, unplayed=False, offset=0, limit=100):
        where, args = [], []
        if feed_id: where.append('e.feed_id=?'); args.append(feed_id)
        if unplayed: where.append("COALESCE(p.state,'unplayed') NOT IN ('played','dismissed') AND (e.eligible=1 OR e.file IS NOT NULL)")
        rows = self.rows('''SELECT e.id,e.feed_id,e.title,e.published,e.duration,e.eligible,e.file IS NOT NULL AS downloaded,e.bytes,e.error,
            f.title AS show_title,COALESCE(p.position,0) AS position,COALESCE(p.state,'unplayed') AS state,
            COALESCE(p.epoch,0) AS epoch FROM episodes e JOIN feeds f ON e.feed_id=f.id
            LEFT JOIN progress p ON p.episode_id=e.id '''+(' WHERE '+' AND '.join(where) if where else '')+
            ' ORDER BY e.published DESC,e.id LIMIT ? OFFSET ?',tuple(args+[max(1,min(500,limit)),max(0,offset)]))
        for row in rows:
            row['downloaded'] = bool(row['downloaded'])
            row['eligible'] = bool(row['eligible'])
        return rows

    def progress(self, episode_id, event):
        event_id = str(event.get('event_id',''))
        if not re.fullmatch(r'[a-zA-Z0-9_-]{8,100}',event_id): raise ValueError('A unique event_id is required')
        action = event.get('action','progress')
        if action not in ('progress','played','dismissed','reset'): raise ValueError('Invalid progress action')
        position = float(event.get('position',0))
        if not 0 <= position <= 60*60*24*30: raise ValueError('Invalid position')
        now = self.clock()
        observed = min(now,float(event.get('observed',now)))
        if not 0 <= observed <= now: raise ValueError('Invalid event time')
        with self.lock, self.db:
            episode = self.rows('SELECT duration FROM episodes WHERE id=?',(episode_id,))
            if not episode: raise KeyError(episode_id)
            self.db.execute('INSERT OR IGNORE INTO progress(episode_id) VALUES(?)',(episode_id,))
            previous = self.rows('SELECT * FROM progress WHERE episode_id=?',(episode_id,))[0]
            if self.rows('SELECT id FROM events WHERE id=?',(event_id,)): return previous
            self.db.execute('INSERT INTO events VALUES(?,?,?)',(event_id,episode_id,now))
            if 'position' not in event: position = previous['position']
            # Reset is compare-and-set. Delayed events from older generations cannot undo it.
            if int(event.get('epoch',0)) != previous['epoch']: return previous
            if action == 'reset':
                self.db.execute("UPDATE progress SET position=0,state='unplayed',epoch=epoch+1,observed=?,updated=? WHERE episode_id=?",(now,now,episode_id))
            else:
                state = previous['state']
                if action in ('played','dismissed'): state = action
                elif state not in ('played','dismissed') and position > 0: state = 'in_progress'
                if observed >= previous['observed']:
                    self.db.execute('UPDATE progress SET position=?,state=?,observed=?,updated=? WHERE episode_id=?',(position,state,observed,now,episode_id))
                elif action in ('played','dismissed'):
                    self.db.execute('UPDATE progress SET state=?,updated=? WHERE episode_id=?',(state,now,episode_id))
            return self.rows('SELECT * FROM progress WHERE episode_id=?',(episode_id,))[0]

    def download(self, episode_id):
        episode = self.rows('SELECT * FROM episodes WHERE id=?',(episode_id,))[0]
        if episode['file']: return
        directory = self.media/episode['feed_id']
        directory.mkdir(exist_ok=True)
        target = directory/(episode_id+'.mp3')
        partial = target.with_suffix('.part')
        used = self.rows('SELECT COALESCE(SUM(bytes),0) AS bytes FROM episodes')[0]['bytes']
        try:
            total = 0
            with self.fetch(episode['enclosure']) as response, partial.open('wb') as output:
                mime = (response.getheader('Content-Type') or '').split(';')[0].lower()
                if mime and not (mime.startswith('audio/') or mime in ('application/octet-stream','binary/octet-stream')):
                    raise ValueError('Non-audio enclosure')
                while chunk := response.read(128*1024):
                    total += len(chunk)
                    if total > self.max_episode_bytes or used + total > self.max_bytes or shutil.disk_usage(directory).free < len(chunk)+128*1024**2:
                        raise ValueError('Storage limit reached')
                    output.write(chunk)
                if total == 0: raise ValueError('Empty enclosure')
                length = response.getheader('Content-Length')
                if length is not None and total != int(length): raise ValueError('Incomplete enclosure')
                output.flush(); os.fsync(output.fileno())
            os.replace(partial,target)
            with self.lock, self.db:
                self.db.execute('UPDATE episodes SET file=?,bytes=?,error=?,attempts=0,retry_at=0 WHERE id=?',(str(target.relative_to(self.media)),total,'',episode_id))
        except Exception as exc:
            partial.unlink(missing_ok=True)
            LOG.warning('Episode %s download failed (%s)',episode_id,type(exc).__name__)
            with self.lock, self.db:
                self.db.execute('UPDATE episodes SET error=?,attempts=attempts+1,retry_at=? WHERE id=?',('Download failed or storage limit reached; will retry',self.clock()+min(86400,60*2**min(episode['attempts'],10)),episode_id))

    def tick(self, download=True):
        if not self.worker_lock.acquire(blocking=False): return
        try:
            with self.lock, self.db:
                for feed in self.rows('SELECT id FROM feeds'): self.reevaluate(feed['id'])
            for feed in self.rows('SELECT * FROM feeds'):
                if feed['last_refresh'] is None or self.clock()-feed['last_refresh'] >= 1800:
                    self.refresh(feed['id'])
            if download:
                for episode in self.rows('SELECT id FROM episodes WHERE eligible=1 AND file IS NULL AND retry_at<=? ORDER BY published DESC LIMIT 10',(self.clock(),)):
                    self.download(episode['id'])
        finally:
            self.worker_lock.release()

    def backup(self, full=False, destination=None):
        """SQLite online backup plus feeds/history. Full archives additionally contain all media."""
        root = Path(destination or self.config/'backups')
        root.mkdir(parents=True,exist_ok=True)
        name = dt.datetime.fromtimestamp(self.clock(),UTC).strftime('%Y%m%dT%H%M%S')+'-'+secrets.token_hex(3)
        dbcopy = root/(name+'.sqlite3')
        with self.worker_lock, self.lock:
            with contextlib.closing(sqlite3.connect(dbcopy)) as db:
                self.db.backup(db)
                db.execute("PRAGMA journal_mode=DELETE")
            if not full: return dbcopy
            target = root/(name+'.tar')
            partial = target.with_suffix('.tar.part')
            try:
                with tarfile.open(partial,'w') as tar:
                    tar.add(dbcopy,arcname='config/library.sqlite3')
                    tar.add(self.config/'owner-token',arcname='config/owner-token')
                    with contextlib.closing(sqlite3.connect(dbcopy)) as snapshot:
                        files = [row[0] for row in snapshot.execute('SELECT file FROM episodes WHERE file IS NOT NULL')]
                    for file in files:
                        tar.add(self.media/file,arcname='podcasts/'+file)
                os.replace(partial,target)
            finally:
                partial.unlink(missing_ok=True)
                dbcopy.unlink(missing_ok=True)
            return target

    def close(self): self.db.close()

class Handler(BaseHTTPRequestHandler):
    server_version = 'Rewindamp/0.1'
    def log_message(self, format, *args):
        # HTTP request targets can contain private values; log only status and method.
        pass
    @property
    def archive(self): return self.server.archive
    def reply(self, code, body):
        data = json.dumps(body,allow_nan=False).encode()
        self.send_response(code); self.send_header('Content-Type','application/json')
        self.send_header('Cache-Control','no-store'); self.send_header('Content-Length',str(len(data)))
        self.send_header('X-Content-Type-Options','nosniff'); self.end_headers()
        if self.command != 'HEAD': self.wfile.write(data)
    def authorized(self):
        header=self.headers.get('Authorization','')
        return header.startswith('Bearer ') and self.archive.authenticated(header[7:])
    def do_HEAD(self): self.do_GET()
    def do_GET(self):
        try:
            path = urlsplit(self.path).path
            if path == '/api/auth/status': return self.reply(200,{'password_set':self.archive.password_set()})
            if path == '/health': return self.reply(200,{'status':'ok','service':'rewindamp-server'})
            if path in ('/','/app.js','/style.css','/favicon.png','/favicon.ico','/assets/rewindamp-logo.png'):
                file = Path(__file__).parent/'static'/({'/':'index.html'}.get(path,path[1:]))
                data = file.read_bytes()
                self.send_response(200); self.send_header('Content-Type',mimetypes.guess_type(str(file))[0] or 'text/plain')
                self.send_header('Content-Length',str(len(data))); self.send_header('X-Content-Type-Options','nosniff')
                self.send_header('Content-Security-Policy',"default-src 'self'; style-src 'self'; script-src 'self'; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'")
                self.end_headers()
                if self.command != 'HEAD': self.wfile.write(data)
                return
            if not self.authorized(): return self.reply(401,{'error':'Sign in required'})
            query = parse_qs(urlsplit(self.path).query)
            if path == '/api/podcasts/search':
                try: return self.reply(200,{'items':self.archive.search_podcasts(query.get('term',[''])[0]),'country':'AU','provider':'Apple Podcasts'})
                except CatalogueRateLimited: return self.reply(429,{'error':'Catalogue search is busy. Try again in a minute.'})
                except CatalogueUnavailable: return self.reply(502,{'error':'Apple Podcasts search is unavailable. Try again later or add an RSS feed.'})
            if path == '/api/feeds':
                return self.reply(200,{'items':self.archive.rows('SELECT f.*, (SELECT COUNT(*) FROM episodes e WHERE e.feed_id=f.id AND e.file IS NOT NULL) AS downloaded FROM feeds f ORDER BY title')})
            if path == '/api/episodes':
                return self.reply(200,{'items':self.archive.episodes(query.get('feed_id',[None])[0],query.get('unplayed',['false'])[0]=='true',int(query.get('offset',['0'])[0]),int(query.get('limit',['100'])[0]))})
            match = re.fullmatch(r'/api/episodes/([a-f0-9]{32})/audio',path)
            if match: return self.audio(match[1])
            return self.reply(404,{'error':'Not found'})
        except (ValueError,KeyError): self.reply(400,{'error':'Invalid request'})
        except (BrokenPipeError,ConnectionResetError): pass
        except Exception:
            LOG.exception('Request failed'); self.reply(500,{'error':'Server error'})
    def do_POST(self):
        try:
            if self.headers.get('Origin'):
                origin = urlsplit(self.headers['Origin'])
                if origin.netloc != self.headers.get('Host'): return self.reply(403,{'error':'Cross-origin request blocked'})
            length = int(self.headers.get('Content-Length','0'))
            if not 0 < length <= 64*1024: return self.reply(413,{'error':'JSON body required, maximum 64 KiB'})
            body = json.loads(self.rfile.read(length))
            if not isinstance(body,dict): raise ValueError('JSON object required')
            path = urlsplit(self.path).path
            if path == '/api/auth/login':
                try: token=self.archive.login(body.get('password'))
                except CatalogueRateLimited: return self.reply(429,{'error':'Too many sign-in attempts. Try again in a minute.'})
                if token is None: return self.reply(401,{'error':'Incorrect password'})
                return self.reply(200,{'access_token':token})
            if not self.authorized(): return self.reply(401,{'error':'Sign in required'})
            if path == '/api/auth/password':
                with self.archive.auth_lock:
                    if self.archive.password_set():
                        try: valid=self.archive.verify_password(body.get('current_password'))
                        except CatalogueRateLimited: return self.reply(429,{'error':'Too many sign-in attempts. Try again in a minute.'})
                        if not valid: return self.reply(401,{'error':'Current password is incorrect'})
                    try: token=self.archive.set_password(body.get('new_password'))
                    except ValueError: return self.reply(400,{'error':'Choose a password with 10–256 characters'})
                return self.reply(200,{'access_token':token})
            if path == '/api/auth/logout':
                self.archive.logout(self.headers.get('Authorization','')[7:])
                return self.reply(200,{'signed_out':True})
            if path == '/api/feeds':
                feed_id = self.archive.subscribe(body.get('title','Podcast'),body['url'],body.get('mode','future'),body.get('years',0),body.get('since'),body.get('only_if_new') is True)
                return self.reply(201,{'id':feed_id})
            match = re.fullmatch(r'/api/feeds/([a-f0-9]{32})/refresh',path)
            if match:
                with self.archive.lock,self.archive.db:
                    self.archive.db.execute('UPDATE feeds SET last_refresh=NULL WHERE id=?',(match[1],))
                return self.reply(202,{'queued':True})
            match = re.fullmatch(r'/api/progress/([a-f0-9]{32})',path)
            if match: return self.reply(200,self.archive.progress(match[1],body))
            match = re.fullmatch(r'/api/episodes/([a-f0-9]{32})/download',path)
            if match:
                with self.archive.lock,self.archive.db:
                    self.archive.db.execute('UPDATE episodes SET eligible=1,requested=1,retry_at=0 WHERE id=?',(match[1],))
                return self.reply(202,{'queued':True})
            return self.reply(404,{'error':'Not found'})
        except KeyError: self.reply(404,{'error':'Unknown episode or missing field'})
        except (ValueError,TypeError,OverflowError): self.reply(400,{'error':'Invalid request'})
        except Exception:
            LOG.exception('Request failed'); self.reply(500,{'error':'Server error'})
    def audio(self, episode_id):
        rows = self.archive.rows('SELECT file FROM episodes WHERE id=?',(episode_id,))
        if not rows or not rows[0]['file']: return self.reply(404,{'error':'Episode has not downloaded yet'})
        file = self.archive.media/rows[0]['file']
        size = file.stat().st_size
        start,end,code = 0,size-1,200
        value = self.headers.get('Range')
        if value:
            match = re.fullmatch(r'bytes=(\d*)-(\d*)',value)
            if not match or not any(match.groups()):
                self.send_response(416);self.send_header('Content-Range',f'bytes */{size}');self.end_headers();return
            a,b = match.groups()
            if a: start,end = int(a),min(int(b) if b else size-1,size-1)
            else: start = max(0,size-int(b))
            if start > end or start >= size:
                self.send_response(416);self.send_header('Content-Range',f'bytes */{size}');self.end_headers();return
            code = 206
        self.send_response(code);self.send_header('Content-Type','audio/mpeg');self.send_header('Accept-Ranges','bytes')
        self.send_header('Cache-Control','private, no-store');self.send_header('Content-Length',str(end-start+1))
        if code == 206:self.send_header('Content-Range',f'bytes {start}-{end}/{size}')
        self.end_headers()
        if self.command == 'HEAD':return
        with file.open('rb') as f:
            f.seek(start);remaining=end-start+1
            while remaining:
                chunk=f.read(min(128*1024,remaining))
                if not chunk:break
                self.wfile.write(chunk);remaining-=len(chunk)

class Server(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, archive):
        super().__init__(address,Handler);self.archive=archive
    def get_request(self):
        connection,address=super().get_request();connection.settimeout(30);return connection,address

def main():
    os.umask(0o077)
    parser=argparse.ArgumentParser()
    parser.add_argument('command',nargs='?',default='serve',choices=['serve','backup','token','refresh','password'])
    parser.add_argument('--config',default=os.getenv('REWINDAMP_CONFIG','/config'))
    parser.add_argument('--media',default=os.getenv('REWINDAMP_MEDIA','/podcasts'))
    parser.add_argument('--port',type=int,default=8080)
    parser.add_argument('--destination');parser.add_argument('--full',action='store_true')
    args=parser.parse_args()
    logging.basicConfig(level=logging.INFO,format='%(asctime)s %(levelname)s %(message)s')
    archive=Archive(args.config,args.media)
    if args.command=='token':
        if archive.password_set(): print('Password sign-in is configured. Reset it locally with: python server.py password')
        else: print(archive.token)
        return
    if args.command=='password':
        password=getpass.getpass('New owner password (10+ characters): ')
        if password!=getpass.getpass('Confirm password: '): raise SystemExit('Passwords did not match; unchanged')
        try:archive.set_password(password)
        except ValueError as exc:raise SystemExit(str(exc))
        print('Password saved. Sign in again on your devices.');return
    if args.command=='backup': print(archive.backup(args.full,args.destination));return
    archive.seed()
    if args.command=='refresh':
        for feed in archive.rows('SELECT id FROM feeds'): archive.refresh(feed['id'])
        return
    stop=threading.Event()
    def worker():
        last_backup=0
        while not stop.is_set():
            try:
                archive.tick()
                if time.time()-last_backup>86400:archive.backup();last_backup=time.time()
            except Exception:LOG.exception('Worker failed; retrying')
            stop.wait(15)
    thread=threading.Thread(target=worker,daemon=True);thread.start()
    LOG.info('Rewindamp server listening on port %s; owner token is in /config/owner-token',args.port)
    try:Server(('0.0.0.0',args.port),archive).serve_forever()
    finally:stop.set()

if __name__=='__main__':main()
