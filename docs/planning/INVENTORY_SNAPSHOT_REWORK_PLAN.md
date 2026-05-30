# Inventory Snapshot Rework Plan

This document proposes a full redesign of the recording-side inventory snapshot pipeline.

The current implementation spends too much CPU time inside the inventory change detector because it serializes each item stack into Paper's compressed byte form every 5 ticks just to compare the current state against the previous snapshot. If a change is detected, it then serializes the inventory again to write the event.

The binary replay format is still considered alpha, so this plan assumes breaking changes are acceptable on the binary replay path if they let us fix the underlying design cleanly.

## Goal

Replace the current string-based, compressed-item snapshot flow with a binary-first inventory pipeline that:

1. captures each slot into a canonical raw binary form suitable for fast comparison
2. compares snapshots using the cheapest safe method available
3. uses an explicit equipment-state path for main hand, off hand, armor, and held slot
4. reuses the already-captured snapshot when writing the recording event
5. removes redundant processing and duplicated inventory data where practical
6. makes the binary replay format a better fit for inventory payloads

## Non-Goals for the First Iteration

- No attempt to preserve binary replay compatibility with the current alpha inventory encoding.
- No requirement to redesign legacy JSON replay loading in the same pass.
- No requirement to implement delta-compressed inventory history in the first version.
- No attempt to make inventory capture asynchronous. Bukkit and Paper inventory access remains on the server thread.
- No broad rewrite of unrelated replay event formats.

## Current Problems

### 1. The comparison path is already doing the expensive work

The current `RecordingSession.tickInventoryCheck()` flow builds a `List<String>` snapshot for each tracked player. Each slot is serialized through `ItemStackSerializer.serializeItem(...)`, which calls `ItemStack.serializeAsBytes()` and ends up producing compressed NBT bytes that are Base64-encoded into strings.

That means the hot path is already paying for:

- item serialization
- NBT compression
- Base64 encoding
- string allocation
- list allocation

before it even knows whether the inventory changed.

### 2. Changed snapshots are processed twice

If the new list differs from the previous list, the code currently calls `TimelineBuilder.captureInventory(...)`, which serializes the whole inventory again to create the `InventoryUpdate` event.

This doubles the processing cost for every changed snapshot.

### 3. The binary recording path stores stringified item payloads

The binary append-log and finalized `.br` archive currently store inventory item data as strings. Those strings are not natural binary replay data. They are Base64 wrappers around already-compressed item bytes.

This has several downsides:

- extra size overhead from Base64
- extra CPU overhead converting bytes to strings and back
- poor fit for the binary codec and string table
- awkward layering because per-item compressed blobs are then packed into a replay payload that is later LZ4-compressed as a whole

### 4. The snapshot shape duplicates state

The current inventory event stores:

- `mainHand`
- `offHand`
- `armor`
- `contents`

`contents` already includes the hotbar and main inventory, so `mainHand` is partially duplicated state. The current shape is convenient for playback, but it is not the cleanest canonical storage format.

### 5. The initial capture and periodic cache are disconnected

Session startup emits an initial inventory snapshot, but the periodic comparison cache is not seeded from that data. The first periodic check therefore repeats work and records another snapshot even if nothing changed.

### 6. The poller does work even when nothing plausible changed

The current design checks every tracked player every 5 ticks, regardless of whether that player's inventory was touched by any likely inventory-affecting action since the last check.

That keeps behavior simple, but it is wasteful under normal play.

### 7. The current equipment path is not robust enough to replace inventory snapshots

The existing `HeldItemChange` event only captures main hand and off hand. It does not capture armor, and it does not currently form a complete, canonical equipment state path.

That means the full inventory snapshot is still doing double duty today:

- inventory UI state
- armor state
- fallback equipment correction when hand items change without a dedicated hand event

This coupling is part of why the current inventory path is both expensive and overly broad.

## Recommended Design

### 1. Introduce a canonical binary snapshot model

Add dedicated snapshot types for recording-time storage capture and recording-time equipment capture.

Example shape:

```java
public record CapturedInventoryStorageSnapshot(
        List<CapturedItemSlot> storage,
        long snapshotHash
) {}

public record CapturedEquipmentState(
        int heldSlot,
        CapturedItemSlot mainHand,
        CapturedItemSlot offHand,
        List<CapturedItemSlot> armor,
        long stateHash
) {}

public record CapturedEquipmentStateSnapshot(
        CapturedEquipmentState state,
        boolean changed
) {}

public record CapturedInventoryStorageSnapshotResult(
        CapturedInventoryStorageSnapshot snapshot,
        boolean changed
) {}

public record CapturedPlayerState(
        CapturedEquipmentState equipment,
        List<CapturedItemSlot> storage,
        long combinedHash
) {}

public record CapturedItemSlot(
        byte[] rawBytes,
        int length,
        int fingerprint,
        boolean empty
) {}
```

Notes:

- `rawBytes` should be the canonical binary item representation used by the new binary replay format.
- `fingerprint` is a cheap comparison aid, not the sole correctness check.
- equipment and storage are separate state families with separate capture cadence.
- `snapshotHash` and `stateHash` are optional but useful for quick whole-state rejection before slot-by-slot comparison.

The exact types do not matter as much as the invariant: capture once, compare once, encode once.

### 2. Add an explicit robust equipment state path

The replay format should stop treating equipment as an incidental subset of the full inventory snapshot. Equipment should have its own canonical event and cache.

Recommended direction:

- define an `EquipmentStateUpdate` event that carries held slot, main hand, off hand, and armor
- capture and compare equipment every tick using a low-cost binary path
- emit the equipment event only when the equipment state changed
- remove equipment payloads from the full inventory storage event once the new equipment path is in place

This gives us the behavior you want:

- per-tick main hand responsiveness
- offhand and armor covered by the same path
- no duplication between the equipment path and the storage inventory path

Suggested event payload shape in the binary codec:

```text
EquipmentStateUpdateV2
- tick
- uuid ref
- held slot
- main hand slot payload
- offhand slot payload
- armor slot count
- repeated armor slot payloads
```

This should become the canonical source for entity equipment playback.

### 3. Stop treating binary inventory payloads as strings

The new binary replay inventory encoding should store binary item payloads directly, not Base64 strings and not string-table entries.

Recommended direction:

- replace the string-based inventory payload in the binary codec with explicit length-prefixed binary item payloads
- treat `null` or empty slots as a dedicated flag, not as a nullable string
- store only storage inventory sections in the inventory event

Suggested event payload shape in the binary codec:

```text
InventoryStorageUpdateV2
- tick
- uuid ref
- storage slot count
- repeated storage slot payloads
```

Each slot payload can be:

```text
- 1 byte empty flag
- if non-empty:
  - varint byte length
  - raw item bytes
```

Playback convenience should come from combining the latest equipment state with the latest storage snapshot, not from storing duplicate equipment inside the inventory payload.

### 4. Use raw item bytes for comparison

The comparison path should work on the new raw item payloads, not on compressed-and-Base64-encoded strings.

The raw format for the first implementation should be canonical uncompressed NBT bytes for the item stack.

This is the lowest-risk implementation path and keeps the redesign focused on removing compression, Base64, and duplicate work from the hot path before introducing a custom codec design surface.

Recommendation for the first implementation:

- use canonical uncompressed NBT bytes
- avoid Base64 entirely in the hot path
- defer any BetterReplay-owned custom item codec until benchmarks show a clear need

The polling cadence should be split by state type:

- equipment state: compare every tick
- storage inventory: compare at the slower inventory interval, with dirty-player shortcuts where possible

### 5. Do not rely on checksum alone for correctness

A checksum is useful, but it should not be the only comparison guard.

Recommended comparison order:

1. compare held slot value
2. compare section sizes if they are variable
3. compare whole-snapshot hash if present
4. compare slot length and slot fingerprint
5. if fingerprint and length match, confirm with byte equality before declaring the slot unchanged

This keeps the common unequal case cheap while avoiding correctness risk from hash collisions.

It is also possible that direct length-plus-byte comparison over cached raw bytes is already fast enough. We should benchmark both approaches instead of assuming the hash is always worth it.

### 6. Reuse the captured snapshot when writing the event

Once the recorder determines that a player's equipment or storage inventory changed, it should hand the already-captured state to the event builder.

That means:

- no second serialization pass
- no second inventory walk
- no second allocation of equivalent payloads

The builder should convert the captured equipment state or storage snapshot directly into the in-memory timeline event representation or append-log record.

### 7. Separate equipment and storage layouts

Use separate canonical recording layouts for equipment state and storage inventory state rather than a mixture of overlapping views.

Recommended equipment layout:

- held slot index
- main hand
- offhand
- 4 armor slots

Recommended storage inventory layout:

- 36 storage slots

Recommended source reads:

- use `PlayerInventory.getStorageContents()` for the canonical 36-slot storage section
- read main hand, offhand, armor, and held slot from their dedicated accessors for equipment capture
- avoid using `getContents()` as the canonical recording source if it introduces overlap or ambiguity for the binary format

Then derive:

- entity equipment packets from the latest equipment state
- inventory UI contents from the latest storage snapshot plus the latest equipment state

Benefits:

- removes duplicate equipment storage from the inventory path
- keeps per-tick equipment checks cheap and targeted
- simplifies change detection because equipment and storage no longer compete inside one event type

If playback needs a unified view, it should compose one from the latest equipment event and the latest storage event instead of reading a duplicated monolithic snapshot.

### 8. Seed the comparison cache from the initial capture

The startup capture should initialize both the player's last-known equipment state and the player's last-known storage snapshot immediately.

That removes the guaranteed duplicate work on the first periodic inventory check.

### 9. Add a dirty-player optimization layer for storage only

The periodic storage poller likely still needs to exist as a safety net, because not every storage mutation is reliably exposed through a narrow event set. However, it does not need to treat all tracked players as equally suspicious all the time.

Recommended approach:

- maintain an `inventoryDirtyPlayers` set in the recording session
- mark players dirty on likely storage inventory-affecting actions
- run the expensive storage snapshot capture every 5 ticks only for dirty players
- run a slower fallback sweep for clean players at a much lower rate, such as every 20 or 40 ticks

Equipment state should not depend on this dirty-set optimization. Equipment checks are small enough that the plan should assume a direct every-tick comparison.

Likely dirty sources include:

- item held changes
- swap hand items
- drops
- pickups
- inventory click and drag interactions
- consumption
- crafting and smelting retrieval where observable
- armor equip changes where observable
- durability loss after item use or combat

This should be treated as an optimization layer on top of the raw-byte redesign and explicit equipment path, not as a substitute for either.

### 10. Consider slot-level storage deltas as a follow-up phase

Once the canonical raw storage snapshot exists, the next logical storage improvement is to store only the changed storage slots instead of always emitting a full inventory snapshot.

That would reduce archive size and append-log volume for frequent small changes.

However, it increases playback and seek complexity, so it should be treated as a second-phase optimization unless benchmarking shows full snapshots are still too expensive after the capture redesign.

## Recommended Implementation Plan

### Phase 1. Introduce the new capture abstraction

- add a dedicated inventory capture component
- define the canonical captured storage snapshot and equipment state types
- add a separate equipment-state cache and every-tick comparison path
- replace the string-list storage comparison path with raw binary capture and cached snapshot comparison
- seed the initial equipment and storage caches from startup capture
- change the event-building path to consume the captured state directly

Deliverable:

- unchanged behavior from the player's perspective
- major reduction in hot-path allocation and CPU work
- equipment updates no longer depend on full inventory snapshots

### Phase 2. Redesign binary inventory encoding

- replace `TimelineEvent.InventoryUpdate` and `TimelineEvent.HeldItemChange` with dedicated split storage and equipment event models that match the new pipeline
- change the binary append-log codec to store equipment payloads and storage inventory payloads as binary slot data rather than string refs
- change finalized archive payload decoding accordingly
- bump the binary inventory encoding version or overall replay format version as needed
- deliberately remove support for the old alpha binary inventory payloads

Recommendation:

- prefer a clean version bump over a compatibility maze
- keep backward compatibility only for legacy JSON replay loading, not for alpha binary replay payloads

### Phase 3. Reduce unnecessary polling

- keep equipment state on an explicit every-tick low-cost comparison path
- add dirty-player tracking for storage inventory
- add the slower safety sweep for players not marked dirty
- expand event coverage for likely storage mutations where it is safe and deterministic

Deliverable:

- lower steady-state MSPT impact when players are mostly idle

### Phase 4. Optional storage improvements

- evaluate slot-level delta events
- evaluate item payload deduplication inside a snapshot or across nearby snapshots
- evaluate whether equipment-only changes deserve a smaller dedicated event type

These should be driven by benchmark results, not by assumption.

## Validation and Benchmarking

This work needs explicit measurement. The goal is not only to change the format but to prove that the hot path got cheaper.

### Required regression tests

- unchanged equipment does not emit repeated equipment updates
- unchanged storage inventory does not emit repeated snapshots
- changed equipment reuses captured payloads instead of reserializing
- changed storage inventory reuses captured payloads instead of reserializing
- initial startup capture seeds both comparison caches
- playback still reconstructs storage, armor, offhand, and held-item state correctly from the split event model
- binary round-trip works for equipment and storage payloads across empty slots, normal items, meta-heavy items, and nested container items

### Required performance-oriented tests or benchmarks

- compare old and new every-tick equipment capture cost
- compare old and new storage snapshot capture cost for unchanged inventories
- compare cost with plain items versus meta-heavy items
- compare direct byte equality versus fingerprint-plus-byte-confirmation
- measure archive size impact of split binary equipment and storage payloads versus Base64 strings

The existing benchmark tooling should be extended if needed so this change can be measured repeatedly rather than judged from one-off spark captures.

## Finalized Decisions

### 1. Raw item representation

The first implementation should use canonical uncompressed NBT bytes as the raw item representation.

A BetterReplay-owned compact item codec stays out of scope for now and should only be reconsidered if benchmarking later shows that uncompressed NBT is still a meaningful bottleneck.

### 2. In-memory event model

The in-memory timeline event model should change along with the binary codec.

`TimelineEvent.InventoryUpdate` and `TimelineEvent.HeldItemChange` should be replaced by dedicated split storage and equipment event models so the recording path, in-memory pipeline, and binary encoding all share the same state boundaries.

### 3. Binary replay compatibility

The new implementation should not preserve backward compatibility for existing alpha binary replay inventory payloads.

Binary replay loading should move forward with a clean version bump and a simpler decoder. Backward compatibility remains relevant only for legacy JSON replay loading.

### 4. Playback reconstruction rule

Playback should synchronize split equipment and storage state by applying the latest equipment state and latest storage state independently on seek and spawn.

Entity rendering should use equipment state only. Inventory UI composition should use the latest storage snapshot plus the latest equipment state. This is the canonical playback rule for the split model.

## Recommended Outcome

The first implementation should aim for the following end state:

- equipment state is captured every tick through a dedicated low-cost path
- storage inventory snapshots are captured into raw binary slot payloads
- both state families are compared without compression or Base64 conversion
- detected changes reuse the same captured payloads for recording
- the binary replay format stores equipment and storage payloads directly without duplication
- the initial startup capture seeds both caches
- optional dirty-player tracking reduces how often unchanged storage inventories are rescanned

That gives BetterReplay a cleaner binary format, a cheaper hot path, a responsive every-tick equipment model, and a much better foundation for future inventory delta work.