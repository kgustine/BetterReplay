package me.justindevb.replay.storage;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReplayStoragePacketLimitException extends RuntimeException {

    private static final Pattern PACKET_TOO_BIG_PATTERN = Pattern.compile("Packet for query is too large \\(([\\d,]+) > ([\\d,]+)\\)");

    public ReplayStoragePacketLimitException(String message, Throwable cause) {
        super(message, cause);
    }

    public static ReplayStoragePacketLimitException fromPacketTooBig(Throwable throwable) {
        String packetMessage = findPacketTooBigMessage(throwable);
        if (packetMessage == null) {
            return null;
        }

        Matcher matcher = PACKET_TOO_BIG_PATTERN.matcher(packetMessage);
        if (!matcher.find()) {
            return new ReplayStoragePacketLimitException(
                    "replay archive size exceeds the MySQL max_allowed_packet limit. Increase max_allowed_packet on the database server or use file storage.",
                    throwable);
        }

        double replaySizeMiB = parsePacketSize(matcher.group(1)) / (1024d * 1024d);
        double maxPacketMiB = parsePacketSize(matcher.group(2)) / (1024d * 1024d);
        return new ReplayStoragePacketLimitException(
                String.format(Locale.US,
                        "replay archive size %.2f MiB exceeds the MySQL max_allowed_packet limit of %.2f MiB. Increase max_allowed_packet on the database server or use file storage.",
                        replaySizeMiB,
                        maxPacketMiB),
                throwable);
    }

    public static ReplayStoragePacketLimitException find(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ReplayStoragePacketLimitException packetLimitException) {
                return packetLimitException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String findPacketTooBigMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("Packet for query is too large")) {
                return message;
            }
            current = current.getCause();
        }
        return null;
    }

    private static long parsePacketSize(String value) {
        return Long.parseLong(value.replace(",", ""));
    }
}
