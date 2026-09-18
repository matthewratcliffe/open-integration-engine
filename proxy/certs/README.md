# TLS material for the mTLS proxy

`compose.proxy.yaml` mounts this directory read-only at `/etc/nginx/certs` and
expects three files:

| File | What it is |
| --- | --- |
| `server.crt` | the certificate nginx presents to callers |
| `server.key` | its private key |
| `clients-ca.crt` | the CA that signs client certificates you trust |

`clients-ca.crt` is the trust anchor for `ssl_verify_client on`. To trust more
than one issuer, concatenate the PEM certificates into this single file.

Everything here except this README is gitignored. Private keys do not belong in
git; in a real deployment `server.crt`/`server.key` come from your normal CA or
ACME client, and `clients-ca.crt` from whoever issues your partners' certificates.

## Development certificates

```
./scripts/gen-dev-certs.sh oie.local
```

That writes a throwaway CA, a server certificate with a proper subjectAltName,
and a `client.crt`/`client.key` pair to test with. On Windows, run it under Git
Bash with `MSYS_NO_PATHCONV=1` so the `/CN=...` subject is not rewritten into a
path.
