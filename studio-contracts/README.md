# Shared Studio ↔ Duoport contract, version 1

Source contract: `seekernimbus25/screenshot-match/contracts/kotlin/DuoportContract.kt`.

This module adds pure Kotlin serializable request/result types but does **not** add an exported camera Activity or change the recording engine. The contract is copied on purpose for initial isolated integration; eventually automate parity checks so types cannot drift.

Next: add an explicit StudioCaptureActivity that translates the request into existing Duoport shot/teleprompter/capture state, returns real recording metadata, and rejects unknown contract versions or unauthorized callers. Keep Duoport's current free-shoot launcher intact.

Build: `./gradlew :studio-contracts:build`. Phone-based integration is still required.
