# Trusted CA certificates

Each `*.pem` here is one CA certificate that every deploy adds to TLS Manager's
**trusted certificates**, under its file name without `.pem` as the alias:
`htrak-root-ca.pem` becomes `htrak-root-ca`. A TLS listener accepts client
certificates issued by a CA once that alias is ticked under **Trusted server
certificates** in the channel's TLS Settings -- and, for the HTrak Root CA, OCSP
checking is set to **Disabled**: its certificates name no OCSP responder, which
TLS Manager otherwise refuses even on Soft Fail (`docs/mtls.md`).

| File | |
| --- | --- |
| `htrak-root-ca.pem` | **HTrak Root CA**, which signs the client certificates hTrak issues to partners (`sdpr-prod`, ...) with the Certificate Generator. SHA-256 `AC:0D:1C:60:27:18:1A:AB:E7:78:F5:CC:53:04:CF:F1:51:D9:9D:62:35:4E:59:0D:61:E3:74:77:E3:CF:5E:0B`, valid to 2056 |

These are public certificates, never keys. Adding a file here is granting trust:
a listener that ticks the alias accepts any client certificate the CA signs, so
review it like a permission change. Re-deploying an unchanged file does nothing;
a changed one replaces the alias in place and redeploys the running channels
that trust it. Removing a file does not remove the alias from TLS Manager.

`scripts/oie-tls-import.sh trust <alias> <file>` does the import, and works
against any engine.
