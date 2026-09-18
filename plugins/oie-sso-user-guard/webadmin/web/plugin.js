/*
 * SSO User Guard -> OIE web console.
 *
 * Makes the Edit User dialog read-only for accounts that sign in through the
 * identity provider, and says why.
 *
 * The engine already treats these accounts as the provider's property: the OIDC
 * extension rewrites username, email, first name, last name and organization
 * from the token's claims on EVERY sign-in, not just when it creates the
 * account. So an administrator who edits one of those fields here has not
 * changed anything -- they have queued a change that the next sign-in silently
 * reverts. This makes that visible before the typing rather than after.
 *
 * How an SSO account is recognised: the OIDC extension binds the provider's
 * subject to the engine user as the user preference `oidc.subject`, which is
 * what it looks the account up by on each login. Its presence is the marker,
 * and it is readable over the ordinary users API. Nothing here guesses from the
 * username or assumes anything about the claim.
 *
 * WHAT THIS IS NOT: enforcement. It disables form controls in one browser. The
 * REST API, the Swing Administrator and curl all still accept an edit, and the
 * engine still applies it -- until the next sign-in overwrites it. Enforcement
 * would have to live in the engine, and the single authorization-plugin slot is
 * already held by the OIDC extension.
 *
 * The console offers no registration hook for the Users page -- registerNavItem,
 * registerView, registerSettingsPanel, registerChannelTab, registerDashboardTab
 * and the rest cover everything except this -- so the dialog is found in the DOM
 * instead. That is a deliberate trade with a known failure mode: if the console
 * changes the dialog's markup, the selectors stop matching and the dialog simply
 * behaves as it does today. It cannot leave a half-locked form, and it cannot
 * break the page. The durable fix belongs upstream in oie-web-client, where the
 * same `oidc.subject` signal is already used to note that a password is managed
 * by the provider.
 *
 * Plain ES module JavaScript: no JSX, no imports, no build step -- the console
 * dynamically import()s this file and passes its own `platform` into
 * register(), the same way the bundled plugins receive it.
 */

// The user preference the OIDC extension writes when it binds an account.
const SSO_PREFERENCE = 'oidc.subject';

// The dialog this applies to. The console's own title, matched on its stable
// leading words rather than the whole string, which carries the username.
const EDIT_USER_TITLE = /^Edit User\b/;

// Marks a dialog as handled, so repeated mutations do not re-do the work.
const HANDLED = 'data-sso-guard';

const NOTE_CLASS = 'sso-guard-note';

const NOTE_TEXT =
    'This account is managed by your identity provider. Its username, name, '
    + 'email and organization are set from the provider every time it signs in, '
    + 'so changes made here would be overwritten at the next sign-in.';

/*
 * Engine responses come through XStream, so a one-element list is not a list:
 *   {"list":{"user":[{...},{...}]}}   two users
 *   {"list":{"user":{...}}}           one user
 *   {"list":null} / {"list":""}       none
 * Same shape for <properties><property>. Normalise all of them to an array.
 */
function asArray(value) {
    if (value == null || value === '') return [];
    return Array.isArray(value) ? value : [value];
}

function get(path) {
    return fetch('/api' + path, {
        headers: { Accept: 'application/json', 'X-Requested-With': 'oie-webadmin' },
        credentials: 'same-origin',
    }).then(function (res) {
        if (!res.ok) throw new Error(path + ': HTTP ' + res.status);
        return res.json();
    });
}

function userIdByName(username) {
    return get('/users').then(function (body) {
        const users = asArray(body && body.list && body.list.user);
        const match = users.filter(function (u) { return u && u.username === username; })[0];
        return match ? match.id : null;
    });
}

function isSsoBound(userId) {
    if (userId == null) return Promise.resolve(false);
    return get('/users/' + encodeURIComponent(userId) + '/preferences').then(function (body) {
        const props = asArray(body && body.properties && body.properties.property);
        return props.some(function (p) {
            // {"@name":"oidc.subject","$":"https://issuer/#sub"} -- a bound
            // account always carries a value; an empty one is not a binding.
            return p && p['@name'] === SSO_PREFERENCE && String(p.$ || '').trim() !== '';
        });
    });
}

/*
 * A per-username cache. The answer only changes when an account is first bound
 * or unbound, and the cost of being briefly stale is that one dialog opens
 * editable -- so it is worth avoiding two API calls every time a dialog opens,
 * and not worth invalidating cleverly. Cleared when the page reloads.
 */
const verdicts = new Map();

function isSsoUser(username) {
    if (verdicts.has(username)) return Promise.resolve(verdicts.get(username));
    return userIdByName(username)
        .then(isSsoBound)
        .then(function (sso) {
            verdicts.set(username, sso);
            return sso;
        })
        .catch(function (err) {
            // Never lock a form because a lookup failed: an administrator who
            // cannot reach the API still needs the dialog to work as it always
            // has. Not cached, so the next open retries.
            console.warn('[sso-user-guard] could not determine SSO status:', err.message || err);
            return false;
        });
}

// The username the dialog is editing, taken from the field rather than parsed
// out of the title -- a username may contain the em dash the title separates on.
function editedUsername(dialog) {
    const input = dialog.querySelector('input[type="text"]');
    return input && input.value ? input.value.trim() : '';
}

function dialogTitle(dialog) {
    const header = dialog.querySelector('.modal-header');
    return header ? header.textContent.trim() : '';
}

function lock(dialog) {
    const controls = dialog.querySelectorAll('input, select, textarea');
    if (!controls.length) return false;

    controls.forEach(function (el) {
        el.disabled = true;
        el.setAttribute('aria-disabled', 'true');
    });

    // Save only. Cancel and the header's close button stay live, or the dialog
    // becomes a trap.
    dialog.querySelectorAll('.modal-foot button').forEach(function (btn) {
        if ((btn.textContent || '').trim() === 'Save') {
            btn.disabled = true;
            btn.setAttribute('aria-disabled', 'true');
        }
    });

    const body = dialog.querySelector('.modal-body');
    if (body && !body.querySelector('.' + NOTE_CLASS)) {
        const note = document.createElement('div');
        note.className = 'hint ' + NOTE_CLASS;
        // Below, not above: it is inserted first, so the gap belongs after it.
        note.style.marginBottom = '11px';
        note.textContent = NOTE_TEXT;
        body.insertBefore(note, body.firstChild);
    }

    return true;
}

function handle(dialog) {
    if (dialog.getAttribute(HANDLED)) return;
    if (!EDIT_USER_TITLE.test(dialogTitle(dialog))) return;

    // Claim it before the lookup resolves, so a burst of mutations while the
    // dialog animates in does not start the same lookup several times.
    dialog.setAttribute(HANDLED, 'pending');

    const username = editedUsername(dialog);
    if (!username) {
        dialog.setAttribute(HANDLED, 'no-username');
        return;
    }

    isSsoUser(username).then(function (sso) {
        if (!dialog.isConnected) return;
        if (!sso) {
            dialog.setAttribute(HANDLED, 'local');
            return;
        }
        dialog.setAttribute(HANDLED, lock(dialog) ? 'locked' : 'no-controls');

        /*
         * The dialog body is built imperatively and mounted inside a Radix
         * shell, so nothing should re-render it and undo this. Should that ever
         * change, re-apply rather than leave a form that looks locked and is
         * not. childList only: watching attributes would see our own disabled=
         * writes and loop.
         */
        const observer = new MutationObserver(function () {
            if (!dialog.isConnected) { observer.disconnect(); return; }
            lock(dialog);
        });
        observer.observe(dialog, { childList: true, subtree: true });
    });
}

function scan() {
    document.querySelectorAll('.modal-overlay [role="dialog"]').forEach(handle);
}

export function register(platform) {
    // platform is unused: this surface has no registration hook to hang off.
    // Taking the argument keeps the signature the console calls with.
    void platform;

    // The overlay is added to the document when a dialog opens, so the whole
    // body is the only thing worth watching. scan() is cheap -- one querySelector
    // over a page that holds at most a couple of overlays -- and every dialog it
    // finds is marked, so the repeated work is a no-op.
    const observer = new MutationObserver(scan);
    observer.observe(document.body, { childList: true, subtree: true });

    // A dialog already open when a plugin loads (possible on a slow first paint).
    scan();
}
