# Publishing DualFrame on Google Play

The code is ready for Play: it targets Android 16 (API 36), asks for only the camera and
microphone, collects no data, and CI builds a signed `.aab` once you add an upload key.
These are the steps only you can do.

## Before you start: the name is permanent

Once uploaded, the package ID `com.dualframe.camera` can never change. The display name
("DualFrame") can be changed any time. If you want a different ID (for example to match
"Duoport"), change it before the first upload.

## 1. Create your upload key (once, about 5 minutes)

Google signs the app for users with its own key. You sign uploads with an **upload key**
that only you hold. It must never go in git.

**With Android Studio:** Build → Generate Signed App Bundle → Create new… Save it as
`upload.jks` with alias `upload`.

**Or with a terminal** (needs Java installed):

```
keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Keep `upload.jks` and both passwords in a password manager. If you lose them, Play support
can reset the upload key, but that takes days.

## 2. Give the key to the build (GitHub secrets)

Turn the key file into text:

- macOS: `base64 -i upload.jks | pbcopy`
- Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload.jks")) | Set-Clipboard`
- Linux: `base64 -w0 upload.jks`

On GitHub open the repo → Settings → Secrets and variables → Actions → New repository
secret, and add:

| Name | Value |
|---|---|
| `UPLOAD_KEYSTORE_BASE64` | the text you just copied |
| `UPLOAD_KEYSTORE_PASSWORD` | the keystore password |
| `UPLOAD_KEY_ALIAS` | `upload` |
| `UPLOAD_KEY_PASSWORD` | the key password |

From the next push to `main`, each release also has **DualFrame.aab**. That's the file you
upload to Play. The `.apk` stays signed with the sideload key, so copies already installed
keep updating as before.

## 3. Publish the privacy policy

Play needs a public URL. The policy is `docs/privacy-policy.html`.

- **GitHub Pages** (the repo must be public, or on a paid GitHub plan): repo → Settings →
  Pages → Deploy from a branch → `main`, folder `/docs`. The URL is
  `https://seekernimbus25.github.io/Duoport/privacy-policy.html`.
- Or paste the same text into any free page (Google Sites, Notion public page) and use
  that URL.

## 4. Play Console

1. Sign up at play.google.com/console ($25 one-time, ID verification).
2. Create app → name **DualFrame**, App, Free.
3. App content (Policy → App content):
   - Privacy policy: the URL from step 3
   - Ads: No
   - App access: all functionality is available without special access
   - Content rating: answer the questionnaire (camera utility, no user-to-user content)
   - Target audience: 13+ (or 18+), not designed for children
   - Data safety: **No data collected, no data shared.** Camera and microphone are used
     only on the device.
   - Government / financial / health apps: No
4. Store listing: copy from `docs/store-listing.md`, upload the images from `docs/play/`,
   and add 2–8 phone screenshots.

## 5. Testing and release

New personal developer accounts must run a **closed test with at least 12 testers for 14
days** before they can publish to everyone.

1. Testing → Closed testing → Create track → upload `DualFrame.aab` from the latest release.
2. Add testers (an email list or a Google Group) and send them the opt-in link.
3. Check **Pre-launch report** in the Console: Google installs the app on real phones from
   different makers and reports crashes. It's the best check that it works beyond your own
   phone.
4. After 14 days: Production → Apply for production access → create a release with the
   newest `.aab`.

Each new build: push to `main`, wait for the release, upload its `.aab` in the Console.
Version codes rise automatically with the build number.
