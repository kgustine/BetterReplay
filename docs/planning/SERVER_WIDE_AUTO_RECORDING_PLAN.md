# Server-Wide Auto Recording Plan

This plan covers three related capabilities:

- dynamic player enrollment for an already-running recording
- one-off all-player recording
- rolling auto-recording for one or more players or all players

The important design point is that dynamic enrollment is the reusable primitive. Server-wide recording and future recording modes should use the same add-player path instead of each feature inventing its own join logic.

Example: with 30-minute auto-record segments enabled, BetterReplay should produce 48 recordings per day while eligible targets are present.

## Goals

Add these capabilities in a way that fits the current recording pipeline:

1. Allow a player to be added to a running recording through internal code, commands, and the public API.
2. Add an all-player recording mode that starts with current online players and can enroll qualifying joiners while active.
3. Add a rolling auto-record controller that creates fixed-duration segments for `all` or one or more named players.
4. Keep `RecordingSession` as the low-level recording primitive and put policy in manager/controller classes.

## Non-Goals for the First Iteration

- No retroactive capture for time before a player was added to the recording.
- No merging of multiple segment files into one replay.
- No cross-server or proxy-wide coordination.
- No retention or pruning policy as part of the first implementation.
- No new storage backend behavior.
- No new timeline event type unless existing playback cannot reliably spawn a late-joining player from the first `PlayerMove` snapshot.
- No config option that changes the meaning of `all`; an all-player recording should naturally include future joiners.
- No special support for a player literally named `all`; treat `all` as a reserved target keyword.

## Current Constraints in the Codebase

The current implementation is optimized for a fixed player list chosen when the session starts:

- `RecorderManager.startSession(...)` creates a `RecordingSession` from a `Collection<Player>`.
- `RecordingSession` constructs `EntityTracker` once and treats that tracked-player set as the session scope.
- `EntityTracker` supports removing and clearing players but not adding players after construction.
- `RecordingSession.captureInitialInventory()` only runs during `start()`.
- `RecordingEventHandler.onQuit(...)` records `PlayerQuit` and removes that player from the tracked set.
- `RecorderManager` and `ReplayManager` do not expose a method for adding players to an active recording.
- `/replay start` currently requires explicit online player names.

This means the missing capability is not mainly storage. The missing capability is a runtime enrollment primitive plus a small policy layer around the existing recording pipeline.

## Design Principles

- Keep `RecordingSession` responsible for recording mechanics: tracked players, snapshots, ticks, append logs, chunk capture, stop, and save.
- Keep `RecorderManager` responsible for active session registration, lookup, tick ownership, and manager-level operations.
- Put all-player and auto-record policy in a controller/listener layer, not inside `RecordingEventHandler`.
- Add players through one code path so manual add, all-player join, target rejoin, and future modes behave consistently.
- Keep Bukkit API access on the server thread through the existing FoliaLib scheduling rules.
- Add any new config keys through `ReplayConfigSetting` with explicit `ReplayConfigReloadScope` values.
- Preserve existing targeted `/replay start` behavior unless a change is explicitly documented and tested.

## Recommended Feature Shape

### 1. Dynamic Enrollment Primitive

Add player enrollment as a first-class operation before adding auto-recording.

Recommended low-level additions:

```java
public boolean addPlayer(UUID uuid)
```

on `EntityTracker`, returning `true` only when the UUID was newly added.

```java
public RecordingPlayerAddResult addTrackedPlayer(Player player)
```

on `RecordingSession`.

Recommended manager/API additions:

```java
RecordingPlayerAddResult addPlayerToSession(String recordingName, Player player);
RecordingPlayerAddResult addPlayerToRecording(String recordingName, Player player);
```

Use a result enum instead of a bare boolean so commands and integrations can give precise feedback:

```java
public enum RecordingPlayerAddResult {
    ADDED,
    ALREADY_TRACKED,
    SESSION_NOT_FOUND,
    SESSION_STOPPED,
    PLAYER_OFFLINE
}
```

Expected behavior:

- adding the same online player twice is idempotent
- adding to a stopped or missing session fails cleanly
- adding an offline player fails for this first iteration
- a successful add emits a baseline snapshot at the current recording tick
- normal movement, inventory, equipment, block, combat, and packet capture begin from that point forward

### 2. Session Target and Enrollment Policy

Separate the desired target from the currently tracked online player set.

Recommended model:

```java
public sealed interface RecordingTarget permits RecordingTarget.AllPlayers, RecordingTarget.Players {
    record AllPlayers() implements RecordingTarget {}
    record Players(Set<UUID> playerUuids) implements RecordingTarget {}
}

public enum RecordingEnrollmentPolicy {
    MANUAL_ONLY,
    TARGET_PLAYERS_ON_JOIN,
    ALL_PLAYERS_ON_JOIN
}

public record RecordingSessionOptions(
        RecordingTarget target,
        RecordingEnrollmentPolicy enrollmentPolicy,
        int durationSeconds,
        boolean autoRecordSegment
) {}
```

Why this shape is preferable:

- `RecordingTarget` answers who the session is intended to record.
- `RecordingEnrollmentPolicy` answers whether join events can add players automatically.
- The active tracked set remains an implementation detail of `RecordingSession`.
- Future modes can reuse the same target and enrollment model without new ad hoc wildcards.

Recommended policy mapping:

| Mode | Target | Enrollment policy |
|---|---|---|
| Current manual targeted recording | `Players(...)` | `TARGET_PLAYERS_ON_JOIN` |
| Manual all-player recording | `AllPlayers` | `ALL_PLAYERS_ON_JOIN` |
| Auto-record named player(s) | `Players(...)` | `TARGET_PLAYERS_ON_JOIN` |
| Auto-record all players | `AllPlayers` | `ALL_PLAYERS_ON_JOIN` |

Resolved first-iteration decision: ordinary targeted `/replay start <name> <players...>` and targeted auto-record sessions should use `TARGET_PLAYERS_ON_JOIN` so selected players are re-added when they rejoin. This is an intentional behavior change and must be documented and tested.

### 3. Enrollment Controller

Do not hide join policy inside `RecordingEventHandler`.

Add a small join/enrollment layer, either as a dedicated `RecordingEnrollmentController` or as a listener owned by `RecorderManager`.

Responsibilities:

- listen for `PlayerJoinEvent`
- find active sessions whose enrollment policy accepts the joining player
- call the same `addPlayerToSession(...)` path used by commands and API callers
- notify `AutoRecordController` when a waiting target becomes available
- avoid duplicate additions when multiple join handlers observe the same player

`RecordingEventHandler` should remain focused on serializing recording-time events for players that are already tracked.

### 4. Rolling Auto-Record Controller

Add a dedicated coordinator for long-running rolling recording.

Recommended name:

- `AutoRecordController`

Responsibilities:

- resolve the configured or command-provided target
- start a segment only when at least one eligible target is online
- wait when all named targets are offline or the server is empty for `all`
- stop and save the active segment when the segment duration is reached
- immediately start the next segment if at least one target is available
- stay idle after rollover if no target is available
- expose current status for commands and API callers
- stop cleanly during plugin disable without scheduling replacement segments

Important implementation detail:

- Do not rely on `RecordingSession` auto-stopping itself for auto-record rollover unless `RecorderManager` exposes a callback that the controller can observe.
- Prefer starting auto-record segments with an indefinite session duration and let `AutoRecordController` own the segment timer.
- This lets the controller stop through `RecorderManager.stopSession(...)`, fire normal stop behavior, and start the replacement segment intentionally.

## Dynamic Player Enrollment Details

`RecordingSession.addTrackedPlayer(Player)` should be the canonical path for a late add.

Recommended sequence:

1. Reject null, offline, stopped, or already tracked players with a specific result.
2. Add the player's UUID to `EntityTracker`.
3. Emit a `PlayerMove` event at the current tick before inventory or equipment events.
4. Capture and emit `EquipmentStateUpdate` at the same tick.
5. Capture and emit `InventoryStorageUpdate` at the same tick.
6. Update `lastEquipmentState` and `lastInventoryStorageSnapshot` so the next periodic check does not immediately duplicate the same state.
7. Clear or ignore dirty flags for the newly captured state.
8. If chunk capture is enabled, rely on the next configured chunk capture tick for the player's chunk baseline.

Snapshot ordering matters. Playback creates recorded players from location-bearing events such as `PlayerMove`. Emitting `PlayerMove` first makes late joins and seek/rebuild behavior more reliable.

The initial snapshot can reuse existing timeline events:

- `PlayerMove` at the current tick with name, world, pose, and location
- `EquipmentStateUpdate` at the current tick
- `InventoryStorageUpdate` at the current tick

Only add a new lifecycle event if tests show that this is insufficient for playback.

## Quit and Rejoin Semantics

Current quit behavior should remain the foundation:

- emit `PlayerQuit` when a tracked player leaves
- remove that player from the active tracked set after the quit event
- keep the recording session active for other tracked players

Recommended rejoin behavior by policy:

| Policy | Rejoin behavior |
|---|---|
| `MANUAL_ONLY` | do not automatically re-add the player; an admin or API caller must add them |
| `TARGET_PLAYERS_ON_JOIN` | re-add the player if their UUID is in the session target set |
| `ALL_PLAYERS_ON_JOIN` | add every joining player |

For all-player sessions:

- `PlayerQuit` should still despawn that player during playback.
- Rejoin should emit a fresh `PlayerMove`, equipment, and inventory snapshot.
- The second appearance should be treated as a new active presence in the same recording, not retroactive continuity.

For named-player auto-record sessions:

- if a tracked player quits, the active segment may continue until its configured duration ends
- if a target player rejoins before rollover, re-add them and continue the same segment
- if the segment ends while all target players are offline, do not start the next segment until at least one target returns

For auto-record all-player sessions:

- if the active segment becomes empty because all players left, let that segment run until its duration completes
- when that segment ends, do not start a replacement segment unless at least one player is online

## Command and UX Design

The most natural command shape is to keep `start` as the verb and make `all` a target.

Recommended command surface:

| Command | Permission | Console | Purpose |
|---|---|---|---|
| `/replay start <name> <player1 player2 ...> [seconds]` | `replay.start` | No, current behavior | Existing targeted recording workflow |
| `/replay start <name> all [seconds]` | `replay.start.all` | Yes, recommended | Start a one-off all-player recording |
| `/replay addplayer <recording> <player1 player2 ...>` | `replay.addplayer` | Yes | Add online players to an active recording |
| `/replay autorecord start <player1 player2 ...\|all> [--minutes <minutes>] [--prefix <prefix>]` | `replay.autorecord` | Yes | Start rolling auto-record for named players or all players |
| `/replay autorecord stop` | `replay.autorecord` | Yes | Stop rolling auto-record and save the active segment |
| `/replay autorecord status` | `replay.autorecord` | Yes | Show rolling auto-record state |

Notes:

- Keep recording names single-token for this feature. Changing `/replay start` name parsing is a separate command parser change.
- Reserve `all` as a target keyword in the player-target position.
- Treat `all` as a reserved target keyword. Do not add special support for a player literally named `all` in the first iteration.
- Do not add a separate `/replay recordall` command unless there is a strong reason. `/replay start <name> all` reads naturally and matches the existing workflow.

### `/replay start <name> all [seconds]`

Purpose:

- start a one-off all-player recording
- seed the session with all currently online players
- add players who join while the session is active

Behavior:

- use the existing duplicate session-name failure behavior
- use existing duration semantics; omitted duration means indefinite
- if no players are online, register a pending all-player recording and start it when the first player joins
- reserve the requested recording name while pending so a second recording cannot claim it before the first player joins

Example:

```text
/replay start evening-build all 3600
```

### `/replay addplayer <recording> <player1 player2 ...>`

Purpose:

- explicitly add online players to a currently running recording

Behavior:

- works for targeted, all-player, and auto-record segments
- reports each requested player as added, already tracked, offline/not found, or session missing
- does not retroactively capture earlier activity
- does not change an all-player session differently than a join would; it just uses the same enrollment primitive immediately

Example:

```text
/replay addplayer incident-42 Steve Alex
```

This command is useful on its own and is also the public behavior proof that dynamic enrollment works before auto-record is added.

### `/replay autorecord start <player1 player2 ...|all> [--minutes <minutes>] [--prefix <prefix>]`

Purpose:

- enable rolling replay segments without requiring a plugin reload

Argument rules:

- at least one target is required: either `all` or one or more player names
- `all` must be the only target token; reject mixed forms such as `all Steve`
- `--minutes <minutes>` is optional and overrides the config default for the current runtime controller session
- `--prefix <prefix>` is optional and overrides the config default for the current runtime controller session
- runtime command choices should not be written back to `config.yml`
- numeric-only player names are valid, so avoid parsing the first positive integer as `segmentMinutes` in the multi-player form
- keeping the existing positional form for `all` and single-player targets is acceptable for compatibility, but the documented durable form should use explicit options

Target rules:

- `all` means all-player auto-record
- any other target values are treated as player names to monitor
- if all named players are offline when the command is run, the controller enters a waiting state
- if some named players are online, start the segment with those players and let `TARGET_PLAYERS_ON_JOIN` add the others when they join
- if target is `all` and the server is empty, the controller enters a waiting state

The multiple-player form is a small command-parser extension, not a recording-pipeline change, because `RecordingTarget.Players(Set<UUID>)` already supports more than one target. The main risk is ambiguity with positional `segmentMinutes`; numeric-only player names are legal, so the durable parser shape is to support explicit `--minutes` and `--prefix` options instead of guessing where the player list ends.

Examples:

```text
/replay autorecord start all
/replay autorecord start all --minutes 30
/replay autorecord start all --minutes 30 --prefix survival
/replay autorecord start Steve --minutes 20 --prefix suspect
/replay autorecord start Steve Alex --minutes 20 --prefix suspects
```

### `/replay autorecord status`

Recommended output:

- enabled or disabled
- target: `all` or player names
- active segment replay name, if any
- segment duration
- active name prefix
- current segment start time
- next scheduled rollover time
- waiting state when no segment is active

### Help and Tab Completion

Update `/replay` help text and completions to make the new surface discoverable.

Recommended help lines:

- `/replay start <name> all [seconds] - Start recording all online and joining players`
- `/replay addplayer <recording> <players...> - Add online players to an active recording`
- `/replay autorecord start <players...|all> [--minutes <minutes>] [--prefix <prefix>] - Start rolling auto-record`
- `/replay autorecord stop - Stop rolling auto-record`
- `/replay autorecord status - Show rolling auto-record status`

Recommended completion behavior:

- after `/replay start <name>`, suggest online player names plus the literal `all`
- after `/replay addplayer`, suggest active recording names
- after `/replay addplayer <recording>`, suggest online player names not already tracked by that session when feasible
- after `/replay autorecord`, suggest `start`, `stop`, and `status`
- after `/replay autorecord start`, suggest online player names plus the literal `all`
- after `/replay autorecord start <players...>`, keep suggesting online player names not already selected plus `--minutes` and `--prefix`
- after `/replay autorecord start all`, suggest `--minutes` and `--prefix` and do not suggest player names

## Permission Proposal

Add explicit permissions instead of treating full-server capture as ordinary targeted recording:

- `replay.start`: existing targeted recording
- `replay.start.all`: one-off all-player recording
- `replay.addplayer`: add players to active recordings
- `replay.autorecord`: start, stop, and inspect rolling auto-record
- `replay.*`: include all of the above

This keeps targeted recording permissions separate from full-server capture and live session mutation.

## Configuration Design

Configuration should provide startup defaults for auto-record. It should not be a general runtime state store; command/API choices that need to survive restart should be written to a dedicated auto-record state file instead.

Suggested shape:

```yaml
Recording:
  Auto-Record:
    Record-On-Startup: false
    Startup-Target: all
    Segment-Duration-Minutes: 30
    Name-Prefix: auto
    Save-Active-Segment-On-Shutdown: true
    Name-Timezone: UTC
```

Do not add `Join-Players-During-Active-All-Recordings` in the first iteration. If a session target is `all`, joining players should be included. A toggle that makes `all` mean only current players would make the feature harder to reason about.

Recommended config keys and reload scopes:

| Key | Default | Reload scope | Notes |
|---|---|---|---|
| `Recording.Auto-Record.Record-On-Startup` | `false` | `FUTURE_ONLY` | Starts auto-record policy during plugin startup only |
| `Recording.Auto-Record.Startup-Target` | `all` | `FUTURE_ONLY` | `all` or a player name used when startup recording is enabled |
| `Recording.Auto-Record.Segment-Duration-Minutes` | `30` | `NEW_SESSIONS_ONLY` | Default segment length for startup and command starts that omit minutes |
| `Recording.Auto-Record.Name-Prefix` | `auto` | `NEW_SESSIONS_ONLY` | Default generated replay-name prefix |
| `Recording.Auto-Record.Save-Active-Segment-On-Shutdown` | `true` | `IMMEDIATE` | Whether graceful shutdown saves the active auto-record segment |
| `Recording.Auto-Record.Name-Timezone` | `UTC` | `NEW_SESSIONS_ONLY` | Time zone used in generated segment names |

Validation rules:

- reject `Segment-Duration-Minutes <= 0`
- reject blank `Name-Prefix`
- reject prefixes containing characters invalid for file-backed replay names
- validate `Name-Timezone` with `ZoneId.of(...)` and fall back to `UTC` with a warning if invalid
- reject blank `Startup-Target`
- log the effective startup auto-record configuration when `Record-On-Startup` is enabled

Config and command precedence:

1. command or API call for the current runtime controller session
2. persisted auto-record state on plugin startup
3. startup config values when no persisted state exists
4. built-in defaults from `ReplayConfigSetting`

Examples:

- plugin startup can auto-start `all` or a named player based on config
- `/replay autorecord start Steve --minutes 15` overrides the current runtime session but does not rewrite config
- if config says `Record-On-Startup: true` and an admin stops auto-record in game, persisted disabled state keeps it stopped on restart until an operator starts auto-record again or removes the state file

## Auto-Record State Persistence

Command-started auto-record should survive graceful plugin/server restarts without rewriting `config.yml`.

Recommended approach:

- store controller intent in a small file under the plugin data folder, for example `plugins/BetterReplay/auto-record-state.yml`
- persist desired auto-record state, not the live `RecordingSession` object or current segment internals
- write the file atomically through a temporary file and replace operation to avoid corrupt state after a crash during save
- update the file when `/replay autorecord start` succeeds, when `/replay autorecord stop` succeeds, and during graceful plugin disable
- load the file after config, storage initialization, and append-log recovery, but before deciding whether config startup auto-record should start
- if persisted state and `Recording.Auto-Record.Record-On-Startup` are both present, persisted state should win because it reflects the last runtime operator choice
- if persisted state is disabled, keep auto-record disabled even when `Record-On-Startup` is true
- if persisted state is missing, fall back to `Record-On-Startup`

Suggested file shape:

```yaml
version: 1
enabled: true
target:
  type: players # all | players
  players:
    - 069a79f4-44e9-4726-a5be-fca90e38aaf5
segmentDurationMinutes: 30
namePrefix: auto
nameTimezone: UTC
saveActiveSegmentOnShutdown: true
updatedAt: 2026-06-14T00:00:00Z
```

Persistence behavior:

- `/replay autorecord start all --minutes 30 --prefix survival` writes enabled state with target `all`
- `/replay autorecord start Steve Alex --minutes 20 --prefix suspects` writes enabled state with a player UUID set after resolving names; store last-known names only if useful for status messages
- `/replay autorecord stop` writes `enabled: false`; this is easier to reason about than deleting the file when config startup is also enabled
- on graceful shutdown, save the active segment according to the configured/controller shutdown policy, then leave enabled state intact so auto-record resumes on next startup
- on hard crash, append-log recovery handles the interrupted segment; the state file only restarts the controller policy afterward

Do not persist pending one-off `/replay start <name> all` waits in this file. That command reserves a replay name for the current runtime only; auto-record persistence should be limited to the rolling controller.

## Replay Naming Strategy

Rolling recordings need deterministic, collision-resistant names.

Recommended format:

```text
<prefix>-yyyy-MM-dd-HH-mm-ss
```

Example:

```text
auto-2026-04-28-10-30-00
```

Recommendations:

- use wall-clock segment start time, not stop time
- use the configured `Name-Timezone`
- append a short numeric suffix if a name collision occurs
- do not fail rollover only because the generated name already exists

## Rollover Behavior

For a 30-minute configuration, the controller should produce one replay per 30-minute window while eligible targets are present.

Recommended rollover sequence:

1. Detect that the active segment duration has been reached.
2. Stop the current session through `RecorderManager.stopSession(name, true)`.
3. If at least one target player is available, immediately create the next session.
4. If no target player is available, switch to a waiting state instead of creating a blank segment.
5. Continue join enrollment against the new active session when the target mode allows it.

`RecordingSession.stop(true)` persists asynchronously after the append log is finalized. A replacement segment can start immediately after the old session is stopped without waiting for storage completion.

This keeps recording gaps as small as possible.

## Startup and Shutdown Behavior

### Startup

On plugin startup:

1. initialize config
2. initialize storage and replay cache
3. recover pending append logs
4. load persisted `auto-record-state.yml`, if present
5. start `AutoRecordController`
6. if persisted state exists and is disabled, leave auto-record stopped
7. if persisted state exists and is enabled, resume that controller intent
8. if no persisted state exists and `Record-On-Startup` is enabled, resolve the configured startup target
9. create the initial segment only if at least one target player is available, otherwise enter a waiting state

Recovery should happen before auto-record starts. Otherwise, a crash-recovered segment and a new live segment can overlap in confusing ways.

### Shutdown

On plugin disable:

- stop `AutoRecordController` first so it cannot schedule a replacement segment
- persist controller intent before shutdown completes so command-started auto-record resumes on next startup
- if configured, stop the active auto-record segment with save enabled
- then let the normal recorder shutdown handle any remaining non-auto sessions
- preserve append-log crash recovery behavior for hard crashes

Current `RecorderManager.shutdown()` stops active sessions with `save=false`. Auto-record save-on-shutdown needs either a controller-owned stop before that call or a manager shutdown overload that can apply a save policy.

## Storage and Playback Impact

This feature should fit the current binary append-log and replay archive flow.

Expected storage impact:

- higher event volume when all players are tracked
- many more replay files in auto-record mode
- more frequent save operations because of rolling segments

Expected playback impact:

- no new format requirement if a late add is represented by `PlayerMove`, `EquipmentStateUpdate`, and `InventoryStorageUpdate`
- existing `PlayerQuit` remains sufficient for despawn behavior
- seek/rebuild behavior must be tested because first-event ordering affects `RecordedEntityFactory`

Only add a new timeline event type if playback cannot consistently spawn a late-added player from the first `PlayerMove` snapshot.

## Concurrency and Threading Notes

Keep all enrollment and rollover decisions on the server thread.

Specific rules:

- `EntityTracker` currently uses `HashSet` and `HashMap`, so session mutation must not happen concurrently with ticking.
- `PlayerJoinEvent` enrollment must run synchronously on the server thread.
- command and API calls that mutate sessions should internally schedule onto the server thread through FoliaLib when called off-thread, because they may touch Bukkit `Player` state and session internals.
- async storage completion must not mutate active session state directly.
- `getTrackedPlayers()` should not be used by new code as a mutable control surface; prefer manager/session methods or defensive snapshots.

This aligns with the project rule that Bukkit API access must remain on the server thread.

## Recommended Implementation Sequence

### Phase 1: Dynamic Enrollment Primitive

- add `EntityTracker.addPlayer(UUID)` with idempotent behavior
- add `RecordingSession.addTrackedPlayer(Player)`
- emit `PlayerMove` before inventory/equipment snapshots for late-added players
- add `RecorderManager.addPlayerToSession(...)`
- add `ReplayManager.addPlayerToRecording(...)`
- add `/replay addplayer <recording> <players...>`
- add regression tests for late add, duplicate add, stopped session, and snapshot ordering

### Phase 2: Session Options and Join Enrollment Policy

- introduce `RecordingTarget`, `RecordingEnrollmentPolicy`, and `RecordingSessionOptions`
- add a `RecorderManager.startSession(...)` overload that accepts options
- add a join enrollment listener/controller
- use `TARGET_PLAYERS_ON_JOIN` for manual targeted recordings and targeted auto-record sessions
- add tests for `MANUAL_ONLY`, `TARGET_PLAYERS_ON_JOIN`, and `ALL_PLAYERS_ON_JOIN`

### Phase 3: Manual All-Player Recording

- add `/replay start <name> all [seconds]`
- seed the session with `Bukkit.getOnlinePlayers()`
- enroll joiners through the same dynamic add path
- add tests for command parsing, permission split, current players, and join-time enrollment

### Phase 4: Auto-Record Controller

- create `AutoRecordController`
- wire startup and shutdown behavior
- add target resolution, waiting-state handling, and segment naming
- add `auto-record-state.yml` persistence for command-started controller intent
- make the controller own rollover timing instead of relying on `RecordingSession` duration auto-stop
- add tests for exact rollover counts and replacement-session startup

### Phase 5: Config, API Docs, and User Docs

- add config keys through `ReplayConfigSetting`
- update `README.md`
- update `docs/CONFIGURATION.md`
- update `docs/COMMANDS.md`
- update `docs/ARCHITECTURE.md`
- update `docs/API.md` for new public API methods and examples
- add `CHANGELOG.md` entries for the user-facing and API-facing changes

## Test Plan

The feature needs regression coverage before implementation is considered complete.

### Unit Tests

- `EntityTracker.addPlayer` adds a new UUID and reports duplicates
- `RecordingSession.addTrackedPlayer` emits `PlayerMove` before inventory/equipment snapshots
- adding a player to an active recording starts per-tick movement capture
- adding the same player twice does not duplicate tracking or snapshots
- adding to a stopped session fails
- adding an offline player fails cleanly
- player joining an active all-player session is added exactly once
- player quitting and rejoining an all-player session emits `PlayerQuit` and a fresh snapshot
- a targeted session does not auto-enroll unrelated players
- a `TARGET_PLAYERS_ON_JOIN` session re-adds configured targets when they rejoin, including manual `/replay start <name> <players...>` recordings
- auto-record for named offline players waits and starts when at least one target joins
- auto-record for multiple named players starts with online targets and later enrolls offline targets on join
- auto-record `all` does not create a blank segment when the server is empty
- an active segment may complete after all tracked players leave, but no replacement starts until a target is present
- rollover at 30 minutes creates the next segment and keeps recording active
- controller does not start duplicate active segments
- generated names are unique and deterministic

### Command Tests

- `/replay start <name> all [seconds]` uses `replay.start.all`
- `/replay start <name> all [seconds]` rejects duplicate names
- `/replay start <name> all [seconds]` waits when no players are online and starts when the first player joins
- `/replay addplayer <recording> <players...>` reports per-player results
- `/replay autorecord start <players...> [--minutes <minutes>] [--prefix <prefix>]` accepts multiple named targets
- `/replay autorecord start all Steve` is rejected because `all` is exclusive
- `/replay autorecord start Steve Alex --minutes 20 --prefix suspects` parses `Steve` and `Alex` as targets, `20` as minutes, and `suspects` as prefix
- `/replay autorecord start 12345 --minutes 20` treats `12345` as a player target, not minutes
- `/replay autorecord status` reports waiting and active states
- tab completion suggests `all` where appropriate

### Integration-Style Tests

- startup with auto-record enabled starts a live segment after append-log recovery
- startup with persisted auto-record enabled resumes the controller after append-log recovery
- persisted enabled or disabled auto-record state takes precedence over `Record-On-Startup`
- startup with `Record-On-Startup` enabled waits correctly when the configured target is absent
- shutdown saves the active auto-record segment when configured
- `/replay autorecord stop` persists disabled state so config startup does not immediately re-enable it until restart policy is intentionally changed
- async save failure of one segment does not prevent the next segment from running
- playback can seek across a late-added player's first snapshot and later `PlayerQuit`

### Performance Checks

- measure tick cost with many simultaneously tracked players
- measure replay count growth for small segment durations
- confirm that PacketEvents listener registration does not duplicate unexpectedly across rollovers
- confirm chunk capture cost remains bounded when every online player is tracked

## Risks and Follow-Ups

### 1. Event Volume

Recording all players continuously can multiply movement, inventory, equipment, block, and entity traffic quickly. If tick cost becomes unacceptable, the next optimization step should be shared capture infrastructure rather than multiple specialized server-wide sessions.

### 2. Initial Snapshot Completeness

Late-added players need a good enough initial snapshot for playback and seeking. `PlayerMove` plus inventory and equipment snapshots should be tested first. Add a dedicated lifecycle event only if playback proves it needs one.

### 3. Chunk Baseline Timing

If chunk capture is enabled, a player added between capture intervals may move before nearby chunk baselines are captured. First-iteration behavior should use the next configured chunk capture tick as the boundary and document/test that behavior.

### 4. Replay Proliferation

Auto-record mode can create many replays. At 30 minutes per segment, the plugin creates up to 48 recordings per day per active auto-record target policy. Storage growth and retention tooling should be tracked as follow-up work.

### 5. `all` Keyword Collision

`all` is a reserved target keyword for this feature. A player literally named `all` is not supported as a command target in the first iteration.

### 6. API Surface

Expose new behavior through additive `ReplayManager` methods rather than changing the meaning of `startRecording(String, Collection<Player>, int)`.

Recommended API additions:

```java
RecordingPlayerAddResult addPlayerToRecording(String recordingName, Player player);
boolean startRecordingAll(String name, int durationSeconds);
boolean startAutoRecording(String namePrefix, RecordingTarget target, int segmentDurationSeconds);
boolean stopAutoRecording(boolean saveActiveSegment);
Optional<AutoRecordingStatus> getAutoRecordingStatus();
```

Compatibility guidance:

- keep `startRecording(String, Collection<Player>, int)` for targeted manual recordings
- use `RecordingTarget.AllPlayers` to signal all players through the API instead of overloading `null` or an empty collection
- document that session mutation API methods internally schedule through FoliaLib when called off-thread and may complete asynchronously if thread switching is required

Recommended convenience overloads:

```java
boolean startRecording(String name, Player player, int durationSeconds);
Map<UUID, RecordingPlayerAddResult> addPlayersToRecording(String recordingName, Collection<Player> players);
boolean startAutoRecording(String namePrefix, Player player, int segmentDurationSeconds);
```

## Recommendation Summary

Implement this as a thin policy layer around the existing recording pipeline rather than a new recording system.

The smallest durable design is:

- teach `RecordingSession` to add players dynamically
- expose dynamic add through `RecorderManager`, `ReplayManager`, and `/replay addplayer`
- represent desired targets separately from the active tracked set
- add join enrollment policy for all-player and target-rejoin behavior
- use `/replay start <name> all [seconds]` for one-off all-player recordings
- add `AutoRecordController` for waiting, segment naming, and rollover
- keep config limited to startup auto-record defaults

That approach matches the current architecture, makes the user-facing commands feel consistent, and creates the reusable add-player foundation needed for future recording modes.
