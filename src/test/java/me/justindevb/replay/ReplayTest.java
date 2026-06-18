package me.justindevb.replay;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import me.justindevb.replay.api.ReplayAPI;
import me.justindevb.replay.retention.ReplayRetentionService;
import me.justindevb.replay.storage.MySQLConnectionManager;
import me.justindevb.replay.storage.MySQLReplayStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class ReplayTest {

    @AfterEach
    void tearDown() {
        ReplayAPI.shutdown();
        ReplayRegistry.getActiveSessions().clear();
    }

    @Test
    void onDisable_waitsForPendingMySqlOperationsBeforeClosingConnections() throws Exception {
        Replay replay = mock(Replay.class, CALLS_REAL_METHODS);
        AutoRecordController autoRecordController = mock(AutoRecordController.class);
        RecorderManager recorderManager = mock(RecorderManager.class);
        MySQLReplayStorage storage = mock(MySQLReplayStorage.class);
        MySQLConnectionManager connectionManager = mock(MySQLConnectionManager.class);
        ReplayRetentionService retentionService = mock(ReplayRetentionService.class);
        PacketEventsAPI<?> packetEventsApi = mock(PacketEventsAPI.class);

        setField(replay, "autoRecordController", autoRecordController);
        setField(replay, "recorderManager", recorderManager);
        setField(replay, "storage", storage);
        setField(replay, "connectionManager", connectionManager);
        setField(replay, "replayRetentionService", retentionService);

        try (MockedStatic<PacketEvents> packetEvents = mockStatic(PacketEvents.class)) {
            packetEvents.when(PacketEvents::getAPI).thenReturn(packetEventsApi);

            replay.onDisable();
        }

        InOrder inOrder = inOrder(autoRecordController, recorderManager, retentionService, storage, connectionManager, packetEventsApi);
        inOrder.verify(autoRecordController).shutdown();
        inOrder.verify(recorderManager).shutdown();
        inOrder.verify(packetEventsApi).terminate();
        inOrder.verify(retentionService).stop();
        inOrder.verify(storage).awaitPendingOperations();
        inOrder.verify(connectionManager).shutdown();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = Replay.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
