# Native Google Sign-In (Credential Manager → Supabase Auth)

**Status: implemented and unit-tested; not yet verified with a real Google account.**
Nothing here is production-ready until the manual test below passes on a real phone.

The APK signs in with Google through Android **Credential Manager** (no Firebase, no custom
domain, no browser redirect) and exchanges the Google ID token with the existing Creatorflow
Supabase Auth project `tnfxmvlwanxpssjxbesf`. Email and password sign-in still works.

## How it works
1. The app makes a random raw nonce (32 bytes, base64url) and gives Google its SHA-256 hex
   (`GetSignInWithGoogleOption.setNonce`). `serverClientId` is the OAuth **Web** client ID, so the
   ID token's audience is the Web client.
2. Credential Manager shows the Google account sheet and returns a `GoogleIdTokenCredential`.
3. The app sends `POST /auth/v1/token?grant_type=id_token` with
   `{provider: "google", id_token, nonce: <raw nonce>}` and the publishable key. Supabase checks
   the token signature, the audience (against the Google provider's Client IDs list) and that
   SHA-256(raw nonce) matches the nonce in the token.
4. The Supabase session (access + refresh token, provider `google`) is stored like an email
   session: app-private preferences, `allowBackup=false`.

Code: `data/GoogleAuth.kt` (nonce, client-ID check, error text), `data/GoogleCredentials.kt`
(Credential Manager), `data/StudioAuthClient.kt` (`signInWithGoogle`, `user`, `logout`),
`sync/AccountLink.kt` (ownership gate and RLS check), Settings → *Your Studio account*.

## One-time setup (done by the project owner, not in code)

### Google Cloud Console → APIs & Services
1. **OAuth consent screen**: app name, support email, scopes `openid`, `email`, `profile`.
   While the app is in *Testing*, add every tester's Google account under *Test users*.
2. **Credentials → Create OAuth client ID → Web application.** No redirect URI is needed for
   the native flow. Copy the **client ID** (`…apps.googleusercontent.com`). The client
   **secret** (`GOCSPX-…`) is not used by the APK; never put it in the repo or a build variable.
3. **Credentials → Create OAuth client ID → Android**, once per signing key:

   | Build | Package | SHA-1 |
   |---|---|---|
   | Sideload / CI (debug and release, `keystore/sideload.jks`) | `com.dualframe.camera` | `A2:7F:1F:0D:C3:D9:7D:82:F2:AC:0F:34:7B:0E:54:42:2F:AA:3F:8F` |
   | Google Play | `com.dualframe.camera` | Play Console → *Test and release → App integrity → App signing key certificate* SHA-1 (add when the app is on Play) |

   You never paste the Android client ID into the app. Google matches the APK by package name
   plus signing SHA-1. If they don't match, the sheet fails with a developer or "reauth" error.
   To recheck a SHA-1: `keytool -list -v -keystore keystore/sideload.jks -alias dualframe -storepass dualframe`.

### Supabase Dashboard (project `tnfxmvlwanxpssjxbesf`) → Authentication → Sign In / Providers → Google
1. Turn **Enable Sign in with Google** on.
2. **Client IDs**: the Web client ID (you can add the Android client IDs too, comma-separated;
   the Web one must be listed, since it is the token's audience).
3. **Client Secret**: only needed if the web Studio also uses Google's redirect flow. It lives
   only in this dashboard field.
4. Leave **Skip nonce checks OFF**. The app always sends a nonce.

### Build variable (public identifier)
Set the Web client ID so CI builds enable the button:
GitHub → repository **Settings → Secrets and variables → Actions → Variables** →
`STUDIO_GOOGLE_WEB_CLIENT_ID` (a repository *secret* of the same name also works).
Locally: `export STUDIO_GOOGLE_WEB_CLIENT_ID=…` or `studio.googleWebClientId=…` in
`~/.gradle/gradle.properties` (never the project's `gradle.properties`).
The build fails if the value is not shaped like `<digits>-<id>.apps.googleusercontent.com`,
so a `GOCSPX-…` secret can't slip in. Without it, the button is shown disabled with
"Google sign-in isn't set up in this build".

## Local work, ownership and RLS (nothing is deleted or overwritten)
- Projects, scenes, takes and unsynced external-camera clips live in Room on the phone. Signing
  in or out never deletes or rewrites them.
- Before linking or syncing, the app checks with the server that the session is real
  (`GET /auth/v1/user` id equals the session's user id), then checks Row Level Security on the
  live project: every sync table answers, no other account's rows are visible
  (`user_id=neq.<me>` returns `[]`), and an insert claiming another user's id into `ideas` is
  refused (401/403 or `42501`). If any check fails, sync stays off with the reason shown and the
  work stays on the phone.
- First sign-in on a phone with no work: linked silently. With existing unlinked work: a dialog
  asks *Link and sync* or *Not now*. Nothing is copied before the person agrees.
- Local work is linked to one account (`studio_local_owner`). Signing in with a **different**
  account keeps sync off ("this phone's work is linked to …"), so two accounts' data are never
  mixed. Older installs that synced before linking existed: the account with a sync base on this
  phone is treated as the owner.
- **Sign-out** revokes this device's refresh token (`/auth/v1/logout?scope=local`), clears
  Credential Manager's remembered choice, cancels background sync and keeps all local data and
  the owner link. Signing back in with the same account resumes sync.
- **Session restore**: on launch the stored session is refreshed and confirmed with the server.
  If the refresh token was revoked, the app signs out with a message. When offline, sync waits
  ("Offline: couldn't confirm your account…") and local work is untouched.

## Automated checks
- JVM `GoogleSignInFlowTest` (runs in CI): nonce hashing, client-ID validation (rejects
  `GOCSPX-…`), the exact id_token request (path, apikey, body, parsed session), user check,
  local logout, refresh keeps the provider, Supabase error messages, the ownership gate, and the
  RLS check (accepts a refusal; fails and removes the row if a foreign insert is accepted).
- Emulator COMB-20: the button exists. Without a client ID it shows the "not set up" message.
  With one, tapping it on an account-less emulator ends in a plain message, not a crash.

## Test sign-in flow (manual, real phone)
Use a tester Google account. Don't create accounts in production for others without
their agreement.
1. Install the CI APK (`Studio-Duoport-apk` artifact) built with `STUDIO_GOOGLE_WEB_CLIENT_ID` set.
2. Create an idea and a project offline first (to see the link dialog later).
3. Settings → *Your Studio account* → **Continue with Google** → pick the tester account.
4. Expect "Signed in as <email> (Google)", then the **Link this phone's work…** dialog → *Link and sync*.
5. Cloud sync section → *Sync now* succeeds. Check the rows in Supabase Table Editor (`ideas`,
   `projects`) carry the new user's `user_id`.
6. Force-stop and reopen: still signed in (session restore), no new sign-in sheet.
7. Sign out: email/Google buttons return, the idea and project are still on the phone.
8. Sign in with a **second** Google account: sync shows it is paused because the work belongs to
   the first account; nothing appears in the second account's tables.
9. Sign in with the first account again: sync resumes.

## Verify in Supabase → Authentication → Users
1. Open the Supabase Dashboard for project `tnfxmvlwanxpssjxbesf` → **Authentication → Users**.
2. Find the tester's email. **Provider** shows `Google` (or `Email, Google` if the same email
   already had a password account; Supabase links identities with the same verified email).
3. **Last sign in** matches the time of step 3. Open the user: *Identities* lists `google` with
   the Google account's `sub`.
4. Optional SQL (SQL Editor, read-only):
   ```sql
   select u.id, u.email, u.last_sign_in_at, i.provider, i.last_sign_in_at as identity_sign_in
   from auth.users u join auth.identities i on i.user_id = u.id
   where i.provider = 'google' order by i.last_sign_in_at desc limit 10;
   ```
5. After step 7, **Authentication → Users → the user → Sessions** (or Auth Logs) shows the
   device session ended. Other devices stay signed in (`scope=local`).

## Troubleshooting
| Message in the app | Cause |
|---|---|
| "…client ID is not on the Supabase Google provider's Client IDs list yet." | Add the Web client ID in Supabase (step 2 above). |
| "Google sign-in is not switched on…" | Supabase Google provider disabled. |
| "Google sign-in failed (…)" / developer error | Android OAuth client package/SHA-1 doesn't match this APK's signing key, or consent screen in Testing without this test user. |
| "No Google account is available…" | Add a Google account on the phone. |
| "…nonce mismatch…" | Clock skew or a stale sheet; retry. Don't turn on *Skip nonce checks*. |
