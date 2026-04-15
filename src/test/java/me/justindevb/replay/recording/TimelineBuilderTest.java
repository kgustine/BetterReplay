package me.justindevb.replay.recording;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimelineBuilderTest {

    private TimelineBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new TimelineBuilder();
    }

    @Test
    void startsEmpty() {
        assertTrue(builder.getTimeline().isEmpty());
    }

    @Test
    void addEvent_accumulatesInOrder() {
        TimelineEvent e1 = new TimelineEvent.PlayerQuit(0, "uuid-1");
        TimelineEvent e2 = new TimelineEvent.PlayerQuit(1, "uuid-2");
        TimelineEvent e3 = new TimelineEvent.PlayerQuit(2, "uuid-3");

        builder.addEvent(e1);
        builder.addEvent(e2);
        builder.addEvent(e3);

        List<TimelineEvent> timeline = builder.getTimeline();
        assertEquals(3, timeline.size());
        assertSame(e1, timeline.get(0));
        assertSame(e2, timeline.get(1));
        assertSame(e3, timeline.get(2));
    }

    @Test
    void multipleEventsAtSameTick_preserveInsertionOrder() {
        TimelineEvent move = new TimelineEvent.EntityMove(5, "uuid-1", "ZOMBIE", "world", 0, 0, 0, 0, 0);
        TimelineEvent swing = new TimelineEvent.Swing(5, "uuid-2", "MAIN_HAND");
        TimelineEvent quit = new TimelineEvent.PlayerQuit(5, "uuid-3");

        builder.addEvent(move);
        builder.addEvent(swing);
        builder.addEvent(quit);

        List<TimelineEvent> timeline = builder.getTimeline();
        assertEquals(3, timeline.size());
        assertInstanceOf(TimelineEvent.EntityMove.class, timeline.get(0));
        assertInstanceOf(TimelineEvent.Swing.class, timeline.get(1));
        assertInstanceOf(TimelineEvent.PlayerQuit.class, timeline.get(2));
    }

    @Test
    void getTimeline_returnsSameListReference() {
        builder.addEvent(new TimelineEvent.PlayerQuit(0, "u"));
        assertSame(builder.getTimeline(), builder.getTimeline());
    }
}
