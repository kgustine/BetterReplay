package me.justindevb.replay.storage;

import me.justindevb.replay.api.ReplayExportQuery;
import me.justindevb.replay.recording.TimelineEvent;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ReplayStorage {

    CompletableFuture<Void> saveReplay(String name, List<TimelineEvent> timeline);

    default CompletableFuture<Void> saveReplay(String name, ReplaySaveRequest request) {
        return saveReplay(name, request.timeline());
    }

    CompletableFuture<List<TimelineEvent>> loadReplay(String name);

    CompletableFuture<List<String>> listReplays();

    CompletableFuture<Boolean> deleteReplay(String name);

    CompletableFuture<Boolean> replayExists(String name);

    CompletableFuture<File> getReplayFile(String name);

    default CompletableFuture<File> getReplayFile(String name, ReplayExportQuery query) {
        return getReplayFile(name);
    }
}

