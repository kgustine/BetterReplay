package me.justindevb.replay.playback;

import java.util.Locale;

public enum ReplayViewerSafetyMode {
    CREATIVE,
    OFF;

    public static ReplayViewerSafetyMode fromConfiguredValue(String value) {
        String normalized = normalize(value);
        return switch (normalized) {
            case "off" -> OFF;
            case "creative" -> CREATIVE;
            default -> CREATIVE;
        };
    }

    public static boolean isSupportedValue(String value) {
        String normalized = normalize(value);
        return "creative".equals(normalized) || "off".equals(normalized);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "creative";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}