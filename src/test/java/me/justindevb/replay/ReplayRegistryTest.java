package me.justindevb.replay;

import me.justindevb.replay.entity.RecordedEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplayRegistryTest {

    @Mock private ReplaySession session1;
    @Mock private ReplaySession session2;

    @BeforeEach
    void setUp() {
        // Clean state
        for (ReplaySession s : ReplayRegistry.getActiveSessions().toArray(new ReplaySession[0])) {
            ReplayRegistry.remove(s);
        }
    }

    @AfterEach
    void tearDown() {
        for (ReplaySession s : ReplayRegistry.getActiveSessions().toArray(new ReplaySession[0])) {
            ReplayRegistry.remove(s);
        }
    }

    @Test
    void add_containsSession() {
        ReplayRegistry.add(session1);
        assertTrue(ReplayRegistry.contains(session1));
    }

    @Test
    void remove_noLongerContains() {
        ReplayRegistry.add(session1);
        ReplayRegistry.remove(session1);
        assertFalse(ReplayRegistry.contains(session1));
    }

    @Test
    void getActiveSessions_returnsAll() {
        ReplayRegistry.add(session1);
        ReplayRegistry.add(session2);
        assertEquals(2, ReplayRegistry.getActiveSessions().size());
    }

    @Test
    void getEntityById_findsAcrossSessions() {
        RecordedEntity entity = mock(RecordedEntity.class);
        // Use lenient() because ConcurrentHashMap iteration order is non-deterministic;
        // session2 may be checked first, making session1's stub unused.
        lenient().when(session1.getRecordedEntity(42)).thenReturn(null);
        when(session2.getRecordedEntity(42)).thenReturn(entity);

        ReplayRegistry.add(session1);
        ReplayRegistry.add(session2);

        assertSame(entity, ReplayRegistry.getEntityById(42));
    }

    @Test
    void getEntityById_notFound_returnsNull() {
        when(session1.getRecordedEntity(anyInt())).thenReturn(null);
        ReplayRegistry.add(session1);

        assertNull(ReplayRegistry.getEntityById(999));
    }

    @Test
    void concurrentAddRemove_doesNotThrow() throws InterruptedException {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            ReplaySession s = mock(ReplaySession.class);
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 50; j++) {
                        ReplayRegistry.add(s);
                        ReplayRegistry.contains(s);
                        ReplayRegistry.getActiveSessions().size();
                        ReplayRegistry.remove(s);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdownNow();
    }

    @Test
    void getSessionForViewer_returnsMatchingSession() {
        Player viewer = mock(Player.class);
        when(session1.getViewer()).thenReturn(viewer);

        ReplayRegistry.add(session1);

        assertSame(session1, ReplayRegistry.getSessionForViewer(viewer));
    }

    @Test
    void getSessionForViewer_returnsNullWhenNoMatch() {
        Player viewer1 = mock(Player.class);
        Player viewer2 = mock(Player.class);
        when(session1.getViewer()).thenReturn(viewer1);

        ReplayRegistry.add(session1);

        assertNull(ReplayRegistry.getSessionForViewer(viewer2));
    }

    @Test
    void getSessionForViewer_returnsNullWhenEmpty() {
        Player viewer = mock(Player.class);
        assertNull(ReplayRegistry.getSessionForViewer(viewer));
    }
}
