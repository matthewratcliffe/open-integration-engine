# SSO User Guard

Makes the web administrator's **Edit User** dialog read-only for accounts that
sign in through the identity provider, and says why:

> This account is managed by your identity provider. Its username, name, email
> and organization are set from the provider every time it signs in, so changes
> made here would be overwritten at the next sign-in.

Every field is disabled and **Save** greys out. *Cancel* and the close button
stay live, so the dialog is never a trap. Local accounts are untouched.

## Why it exists

The claim in that message is literally true, and it is the reason for the
plugin. `oie-oidc-auth` calls `UserProvisioner.refreshProfile()` on **every**
sign-in, not just when it creates the account, and rewrites username, email,
first name, last name and organization from the token's claims whenever the
claim is present and differs from what is stored. An administrator editing one
of those fields in the console has not changed anything — they have queued a
change that the next sign-in silently reverts.

## How an SSO account is recognised

The OIDC extension binds the provider's subject to the engine user as the user
preference `oidc.subject`, and looks the account up by it on each login. Its
presence is the marker, read over the ordinary users API:

```
GET /api/users                         -> username to id
GET /api/users/<id>/preferences        -> oidc.subject present and non-empty
```

Nothing is inferred from the username, and nothing assumes a particular claim or
provider.

## What this is not

**Enforcement.** It disables form controls in one browser. The REST API, the
Swing Administrator and `curl` all still accept an edit, and the engine still
applies it — until the next sign-in overwrites it. Enforcement would have to
live in the engine, and the single authorization-plugin slot is already held by
`oie-oidc-auth`, so it would mean forking that extension.

## How it hooks in, and the catch

The console exposes no registration hook for the Users page. `registerNavItem`,
`registerView`, `registerSettingsPanel`, `registerChannelTab`,
`registerDashboardTab`, `registerConnectorPropertiesPanel`, `registerCommand`
and `registerLoginAuthenticator` cover every other surface; the Edit User dialog
is built imperatively inside the console bundle and mounted in a Radix shell.

So this finds the dialog in the DOM instead — a `MutationObserver` on `body`,
matching an overlay whose header begins *Edit User*. That is a deliberate trade
with a known failure mode: **if the console changes that markup, the selectors
stop matching and the dialog behaves exactly as it does today.** It cannot leave
a half-locked form and it cannot break the page. The lookup failing has the same
outcome — the form stays editable, because an administrator who cannot reach the
API still needs the dialog to work.

The durable fix belongs upstream in
[`oie-web-client`](https://github.com/gibson9583/oie-web-client), which already
reads the same SSO signal to note that a password is managed by the provider —
it just scopes that to the user editing themselves.

## Build and install

```bash
./plugins/oie-sso-user-guard/build.sh
cp plugins/oie-sso-user-guard/dist/ssouserguard-0.1.0.zip extensions/
docker compose up -d --force-recreate engine
./scripts/oie-check-extensions.sh "SSO User Guard"
```

There is nothing to compile. This extension is a console UI and no server code:
no `serverClasses`, no jar, no `apiProvider`. It exists to carry a `webadmin/`
folder, which is how the console discovers a plugin UI, and it loads nothing
into the engine's JVM. Verified: the engine registers a code-free extension
normally, and Web Support lists it —

```
GET /api/extensions/websupport/webplugins
["sftp","fhir","volumemonitor","gitsync","sentinel","ssouserguard","tls-manager",
 "oidcauth","nullsender","thread-viewer","keystore","generator"]
```

**Web Support builds that list at startup**, so a newly installed console plugin
needs an engine restart — dropping files into a running container does nothing,
and the image resets `extensions/` on every boot anyway, so the zip is the only
route that survives.

On an engine without `oie-oidc-auth`, or one where nobody has signed in through
it, no account carries `oidc.subject` and every Edit User dialog stays editable.
