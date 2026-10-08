#!/usr/bin/env python3
"""The EC2 proxy's gate: what nginx asks before it lets a request through.

nginx terminates TLS on 443 and proxies to the engine on 127.0.0.1. Two
requests go past this first (install.sh writes the nginx side):

  GET /docs    an auth_request for the API documentation (/api/, its assets,
               /api/openapi.*, /apiexamples, /javadocs). 204 lets it through:
               the caller's engine session belongs to an account bound to the
               SSO provider. 401 means no session, 403 a session that is not
               SSO -- a local account, admin included.

  POST /api/users/_login
               the console's sign-in, proxied through here when
               WEB_LOCAL_LOGIN=false. A sign-in from the web administrator
               (X-Requested-With: OpenIntegrationEngine-WebAdmin), or from any other
               web page (Sec-Fetch-*/Origin headers), that is not
               the OIDC extension's ticket is refused with a LoginStatus the
               console shows. Everything else -- the SSO ticket, the Swing
               Administrator, scripts, the REST API -- is forwarded untouched,
               so a password still works there: that is the break-glass path.

An account is SSO-bound when it has the oidc.subject user preference, which
the OIDC extension sets when it binds or provisions it (docs/sso.md) and which
also stops that account signing in with a password.

Standard library only, so it runs on the instance's own Python.

  OIE_GATE_LISTEN   host:port to listen on        (default 127.0.0.1:8441)
  OIE_ENGINE_URL    the engine, as nginx reaches it (default https://127.0.0.1:8443)
"""

import hashlib
import http.client
import json
import os
import ssl
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LISTEN = os.environ.get("OIE_GATE_LISTEN", "127.0.0.1:8441")
ENGINE = urllib.parse.urlsplit(os.environ.get("OIE_ENGINE_URL", "https://127.0.0.1:8443"))

WEBADMIN = "OpenIntegrationEngine-WebAdmin"
TICKET_PREFIX = "oidc:ticket:"
SSO_PREFERENCE = "oidc.subject"
REFUSED = ("Password sign-in is turned off for the web administrator. "
           "Sign in with SSO.")

# One documentation page pulls in dozens of assets, each an auth_request, so a
# verdict is kept briefly per session rather than asking the engine every time.
CACHE_SECONDS = 30
CACHE_MAX = 1000
MAX_LOGIN_BODY = 64 * 1024

# Hop-by-hop headers, which a proxy must not forward (RFC 9110 7.6.1).
HOP_BY_HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
              "te", "trailer", "transfer-encoding", "upgrade"}

_cache = {}
_cache_lock = threading.Lock()


def log(message):
    print(message, file=sys.stderr, flush=True)


def engine_connection(timeout):
    if ENGINE.scheme == "https":
        # The engine's own hop is loopback; its certificate is the wildcard (or
        # its self-signed one), neither of which names 127.0.0.1.
        context = ssl.create_default_context()
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        return http.client.HTTPSConnection(ENGINE.hostname, ENGINE.port or 443,
                                           timeout=timeout, context=context)
    return http.client.HTTPConnection(ENGINE.hostname, ENGINE.port or 80, timeout=timeout)


def engine_get(path, cookie, accept):
    """GET path from the engine as the caller's session. Returns (status, body)."""
    conn = engine_connection(15)
    try:
        conn.request("GET", path, headers={
            "Cookie": cookie,
            "Accept": accept,
            # The API rejects requests without it as possible CSRF.
            "X-Requested-With": "oie-gate",
        })
        response = conn.getresponse()
        return response.status, response.read()
    finally:
        conn.close()


def unwrap(value):
    # XStream JSON puts the payload under a single root key: {"user": {...}}.
    if isinstance(value, dict) and len(value) == 1:
        return next(iter(value.values()))
    return value


def docs_verdict(cookie):
    """204 when the session is an SSO-bound account's, 403 when it is another
    account's, 401 when there is no session at all."""
    if not cookie:
        return 401
    status, body = engine_get("/api/users/current", cookie, "application/json")
    if status in (401, 403):
        return 401
    if status != 200:
        raise RuntimeError(f"/api/users/current answered {status}")
    user = unwrap(json.loads(body or b"null"))
    user_id = user.get("id") if isinstance(user, dict) else None
    if user_id is None:
        raise RuntimeError("/api/users/current returned no user id")
    status, body = engine_get(
        f"/api/users/{int(user_id)}/preferences/{urllib.parse.quote(SSO_PREFERENCE)}",
        cookie, "text/plain")
    if status == 200 and body.strip():
        return 204
    if status in (200, 204, 404):
        return 403
    raise RuntimeError(f"reading {SSO_PREFERENCE} answered {status}")


def cached_docs_verdict(cookie):
    key = hashlib.sha256(cookie.encode()).hexdigest() if cookie else ""
    now = time.monotonic()
    with _cache_lock:
        hit = _cache.get(key)
        if hit and hit[1] > now:
            return hit[0]
    verdict = docs_verdict(cookie)
    with _cache_lock:
        if len(_cache) >= CACHE_MAX:
            _cache.clear()
        _cache[key] = (verdict, now + CACHE_SECONDS)
    return verdict


class Gate(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "oie-gate"

    def log_message(self, fmt, *args):
        # Every docs asset is an auth_request; log decisions, not traffic.
        pass

    def reply(self, status, body=b"", content_type="text/plain; charset=utf-8"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if body and self.command != "HEAD":
            self.wfile.write(body)

    def do_GET(self):
        if self.path != "/docs":
            return self.reply(404)
        try:
            verdict = cached_docs_verdict(self.headers.get("Cookie", ""))
        except Exception as error:  # the engine is down or answered oddly
            log(f"docs check failed, refusing: {error}")
            return self.reply(503)
        self.reply(verdict)

    def do_POST(self):
        if urllib.parse.urlsplit(self.path).path != "/api/users/_login":
            return self.reply(404)
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_LOGIN_BODY:
            return self.reply(413)
        body = self.rfile.read(length) if length else b""

        if self.from_browser():
            form = urllib.parse.parse_qs(body.decode("utf-8", "replace"), keep_blank_values=True)
            password = (form.get("password") or [""])[0]
            if not password.startswith(TICKET_PREFIX):
                username = (form.get("username") or [""])[0]
                log(f"refused web administrator password sign-in for {username!r} "
                    f"from {self.headers.get('X-Forwarded-For', '?')}")
                return self.refuse_login()
        self.forward(body)

    def from_browser(self):
        """A sign-in from a web page: the web administrator's own header, or the
        Sec-Fetch-* / Origin headers every current browser adds to a script's
        request -- which covers TLS Manager's sign-in form and any other page
        posting a password. The Swing Administrator, scripts and curl send
        none of them, so they keep password sign-in."""
        return (self.headers.get("X-Requested-With") == WEBADMIN
                or self.headers.get("Sec-Fetch-Mode") is not None
                or self.headers.get("Sec-Fetch-Site") is not None
                or self.headers.get("Origin") is not None)

    def refuse_login(self):
        # The shape the engine answers a failed sign-in with, which the console
        # reads from the body of the 401 and shows on the login card.
        status = {"status": "FAIL", "message": REFUSED}
        if "json" in self.headers.get("Accept", "application/json"):
            body = json.dumps({"com.mirth.connect.model.LoginStatus": status}).encode()
            return self.reply(401, body, "application/json")
        body = (f"<com.mirth.connect.model.LoginStatus><status>FAIL</status>"
                f"<message>{REFUSED}</message></com.mirth.connect.model.LoginStatus>").encode()
        self.reply(401, body, "application/xml")

    def forward(self, body):
        headers = {k: v for k, v in self.headers.items()
                   if k.lower() not in HOP_BY_HOP and k.lower() != "content-length"}
        headers["Content-Length"] = str(len(body))
        conn = engine_connection(120)
        try:
            conn.request("POST", self.path, body=body, headers=headers)
            response = conn.getresponse()
            payload = response.read()
            self.send_response_only(response.status, response.reason)
            # getheaders() keeps repeated headers, Set-Cookie above all.
            for name, value in response.getheaders():
                if name.lower() not in HOP_BY_HOP and name.lower() != "content-length":
                    self.send_header(name, value)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except Exception as error:
            log(f"could not forward the sign-in to the engine: {error}")
            self.reply(502)
        finally:
            conn.close()


def main():
    host, _, port = LISTEN.rpartition(":")
    server = ThreadingHTTPServer((host or "127.0.0.1", int(port)), Gate)
    server.daemon_threads = True
    log(f"oie-gate listening on {LISTEN}, engine {ENGINE.geturl()}")
    server.serve_forever()


if __name__ == "__main__":
    main()
