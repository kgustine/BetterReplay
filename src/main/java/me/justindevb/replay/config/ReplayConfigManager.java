package me.justindevb.replay.config;

import me.justindevb.replay.Replay;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ReplayConfigManager {

    private static final int CURRENT_CONFIG_VERSION = 2;

    private static final String[] HEADER = new String[] {
            "===========================================",
            "        BetterReplay Configuration",
            "==========================================="
    };

    private final Replay plugin;

    public ReplayConfigManager(Replay plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        boolean existed = configFile.exists();

        CommentedFileConfiguration commented = new CommentedFileConfiguration(plugin, configFile);
        commented.load();
        int currentVersion = commented.getInt(ReplayConfigSetting.CONFIG_VERSION.getKey(), 0);
        boolean needsCommentBackfill = existed && currentVersion < CURRENT_CONFIG_VERSION;

        boolean changed = false;
        if (!existed) {
            commented.addHeaderComments(HEADER);
            changed = true;
        } else if (needsCommentBackfill) {
            changed |= commented.addHeaderCommentsIfMissing(HEADER);
        }

        for (ReplayConfigSetting setting : ReplayConfigSetting.values()) {
            changed |= commented.setIfNotExists(setting);
            if (needsCommentBackfill) {
                changed |= commented.ensureSettingComments(setting);
            }
        }

        double configuredMaxSpeed = commented.getDouble(ReplayConfigSetting.PLAYBACK_MAX_SPEED.getKey(), 1.0D);
        if (configuredMaxSpeed < 1.0D) {
            changed |= commented.setIfDifferent(ReplayConfigSetting.PLAYBACK_MAX_SPEED.getKey(), 1.0D);
        }

        changed |= commented.setIfDifferent(ReplayConfigSetting.CONFIG_VERSION.getKey(), CURRENT_CONFIG_VERSION);

        if (changed) {
            commented.save();
        }

        // Ensure managed comments stay readable: header at top and comments above each key.
        rewriteManagedComments(configFile);

        plugin.reloadConfig();
    }

    private void rewriteManagedComments(File configFile) {
        List<String> lines;
        try {
            lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read config for comment rewrite", e);
        }

        Set<String> managedComments = new HashSet<>();
        for (String h : HEADER) managedComments.add(normalizeManagedCommentText(h));
        for (ReplayConfigSetting setting : ReplayConfigSetting.values()) {
            for (String c : setting.getComments()) managedComments.add(normalizeManagedCommentText(c));
        }

        List<String> cleaned = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                String body = normalizeManagedCommentText(trimmed.substring(1));
                if (managedComments.contains(body)) {
                    continue;
                }
            }
            cleaned.add(line);
        }

        while (!cleaned.isEmpty() && cleaned.get(0).trim().isEmpty()) {
            cleaned.remove(0);
        }

        String configVersionLine = extractTopLevelKeyLine(cleaned, ReplayConfigSetting.CONFIG_VERSION.getKey());

        List<String> output = new ArrayList<>();
        for (String h : HEADER) {
            output.add("# " + h);
        }
        output.add("");

        if (configVersionLine != null) {
            output.add(configVersionLine);
            output.add("");
        }

        output.addAll(cleaned);

        for (ReplayConfigSetting setting : ReplayConfigSetting.values()) {
            int lineIndex = findKeyLineIndex(output, setting.getKey());
            if (lineIndex < 0) {
                continue;
            }
            int indent = countLeadingSpaces(output.get(lineIndex));
            String indentStr = " ".repeat(Math.max(0, indent));
            int insertAt = lineIndex;
            for (String comment : setting.getComments()) {
                output.add(insertAt++, indentStr + "# " + comment);
            }
        }

        try {
            Files.writeString(configFile.toPath(), String.join(System.lineSeparator(), output), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to rewrite managed comments", e);
        }
    }

    private int findKeyLineIndex(List<String> lines, String dottedPath) {
        String[] parts = dottedPath.split("\\.");
        int start = 0;
        int end = lines.size();
        int indent = 0;
        int foundIndex = -1;

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            foundIndex = -1;

            for (int lineIndex = start; lineIndex < end; lineIndex++) {
                String line = lines.get(lineIndex);
                if (line.trim().startsWith("#") || line.trim().isEmpty()) {
                    continue;
                }
                if (countLeadingSpaces(line) != indent) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.startsWith(part + ":")) {
                    foundIndex = lineIndex;
                    break;
                }
            }

            if (foundIndex < 0) {
                return -1;
            }

            if (i < parts.length - 1) {
                int childIndent = indent + 2;
                int childStart = foundIndex + 1;
                int childEnd = lines.size();
                for (int j = childStart; j < lines.size(); j++) {
                    String line = lines.get(j);
                    if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                        continue;
                    }
                    if (countLeadingSpaces(line) < childIndent) {
                        childEnd = j;
                        break;
                    }
                }
                start = childStart;
                end = childEnd;
                indent = childIndent;
            }
        }

        return foundIndex;
    }

    private int countLeadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private String normalizeManagedCommentText(String text) {
        return text == null ? "" : text.trim();
    }

    private String extractTopLevelKeyLine(List<String> lines, String key) {
        String keyPrefix = key + ":";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                continue;
            }
            if (countLeadingSpaces(line) == 0 && trimmed.startsWith(keyPrefix)) {
                lines.remove(i);
                return line;
            }
        }
        return null;
    }
}
