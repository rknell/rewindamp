#!/usr/bin/env python3
"""Isolated synthetic podcast fixture; no live feeds, credentials or media mounts."""
import argparse
from pathlib import Path
import sys
server_root=Path(__file__).resolve().parents[1]/'server'
if not server_root.is_dir():server_root=Path(__file__).resolve().parents[2]/'rewindamp-server'
sys.path.insert(0,str(server_root))
from server import Archive, Server
parser=argparse.ArgumentParser();parser.add_argument('directory',type=Path);parser.add_argument('--port',type=int,default=18097);args=parser.parse_args()
config=args.directory/'config';config.mkdir(parents=True,exist_ok=True)
(config/'owner-token').write_text('isolated-podcast-fixture-token-00000001')
archive=Archive(config,args.directory/'podcasts')
feed='f'*32
with archive.lock,archive.db:
    archive.db.execute('INSERT OR IGNORE INTO feeds(id,title,url,mode,subscribed) VALUES(?,?,?,?,?)',(feed,'Synthetic Signals','https://example.com/fixture.rss','all',archive.clock()))
archive.import_feed(feed,b'<rss version="2.0"><channel><title>Synthetic Signals</title><item><title>Podcast Test Signal</title><guid>synthetic-1</guid><pubDate>Tue, 27 Oct 2026 00:00:00 GMT</pubDate><enclosure url="https://example.com/signal.mp3" type="audio/mpeg"/></item></channel></rss>')
id=archive.episodes()[0]['id'];destination=archive.media/feed;destination.mkdir(exist_ok=True)
import shutil
shutil.copyfile(args.directory/'signal.mp3',destination/(id+'.mp3'))
with archive.lock,archive.db:archive.db.execute('UPDATE episodes SET file=?,bytes=?,duration=90,eligible=1 WHERE id=?',(feed+'/'+id+'.mp3',(destination/(id+'.mp3')).stat().st_size,id))
Server(('127.0.0.1',args.port),archive).serve_forever()
