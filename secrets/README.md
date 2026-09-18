# secrets/

Not in git. `keystore.jks` goes here, and nothing else has to.

```
docker compose up -d                 # one engine, once
./scripts/oie-cluster-keystore.sh    # -> secrets/keystore.jks
```

That file is `appdata/keystore.jks` taken off a started engine. It holds the
certificate the admin listener presents **and** the secret key the engine
encrypts channel properties and message content with, so in a cluster every node
must start from the same one — an engine with an empty `appdata` generates a key
of its own, and cannot then read anything another node encrypted.

Every engine reads it through `KEYSTORE_SOURCE`, which the entrypoint copies into
`appdata/` at boot (the engine writes to its keystore, so it cannot be mounted
read-only in place).

| | |
| --- | --- |
| compose | `compose.cluster.yaml` mounts it as the `keystore.jks` secret |
| Kubernetes | `kubectl create secret generic oie-keystore --from-file=keystore.jks=secrets/keystore.jks` |

Back it up with the database. The database holds the encrypted data; this holds
the key. Restoring one without the other is not a restore — the same sentence
the main README applies to the `engine-appdata` volume, and this is the part of
it that matters.

`KEYSTORE_PASSWORD` guards the file and has to be the same everywhere too. Set
it once, before the first boot; changing it later makes the existing keystore
unreadable.
