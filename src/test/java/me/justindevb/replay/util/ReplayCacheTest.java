package me.justindevb.replay.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReplayCacheTest {

    private ReplayCache cache;

    @BeforeEach
    void setUp() {
        cache = new ReplayCache();
    }

    @Test
    void startsEmpty() {
        assertTrue(cache.getReplays().isEmpty());
    }

    @Test
    void setThenGet() {
        cache.setReplays(List.of("replay1", "replay2"));
        assertEquals(List.of("replay1", "replay2"), cache.getReplays());
    }

    @Test
    void getReplays_returnsDefensiveCopy() {
        cache.setReplays(List.of("a", "b"));
        List<String> replays = cache.getReplays();

        assertThrows(UnsupportedOperationException.class, () -> replays.add("c"));
    }

    @Test
    void setReplays_replacesPrevious() {
        cache.setReplays(List.of("old1", "old2"));
        cache.setReplays(List.of("new1"));

        assertEquals(List.of("new1"), cache.getReplays());
    }

    @Test
    void setReplays_emptyList() {
        cache.setReplays(List.of("something"));
        cache.setReplays(List.of());
        assertTrue(cache.getReplays().isEmpty());
    }

    @Test
    void concurrentSetReplays_doesNotCorrupt() throws InterruptedException {
        int threads = 10;
        int iterations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        cache.setReplays(List.of("thread-" + threadId + "-" + i));
                        cache.getReplays(); // concurrent read
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdownNow();

        // Should not throw, list should be consistent
        List<String> result = cache.getReplays();
        assertNotNull(result);
    }
}
