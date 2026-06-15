package me.justindevb.replay;

import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.util.io.SerializedItemData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReplaySessionTest {

    @Test
    void collectEntityCreationEventsForSeek_ignoresInventoryEventsBeforePlayerMove() {
        UUID uuid = UUID.randomUUID();
        String uuidString = uuid.toString();
        TimelineEvent.PlayerMove playerMove = new TimelineEvent.PlayerMove(
                0,
                uuidString,
                "Steve",
                "world",
                1.0,
                64.0,
                1.0,
                0.0f,
                0.0f,
                "STANDING");

        List<TimelineEvent> timeline = List.of(
                new TimelineEvent.EquipmentStateUpdate(
                        0,
                        uuidString,
                        0,
                        SerializedItemData.empty(),
                        SerializedItemData.empty(),
                        List.of(SerializedItemData.empty(), SerializedItemData.empty(), SerializedItemData.empty(), SerializedItemData.empty())),
                new TimelineEvent.InventoryStorageUpdate(
                        0,
                        uuidString,
                        List.of(SerializedItemData.empty())),
                playerMove);

        Map<UUID, TimelineEvent> creationEvents = ReplaySession.collectEntityCreationEventsForSeek(timeline, timeline.size());

        assertSame(playerMove, creationEvents.get(uuid));
    }

    @Test
    void collectEntityCreationEventsForSeek_ignoresInvalidUuids() {
        List<TimelineEvent> timeline = List.of(
                new TimelineEvent.PlayerMove(0, "not-a-uuid", "Steve", "world", 0, 64, 0, 0, 0, "STANDING"));

        Map<UUID, TimelineEvent> creationEvents = ReplaySession.collectEntityCreationEventsForSeek(timeline, timeline.size());

        assertTrue(creationEvents.isEmpty());
    }

    @Test
    void collectEntityCreationEventsForSeek_usesRejoinMoveAfterQuit() {
        UUID uuid = UUID.randomUUID();
        String uuidString = uuid.toString();
        TimelineEvent.PlayerMove initialJoin = new TimelineEvent.PlayerMove(
                0, uuidString, "Steve", "world", 1.0, 64.0, 1.0, 0.0f, 0.0f, "STANDING");
        TimelineEvent.PlayerMove rejoin = new TimelineEvent.PlayerMove(
                40, uuidString, "Steve", "world", 10.0, 64.0, 10.0, 0.0f, 0.0f, "STANDING");

        List<TimelineEvent> timeline = List.of(
                initialJoin,
                new TimelineEvent.PlayerQuit(20, uuidString),
                rejoin);

        Map<UUID, TimelineEvent> creationEvents = ReplaySession.collectEntityCreationEventsForSeek(timeline, timeline.size());

        assertSame(rejoin, creationEvents.get(uuid));
    }

    @Test
    void findInitialReplayTeleportEvent_prefersNearestPlayerAtFirstPlayerTick() {
        TimelineEvent.EntitySpawn nearbyEntity = new TimelineEvent.EntitySpawn(
                0, UUID.randomUUID().toString(), "ZOMBIE", "world", 1.0, 64.0, 1.0);
        TimelineEvent.PlayerMove farPlayer = playerMove(0, UUID.randomUUID(), "Steve", "world", 100.0, 64.0, 0.0);
        TimelineEvent.PlayerMove nearPlayer = playerMove(0, UUID.randomUUID(), "Alex", "world", 5.0, 64.0, 0.0);
        List<TimelineEvent> timeline = List.of(nearbyEntity, farPlayer, nearPlayer);

        TimelineEvent selected = ReplaySession.findInitialReplayTeleportEvent(timeline, "world", 0.0, 64.0, 0.0);

        assertSame(nearPlayer, selected);
    }

    @Test
    void findInitialReplayTeleportEvent_ignoresLaterJoiners() {
        TimelineEvent.PlayerMove firstPlayer = playerMove(0, UUID.randomUUID(), "Steve", "world", 100.0, 64.0, 0.0);
        TimelineEvent.PlayerMove laterNearPlayer = playerMove(40, UUID.randomUUID(), "Alex", "world", 1.0, 64.0, 0.0);
        List<TimelineEvent> timeline = List.of(firstPlayer, laterNearPlayer);

        TimelineEvent selected = ReplaySession.findInitialReplayTeleportEvent(timeline, "world", 0.0, 64.0, 0.0);

        assertSame(firstPlayer, selected);
    }

    @Test
    void findInitialReplayTeleportEvent_usesFirstPlayerWhenNoPreferredWorldMatch() {
        TimelineEvent.PlayerMove firstPlayer = playerMove(0, UUID.randomUUID(), "Steve", "world_nether", 100.0, 64.0, 0.0);
        TimelineEvent.PlayerMove secondPlayer = playerMove(0, UUID.randomUUID(), "Alex", "world_the_end", 1.0, 64.0, 0.0);
        List<TimelineEvent> timeline = List.of(firstPlayer, secondPlayer);

        TimelineEvent selected = ReplaySession.findInitialReplayTeleportEvent(timeline, "world", 0.0, 64.0, 0.0);

        assertSame(firstPlayer, selected);
    }

    @Test
    void findInitialReplayTeleportEvent_fallsBackToFirstEntityWhenNoPlayersExist() {
        TimelineEvent.EntitySpawn firstEntity = new TimelineEvent.EntitySpawn(
                0, UUID.randomUUID().toString(), "ZOMBIE", "world", 1.0, 64.0, 1.0);
        TimelineEvent.EntityMove secondEntity = new TimelineEvent.EntityMove(
                0, UUID.randomUUID().toString(), "COW", "world", 5.0, 64.0, 5.0, 0.0f, 0.0f);
        List<TimelineEvent> timeline = List.of(firstEntity, secondEntity);

        TimelineEvent selected = ReplaySession.findInitialReplayTeleportEvent(timeline, "world", 0.0, 64.0, 0.0);

        assertSame(firstEntity, selected);
    }

    @Test
    void collectLifecycleMessagesForSeek_reportsLateJoin() {
        UUID initialUuid = UUID.randomUUID();
        UUID lateUuid = UUID.randomUUID();
        List<TimelineEvent> timeline = List.of(
                playerMove(0, initialUuid, "Steve"),
                playerMove(40, lateUuid, "Alex"));

        List<String> messages = ReplaySession.collectLifecycleMessagesForSeek(timeline, 0, timeline.size());

        assertEquals(List.of("[BetterReplay] Alex joined"), messages);
    }

    @Test
    void collectLifecycleMessagesForSeek_reportsLateJoinForSingleEventRange() {
        UUID initialUuid = UUID.randomUUID();
        UUID lateUuid = UUID.randomUUID();
        List<TimelineEvent> timeline = List.of(
                playerMove(0, initialUuid, "Steve"),
                playerMove(40, lateUuid, "Alex"));

        List<String> messages = ReplaySession.collectLifecycleMessagesForSeek(timeline, 1, 2);

        assertEquals(List.of("[BetterReplay] Alex joined"), messages);
    }

    @Test
    void collectLifecycleMessagesForSeek_reportsDisconnectWithLastKnownName() {
        UUID uuid = UUID.randomUUID();
        List<TimelineEvent> timeline = List.of(
                playerMove(0, uuid, "Steve"),
                new TimelineEvent.PlayerQuit(20, uuid.toString()));

        List<String> messages = ReplaySession.collectLifecycleMessagesForSeek(timeline, 1, timeline.size());

        assertEquals(List.of("[BetterReplay] Steve disconnected"), messages);
    }

    @Test
    void collectLifecycleMessagesForSeek_reportsDisconnectForSingleEventRange() {
        UUID uuid = UUID.randomUUID();
        List<TimelineEvent> timeline = List.of(
                playerMove(0, uuid, "Steve"),
                new TimelineEvent.PlayerQuit(20, uuid.toString()));

        List<String> messages = ReplaySession.collectLifecycleMessagesForSeek(timeline, 1, 2);

        assertEquals(List.of("[BetterReplay] Steve disconnected"), messages);
    }

    @Test
    void collectLifecycleMessagesForSeek_ignoresInitialPlayersAtRecordingStart() {
        UUID uuid = UUID.randomUUID();
        List<TimelineEvent> timeline = List.of(playerMove(0, uuid, "Steve"));

        List<String> messages = ReplaySession.collectLifecycleMessagesForSeek(timeline, 0, timeline.size());

        assertTrue(messages.isEmpty());
    }

    @Test
    void collectLifecycleMessagesForSeek_reportsRejoinAfterQuit() {
        UUID uuid = UUID.randomUUID();
        List<TimelineEvent> timeline = List.of(
                playerMove(0, uuid, "Steve"),
                new TimelineEvent.PlayerQuit(20, uuid.toString()),
                playerMove(40, uuid, "Steve"));

        List<String> messages = ReplaySession.collectLifecycleMessagesForSeek(timeline, 1, timeline.size());

        assertEquals(List.of(
                "[BetterReplay] Steve disconnected",
                "[BetterReplay] Steve joined"), messages);
    }

    @Test
    void collectLifecycleMessagesForSeek_reportsRejoinForSingleEventRange() {
        UUID uuid = UUID.randomUUID();
        List<TimelineEvent> timeline = List.of(
                playerMove(0, uuid, "Steve"),
                new TimelineEvent.PlayerQuit(20, uuid.toString()),
                playerMove(40, uuid, "Steve"));

        List<String> messages = ReplaySession.collectLifecycleMessagesForSeek(timeline, 2, 3);

        assertEquals(List.of("[BetterReplay] Steve joined"), messages);
    }

    @Test
    void collectLifecycleMessagesForSeek_ignoresBackwardRanges() {
        UUID uuid = UUID.randomUUID();
        List<TimelineEvent> timeline = List.of(
                playerMove(0, uuid, "Steve"),
                new TimelineEvent.PlayerQuit(20, uuid.toString()));

        List<String> messages = ReplaySession.collectLifecycleMessagesForSeek(timeline, timeline.size(), 0);

        assertTrue(messages.isEmpty());
    }

    private TimelineEvent.PlayerMove playerMove(int tick, UUID uuid, String name) {
        return playerMove(tick, uuid, name, "world", 1.0, 64.0, 1.0);
    }

    private TimelineEvent.PlayerMove playerMove(
            int tick,
            UUID uuid,
            String name,
            String world,
            double x,
            double y,
            double z
    ) {
        return new TimelineEvent.PlayerMove(
                tick,
                uuid.toString(),
                name,
                world,
                x,
                y,
                z,
                0.0f,
                0.0f,
                "STANDING");
    }
}
