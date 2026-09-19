# Proactive Provider Compatibility and Extra Context

Local implementation: 2026-09-19. Base commit: `61769239814cc8a0801b7d77babb79b38308c3d4`.
All previous proactive redesign changes remain in the working tree. No commit or push was made.

## Sources Reviewed

Repository: https://github.com/yaselli/orangechat

- `master`: `8e404d09755334f131ddecc168a38e51c57244dd`, dated 2026-09-04.
- `feature/liquid-glass-ui`: `47c12fe85f3145bc04b42e9d3e6e8d86569e63d7`, dated 2026-09-19.
- The feature branch contains the newer extra-info implementation, including
  `264d40a75a6ea719b94372f3f3533dbe26fb2300` (2026-09-14): independent source failures,
  cancellation propagation, timeouts and proactive collection.
- Sources were fetched into separate `yaselli-review/*` refs, not merged. Original AGPL notices are retained.

## Provider Fix

Previously, an initial proactive request consisted of SYSTEM plus historical messages.
An assistant-ended history became an unsupported model/assistant prefill on Gemini/Claude.
The local streaming placeholder could not fix the upstream request.

`beginProactiveRequest` now appends an explicit request-only USER background event.
Existing messages and their IDs are preserved. It is neither a new human utterance nor a saved chat message.
The existing role merger still runs on the provider copy only.

Tool-result turns retain their provider-specific call/result pairing. If visible assistant content follows
an executed tool result, a request-only continuation closes the request as USER. Unexecuted tool turns
are not interrupted by synthetic prompts. Local assistant ownership and cleanup guards remain intact.

## Selected Port

Extra Injection settings now include separately opted-in battery, weather, location/address,
current app, today's top three apps, foreground local OCR, recent notifications and relevant local memories.
Current time remains compatible with its existing independent toggle and proactive behavior.
New sensitive sources are disabled by default.

- Optional sources run concurrently, with individual timeouts and cancellation propagation.
- Normal chat attaches a snapshot to the original USER message's request-only copy, after other input
  transforms. It does not add dynamic device data to SYSTEM or emit it in GenerationChunk messages.
- A memory-only cache retains at most 32 conversation snapshots. Retry/approval continuation reuses
  a matching assistant/user/text/settings snapshot; a missing continuation snapshot never triggers fresh collection.
- Snapshots are not saved to chat, copied to external memory, or written into the proactive activity ledger.
- Background extra collection has its own default-off opt-in. App data also requires the existing
  proactive app-usage consent. Foreground OCR consent never enables unattended background screenshots.
- Weather uses a separate, unlogged HTTP client and rounded coordinates sent to wttr.in. The switch
  discloses that destination. Address lookup continues to use the user's configured Amap service.
- OCR and screenshot resources are released safely on completion/cancellation. Notification results
  are filtered again for the current 24-hour window. Each injected source is capped at 4,000 characters.
- Local memory SQL filters global/current-assistant scope before applying the result limit. No table
  or database version change is required.
- Extra-info sliders save on release, and settings updates use the latest settings snapshot.

Not ported: permanent hidden device-context history, repeated device refresh on every tool step,
implicit background OCR, unrelated service removals, UI/theme refactors or broad generation architecture rewrites.
The local Codex transport, MCP permissions, continuity sync, proactive ledger and existing tool approvals are retained.

## Verification

- `proactive-provider-extra-20260919.log`: full app/ai tests, lint and APK; successful in 7m12s.
  Lint: 0 errors, 504 warnings. This was before the final pending-tool guard.
- Final pending-tool guard and all module tests/APK verified by
  `proactive-provider-extra-final-20260919.log`: successful in 1m5s.
- Final test results: app 160 + ai 107 = 267, zero failures/errors.
- Added tests invoke actual Google/Claude message serializers for initial and continued proactive turns.
  Other tests cover immutable input history, snapshot identity, retries, bounded cache, consent, timeout and cancellation.
- `git diff --check` passed. APK v2 signature verified. New collector/transformer and proactive guard
  were found in DEX. Generated untracked schema output was removed; no database migration was introduced.

APK: `app/build/outputs/apk/debug/OrangeChat-Proactive-ProviderExtra-20260919-arm64.apk`

Package: `me.rerere.orangechat.liquidglass`; version `2.2.3` / `159`.

SHA-256: `25E40FC52F73AE25E0383677E7D8F82662A955A02922F0A0DBDA06DCBC890832`

The source remains uncommitted; the About-page Git ID still identifies the base, not these local edits.

## Phone Acceptance

1. Select Gemini or Claude, leave a conversation ending in assistant, and manually trigger proactive judgment.
   Check that neither reported prefill error occurs. WAIT/STOP need not produce a notification.
2. Test a permitted read-only tool round, then confirm no repeated human message or overwritten assistant bubble appears.
3. In Extra Injection, enable one source at a time. Confirm lack of Android permission skips the source
   without failing chat; disabling it stops new observations. Weather requires network and location permission.
4. Confirm normal chat and tool continuation use one snapshot, not new readings each tool step.
   Background extra collection remains off until explicitly enabled; OCR remains foreground-only.
5. Retest the prior activity ledger, follow-up cap and quiet-hours behavior on the phone.

No real model/network credentials or connected device were used in this verification. Real provider acceptance,
notification delivery, weather/location/OCR behavior, cache-hit rate and mobile UI remain device/API acceptance items.
The separately reported Gemini numeric-enum error and follow-up limit discrepancy were not reproduced here;
they are not claimed fixed by this request-role patch.
