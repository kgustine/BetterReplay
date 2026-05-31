package me.justindevb.replay.playback;

import org.bukkit.GameMode;
import org.bukkit.Location;

public record ReplayViewerState(
        Location returnLocation,
        GameMode originalGameMode,
        boolean allowFlight,
        boolean flying
) {
}