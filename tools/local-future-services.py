"""Local contract doubles only. This is not Billing, Niubiz, or Notifications."""
import hmac
import json
import os
import sqlite3
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

DB = os.environ.get('FIXTURE_DB', '/data/future-fixtures.sqlite')
LOCK = threading.Lock()
with sqlite3.connect(DB) as db:
    db.executescript('CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY,payload TEXT); CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY,payload TEXT); CREATE TABLE IF NOT EXISTS entitlements(account TEXT PRIMARY KEY,active INTEGER); CREATE TABLE IF NOT EXISTS settings(name TEXT PRIMARY KEY,value INTEGER);')


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def respond(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(data)

    def authorized(self, name):
        return hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + os.environ[name])

    def do_GET(self):
        path = urlparse(self.path)
        if path.path == '/health':
            return self.respond(200, {'mode': 'LOCAL_CONTRACT_DOUBLE'})
        if path.path.startswith('/local-contracts/entitlements/') or (path.path.startswith('/internal/v1/accounts/') and path.path.endswith('/entitlements')):
            if not self.authorized('BILLING_FIXTURE_TOKEN'):
                return self.respond(401, {})
            with sqlite3.connect(DB) as db:
                row = db.execute('SELECT active FROM entitlements WHERE account=?', ((path.path.split('/')[-2] if path.path.endswith('/entitlements') else path.path.rsplit('/', 1)[-1]),)).fetchone()
            return self.respond(200, {'proActive': bool(row and row[0])})
        if not self.authorized('FIXTURE_ADMIN_TOKEN'):
            return self.respond(401, {})
        with sqlite3.connect(DB) as db:
            if path.path == '/fixtures/messages':
                email = parse_qs(path.query).get('email', [''])[0]
                rows = [json.loads(row[0]) for row in db.execute('SELECT payload FROM messages ORDER BY rowid')]
                return self.respond(200, [row for row in rows if row.get('email') == email])
            if path.path == '/fixtures/events':
                return self.respond(200, [json.loads(row[0]) for row in db.execute('SELECT payload FROM events ORDER BY rowid')])
        self.respond(404, {})

    def do_POST(self):
        try:
            if self.headers.get('Transfer-Encoding', '').lower() == 'chunked':
                chunks = bytearray()
                while True:
                    size = int(self.rfile.readline(128).split(b';', 1)[0], 16)
                    if size == 0:
                        self.rfile.readline(128)
                        break
                    if size < 0 or len(chunks) + size > 65536:
                        return self.respond(413, {})
                    chunks.extend(self.rfile.read(size))
                    if self.rfile.read(2) != b'\r\n':
                        return self.respond(400, {})
                body = json.loads(chunks)
            else:
                length = int(self.headers.get('Content-Length', '0'))
                if length < 0 or length > 65536:
                    return self.respond(413, {})
                body = json.loads(self.rfile.read(length))
            with LOCK, sqlite3.connect(DB) as db:
                if self.path == '/local-contracts/notifications':
                    if not self.authorized('NOTIFICATION_SERVICE_TOKEN'):
                        return self.respond(401, {})
                    failure = db.execute("SELECT value FROM settings WHERE name='notificationFailure'").fetchone()
                    if failure and failure[0]:
                        return self.respond(503, {})
                    db.execute('INSERT OR IGNORE INTO messages VALUES (?,?)', (self.headers['Idempotency-Key'], json.dumps(body)))
                    return self.respond(202, {'mode': 'LOCAL_CONTRACT_DOUBLE'})
                if self.path == '/local-contracts/events':
                    if not self.authorized('EVENT_FIXTURE_TOKEN'):
                        return self.respond(401, {})
                    db.execute('INSERT OR IGNORE INTO events VALUES (?,?)', (self.headers['Idempotency-Key'], json.dumps(body)))
                    return self.respond(202, {})
                if not self.authorized('FIXTURE_ADMIN_TOKEN'):
                    return self.respond(401, {})
                if self.path == '/fixtures/entitlements':
                    db.execute('INSERT OR REPLACE INTO entitlements VALUES (?,?)', (body['accountId'], bool(body['proActive'])))
                    return self.respond(200, {})
                if self.path == '/fixtures/notification-failure':
                    db.execute("INSERT OR REPLACE INTO settings VALUES ('notificationFailure',?)", (bool(body['enabled']),))
                    return self.respond(200, {})
            self.respond(404, {})
        except (ValueError, KeyError):
            self.respond(400, {})


ThreadingHTTPServer(('0.0.0.0', 8091), Handler).serve_forever()
