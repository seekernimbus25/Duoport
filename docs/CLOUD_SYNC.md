# Cloud sync (Room ⇄ Supabase)

The phone stays the primary store. Signing in (Studio Settings) enables a two-way sync with the
same Supabase project and tables the web Studio uses (`screenshot-match/CLOUD_BACKEND.md`).

## What syncs
| Native collection | Cloud table |
|---|---|
| ideas, projects, scenes, shots, takes | same names |
| media | `media_assets` (metadata + device URI only; footage never uploads) |
| artifacts (with embedded versions) | `artifacts` + `artifact_versions` |
| aiConversations / aiMessages | `ai_conversations` / `ai_messages` |
| treatments, beats, shotProposals, diagrams, storyboards, recipes, shootDays, setups, scheduleProposals, checklist, continuity, omissions, references, … | `production_items` with the web `kind` |
| externalClips | `production_items` kind `external_clip` — **needs** `screenshot-match/supabase/migrations/20261120120000_external_clip_kind.sql` applied; until then these rows are reported as sync errors and stay on the device |

## Rules (identical to the web planner)
Each record keeps a per-user *base* `{version, payloadHash}` (Room table `sync_base`).
Local change + unchanged cloud → conditional `PATCH … where version = base`. Cloud change +
unchanged local → pull. Both changed, or deleted on one side and edited on the other → **conflict**:
the Settings screen lists each one with *Keep this device*, *Keep cloud* or *Decide later*.
No last-write-wins. A write that loses the version race becomes a conflict. Retries are idempotent.
Deletes are tombstones (`deleted_at`). Pushes go parent-first. A local recovery point
(`files/recovery/before-sync-*.json`, last 5) is written before any cloud data changes the device.
An edit made while a sync is running is never overwritten; its base is held back so the next
sync reports it instead.

## Sessions and background work
The refresh token is stored in app-private preferences (`allowBackup=false`); the access token
is renewed automatically when it is about to expire. A revoked refresh token signs the device out.
WorkManager runs a sync every 6 hours on any network while signed in, and shortly after sign-in.
Background runs never resolve conflicts.

## Verification status
- Logic: JVM tests (`app/src/test/.../SyncTest.kt`) drive two simulated devices against an
  in-memory cloud with the trigger semantics: create, pull, edit propagation, tombstone
  propagation, both-edited conflict with cloud untouched until resolved, keep-mine, keep-cloud,
  deleted-vs-edited, lost version race, idempotent retry, first-sync difference, artifact versions.
- Schema: every table and column the client sends was checked read-only against the live project
  (PostgREST returned 200 for all 13 tables). The refresh-token request format was checked too.
- **Not yet verified:** an authenticated sync against the live project on two real devices.
  That needs a real account; do it before relying on cloud data.
