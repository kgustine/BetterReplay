package me.justindevb.replay.api;

import java.util.Set;
import java.util.UUID;

public sealed interface RecordingTarget permits RecordingTarget.AllPlayers, RecordingTarget.Players {
    record AllPlayers() implements RecordingTarget {}

    record Players(Set<UUID> playerUuids) implements RecordingTarget {
        public Players {
            playerUuids = Set.copyOf(playerUuids);
        }
    }
}
