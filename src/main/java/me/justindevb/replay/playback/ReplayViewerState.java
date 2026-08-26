package me.justindevb.replay.playback;

import org.bukkit.GameMode;
import org.bukkit.Location;

public record ReplayViewerState(
        Location returnLocation,
        GameMode originalGameMode,
        boolean allowFlight,
                boolean flying,
                boolean replayVanishApplied
) {

        public ReplayViewerState(Location returnLocation, GameMode originalGameMode, boolean allowFlight, boolean flying) {
                this(returnLocation, originalGameMode, allowFlight, flying, false);
        }

        public ReplayViewerState withReplayVanishApplied(boolean replayVanishApplied) {
                return new ReplayViewerState(returnLocation, originalGameMode, allowFlight, flying, replayVanishApplied);
        }
}