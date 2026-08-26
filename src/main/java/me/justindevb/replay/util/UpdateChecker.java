package me.justindevb.replay.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.justindevb.replay.Replay;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Scanner;
import java.util.function.Consumer;

public class UpdateChecker {

    private static final String MODRINTH_API = "https://api.modrinth.com/v2";

    private final JavaPlugin plugin;
    private final String projectSlug;

    public UpdateChecker(JavaPlugin plugin, String projectSlug) {
        this.plugin = plugin;
        this.projectSlug = projectSlug;
    }

    /**
     * Checks Modrinth for a newer version.
     * <p>
     * If the current version is an alpha build, both alpha and release versions
     * are considered (so the user is notified when the full release lands).
     * If the current version is a release, only releases are considered.
     */
    public void checkForUpdate(String currentVersion, Consumer<UpdateResult> callback) {
        Replay.getInstance().getFoliaLib().getScheduler().runAsync(task -> {
            try {
                boolean runningAlpha = VersionUtil.isAlpha(currentVersion);

                String url = MODRINTH_API + "/project/" + projectSlug + "/version";
                HttpURLConnection con = (HttpURLConnection) URI.create(url).toURL().openConnection();
                con.setRequestProperty("User-Agent", "BetterReplay/" + currentVersion);
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);

                String json;
                try (InputStream is = con.getInputStream();
                     Scanner scanner = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                    json = scanner.hasNext() ? scanner.next() : "";
                }

                JsonArray versions = JsonParser.parseString(json).getAsJsonArray();

                String latestVersion = null;
                String latestType = null;

                // API returns versions newest-first
                for (JsonElement element : versions) {
                    JsonObject ver = element.getAsJsonObject();
                    String type = ver.get("version_type").getAsString();
                    String versionNumber = ver.get("version_number").getAsString();

                    // Release users only see releases
                    if (!runningAlpha && !"release".equals(type)) continue;

                    latestVersion = versionNumber;
                    latestType = type;
                    break;
                }

                if (latestVersion != null) {
                    boolean newer = VersionUtil.compareVersions(latestVersion, currentVersion) > 0;
                    callback.accept(new UpdateResult(latestVersion, latestType, newer));
                }
            } catch (Exception e) {
                plugin.getLogger().info("Unable to check for updates: " + e.getMessage());
            }
        });
    }

    public record UpdateResult(String latestVersion, String versionType, boolean updateAvailable) {}
}
