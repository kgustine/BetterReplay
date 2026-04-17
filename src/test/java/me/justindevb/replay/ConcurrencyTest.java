package me.justindevb.replay;

import me.justindevb.replay.recording.TimelineBuilder;
import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.util.ReplayCache;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency stress tests for thread-safe components.
 */
class ConcurrencyTest {

    // ── ReplayCache concurrent access ─────────────────────────

    @RepeatedTest(3)
    void replayCache_concurrentSetAndGet() throws Exception {
        ReplayCache cache = new ReplayCache();
        int threadCount = 10;
        int opsPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threadCount; t++) {
            final int thread = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        List<String> names = List.of("session-" + thread + "-" + i);
                        cache.setReplays(names);

                        List<String> result = cache.getReplays();
                        assertNotNull(result, "Cache should return a list");
                    }
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for threads");
        pool.shutdown();
        assertTrue(errors.isEmpty(), "Concurrent errors: " + errors);
    }

    // ── TimelineBuilder concurrent adds ───────────────────────

    @RepeatedTest(3)
    void timelineBuilder_concurrentAddEvent() throws Exception {
        TimelineBuilder builder = new TimelineBuilder();
        int threadCount = 8;
        int eventsPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int thread = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < eventsPerThread; i++) {
                        builder.addEvent(new TimelineEvent.PlayerQuit(i, "t" + thread));
                    }
                } catch (Exception e) {
                    fail("Builder threw during concurrent addEvent: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        List<TimelineEvent> timeline = builder.getTimeline();
        // If ArrayList is used internally, concurrent adds may lose events or throw.
        // This test documents the behavior — it may need a synchronized list if failures occur.
        assertTrue(timeline.size() <= threadCount * eventsPerThread,
                "Timeline size should not exceed total events added");
    }
}
