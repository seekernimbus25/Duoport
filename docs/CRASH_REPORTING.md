# Combined Studio + Duoport Android crash reporting

## Architecture

Do not email from a crashing Android process. Android can terminate before the message is sent. A crash SDK keeps a bounded report on device and uploads when the app runs again and has connectivity; alerts go from a private service to project maintainers.

Implemented:
- Sentry Android SDK captures uncaught Kotlin/Java errors and ANRs.
- Sentry Android NDK module covers native camera/codec crashes (native symbols need separate upload to decode addresses).
- Studio Settings > Crash diagnostics has an explicit opt-in switch (default OFF).
- No reporting if Sentry is not configured or the user did not opt in.
- Diagnostic breadcrumbs use fixed developer labels, not user prompts, scripts, filenames, videos, or account data.
- No screenshot / view hierarchy attachment and default personally identifiable info sharing disabled.
- Safe test button captures a non-fatal test event; do not deliberately crash users' apps.
- Logs from the AWS Lambda backend go to CloudWatch separately.

## Finish configuration

1. Create a private Sentry Android project at https://sentry.io (package ID com.dualframe.camera).
2. Obtain its DSN, NOT an authentication token. A DSN is a public client-side routing identifier.
3. In GitHub repository seekernimbus25/Duoport > Settings > Secrets and variables > Actions > New repository secret, name it STUDIO_SENTRY_DSN and paste the DSN as the value.
4. Build the single APK again from branch work/unified-studio-duoport-apk. GitHub Build APK workflow injects this secret into the Gradle build for release and Play, not for emulator tests.
5. Install the new APK. In Studio Settings enable Share crash reports; tap Send safe diagnostic test event. Inspect Sentry > Issues and verify the event arrived.
6. In Sentry > Alerts add an issue alert for new, regressed or spiking crashes, delivered by email to authorized team members. The receiving addresses are configured in Sentry, not in the Android APK.
7. Update the privacy notice and Play Store Data Safety form before distributing. Keep an eye on event volume, retention, and Sentry plan limits.

## Caveats

- Reports can be queued offline and uploaded after restarting; no tool guarantees every crash reaches the server immediately.
- ANR and native crash capture can vary by OS, SDK, and device; native symbolication requires uploading debugging symbols for your own libraries.
- Crash stacktraces and exception messages may inadvertently contain private details. Do not put AI inputs, tokens, recording URIs, scripts or user data in exception messages.
- Turning off consent closes the SDK and prevents further reporting during that run. Previously uploaded reports remain subject to the Sentry project's retention and deletion policies.
- Crash reports from AWS Lambda are independent and appear in CloudWatch, not automatically in Sentry.
