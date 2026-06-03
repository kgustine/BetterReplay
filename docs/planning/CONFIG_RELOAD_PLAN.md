# Config Reload Plan

## Goal

Add an admin-facing config reload path so server operators can apply most config edits without a full server restart, while being explicit about the settings that should only affect new sessions or still require a restart.

The current codebase already has one useful building block: `ReplayConfigManager.initialize()` rewrites missing keys/comments and ends with `plugin.reloadConfig()`. The hard part is not re-reading `config.yml`; the hard part is safely reconciling long-lived services and already-running recording/playback sessions that have copied config-derived state into fields.

## Difficulty Scale

- Low: reloading `plugin.getConfig()` is enough, or only a bounded one-shot action is needed.
- Medium: requires recreating a long-lived service or adding a small runtime coordination point.
- High: requires mutating or replacing state owned by active recording/playback sessions.
- Very High: requires a live backend swap, connection churn, or data-path migration while work may already be in flight.

## Runtime Buckets

- Live-read settings: consumers call `ReplayConfigSetting.*.getXxx(replay.getConfig())` at decision time. These can usually take effect immediately after reload.
- Service-scoped settings: values are copied into a service object during startup and need that service restarted or replaced.
- Session-scoped settings: values are copied into `RecordingSession`, `ReplaySession`, or `ReplayBlockManager` constructors. Reload can affect only new sessions unless those classes are refactored.
- Startup-only settings: values are only used during `Replay.onEnable()` and are not worth hot-swapping in a first implementation.

## Config Option Matrix

| Key | Default | Current owner / read pattern | Recommended reload behavior | Difficulty | Risk / notes |
| --- | --- | --- | --- | --- | --- |
| `Config-Version` | `6` | Managed by `ReplayConfigManager.initialize()` during config normalization. | Re-run config manager during reload, but do not treat this as an operator-facing runtime option. | Low | Internal migration/version stamp only. Risk is mainly accidental file rewrite churn, not runtime behavior. |
| `General.Check-Update` | `true` | Read in `Replay.checkForUpdate()` during startup only. | Apply to future manual update checks or next startup. Do not try to retroactively cancel a completed startup check. | Low | No meaningful hot state to mutate. If desired, reload could optionally trigger a fresh check when this flips to `true`. |
| `General.Storage-Type` | `file` | Read once in `Replay.initStorage()` to create `FileReplayStorage` or `MySQLReplayStorage`. | Restart required in v1. | Very High | Live backend swap can split writes across backends, invalidate retention/cache/storage references, and change where active recordings save when they stop. |
| `General.MySQL.host` | `host` | Read once in `Replay.initStorage()` when MySQL is selected. | Restart required in v1. | Very High | Requires replacing the connection manager/data source under active work. Same risk profile as `General.Storage-Type`. |
| `General.MySQL.port` | `3306` | Read once in `Replay.initStorage()` when MySQL is selected. | Restart required in v1. | Very High | Same as host. A live reconnect path would need backend draining and failure handling. |
| `General.MySQL.database` | `database` | Read once in `Replay.initStorage()` when MySQL is selected. | Restart required in v1. | Very High | Same as host, plus higher operator foot-gun risk if changed mid-flight. |
| `General.MySQL.user` | `username` | Read once in `Replay.initStorage()` when MySQL is selected. | Restart required in v1. | Very High | Same as host. Credential rotation while in-flight writes exist is easy to get wrong. |
| `General.MySQL.password` | `password` | Read once in `Replay.initStorage()` when MySQL is selected. | Restart required in v1. | Very High | Same as user. Also sensitive from an operational/auditing perspective. |
| `Recording.Chunk-Capture.Enabled` | `false` | Copied into `RecordingSession.chunkCaptureConfig` during session construction. | Affect new recordings only. | Medium | Active recordings already created or skipped a `ChunkCaptureCoordinator`; changing mid-recording would need coordinator replacement and state transfer. |
| `Recording.Chunk-Capture.Radius` | `1` | Copied into `RecordingSession.chunkCaptureConfig` during session construction. | Affect new recordings only. | Medium | Changing radius mid-recording changes tracked chunk interest and captured baseline coverage. Safe for new sessions; risky for active ones. |
| `Recording.Chunk-Capture.Capture-Interval-Ticks` | `20` | Copied into `RecordingSession.chunkCaptureConfig` during session construction. | Affect new recordings only. | Medium | Mid-recording change would require reconfiguring coordinator timing without losing already-tracked chunks. |
| `Recording.Chunk-Capture.Max-Unique-Chunks-Per-Recording` | `20000` | Copied into `RecordingSession.chunkCaptureConfig` during session construction. | Affect new recordings only. | Medium | Tightening the cap mid-recording is ambiguous once the current session has already crossed or approached the old limit. |
| `Playback.Speed-Step` | `0.2` | Copied into `ReplaySession.speedStep` in the constructor. | Affect new replay sessions only. | Medium | Existing replay viewers keep the old control increment until their session ends. Making this live would require mutable session config instead of final fields. |
| `Playback.Max-Speed` | `1.0` | Copied into `ReplaySession.maxSpeed` in the constructor. | Affect new replay sessions only. | Medium | Existing sessions keep the old clamp. If changed lower than a currently running speed, a live update would also need to clamp active sessions immediately. |
| `Playback.Viewer-Safety-Mode` | `creative` | Read live by `ReplayViewerStateManager.applyReplaySafety()` when a replay starts. | Immediate for newly started replay sessions. | Low | Active viewers already put into replay safety are not retroactively changed. That is acceptable and should be documented in command output. |
| `Playback.Restore-Viewer-Location-On-Stop` | `true` | Read live by `ReplayViewerStateManager.restoreViewerState()`. | Immediate, including for active sessions that stop after the reload. | Low | Behavior at stop time changes for existing sessions, which is probably what admins want. |
| `Playback.Restore-Viewer-GameMode-On-Stop` | `true` | Read live by `ReplayViewerStateManager.restoreViewerState()`. | Immediate, including for active sessions that stop after the reload. | Low | Same as location restore. |
| `Playback.Restore-Viewer-Flight-On-Stop` | `true` | Read live by `ReplayViewerStateManager.restoreViewerState()`. | Immediate, including for active sessions that stop after the reload. | Low | Same as other restore toggles. |
| `Playback.Restore-Viewer-State-On-Rejoin` | `true` | Read live by `ReplayViewerStateManager.onPlayerJoin()`. | Immediate, including already-queued pending restores. | Low | Pending restore entries already exist in memory, but the allow/deny decision is made at join time, so reload works naturally. |
| `Playback.Chunk-Mode` | `1` | Copied into `ReplayBlockManager.chunkPlaybackMode` in the constructor. | Affect new replay sessions only. | High | Changing mode live would invalidate assumptions about chunk resend/restore queues already built for the active session. |
| `Playback.Chunk-View-Radius` | `3` | Copied into `ReplayBlockManager.chunkPlaybackRadius` in the constructor. | Affect new replay sessions only. | High | Live mutation would require rebuilding visible replay/live chunk state, queue sizing, and in-flight preparation limits safely. |
| `Playback.Chunk-Send-Limit-Per-Tick` | `1` | Copied into `ReplayBlockManager.maxReplayChunkAppliesPerRefresh` in the constructor. | Affect new replay sessions only in v1. | Medium | Could become live later, but current constructor also derives in-flight prepare limits from it, so a full live update is not just a setter. |
| `Playback.Chunk-Clear-Limit-Per-Tick` | `1` | Copied into `ReplayBlockManager.maxLiveChunkRestoresPerRefresh` in the constructor. | Affect new replay sessions only in v1. | Medium | Same as send limit. The queue/backpressure math is derived at construction time. |
| `Playback.Chunk-Timing-Diagnostics` | `false` | Copied into `ReplayBlockManager.chunkTimingDiagnosticsEnabled` in the constructor. | Affect new replay sessions only in v1. | Medium | Technically simpler than other chunk settings, but keeping all chunk-playback options session-scoped avoids partial semantics in the first release. |
| `Retention.Enabled` | `false` | Copied into `RetentionPolicy`, then into `ReplayRetentionService` at startup. | Recreate and restart the retention service on reload. | Medium | Must stop the old task before starting the new one. If a scan is already running, let it finish rather than racing it. |
| `Retention.Max-Age` | `30d` | Copied into `RetentionPolicy` at startup. | Recreate and restart the retention service on reload. | Medium | Straightforward if policy replacement is atomic. Risk is duplicate scheduling or stale policy if service restart is incomplete. |
| `Retention.Check-Interval` | `1h` | Copied into `RetentionPolicy` at startup and converted to scheduler interval in `ReplayRetentionService.start()`. | Recreate and restart the retention service on reload. | Medium | Requires rescheduling the timer. Also preserve current minimum clamp behavior. |
| `Retention.Delete-Partial-Failures` | `false` | Copied into `RetentionPolicy` at startup. | Recreate and restart the retention service on reload. | Medium | Same restart requirement as other retention settings. |
| `Retention.Log-Deletions` | `true` | Copied into `RetentionPolicy` at startup. | Recreate and restart the retention service on reload. | Medium | Same restart requirement as other retention settings. |
| `List.Page-Size` | `10` | Read live in `ReplayCommand` when `/replay list` runs. | Immediate. | Low | No runtime coordination needed beyond `reloadConfig()`. |
| `List.Protected-Highlight-Color` | `&6` | Read live in `ReplayCommand` when `/replay list` runs. | Immediate. | Low | Same as page size. |

## Recommended Scope For V1

Implement `/replay reload` with three outcome buckets instead of pretending that every key is equally hot-reloadable.

### Apply Immediately

- `Config-Version` normalization via `ReplayConfigManager.initialize()`
- `General.Check-Update` for future behavior only
- `Playback.Viewer-Safety-Mode`
- `Playback.Restore-Viewer-Location-On-Stop`
- `Playback.Restore-Viewer-GameMode-On-Stop`
- `Playback.Restore-Viewer-Flight-On-Stop`
- `Playback.Restore-Viewer-State-On-Rejoin`
- `List.Page-Size`
- `List.Protected-Highlight-Color`

### Apply To New Sessions Only

- All `Recording.Chunk-Capture.*` settings
- `Playback.Speed-Step`
- `Playback.Max-Speed`
- All `Playback.Chunk-*` settings

### Reinitialize Internally During Reload

- All `Retention.*` settings by stopping and recreating `ReplayRetentionService`

### Explicitly Defer To Restart

- `General.Storage-Type`
- All `General.MySQL.*` settings

## Why Storage Should Stay Restart-Only Initially

`Replay.initStorage()` does more than parse config. It creates the active `ReplayStorage`, optional `MySQLConnectionManager`, and a `ReplayCache` populated from the chosen backend. A live swap would also need to answer these questions safely:

- What happens if a recording started under file storage but stops after the reload and now saves into MySQL?
- What happens to retention if it is holding a reference to the old storage while the cache points at the new one?
- What happens to in-flight async operations that were dispatched through the old storage implementation?
- How are backend initialization failures rolled back without leaving the plugin in a half-swapped state?

That is a different class of change from config reload convenience. It should be treated as a dedicated storage hot-swap project, not bundled into `/replay reload`.

## Proposed Command Semantics

Add a `/replay reload` subcommand and a dedicated permission such as `replay.reload`.

Suggested user-facing behavior:

1. Re-run `ReplayConfigManager.initialize()`.
2. Recreate the retention service from the new config.
3. Do not touch active recordings or active replay sessions.
4. Report a summary to the sender:
   - config file reloaded successfully
   - retention service restarted
   - some settings only affect new recordings/replays
   - storage backend changes still require restart

That command contract is honest, useful, and small enough to ship safely.

## Implementation Sketch

### Phase 1

- Add `Replay.reloadRuntimeConfig()` as the single orchestration point.
- In that method:
  - call `new ReplayConfigManager(this).initialize()`
  - stop the existing `ReplayRetentionService` if present
  - rebuild policy via `RetentionPolicy.fromConfig(getConfig(), getLogger())`
  - create and start a new `ReplayRetentionService`
- Add `/replay reload` handling to `ReplayCommand`
- Add permission wiring in `plugin.yml`

### Phase 2

- Improve reload result reporting so the command can explicitly list:
  - immediate keys
  - new-session-only keys
  - restart-required keys detected as changed
- Optionally trigger `checkForUpdate()` when `General.Check-Update` is enabled by reload

### Phase 3

- If live updates for active sessions become desirable, refactor session-scoped config into mutable runtime snapshots:
  - `ReplaySessionRuntimeConfig`
  - `ReplayBlockManagerRuntimeConfig`
  - `RecordingRuntimeConfig`
- Add narrowly-scoped mutation methods instead of re-reading config everywhere.

This phase should be separate from the first reload command. It has a materially higher regression risk.

## Validation And Test Plan For The Eventual Implementation

- Add command tests covering `/replay reload` success, permission denial, and sender messaging.
- Add retention service tests proving old timers stop and new policy values take effect after reload.
- Add regression tests proving live-read settings (`List.*`, viewer restore toggles) change behavior after reload.
- Add regression tests proving session-scoped settings only affect sessions created after reload.
- Add regression tests proving storage config changes are reported as restart-required and do not hot-swap the backend.

## Recommendation

Ship `/replay reload` as a bounded runtime refresh, not as a full hot-reconfiguration system.

That means:

- yes to config file reload
- yes to retention service restart
- yes to immediate live-read command/playback restore settings
- yes to new-session-only semantics for recording/playback constructor-copied settings
- no to live storage backend swapping in the first version

That scope is the best tradeoff between operator convenience and safety in the current architecture.