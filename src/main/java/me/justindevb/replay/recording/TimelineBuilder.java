package me.justindevb.replay.recording;

import me.justindevb.replay.storage.ReplayAppendLogWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects per-tick snapshots into the final timeline data structure.
 */
public class TimelineBuilder {

    private final List<TimelineEvent> timeline;
    private final ReplayAppendLogWriter appendLogWriter;

    public TimelineBuilder() {
        this(null, true);
    }

    public TimelineBuilder(ReplayAppendLogWriter appendLogWriter, boolean retainTimeline) {
        this.timeline = retainTimeline ? new ArrayList<>() : null;
        this.appendLogWriter = appendLogWriter;
    }

    public void addEvent(TimelineEvent event) {
        if (timeline != null) {
            timeline.add(event);
        }
        if (appendLogWriter != null) {
            try {
                appendLogWriter.append(event);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to append timeline event to recording temp log", e);
            }
        }
    }

    public List<TimelineEvent> getTimeline() {
        return timeline != null ? timeline : List.of();
    }
}
