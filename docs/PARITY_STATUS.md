# Native parity status (web → Android), 20 Nov 2026

Release acceptance is `screenshot-match/FEATURE_PARITY_CHECKLIST.md`. "Automated" names the test that
checks the acceptance rule; "Device QA" is what still needs a real phone (none of it is done yet).

| # | Feature | Native implementation | Automated check | Still unverified |
|---|---|---|---|---|
| 1 | Idea capture text/voice/image/link | Ideas tab: text, link, photo (downscaled copy), voice note (MediaRecorder, app storage), expansions; `original` never edited | `continuityCorrectionKeepsOriginalAndIdeaOriginalUnchanged`, COMB-02 | mic/photo picker on device |
| 2 | Convert idea → project | `convertIdea` (idempotent) | existing instrumentation, COMB-03 | — |
| 3 | Artifacts & versions | Script approve freezes a version; edits change the draft | `treatmentApprovalSupersedes…`, `critiqueRevision…` | — |
| 4 | AI Director chat | Persistent conversations (synced), mode/role, archive retrieval toggle, citations shown, archive search | `conversationsPersistWithCitations` | live authenticated call, NDJSON streaming is buffered (no token-by-token UI) |
| 5 | Treatments | AI proposals accepted as drafts; approve supersedes | existing instrumentation, COMB-07 | live AI call |
| 6 | Beat board | Up/down reorder swaps `order` only; shot links | `beatReorderKeepsShotLinks…` | drag reorder (buttons only) |
| 7 | Shot proposals → shots | AI ideas per beat saved as pending; accept/reject once; accepted shot keeps `beatId` + `proposalId` | same test | live AI call |
| 8 | Script critique | Critique dialog; revision saved as new **draft** version (author assistant) | `critiqueRevisionIsANewDraft…` | live AI call |
| 9 | Storyboards | Photo → ≤960 px JPEG data URI; one chosen frame per shot; ZIP includes images | `storyboardChosenIsUnique…` | drawing/sketch input, AI image generation (paid) not built |
| 10 | Diagrams (metres) | Element list editor; editing an approved diagram forks a draft (`parentId`); approve supersedes | `…DiagramForksWhenApproved` | graphical canvas editor (list editor only) |
| 11 | Lookbook recipes | Save diagram as recipe; apply copies elements with new ids; recipe unchanged | same test | — |
| 12 | AI cinematographer | `op: setup`; unknown ids dropped; preview then accept | same test (preview rules) | live AI call |
| 13 | Shoot days & orders | Day order editor; approve keeps history; `storyOrder` untouched | `shootingOrderNeverChangesNarrativeOrder…` | — |
| 14 | AI schedule | Pending proposal, applied only on Approve; history kept | same test | live AI call |
| 15 | Production setups | Impact list; a shot belongs to one setup; approved diagrams untouched | `checklistRegenerate…AndSetupsAreExclusive` | — |
| 16 | Pre-shoot checklist | Regenerate from shot plans by stable key; statuses kept; optional never blocks | same test | — |
| 17 | Shoot Mode | Full screen, ≥64 dp buttons: record, log external file, room tone, usable/unusable, prefer, complete, next | COMB-12 | on-set usability |
| 18 | Duoport capture | Activity-result contract; cancel/fail create nothing; measured fps/size/codec | existing instrumentation, COMB-05/09 | 4K/codec on real hardware |
| 19 | Takes extras | Ratings 1–5, audio and room-tone takes, actual settings shown | `roomToneTakeSatisfiesScene…` | — |
| 20 | Continuity | Corrections keep history; record vs speculative; resolve | `continuityCorrection…` | continuity photos not built |
| 21 | Coverage & pickups | Dashboard; pickup → shot in same scene; intentional omissions | `roomToneTakeSatisfiesSceneAndPickup…` | AI pickup suggestions (`op: production`, saved as pending pickup proposals) not live-tested |
| 22 | Wrap & readiness | 4 levels; filename-only clips and unverified media never "verified" | `packageNeverClaims…`, COMB-13 | — |
| 23 | Export JSON/MD/CSV/ZIP | Pure builder; CI runs web `validate-package.mjs` | `ProductionAndMediaTest` + `scripts/validate_export.sh` | opening the files in an editor |
| 24 | Archive upload/search/DNA | Upload PDF/DOCX/MD/TXT to private `studio-files/<uid>/archive/…`, `archive_documents` row, ingest; hybrid search | — | live upload/ingest, Creative DNA UI and the two-account isolation test are **not done** natively |
| 25 | Cloud copy & sync | Room ⇄ Supabase, conflicts, tombstones, WorkManager (docs/CLOUD_SYNC.md) | `SyncTest` (two simulated devices) | live two-device sync with a real account; `external_clip` migration must be applied |
| 26 | Backup/import | Keeps every id; refuses conflicting records | existing instrumentation | — |
| 27 | Camera profile | Camera2 probe → `verified: true` only when device-reported | `cameraProfileIsVerifiedOnlyWhenDeviceReported`, COMB-14 | — |

External camera footage import (handoff step 5) is covered by
`externalFootageImportVerifiesChecksumAndOnlyMatchesLoggedNames` (real MediaStore content URI).

Not production-ready: no physical-device QA, no live authenticated AI or sync run, Sentry not
receiving events until `STUDIO_SENTRY_DSN` is set, no release signing / Play publishing.

## Verified CI evidence (same commit `f900ae7`)
- Android 14 emulator: **21/21** instrumentation tests and **14/14** UI acceptance cases (COMB-01…14) —
  https://github.com/seekernimbus25/Duoport/actions/runs/44705358334
- Release build + JVM unit tests + web `validate-package.mjs` on the exported package —
  https://github.com/seekernimbus25/Duoport/actions/runs/44705358228
