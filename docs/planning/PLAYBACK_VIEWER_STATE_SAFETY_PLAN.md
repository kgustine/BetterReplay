# Playback Viewer State Safety Plan

This document proposes a replay-playback safety layer for the real viewer so server staff can inspect replays without being left vulnerable in the live world.

The immediate driver is simple: moderators often start replays while standing in survival mode, and the first replay position may be over lava or another hazard. The default replay start path should move the viewer into a safe mode and return them to their original live-world state when playback ends.

## Goal

Add a viewer-state safety flow for playback that:

1. captures the viewer's original pre-replay location before replay playback moves them
2. captures the viewer's original gameplay mode before replay playback changes it
3. switches the viewer into a replay-safe mode by default, with creative mode as the default policy
4. restores the viewer to their original location and gameplay mode when the replay ends
5. preserves that original state across replay-to-replay handoffs so nested playback does not overwrite the real return point

## Non-Goals for the First Iteration

- No attempt to redesign replay timeline data or recording storage. This is a viewer-session concern, not a replay-format concern.
- No requirement to snapshot the viewer's entire player profile such as health, hunger, potion effects, XP, or ender chest contents.
- No requirement to persist pending restore state across a full server restart.
- No spectator-mode control redesign. The existing playback UI is inventory-driven, so creative mode is the safer first fit.

## Current Behavior in the Codebase

The current playback lifecycle already preserves some viewer state, but not the parts that matter for safety:

- `ReplayManagerImpl.startReplay(...)` loads replay data and constructs a `ReplaySession`.
- `ReplaySession.start()` copies or transfers the viewer's saved inventory, finds the first replay location, and teleports the viewer there.
- `ReplaySession.stop()` restores inventory, fake entities, and replay chunk overlays.
- `ReplayInventoryUI.transferSavedInventory(...)` already preserves the original inventory across nested replay sessions.
- There is no equivalent saved-state path for the viewer's location or `GameMode`.

That means a moderator who starts a replay while in survival mode is still in survival mode after the replay teleports them into the replay scene.

## Why Creative Mode Is the Right Default

Creative mode is the best first default for this feature because:

- the current playback controls live in the player's inventory, and spectator mode would conflict with that workflow
- creative mode prevents the most common hazard cases that triggered this request, including lava and fall damage
- creative mode keeps the player's control scheme close to today's playback behavior, which lowers migration risk

The feature can still be configurable, but the default should be safe out of the box.

## Recommended Feature Shape

### 1. Add an Explicit Viewer State Snapshot

Introduce a small immutable snapshot for the viewer's real pre-replay state.

Example shape:

```java
public record ReplayViewerState(
        Location returnLocation,
        GameMode originalGameMode,
        boolean allowFlight,
        boolean flying
) {}
```

Notes:

- `returnLocation` should be cloned when captured so later mutations do not alter the saved target.
- `allowFlight` and `flying` should be captured alongside `GameMode` because creative mode changes those semantics.
- The first iteration does not need to grow into a full player snapshot object unless later requirements justify it.

### 2. Centralize Viewer-State Lifecycle Logic

Do not spread this logic across `ReplayInventoryUI` and ad hoc code inside `ReplaySession`.

Recommended direction:

- keep `ReplayInventoryUI` focused on inventory save and restore
- add a dedicated helper such as `ReplayViewerStateController` or `ReplayViewerStateSnapshot`
- let `ReplaySession` coordinate when capture, transfer, replay-mode application, and restore happen

This keeps inventory concerns separate from location and gamemode concerns while still matching the existing playback lifecycle.

### 3. Capture State Before Any Replay Mutation

The viewer's original state must be captured before any of these happen:

- teleport to the replay's first location
- inventory replacement with replay controls
- any replay-safe gamemode change

Recommended start sequence:

1. resolve whether the viewer already has an active replay session
2. if a replay is already active, transfer the original saved viewer state from that session instead of capturing a new one
3. otherwise capture the viewer's live-world location, `GameMode`, and flight flags
4. apply replay-safe mode if enabled, using creative as the default
5. clear fall distance and optionally zero velocity to avoid transition artifacts
6. teleport the viewer to the replay's first recorded location
7. continue with replay inventory controls and normal playback startup

The critical invariant is that the saved return state must always represent the viewer's real pre-first-replay state, not a state already mutated by replay playback.

### 4. Restore State Only When the Final Replay Ends

Nested replay sessions already transfer saved inventory instead of overwriting it. Viewer-state restoration should follow the same rule.

Recommended behavior:

- if replay B starts while replay A is already active for the same viewer, transfer the original viewer snapshot from A into B
- do not restore the viewer to the live world during that handoff
- restore the viewer only when the final replay session in that chain stops

This avoids bouncing the viewer back to the original location between replay transitions.

### 5. Restore Order Should Favor Safety

The restore path should not immediately drop the viewer back into their original gamemode before the teleport back to safety has completed.

Recommended stop sequence:

1. cancel playback work and clear fake replay state as today
2. restore the saved inventory
3. teleport the viewer back to the saved return location while they are still in replay-safe mode
4. restore the original `GameMode`
5. restore the original `allowFlight` and `flying` flags
6. clear the saved viewer-state snapshot

Restoring location before restoring the original gamemode reduces the chance of briefly exposing the viewer to live-world danger during the transition.

## Disconnect Handling

Disconnects need an explicit plan because Bukkit persists player location and gamemode across logout.

If a viewer logs out mid-replay and the plugin only stops the session without restoring state, that player can log back in at the replay location or with the wrong gamemode.

Recommended first-iteration behavior:

- when the viewer disconnects during playback, stop the replay session
- if the player is already offline, move the saved viewer-state snapshot into an in-memory pending-restore registry keyed by UUID
- on the next `PlayerJoinEvent`, restore location, `GameMode`, and flight flags on the main thread and then clear the pending entry

This should be in-memory only for the first iteration. Persisting pending restore state across full server restart can be deferred.

## Configuration Surface

The default behavior should be safe without requiring admins to opt in.

Recommended config direction:

```yaml
Playback:
  Viewer-Safety-Mode: creative
  Restore-Viewer-Location-On-Stop: true
  Restore-Viewer-GameMode-On-Stop: true
  Restore-Viewer-Flight-On-Stop: true
  Restore-Viewer-State-On-Rejoin: true
```

Notes:

- `Viewer-Safety-Mode` should accept at least `creative` and `off`.
- The default should be `creative`.
- Splitting restore toggles keeps rollout flexible if operators want the safe startup behavior but not every restore path.

## Event and API Considerations

The existing public API does not need a breaking change for the first implementation.

Recommended approach:

- keep `ReplaySession` as the owner of viewer-state capture and restore orchestration
- avoid changing `ReplayManager.startReplay(...)` or `ReplayManager.stopReplay(...)` signatures in the first pass
- keep `ReplayStartEvent` and `ReplayStopEvent` semantics stable unless a concrete plugin integration requires a new post-restore event

If a later integration needs to observe the restored state, a separate post-restore event would be cleaner than silently changing what `ReplayStopEvent` means.

## Testing Plan

This feature needs regression coverage because it changes the replay lifecycle and nested-session behavior.

Recommended tests:

1. `ReplaySession.start()` captures the viewer's original location before replay teleport occurs.
2. `ReplaySession.start()` applies creative mode when `Playback.Viewer-Safety-Mode` is enabled.
3. `ReplaySession.stop()` restores the viewer's original location and `GameMode`.
4. starting a second replay while one is active transfers the original viewer snapshot instead of overwriting it with the in-replay state.
5. viewer disconnect during replay queues and reapplies restore state on the next join.
6. disabling viewer safety mode preserves current playback behavior.

## Implementation Notes

The current codebase already suggests the right seam for this work:

- `ReplayInventoryUI` already implements saved-inventory transfer across replay handoffs
- `ReplaySession.start()` is where the viewer is teleported into replay space
- `ReplaySession.stop()` is where inventory and chunk overlays are restored

That makes `ReplaySession` the correct orchestration point, with a small dedicated helper object to avoid mixing viewer-state logic into the inventory UI class.

## Open Questions

1. Should the plugin also snapshot health, fire ticks, and food level for moderators who begin the replay while already injured or burning?
2. Should replay-safe mode always force creative when enabled, or only when the viewer is currently in a survival-like mode?
3. Should manual teleports performed by the viewer during replay be ignored in favor of always returning to the original pre-replay location? The recommended answer is yes for determinism.