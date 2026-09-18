# Single sign-on with Microsoft Entra ID

The web administrator can sign people in through OpenID Connect as well as with a local
username and password. Both stay available: SSO adds a button to the login card, it does
not replace the form.

**It is configured in the console itself** — *Settings → OIDC Authentication* — and stored
in the engine database with the client secret encrypted. Nothing about your tenant belongs
in `.env`, and no client secret ever reaches the browser or a compose file.

There is one thing to install first, because the engine half of OIDC is an extension.

## How it hangs together

Three parts, and none of them is a fork of anything:

| | |
| --- | --- |
| The web administrator | Already speaks this protocol. Its login card asks the engine whether SSO is on offer and shows the button only if it is. Needs the console at **0.9.0 or newer** — `websupport-1.0.3.zip` as pinned in this repo is newer. |
| [`oie-oidc-auth`](https://github.com/gibson9583/oie-oidc-auth) | The engine extension (plugin name **OIDC Authentication**, `oidcauth` in API paths). Runs the authorization-code exchange, validates the ID token against the provider's JWKS, maps the identity onto an engine user, optionally creates that user, and assigns RBAC roles from a claim. It also serves the settings page you configure it from. Requires OIE **4.6.0**. |
| This repository | Installs the extension, and stays out of the way afterwards. |

The sign-in itself:

1. The console asks the engine `GET /api/extensions/oidcauth/public`. No policy, or a policy
   that is saved but not enabled, and there is no button — which is why an engine without
   the extension simply shows the normal login.
2. The button posts to `/api/extensions/oidcauth/start`; the engine builds the authorize URL
   and the browser goes to Entra.
3. Entra sends the browser back to **`<console URL>/oidc/callback`**, a route the console
   owns. It hands the `code` to `/api/extensions/oidcauth/callback`.
4. The engine exchanges the code, validates the ID token, and returns a one-time ticket. The
   console logs in with that ticket, and from there the session is an ordinary engine
   session.

The client secret stays between the engine and Entra.

## 1. Install the extension

Append its pinned entry to `OIE_EXTENSION_URLS` in `.env` — the same mechanism as every
other community extension, and the checksum is enforced at boot:

```
sha256:a18d208ca4e6ca0700790d971fde5b4212df70b3dc5b34ae3cc9032afdb3a5c2@https://github.com/gibson9583/oie-oidc-auth/releases/download/v1.0.1/oidcauth-1.0.1.zip
```

```bash
docker compose up -d
./scripts/oie-check-extensions.sh "OIDC Authentication"
```

Installing it changes nothing on its own: with no policy saved, the engine answers
`configured: false` and the login card is the one you already have. It is the tick box in
the console that turns SSO on.

## 2. Register the application in Entra

In the Entra admin centre, **Identity → Applications → App registrations → New
registration**:

1. **Name** anything recognisable, e.g. `Open Integration Engine`.
2. **Supported account types**: single tenant, unless you have a reason otherwise.
3. **Redirect URI**: platform **Web**. The value is the browser-facing URL of the console
   plus `/oidc/callback`:

   ```
   https://oie.example.org/oie-webadmin/oidc/callback
   ```

   The settings page prints the exact string to use once you have filled in the web
   administrator URL — copy it from there rather than typing it. It must match character
   for character, including the port if the console is served on one, or Entra refuses with
   `AADSTS50011`.
4. **Certificates & secrets → New client secret**. Copy the *value* (not the id) — it is
   shown once.
5. **Token configuration → Add optional claim** if you want roles. Either add the `groups`
   claim (and map group object ids), or define **App roles** and assign users and groups to
   them under **Enterprise applications → Users and groups**; app roles arrive in the
   `roles` claim.

Note the **Directory (tenant) ID** and **Application (client) ID** from the overview page.

## 3. Fill the form in

Sign in to the console with a local administrator account and open **Settings → OIDC
Authentication**. For Entra:

| Field | Value |
| --- | --- |
| Enable OIDC login | Tick it **first**: every other field is greyed out until you do. Unticking it is also how you turn SSO off later. |
| Discovery URL | `https://login.microsoftonline.com/<tenant-id>/v2.0/.well-known/openid-configuration` |
| Client ID | the application (client) id |
| Client secret | the secret *value* from step 2. Stored encrypted; the form shows it masked afterwards. |
| Web administrator URL | the browser-facing console URL, e.g. `https://oie.example.org/oie-webadmin`. The redirect URI shown at the top of the page is this plus `/oidc/callback`. |
| Scopes | `openid profile email` |
| Sign-in button label | `Microsoft Entra ID` — this is the text on the login card's button. Blank gives you "SSO". |
| Username claim | `preferred_username` for Entra. This is what the engine user is named after. |
| JIT provision unknown users | Yes, unless you intend to create every engine user by hand first. |
| Roles claim | `roles` for app roles, `groups` (the default) if you mapped group ids. |
| Claim-to-role mappings | a row per mapping, e.g. `oie-admin` → `Administrator`. |
| Role synchronization | `always`, so removing someone from a group in Entra takes effect next time they sign in. |

**Test connection** before saving. It fetches the discovery document and the signing keys
and reports the issuer and key count; it changes nothing, on the form or on the engine.
Then **Save**.

The remaining fields — clock skew, maximum token age, JWKS cache TTL, allowed algorithms,
the JIT claim names, username prefix, linked accounts, auto-redirect — have working
defaults. Leave them alone until something makes you change them.

## What the login card does

Once the policy is saved and enabled, `GET /api/extensions/oidcauth/public` answers
`configured: true` and the card changes:

- it opens on **Sign in with `<label>`**, with **Use local sign-in** under it;
- the local form then carries a **Sign in with SSO** button of its own, so the two are
  always one click apart;
- the choice is remembered per engine, in that browser, so an operator who works locally
  keeps getting the form and everyone else keeps getting the button;
- a failed SSO sign-in drops back to the local form with the reason shown, rather than
  stranding you on a card with one button that does not work.

**Local accounts are untouched by any of this.** They keep their passwords, their
permissions and their place on the card — which is the property that matters when the
identity provider is down and the emergency admin account is all you have. Turning on
*Auto-redirect* sends the browser straight to Entra instead of showing the card at all;
leave it off until SSO is proven on the deployment, and note that even then the card
remains reachable.

### Roles

Signing in and being able to do something are separate questions. If an RBAC plugin is
installed on the engine, a user who arrives with no role signs in successfully and then
finds they can do nothing — the console says as much. Map the claim values onto this
engine's roles, or give everyone a floor with a default role. The map's right-hand side is
a dropdown of the roles this engine actually has, so a typo there is hard to make.

*Role synchronization = always* re-applies the mapping on each sign-in. *jit-only* assigns roles
when the account is first created and leaves them alone afterwards; *never* leaves role
management entirely to the engine.

**With no RBAC extension installed the question inverts, and it is the more dangerous
way round.** The engine has no permission model at all, so a JIT-provisioned user holds
full administrative access from their first sign-in, and the identity provider's
membership is the only gate on the engine. The extension says so in the log when you save
a policy in that state:

```
OIDC JIT provisioning is ON and the role-based-access-control extension is NOT
installed. The engine has no permission model without it, so every user this
provisions will hold full administrative access on first sign-in.
```

This stack ships no RBAC extension. So on a deployment that matters, either install one
and set a default role before turning JIT on, or leave JIT off and name the identities
explicitly in **Linked accounts** (`someone@example.org` → an existing engine user), which
admits exactly the people you list and no one else.

## An SSO account belongs to the provider

The extension does not just create an account and leave it. On **every** sign-in it
rewrites the engine user's **username, email, first name, last name and organization**
from the token's claims, whenever a claim is present and differs from what is stored. A
missing or blank claim leaves the existing value alone — it refreshes, it never blanks.

So the provider is the source of truth for those fields, and editing them in the console
achieves nothing: the next sign-in puts them back. Two consequences worth knowing before
someone spends an afternoon on it:

- **Names come from one claim, split on the last space.** There is no `given_name` /
  `family_name` support: the claim named in *Name claim* (`name` by default) is split at
  its last space, so `Ada Lovelace` gives first `Ada`, last `Lovelace`. A provider that
  sends the email address as `name` — Auth0 does this for database users with no name set
  — produces a first name that is an email address and an empty last name. Fix that at the
  provider, not here.
- **The Edit User dialog lies about it by default.** The console lets an administrator
  type into those fields and save them, with no hint that they are about to be reverted.
  `plugins/oie-sso-user-guard/` is this repository's answer: a console-only extension that
  makes the dialog read-only for provider-managed accounts and says why. It recognises
  them by the `oidc.subject` user preference the extension binds them with, so it needs no
  configuration. See its README for how it hooks in and how it fails — safely, by leaving
  the dialog exactly as it is today.

Neither is enforcement: the REST API and the Swing Administrator still accept an edit to
an SSO account, and the engine still stores it, until the next sign-in overwrites it.

## Turning it off

Three ways, in increasing order of bluntness:

- untick **Enable OIDC login** and save — the login card goes back to the plain form;
- the kill switch, for when the console is the thing you cannot reach:

  ```bash
  OIE_OIDC_DISABLED=true \
  docker compose -f compose.yaml -f compose.sso-killswitch.yaml up -d
  ```

  It beats the stored policy, so the engine refuses every OIDC sign-in and stops
  advertising SSO, whatever the form says. The settings page shows a banner while it is in
  force. This is the one to reach for when the provider is broken, or a redirect loop is
  keeping you off the card;
- remove the extension from `OIE_EXTENSION_URLS`. The stored policy survives, dormant,
  until the extension comes back.

## Configuring from the environment instead

Every field also has an `OIE_OIDC_*` variable, and a system property
(`org.openintegrationengine.oidc.<key>`) behind that. The order is: environment variable
beats system property, both beat the stored policy.

That override is a **pin**, not a default. A field backed by a set variable is shown
read-only in the console, listed in a "pinned by the operator environment" note, and
saving the form leaves it untouched. So the two ways of configuring this do not mix well:
pick one. If you pin from the environment, pin the whole policy, and expect the settings
page to be a viewer.

This repository configures from the console, which is why `compose.sso-killswitch.yaml`
passes only the kill switch and no longer passes settings. If you need the other
direction — a deployment that configures machines rather than consoles, with the policy in
your secret store and the console read-only — pass the variables in an overlay of your own,
declared in list form so that an unset variable stays *absent* from the container rather
than arriving present-and-empty.

## Checking it

```bash
# 1. The extension is loaded:
OIE_INSECURE=true OIE_PASSWORD=... ./scripts/oie-check-extensions.sh "OIDC Authentication"

# 2. The engine is offering SSO (no credentials needed -- this is the call the
#    login card makes). "configured": false means the extension is installed but
#    the policy is absent, not saved, or not enabled. The header is not optional:
#    the engine's CSRF filter answers 400 to any API request without it.
curl -sk -H 'X-Requested-With: oie' https://localhost:8443/api/extensions/oidcauth/public

# 3. The console is being offered the settings page (needs a session):
curl -sk -b cookies -H 'X-Requested-With: oie' \
     https://localhost:8443/api/extensions/websupport/webplugins

# 4. Nothing is pinning the form from the environment:
docker exec oie-engine-1 env | grep OIE_OIDC_
```

Then open the console: the login card should carry a **Microsoft Entra ID** button.

## Verified, and not

Verified on this stack, with the extension installed and the engine running:

- the entrypoint downloaded the pinned release and verified it before the engine started —
  `checksum verified for extension 5 (from download)`, `installed extension ext-5.zip` — so
  the pin is the artifact and not a hope;
- the engine loaded it: `./scripts/oie-check-extensions.sh "OIDC Authentication"` reports
  `ok`;
- `GET /api/extensions/oidcauth/public` answers `{"configured":false}` with no policy
  saved. That is the call the login card makes, and it is why installing the extension
  changes nothing on its own;
- the console is offered the settings page —
  `GET /api/extensions/websupport/webplugins` now lists `oidcauth` alongside the other
  plugin UIs — and it renders: *Settings → OIDC Authentication* shows the redirect-URI
  line, the **Enable OIDC login** tick, every field from the schema below it (greyed until
  the tick, which is why you tick it first), and **Save / Refresh / Test connection** in the
  task panel. No field carries a "pinned" note;
- `GET /api/extensions/oidcauth/configuration` returns the policy and the schema the
  settings page renders from — `enabled`, `discovery-url`, `client-id`, `client-secret`,
  `web-administrator-url`, `provider-label`, `auto-redirect`, `scopes`, `username-claim`,
  `username-prefix`, `linked-accounts`, `allowed-algorithms`, `clock-skew-seconds`,
  `max-token-age-seconds`, `jwks-cache-ttl-seconds`, `jit.*` and `roles.*` — with
  `_killSwitch: false`, `_redirectUri` and **no `_pinned` list**: nothing in this stack's
  environment is overriding a field, which is the whole point of dropping the settings
  overlay;
- with `OIE_OIDC_DISABLED` unset, `docker compose -f compose.yaml -f
  compose.sso-killswitch.yaml config` renders it `null` — Compose omits it from the
  container rather than passing an empty string, which matters because the extension reads
  a *present* `OIE_OIDC_*` variable as an operator override. Set it, and the same command
  renders `"true"`;
- the console bundle shipped with this stack implements exactly the login protocol above
  (`/extensions/oidcauth/public`, `/start`, `/callback`, the `/oidc/callback` route and the
  ticket login), shows a **Sign in with `<label>`** button when the engine reports
  `configured`, and keeps a **Sign in with SSO** button on the local form;
- the console's pinning, kill-switch and policy-error banners are driven by `_pinned`,
  `_killSwitch` and `_error` on the configuration response, so an environment override or
  the kill switch is visible in the UI rather than silently winning.

**Not verified here:** an actual sign-in, and therefore the SSO button on the login card.
That needs an Entra tenant, an app registration and a saved, enabled policy, none of which
this repository carries. Treat the first sign-in on a new deployment as the real test, and
do it with local sign-in still available in another browser window.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| No SSO button on the login card | `/api/extensions/oidcauth/public` returns `configured: false` — the policy is not saved, not enabled, or rejected at load — or 404 because the extension is not installed. |
| A field in the settings page is read-only and marked "pinned" | An `OIE_OIDC_*` variable or system property is set for it. Unset it and restart, or accept that the environment owns that field. |
| "This policy is not in force" banner | The engine rejected the saved policy at load; the banner carries the parse error. Until it parses, the login card offers no SSO. |
| `AADSTS50011: redirect URI does not match` | The URI registered in Entra is not exactly the one the settings page prints. Scheme, host, port and path all count. |
| The provider signs you in, the console then says the sign-in was rejected; the log says `username claim missing` | The **Username claim** names a claim this provider does not issue. `preferred_username` is Entra's; Auth0 sends `email`, `nickname`, `name` and `sub` instead. Check an actual token rather than guessing. |
| Same, but the log says `User is not authorized for this engine` | The claim resolved, but no engine user has that username and nothing was allowed to create one. Turn on **JIT provision unknown users**, or map the identity to an existing user in **Linked accounts**. |
| Sign-in succeeds, console says the account has no permissions | The identity mapped to an engine user with no RBAC role. Set a claim-to-role map or a default role. |
| Sign-in is rejected with a token error | Clock skew, or an algorithm the policy does not allow. Both are fields in the settings page. |
| Everything worked, then stopped after a rebuild | The extension is fetched at boot and verified against the pinned checksum. A new release needs a new checksum; startup aborts rather than installing something unexpected. |

## A note on exposure

The redirect URI is a browser-facing URL, so SSO only makes sense on a deployment the
browser can actually reach over TLS — which on this stack means the nginx overlay
(`compose.proxy.yaml`) or your own ingress, not the loopback-bound `8443` the base compose
file publishes. If you front the engine with the mTLS proxy, remember that Entra's redirect
back to the browser has to traverse it too: a client-certificate requirement on the console
port and an interactive browser login are not always compatible.
