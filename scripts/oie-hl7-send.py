#!/usr/bin/env python3
"""
Sends a test HL7 v2 message to an Open Integration Engine HTTP Listener over
mutual TLS, reporting each step of the connection so a failure says *where* it
failed: DNS, TCP, the TLS handshake, the client certificate, HTTP, or the ACK.

    ./scripts/oie-hl7-send.py --cert client.crt --key client.key --cacert ca.crt
    ./scripts/oie-hl7-send.py --cert client.pem --path /ingest --message adt.hl7
    ./scripts/oie-hl7-send.py --cert client.pem --insecure --probe

Defaults to oie1.htrak.com:21066, path /. With no --message a fresh ADT^A01 is
generated with a unique control ID (MSH-10) and the current timestamp.

Certificates are PEM. --cert may hold the certificate and key together, and
the certificate may be followed by its intermediates. For a .p12/.pfx, convert:

    openssl pkcs12 -in client.p12 -out client.pem -nodes

An encrypted key's passphrase is read from OIE_KEY_PASSWORD, not a flag, so
it stays out of shell history.

--probe also runs `openssl s_client` (when openssl is on PATH) to list the CA
names the server accepts client certificates from. This also runs on its own
when the handshake is rejected.

Exit status: 0 on HTTP 2xx with no ACK, or an AA/CA ACK; 1 on a negative ACK
or non-2xx status; 2 on a connection or TLS failure; 3 on bad local input.

Requires Python 3.8+, standard library only.
"""

import argparse
import datetime
import http.client
import os
import shutil
import socket
import ssl
import subprocess
import sys
import time

DEFAULT_HOST = "oie1.htrak.com"
DEFAULT_PORT = 21066

EXIT_OK, EXIT_REJECTED, EXIT_CONNECT, EXIT_INPUT = 0, 1, 2, 3


# --- output -----------------------------------------------------------------

def step(title):
    print(f"\n== {title} " + "=" * max(0, 70 - len(title)))


def ok(msg):
    print(f"  [ OK ] {msg}")


def info(msg):
    print(f"         {msg}")


def warn(msg):
    print(f"  [WARN] {msg}")


def fail(msg):
    print(f"  [FAIL] {msg}")


def hint(msg):
    print(f"  [HINT] {msg}")


def ms(start):
    return f"{(time.perf_counter() - start) * 1000:.0f} ms"


# --- certificates -----------------------------------------------------------

def _name(rdns):
    """Flattens the tuple-of-tuples DN that ssl returns into 'CN=x, O=y'."""
    short = {"commonName": "CN", "organizationName": "O",
             "organizationalUnitName": "OU", "countryName": "C",
             "stateOrProvinceName": "ST", "localityName": "L",
             "emailAddress": "E", "domainComponent": "DC"}
    return ", ".join(f"{short.get(k, k)}={v}" for rdn in rdns for k, v in rdn)


def describe_cert(cert, indent="         "):
    """Prints subject, issuer, SANs and validity of a decoded certificate."""
    print(f"{indent}subject : {_name(cert.get('subject', ()))}")
    print(f"{indent}issuer  : {_name(cert.get('issuer', ()))}")
    sans = [v for _, v in cert.get("subjectAltName", ())]
    if sans:
        print(f"{indent}SANs    : {', '.join(sans)}")
    if "serialNumber" in cert:
        print(f"{indent}serial  : {cert['serialNumber']}")
    not_before, not_after = cert.get("notBefore"), cert.get("notAfter")
    if not_before and not_after:
        print(f"{indent}valid   : {not_before}  ->  {not_after}")
        now = time.time()
        start = ssl.cert_time_to_seconds(not_before)
        end = ssl.cert_time_to_seconds(not_after)
        if now < start:
            warn("certificate is not valid yet")
        elif now > end:
            warn("certificate has EXPIRED")
        elif end - now < 30 * 86400:
            warn(f"certificate expires in {int((end - now) / 86400)} days")


def decode_pem_file(path):
    """Decodes the first certificate in a PEM file without a handshake.

    ssl._ssl._test_decode_cert is private but has been stable since 3.x and is
    the only stdlib way to read a certificate file. Returns None if missing.
    """
    try:
        return ssl._ssl._test_decode_cert(path)
    except Exception:
        return None


# --- HL7 --------------------------------------------------------------------

def sample_message():
    now = datetime.datetime.now()
    stamp = now.strftime("%Y%m%d%H%M%S")
    control_id = f"TEST{now.strftime('%Y%m%d%H%M%S%f')}"
    segments = [
        f"MSH|^~\\&|OIE-TEST|HTAK|OIE|HTAK|{stamp}||ADT^A01^ADT_A01|{control_id}|P|2.5.1",
        f"EVN|A01|{stamp}",
        "PID|1||TEST000001^^^HTAK^MR||TEST^PATIENT^ONE||19800101|U|||"
        "1 TEST STREET^^TESTVILLE^QLD^4000^AUS",
        f"PV1|1|I|WARD1^ROOM1^BED1||||||||||||||||TESTVISIT{now.strftime('%H%M%S')}"
        f"|||||||||||||||||||||||||{stamp}",
    ]
    return "\r".join(segments) + "\r"


def load_message(path):
    with open(path, "r", encoding="utf-8", newline="") as fh:
        text = fh.read()
    # HL7 v2 separates segments with CR. Files edited on Windows or Unix carry
    # CRLF or LF, which the engine's parser would treat as part of a field.
    text = text.replace("\r\n", "\r").replace("\n", "\r").strip("\r") + "\r"
    if not text.startswith("MSH"):
        warn("message does not start with MSH -- is this an HL7 v2 file?")
    return text


def show_hl7(text, indent="         "):
    for seg in filter(None, text.replace("\r\n", "\r").replace("\n", "\r").split("\r")):
        print(f"{indent}{seg}")


def parse_ack(body):
    """Returns (MSA-1, MSA-2, MSA-3, ERR-segments) if body is an HL7 ACK."""
    segments = body.replace("\r\n", "\r").replace("\n", "\r").split("\r")
    if not segments or not segments[0].startswith("MSH") or len(segments[0]) < 4:
        return None
    sep = segments[0][3]
    msa = next((s.split(sep) for s in segments if s.startswith("MSA")), None)
    if not msa:
        return None
    errs = [s for s in segments if s.startswith("ERR")]
    field = lambda i: msa[i] if len(msa) > i else ""
    return field(1), field(2), field(3), errs


# --- probe ------------------------------------------------------------------

def openssl_probe(host, port, sni, timeout, cert=None, key=None, cacert=None):
    """Asks openssl which client CAs the server will accept.

    Python's ssl module does not expose the CertificateRequest message, so
    this is the one diagnostic that has to go through the openssl CLI. Our
    client cert is presented too, so a server that drops anonymous clients
    still gets far enough to print its list.
    """
    exe = shutil.which("openssl")
    if not exe:
        info("openssl not on PATH -- skipping the client CA probe")
        return
    cmd = [exe, "s_client", "-connect", f"{host}:{port}", "-servername", sni]
    if cacert:
        cmd += ["-CAfile", cacert]
    if cert:
        cmd += ["-cert", cert] + (["-key", key] if key else [])
        if os.environ.get("OIE_KEY_PASSWORD"):
            cmd += ["-pass", "env:OIE_KEY_PASSWORD"]
    info("$ " + " ".join(cmd))
    try:
        res = subprocess.run(cmd, input=b"", capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        warn("openssl probe timed out")
        return
    lines = (res.stdout + res.stderr).decode("utf-8", "replace").splitlines()

    # openssl prints "Requested Signature Algorithms" only when the server
    # sent a CertificateRequest, i.e. asked for a client certificate at all.
    requested = any(l.startswith("Requested Signature Algorithms") for l in lines)
    try:
        i = next(n for n, l in enumerate(lines)
                 if l.startswith("Acceptable client certificate CA names"))
    except StopIteration:
        if requested:
            ok("server requests a client certificate (it names no specific "
               "CAs, so any trusted issuer may be accepted)")
        else:
            warn("server did NOT request a client certificate -- client auth "
                 "looks off on this listener, or the handshake failed first")
    else:
        names = []
        for l in lines[i + 1:]:
            if not l or l.startswith(("Requested", "Client Certificate Types",
                                      "Peer signing", "Shared", "---")):
                break
            names.append(l.strip())
        ok(f"server accepts client certificates from {len(names)} CA(s)")
        # A long list is the JVM's system truststore ("Trust system
        # truststore" on); what matters is whether our issuer is in it.
        if len(names) > 12:
            info("(a list this long is the system truststore -- the public CAs)")
        else:
            for n in names:
                info("  " + n)
        issuer_cn = _issuer_cn(cert)
        if issuer_cn:
            hit = [n for n in names if f"CN={issuer_cn}" in n]
            if hit:
                ok(f"our cert's issuer CN={issuer_cn} IS in that list")
            else:
                fail(f"our cert's issuer CN={issuer_cn} is NOT in that list")
                hint("in TLS Manager, import that CA under Additional Trusted "
                     "Certificates and tick it in the listener's TLS Settings "
                     "as a trusted certificate, then redeploy the channel.")
    for l in lines:
        if (l.startswith(("New, ", "Verify return code", "Verification"))
                or "alert" in l or ":error:" in l):
            info(l.strip())


def _issuer_cn(cert_path):
    """commonName of the issuer of the first certificate in cert_path."""
    cert = decode_pem_file(cert_path) if cert_path else None
    for rdn in (cert or {}).get("issuer", ()):
        for k, v in rdn:
            if k == "commonName":
                return v
    return None


# --- main -------------------------------------------------------------------

def classify_tls_error(exc, sent_cert):
    """Turns an SSLError into a hint about which side rejected what."""
    text = str(exc).lower()
    if isinstance(exc, ssl.SSLCertVerificationError):
        hint("WE rejected the SERVER's certificate: "
             f"{exc.verify_message} (code {exc.verify_code}).")
        if "hostname" in text or "ip address mismatch" in text:
            hint("the server's cert does not name this host (a cert with no "
                 "SANs names none). Rerun with --no-hostname-check to test the "
                 "rest; to fix it, give the listener a cert with this host in "
                 "its SANs.")
        else:
            hint("pass the issuing CA with --cacert, or --insecure to confirm "
                 "the rest of the path works.")
        return
    if "certificate required" in text or ("handshake failure" in text and not sent_cert):
        hint("the SERVER requires a client certificate and none was sent -- "
             "pass --cert/--key.")
    elif "unknown ca" in text:
        hint("the SERVER does not trust the CA that issued our client cert. "
             "Its issuer must be in the listener's 'Trusted server "
             "certificates' in TLS Manager (see --probe output).")
    elif "bad certificate" in text or "certificate unknown" in text:
        hint("the SERVER rejected our client certificate: wrong CA, missing "
             "intermediate in --cert, expired, revoked (CRL/OCSP Hard Fail), "
             "or a Subject DN validation filter that doesn't match.")
    elif "access denied" in text:
        hint("the SERVER accepted the chain but denied this client -- usually "
             "Subject DN validation on the listener.")
    elif "protocol version" in text or "unsupported protocol" in text:
        hint("no TLS version in common; try --tls-min 1.2 / --tls-max 1.2.")
    elif "wrong version number" in text or "record layer failure" in text:
        hint("the port is not speaking TLS -- the listener may be plain HTTP, "
             "or this is a different service.")
    elif "eof" in text or "reset" in text:
        hint("the server closed the connection mid-handshake; with mTLS this "
             "is commonly a rejected or missing client certificate.")


def main():
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default=DEFAULT_HOST)
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    p.add_argument("--path", default="/", help="HTTP Listener context path (default /)")
    p.add_argument("--cert", help="client certificate PEM (may include key and chain)")
    p.add_argument("--key", help="client private key PEM, if not in --cert")
    p.add_argument("--cacert", help="CA bundle to verify the server with "
                                    "(default: system trust store)")
    p.add_argument("--insecure", action="store_true",
                   help="do not verify the server certificate (diagnosis only)")
    p.add_argument("--no-hostname-check", action="store_true",
                   help="verify the chain but not the hostname")
    p.add_argument("--sni", help="SNI / hostname to verify (default: --host)")
    p.add_argument("--tls-min", choices=["1.2", "1.3"])
    p.add_argument("--tls-max", choices=["1.2", "1.3"])
    p.add_argument("--message", help="HL7 file to send (default: generated ADT^A01)")
    p.add_argument("--content-type", default="application/hl7-v2; charset=utf-8")
    p.add_argument("--header", action="append", default=[], metavar="'Name: value'",
                   help="extra request header, repeatable")
    p.add_argument("--timeout", type=float, default=30.0, help="seconds (default 30)")
    p.add_argument("--probe", action="store_true",
                   help="also list the server's acceptable client CAs via openssl")
    args = p.parse_args()

    # Certificate DNs carry non-ASCII names; a Windows console or a redirect
    # defaults to cp1252, which cannot print them.
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")
        except AttributeError:
            pass

    sni = args.sni or args.host
    # Git Bash rewrites an argument like /ingest into C:/Program Files/Git/ingest.
    if len(args.path) > 2 and args.path[1] == ":" and args.path[2] in "/\\":
        p.error(f"--path {args.path!r} looks like a Windows path: Git Bash converted it. "
                "Pass it as //ingest, or set MSYS_NO_PATHCONV=1.")
    path = "/" + args.path.lstrip("/")

    print(f"OIE HL7-over-HTTPS mTLS test  {datetime.datetime.now().isoformat(timespec='seconds')}")
    info(f"target  : https://{args.host}:{args.port}{path}")
    info(f"python  : {sys.version.split()[0]}  /  {ssl.OPENSSL_VERSION}")

    # 1. Local inputs -------------------------------------------------------
    step("1. Client certificate and TLS context")
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    if args.tls_min:
        ctx.minimum_version = getattr(ssl.TLSVersion, "TLSv" + args.tls_min.replace(".", "_"))
    if args.tls_max:
        ctx.maximum_version = getattr(ssl.TLSVersion, "TLSv" + args.tls_max.replace(".", "_"))

    if args.insecure:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        warn("server certificate verification DISABLED (--insecure)")
    else:
        try:
            if args.cacert:
                ctx.load_verify_locations(cafile=args.cacert)
                ok(f"trusting CA bundle {args.cacert}")
            else:
                ctx.load_default_certs()
                ok("trusting the system certificate store")
        except (OSError, ssl.SSLError) as exc:
            fail(f"could not load CA bundle: {exc}")
            return EXIT_INPUT
        if args.no_hostname_check:
            ctx.check_hostname = False
            warn("hostname verification disabled (--no-hostname-check)")

    if args.cert:
        cert = decode_pem_file(args.cert)
        if cert:
            describe_cert(cert)
        try:
            ctx.load_cert_chain(args.cert, args.key,
                                password=os.environ.get("OIE_KEY_PASSWORD"))
        except ssl.SSLError as exc:
            fail(f"could not load client cert/key: {exc}")
            if "key values mismatch" in str(exc).lower():
                hint("--key is not the private key for --cert.")
            elif "bad decrypt" in str(exc).lower() or "bad password" in str(exc).lower():
                hint("the key is encrypted -- set OIE_KEY_PASSWORD.")
            else:
                hint("both must be PEM; convert a .p12/.pfx with "
                     "`openssl pkcs12 -in x.p12 -out x.pem -nodes`.")
            return EXIT_INPUT
        except OSError as exc:
            fail(f"could not read client cert/key: {exc}")
            return EXIT_INPUT
        ok("client certificate and key loaded and match")
    else:
        warn("no --cert given: sending NO client certificate (expect an mTLS "
             "listener to reject this)")

    try:
        message = load_message(args.message) if args.message else sample_message()
    except OSError as exc:
        fail(f"could not read message: {exc}")
        return EXIT_INPUT
    body = message.encode("utf-8")

    if args.probe:
        step("1b. Server's acceptable client CAs (openssl probe)")
        openssl_probe(args.host, args.port, sni, args.timeout, args.cert, args.key,
                          args.cacert)

    # 2. DNS ----------------------------------------------------------------
    step("2. DNS")
    t = time.perf_counter()
    try:
        addrs = socket.getaddrinfo(args.host, args.port, type=socket.SOCK_STREAM)
    except socket.gaierror as exc:
        fail(f"cannot resolve {args.host}: {exc}")
        return EXIT_CONNECT
    ips = list(dict.fromkeys(a[4][0] for a in addrs))
    ok(f"{args.host} -> {', '.join(ips)}  ({ms(t)})")

    # 3. TCP ----------------------------------------------------------------
    step("3. TCP connect")
    t = time.perf_counter()
    try:
        raw = socket.create_connection((args.host, args.port), timeout=args.timeout)
    except socket.timeout:
        fail(f"timed out after {args.timeout:.0f}s")
        hint("firewall / security group / NACL dropping the port, or nothing "
             "listening and packets are being silently discarded.")
        return EXIT_CONNECT
    except ConnectionRefusedError:
        fail("connection refused")
        hint("host is up but nothing listens on this port -- channel not "
             "deployed/started, or the listener is bound to another port.")
        return EXIT_CONNECT
    except OSError as exc:
        fail(f"connect failed: {exc}")
        return EXIT_CONNECT
    ok(f"connected to {raw.getpeername()[0]}:{raw.getpeername()[1]} "
       f"from {raw.getsockname()[0]}:{raw.getsockname()[1]}  ({ms(t)})")

    # 4. TLS ----------------------------------------------------------------
    step("4. TLS handshake")
    t = time.perf_counter()
    try:
        tls = ctx.wrap_socket(raw, server_hostname=sni)
    except (ssl.SSLError, OSError) as exc:
        fail(f"handshake failed: {exc}")
        classify_tls_error(exc, bool(args.cert))
        raw.close()
        # When we rejected the server, its client CA list is beside the point.
        if not args.probe and not isinstance(exc, ssl.SSLCertVerificationError):
            step("4b. Server's acceptable client CAs (openssl probe)")
            openssl_probe(args.host, args.port, sni, args.timeout, args.cert, args.key,
                          args.cacert)
        return EXIT_CONNECT
    ok(f"{tls.version()}  {tls.cipher()[0]}  ({ms(t)})")
    if tls.version() == "TLSv1.3" and args.cert:
        info("TLS 1.3: the server checks our client cert after this point, so "
             "a rejection surfaces on the first read below, not here.")
    peer = tls.getpeercert()
    if peer:
        info("server certificate:")
        describe_cert(peer, indent="           ")
    else:
        # verify_mode CERT_NONE hides the parsed form; show what we can.
        der = tls.getpeercert(binary_form=True)
        info(f"server certificate: {len(der or b'')} bytes DER "
             "(details hidden by --insecure; rerun with --cacert to see them)")
    chain = getattr(tls, "get_verified_chain", None)
    if chain and peer:
        try:
            info(f"verified chain length: {len(chain())}")
        except Exception:
            pass

    # 5. HTTP ---------------------------------------------------------------
    step("5. HTTP POST")
    headers = {
        "Host": args.host if args.port == 443 else f"{args.host}:{args.port}",
        "Content-Type": args.content_type,
        "Content-Length": str(len(body)),
        "Accept": "*/*",
        "User-Agent": "oie-hl7-send/1.0",
        "Connection": "close",
    }
    for h in args.header:
        name, _, value = h.partition(":")
        headers[name.strip()] = value.strip()
    request = f"POST {path} HTTP/1.1\r\n" + "".join(
        f"{k}: {v}\r\n" for k, v in headers.items()) + "\r\n"

    print("  >> request")
    for line in request.rstrip("\r\n").split("\r\n"):
        info(line)
    info("")
    show_hl7(message)

    t = time.perf_counter()
    try:
        tls.sendall(request.encode("ascii") + body)
        resp = http.client.HTTPResponse(tls, method="POST")
        resp.begin()
        resp_body = resp.read()
    except ssl.SSLError as exc:
        fail(f"TLS error after handshake: {exc}")
        classify_tls_error(exc, bool(args.cert))
        return EXIT_CONNECT
    except socket.timeout:
        fail(f"no response within {args.timeout:.0f}s")
        hint("connected fine but the channel did not answer -- check the "
             "listener's response settings and the channel's message log.")
        return EXIT_CONNECT
    except (OSError, http.client.HTTPException) as exc:
        fail(f"HTTP exchange failed: {exc!r}")
        if args.cert:
            hint("a reset right after a TLS 1.3 handshake usually means the "
                 "client certificate was rejected; try --tls-max 1.2 to get "
                 "the real alert during the handshake.")
        return EXIT_CONNECT
    finally:
        try:
            tls.close()
        except OSError:
            pass

    elapsed = ms(t)
    print("  << response")
    info(f"HTTP/{resp.version / 10:.1f} {resp.status} {resp.reason}  ({elapsed})")
    for k, v in resp.getheaders():
        info(f"{k}: {v}")
    info("")
    text = resp_body.decode(resp.headers.get_content_charset() or "utf-8", "replace")
    if text.strip():
        show_hl7(text)
    else:
        info("(empty body)")

    # 6. Verdict ------------------------------------------------------------
    step("6. Result")
    if not 200 <= resp.status < 300:
        fail(f"HTTP {resp.status} {resp.reason}")
        if resp.status == 404:
            hint("wrong --path: it must match the HTTP Listener's context path.")
        elif resp.status in (401, 403):
            hint("the listener has HTTP authentication configured; add it with "
                 "--header 'Authorization: ...'.")
        elif resp.status >= 500:
            hint("the channel errored -- check its message log / server log.")
        return EXIT_REJECTED

    ack = parse_ack(text)
    if ack is None:
        ok(f"HTTP {resp.status}; body is not an HL7 ACK (listener response "
           "setting decides what comes back)")
        return EXIT_OK
    code, ctrl, msg, errs = ack
    for e in errs:
        info(e)
    if code in ("AA", "CA"):
        ok(f"ACK {code}  control id {ctrl}" + (f"  '{msg}'" if msg else ""))
        return EXIT_OK
    fail(f"ACK {code or '?'}  control id {ctrl}" + (f"  '{msg}'" if msg else ""))
    return EXIT_REJECTED


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
