package me.justindevb.replay;

import me.justindevb.replay.api.ReplayManager;
import me.justindevb.replay.api.AutoRecordingStatus;
import me.justindevb.replay.api.RecordingPlayerAddResult;
import me.justindevb.replay.api.RecordingTarget;
import me.justindevb.replay.benchmark.ReplayBenchmarkCommand;
import me.justindevb.replay.config.ReplayConfigReloadResult;
import me.justindevb.replay.config.ReplayConfigSetting;
import me.justindevb.replay.debug.ReplayDebugCommand;
import me.justindevb.replay.export.ReplayExportCommand;
import me.justindevb.replay.storage.ReplayDeleteResult;
import me.justindevb.replay.storage.ReplaySummary;
import me.justindevb.replay.util.ReplayMessages;
import me.justindevb.replay.util.ReplayNames;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.logging.Level;

public class ReplayCommand implements CommandExecutor, TabCompleter {
    private static final String DEFAULT_LIST_PROTECTED_COLOR = "\u00A76";
    private static final char LEGACY_COLOR_CODE_CHAR = '\u00A7';
    private static final String LEGACY_COLOR_CODES = "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx";

    private final ReplayManager replayManager;
    private final ReplayBenchmarkCommand replayBenchmarkCommand;
    private final ReplayExportCommand replayExportCommand;
    private final ReplayDebugCommand replayDebugCommand;

    public ReplayCommand(ReplayManager replayManager) {
        this(replayManager, null, null, null);
    }

    ReplayCommand(ReplayManager replayManager, ReplayBenchmarkCommand replayBenchmarkCommand, ReplayExportCommand replayExportCommand,
                  ReplayDebugCommand replayDebugCommand) {
        this.replayManager = replayManager;
        this.replayBenchmarkCommand = replayBenchmarkCommand;
        this.replayExportCommand = replayExportCommand;
        this.replayDebugCommand = replayDebugCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("export") && replayExportCommand != null) {
            return replayExportCommand.handle(sender, args);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("benchmark") && replayBenchmarkCommand != null) {
            return replayBenchmarkCommand.handle(sender, args);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("debug") && replayDebugCommand != null) {
            return replayDebugCommand.handle(sender, args);
        }

        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Must be a player to execute this command");
                return true;
            }
            sendHelp(p);
            return true;
        }

        String subcommand = args[0].toLowerCase();

        if (subcommand.equals("start")) {
            return handleStart(sender, args);
        }
        if (subcommand.equals("addplayer")) {
            return handleAddPlayer(sender, args);
        }
        if (subcommand.equals("autorecord")) {
            return handleAutoRecord(sender, args);
        }

        if (!(sender instanceof Player p)) {
            return switch (subcommand) {
                case "protect" -> handleProtect(sender, args, "console");
                case "unprotect" -> handleUnprotect(sender, args);
                case "reload" -> handleReload(sender);
                default -> {
                    sender.sendMessage("Must be a player to execute this command");
                    yield true;
                }
            };
        }

        switch (subcommand) {
            case "stop" -> {
                if (!p.hasPermission("replay.stop")) {
                    p.sendMessage("You do not have permission");
                    return true;
                }
                if (args.length < 2) {
                    p.sendMessage("§c/replay stop <name>");
                    return true;
                }
                String sessionName = joinArgs(args, 1);
                java.util.Optional<String> invalidSessionName = ReplayNames.validateRecordingName(sessionName);
                if (invalidSessionName.isPresent()) {
                    p.sendMessage("§c" + invalidSessionName.get());
                    return true;
                }
                if (replayManager.stopRecording(sessionName, true)) {
                    ReplayMessages.send(p, "§aStopped recording session: " + sessionName);
                } else {
                    p.sendMessage("§cNo active session with that name!");
                }
            }
            case "play" -> {

                if (!p.hasPermission("replay.play")) {
                    p.sendMessage("You do not have permission");
                    return true;
                }

                if (args.length < 2) {
                    p.sendMessage("§c/replay play <name> [server:<server>]");
                    return true;
                }

                String replayName = args[1];
                java.util.Optional<String> invalidReplayName = ReplayNames.validateReplayName(replayName);
                if (invalidReplayName.isPresent()) {
                    p.sendMessage("§c" + invalidReplayName.get());
                    return true;
                }

                String foundTargetServer = null;

                for (int i = 2; i < args.length; i++) {

                    String arg = args[i];

                    if (arg.regionMatches(
                            true,
                            0,
                            "server:",
                            0,
                            "server:".length()
                    )) {

                        String server =
                                arg.substring("server:".length())
                                        .trim();

                        if (!server.isEmpty()) {
                            foundTargetServer = server;
                        }

                        break;
                    }
                }

                Replay plugin = Replay.getInstance();

                // Must be effectively final for lambda usage
                final String targetServer = foundTargetServer != null
                        ? foundTargetServer
                        : configuredDefaultReplayServer(plugin);

                plugin.getReplayStorage()
                        .replayExists(replayName)
                        .thenAccept(exists -> {

                            if (!exists) {

                                plugin.getFoliaLib()
                                        .getScheduler()
                                        .runLater(
                                                () -> ReplayMessages.send(p, "§cReplay not found: " + replayName),
                                                1L
                                        );

                                return;
                            }

                            if (targetServer == null) {

                                replayManager.startReplay(
                                        replayName,
                                        p
                                );

                                return;
                            }

                            plugin.getFoliaLib()
                                    .getScheduler()
                                    .runLater(
                                            () -> {
                                                boolean transferRequested = plugin.getTransferManager()
                                                        .requestReplayTransfer(
                                                                p,
                                                                replayName,
                                                                targetServer
                                                        );

                                                if (!transferRequested) {
                                                    return;
                                                }

                                                ReplayMessages.send(p, "§aConnecting to replay server §e"
                                                        + targetServer
                                                        + "§a...");
                                            },
                                            1L
                                    );

                        })
                        .exceptionally(ex -> {

                            plugin.getLogger().log(
                                    Level.SEVERE,
                                    "Failed to verify replay existence: "
                                            + replayName,
                                    ex
                            );

                            plugin.getFoliaLib()
                                    .getScheduler()
                                    .runLater(
                                            () -> p.sendMessage(
                                                    "§cFailed to load replay information."
                                            ),
                                            1L
                                    );

                            return null;
                        });

                return true;
            }

            case "list" -> {
                if (!p.hasPermission("replay.list")) {
                    p.sendMessage("You do not have permission");
                    return true;
                }

                int parsedPage = 1;
                if (args.length >= 2) {
                    try {
                        parsedPage = Math.max(1, Integer.parseInt(args[1]));
                    } catch (NumberFormatException ignored) {
                        p.sendMessage("§c/replay list [page]");
                        return true;
                    }
                }

                final int page = parsedPage;

                replayManager.listSavedReplaySummaries()
                        .thenAccept(replays -> Bukkit.getScheduler().runTask(Replay.getInstance(), () -> {
                            if (replays.isEmpty()) {
                                p.sendMessage("§cNo replays found.");
                                return;
                            }

                            Replay plugin = Replay.getInstance();
                            int perPage = ReplayConfigSetting.LIST_PAGE_SIZE.getInt(plugin.getConfig());
                            String protectedHighlightColor = resolveConfiguredColor(
                                    ReplayConfigSetting.LIST_PROTECTED_HIGHLIGHT_COLOR.getString(plugin.getConfig()));
                            int totalPages = (int) Math.ceil((double) replays.size() / perPage);

                            if (page > totalPages) {
                                p.sendMessage("§cPage out of range. Max page: " + totalPages);
                                return;
                            }

                            int from = (page - 1) * perPage;
                            int to = Math.min(from + perPage, replays.size());

                            p.sendMessage("§6Replays §7(Page " + page + "/" + totalPages + ")");
                            for (int i = from; i < to; i++) {
                                ReplayMessages.send(p, "§e- " + formatReplayListName(replays.get(i), protectedHighlightColor));
                            }

                            Component navigation = Component.empty();

                            if (page > 1) {
                                navigation = navigation.append(
                                        Component.text("§e[Previous]")
                                                .clickEvent(ClickEvent.runCommand("/replay list " + (page - 1)))
                                                .hoverEvent(HoverEvent.showText(Component.text("Go to page " + (page - 1))))
                                );
                            } else {
                                navigation = navigation.append(Component.text("§7[Previous]"));
                            }

                            navigation = navigation.append(Component.text(" §8| "));

                            if (page < totalPages) {
                                navigation = navigation.append(
                                        Component.text("§e[Next]")
                                                .clickEvent(ClickEvent.runCommand("/replay list " + (page + 1)))
                                                .hoverEvent(HoverEvent.showText(Component.text("Go to page " + (page + 1))))
                                );
                            } else {
                                navigation = navigation.append(Component.text("§7[Next]"));
                            }

                            p.sendMessage(navigation);
                        }))
                        .exceptionally(ex -> {
                            Replay.getInstance().getLogger().log(Level.SEVERE, "Failed to print list", ex);
                            return null;
                        });

                return true;
            }

            case "delete" -> {
                if (!p.hasPermission("replay.delete")) {
                    p.sendMessage("You do not have permission");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage("Usage: /replay delete <name>");
                    return true;
                }
                String name = joinArgs(args, 1);
                java.util.Optional<String> invalidReplayName = ReplayNames.validateReplayName(name);
                if (invalidReplayName.isPresent()) {
                    p.sendMessage("§c" + invalidReplayName.get());
                    return true;
                }
                replayManager.deleteSavedReplay(name)
                        .thenAccept(result -> {
                            Replay.getInstance().getFoliaLib().getScheduler().runNextTick(task -> {
                                if (result == ReplayDeleteResult.DELETED) {
                                    ReplayMessages.send(p, "§aDeleted replay: " + name);
                                } else if (result == ReplayDeleteResult.PROTECTED) {
                                    ReplayMessages.send(p, "§cReplay is protected and must be unprotected before deletion: " + name);
                                } else {
                                    ReplayMessages.send(p, "§cReplay not found: " + name);
                                }
                            });
                        })
                        .exceptionally(ex -> {
                            Replay.getInstance().getLogger().log(Level.SEVERE, "Failed to delete replay: " + name, ex);
                            Replay.getInstance().getFoliaLib().getScheduler().runNextTick(task ->
                                    ReplayMessages.send(p, "§cFailed to delete replay: " + name));
                            return null;
                        });
                        return true;
            }
            case "protect" -> {
                return handleProtect(sender, args, p.getName());
            }
            case "unprotect" -> {
                return handleUnprotect(sender, args);
            }
            case "reload" -> {
                return handleReload(sender);
            }
            default -> {
                ReplayMessages.send(p, "§cUnknown subcommand: §f" + args[0]);
                sendHelp(p);
            }
        }
        return true;
    }

    private void sendHelp(Player p) {
        p.sendMessage("§6§lBetterReplay Commands:");
        if (p.hasPermission("replay.start"))
            p.sendMessage("§e/replay start <name> <player1 player2 ...> [seconds] §7- Start recording");
        if (p.hasPermission("replay.start.all"))
            p.sendMessage("§e/replay start <name> all [seconds] §7- Record all players");
        if (p.hasPermission("replay.addplayer"))
            p.sendMessage("§e/replay addplayer <recording> <players...> §7- Add players to an active recording");
        if (p.hasPermission("replay.autorecord"))
            p.sendMessage("§e/replay autorecord start <players...|all> [--minutes <minutes>] [--prefix <prefix>] §7- Start rolling auto-record");
        if (p.hasPermission("replay.stop")) {
            p.sendMessage("§e/replay stop <name> §7- Stop an active recording");
            var sessions = replayManager.getActiveRecordings();
            if (!sessions.isEmpty()) {
                p.sendMessage("§7  Active: §f" + String.join("§7, §f", sessions));
            }
        }
        if (p.hasPermission("replay.play"))
            p.sendMessage("§e/replay play <name> [server:<server>] §7- Play a saved replay");
        if (p.hasPermission("replay.list"))
            p.sendMessage("§e/replay list [page] §7- List saved replays");
        if (p.hasPermission("replay.delete"))
            p.sendMessage("§e/replay delete <name> §7- Delete a saved replay");
        if (p.hasPermission("replay.protect"))
            p.sendMessage("§e/replay protect <name> §7- Protect a replay from deletion");
        if (p.hasPermission("replay.unprotect"))
            p.sendMessage("§e/replay unprotect <name> §7- Remove replay deletion protection");
        if (p.hasPermission("replay.reload"))
            p.sendMessage("§e/replay reload §7- Reload config and restart retention tasks");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {

        if (args.length > 0 && args[0].equalsIgnoreCase("export") && replayExportCommand != null) {
            return replayExportCommand.tabComplete(sender, args);
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("benchmark") && replayBenchmarkCommand != null) {
            return replayBenchmarkCommand.tabComplete(sender, args);
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("debug") && replayDebugCommand != null) {
            return replayDebugCommand.tabComplete(sender, args);
        }

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();

            if (sender.hasPermission("replay.start")) completions.add("start");
            if (sender.hasPermission("replay.addplayer")) completions.add("addplayer");
            if (sender.hasPermission("replay.autorecord")) completions.add("autorecord");
            if (sender.hasPermission("replay.stop")) completions.add("stop");
            if (sender.hasPermission("replay.play")) completions.add("play");
            if (sender.hasPermission("replay.delete")) completions.add("delete");
            if (sender.hasPermission("replay.list")) completions.add("list");
            if (sender.hasPermission("replay.protect")) completions.add("protect");
            if (sender.hasPermission("replay.unprotect")) completions.add("unprotect");
                if (sender.hasPermission("replay.reload")) completions.add("reload");

            return completions.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }

        if (args.length >= 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("play")
            || args[0].equalsIgnoreCase("protect") || args[0].equalsIgnoreCase("unprotect"))) {
            if (!sender.hasPermission("replay." + args[0].toLowerCase()))
                return Collections.emptyList();

            List<String> cachedReplays = replayManager.getCachedReplayNames();

            String prefix = joinArgs(args, 1).toLowerCase();

            List<String> matches = cachedReplays.stream()
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .toList();
            if (matches.isEmpty() && args.length == 2) {
                return List.of("<name>");
            }
            return matches;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("stop")) {
            if (!sender.hasPermission("replay.stop"))
                return Collections.emptyList();

            String prefix = joinArgs(args, 1).toLowerCase();

            List<String> matches = replayManager.getActiveRecordings()
                    .stream()
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .toList();
            if (matches.isEmpty() && args.length == 2) {
                return List.of("<name>");
            }
            return matches;
        }


        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            if (!sender.hasPermission("replay.start"))
                return Collections.emptyList();
            return List.of("<name>");
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("start")) {
            if (!sender.hasPermission("replay.start") && !sender.hasPermission("replay.start.all"))
                return Collections.emptyList();

            // First player slot — only suggest player names, no duration yet
            String currentArg = args[2].toLowerCase();

            List<String> suggestions = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(currentArg))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            if ("all".startsWith(currentArg)) {
                suggestions.add("all");
            }
            return suggestions;
        }

        if (args.length >= 4 && args[0].equalsIgnoreCase("start")) {
            if (!sender.hasPermission("replay.start"))
                return Collections.emptyList();

            // Collect already-selected player names so we don't suggest them again
            java.util.Set<String> alreadySelected = new java.util.HashSet<>();
            for (int i = 2; i < args.length - 1; i++) {
                alreadySelected.add(args[i].toLowerCase());
            }

            String currentArg = args[args.length - 1].toLowerCase();

            List<String> suggestions = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> !alreadySelected.contains(name.toLowerCase()))
                    .filter(name -> name.toLowerCase().startsWith(currentArg))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

            // Show duration hint now that at least one player is selected
            if (currentArg.isEmpty() || "[seconds]".startsWith(currentArg)) {
                suggestions.add("[seconds]");
            }

            return suggestions;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("addplayer")) {
            if (!sender.hasPermission("replay.addplayer"))
                return Collections.emptyList();
            if (args.length == 2) {
                return replayManager.getActiveRecordings().stream()
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .toList();
            }
            String currentArg = args[args.length - 1].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(currentArg))
                    .toList();
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("autorecord")) {
            if (!sender.hasPermission("replay.autorecord"))
                return Collections.emptyList();
            if (args.length == 2) {
                return List.of("start", "stop", "status").stream()
                        .filter(value -> value.startsWith(args[1].toLowerCase()))
                        .toList();
            }
            if (args.length >= 3 && args[1].equalsIgnoreCase("start")) {
                String currentArg = args[args.length - 1].toLowerCase();
                List<String> suggestions = Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(currentArg))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                if ("all".startsWith(currentArg)) suggestions.add("all");
                if ("--minutes".startsWith(currentArg)) suggestions.add("--minutes");
                if ("--prefix".startsWith(currentArg)) suggestions.add("--prefix");
                return suggestions;
            }
        }

        return Collections.emptyList();
    }

    private boolean handleStart(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /replay start <name> <player1 player2 ...|all> [durationSeconds]");
            return true;
        }

        String sessionName = args[1];
        if (args[2].equalsIgnoreCase("all")) {
            if (!sender.hasPermission("replay.start.all")) {
                sender.sendMessage("You do not have permission");
                return true;
            }
            if (args.length > 4) {
                sender.sendMessage("§cUsage: /replay start <name> all [durationSeconds]");
                return true;
            }
            int duration = -1;
            if (args.length == 4) {
                try {
                    duration = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cUsage: /replay start <name> all [durationSeconds]");
                    return true;
                }
            }
            boolean waiting = Bukkit.getOnlinePlayers().isEmpty();
            if (replayManager.startRecordingAll(sessionName, duration)) {
                sender.sendMessage(waiting
                        ? "§aRecording session queued until a player joins: " + sessionName
                        : "§aStarted recording session: " + sessionName + " (" + (duration == -1 ? "∞" : duration + "s") + ")");
            } else {
                sender.sendMessage("§cSession with that name already exists!");
            }
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("Must be a player to execute this command");
            return true;
        }
        if (!sender.hasPermission("replay.start")) {
            sender.sendMessage("You do not have permission");
            return true;
        }

        int duration = parseOptionalTrailingInt(args, 2);
        int endIndex = duration != -1 ? args.length - 1 : args.length;
        List<Player> targets = new ArrayList<>();
        for (int i = 2; i < endIndex; i++) {
            Player target = Bukkit.getPlayerExact(args[i]);
            if (target != null) {
                targets.add(target);
            } else {
                sender.sendMessage("§cPlayer not found: " + args[i]);
            }
        }
        if (targets.isEmpty()) {
            sender.sendMessage("§cNo valid players to record.");
            return true;
        }
        if (replayManager.startRecording(sessionName, targets, duration)) {
            sender.sendMessage("§aStarted recording session: " + sessionName + " (" + (duration == -1 ? "∞" : duration + "s") + ")");
        } else {
            sender.sendMessage("§cSession with that name already exists!");
        }
        return true;
    }

    private boolean handleAddPlayer(CommandSender sender, String[] args) {
        if (!sender.hasPermission("replay.addplayer")) {
            sender.sendMessage("You do not have permission");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /replay addplayer <recording> <players...>");
            return true;
        }
        String recordingName = args[1];
        for (int i = 2; i < args.length; i++) {
            Player target = Bukkit.getPlayerExact(args[i]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found: " + args[i]);
                continue;
            }
            RecordingPlayerAddResult result = replayManager.addPlayerToRecording(recordingName, target);
            sender.sendMessage(formatAddPlayerResult(args[i], result));
            if (result == RecordingPlayerAddResult.SESSION_NOT_FOUND) {
                break;
            }
        }
        return true;
    }

    private boolean handleAutoRecord(CommandSender sender, String[] args) {
        if (!sender.hasPermission("replay.autorecord")) {
            sender.sendMessage("You do not have permission");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /replay autorecord <start|stop|status>");
            return true;
        }
        return switch (args[1].toLowerCase()) {
            case "start" -> handleAutoRecordStart(sender, args);
            case "stop" -> {
                if (replayManager.stopAutoRecording(true)) {
                    sender.sendMessage("§aStopped auto-record.");
                } else {
                    sender.sendMessage("§cAuto-record is not running.");
                }
                yield true;
            }
            case "status" -> {
                Optional<AutoRecordingStatus> status = replayManager.getAutoRecordingStatus();
                if (status.isEmpty()) {
                    sender.sendMessage("§7Auto-record is disabled.");
                } else {
                    AutoRecordingStatus value = status.get();
                    sender.sendMessage("§aAuto-record enabled: §f" + value.targetDescription());
                    sender.sendMessage(value.waiting()
                            ? "§7Waiting for eligible players."
                            : "§7Active segment: §f" + value.activeSegmentName());
                }
                yield true;
            }
            default -> {
                sender.sendMessage("§cUsage: /replay autorecord <start|stop|status>");
                yield true;
            }
        };
    }

    private boolean handleAutoRecordStart(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /replay autorecord start <players...|all> [--minutes <minutes>] [--prefix <prefix>]");
            return true;
        }
        Replay plugin = Replay.getInstance();
        int minutes = ReplayConfigSetting.AUTO_RECORD_SEGMENT_DURATION_MINUTES.getInt(plugin.getConfig());
        String prefix = ReplayConfigSetting.AUTO_RECORD_NAME_PREFIX.getString(plugin.getConfig());
        List<String> targetTokens = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            String token = args[i];
            if (token.equalsIgnoreCase("--minutes")) {
                if (i + 1 >= args.length) {
                    sender.sendMessage("§cMissing value for --minutes");
                    return true;
                }
                try {
                    minutes = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid minutes: " + args[i]);
                    return true;
                }
            } else if (token.equalsIgnoreCase("--prefix")) {
                if (i + 1 >= args.length) {
                    sender.sendMessage("§cMissing value for --prefix");
                    return true;
                }
                prefix = args[++i];
            } else {
                targetTokens.add(token);
            }
        }
        if (targetTokens.isEmpty()) {
            sender.sendMessage("§cAt least one target is required.");
            return true;
        }
        if (minutes <= 0) {
            sender.sendMessage("§cMinutes must be greater than zero.");
            return true;
        }
        RecordingTarget target;
        if (targetTokens.stream().anyMatch(token -> token.equalsIgnoreCase("all"))) {
            if (targetTokens.size() != 1) {
                sender.sendMessage("§cThe all target cannot be combined with player names.");
                return true;
            }
            target = new RecordingTarget.AllPlayers();
        } else {
            java.util.Set<java.util.UUID> uuids = new java.util.LinkedHashSet<>();
            for (String targetName : targetTokens) {
                Player online = Bukkit.getPlayerExact(targetName);
                if (online != null) {
                    uuids.add(online.getUniqueId());
                } else {
                    OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
                    uuids.add(offline.getUniqueId());
                }
            }
            target = new RecordingTarget.Players(uuids);
        }
        if (replayManager.startAutoRecording(prefix, target, minutes * 60)) {
            sender.sendMessage("§aStarted auto-record.");
        } else {
            sender.sendMessage("§cAuto-record is already running or the request was invalid.");
        }
        return true;
    }

    private int parseOptionalTrailingInt(String[] args, int firstOptionalIndex) {
        if (args.length <= firstOptionalIndex) {
            return -1;
        }
        try {
            return Integer.parseInt(args[args.length - 1]);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String formatAddPlayerResult(String name, RecordingPlayerAddResult result) {
        return switch (result) {
            case ADDED -> "§aAdded player to recording: " + name;
            case ALREADY_TRACKED -> "§ePlayer is already tracked: " + name;
            case SESSION_NOT_FOUND -> "§cNo active session with that name!";
            case SESSION_STOPPED -> "§cRecording is stopped: " + name;
            case PLAYER_OFFLINE -> "§cPlayer is offline: " + name;
        };
    }

    private String joinArgs(String[] args, int fromIndex) {
        if (fromIndex >= args.length) {
            return "";
        }
        return String.join(" ", Arrays.copyOfRange(args, fromIndex, args.length)).trim();
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("replay.reload")) {
            sender.sendMessage("You do not have permission");
            return true;
        }

        Replay plugin = Replay.getInstance();
        ReplayConfigReloadResult result = plugin.reloadRuntimeConfig();

        sender.sendMessage("§aReloaded BetterReplay config.");
        if (result.retentionServiceRestarted()) {
            if (result.retentionRestartChanges().isEmpty()) {
                sender.sendMessage("§7Retention service restarted.");
            } else {
                sender.sendMessage("§7Retention service restarted for: " + formatSettings(result.retentionRestartChanges()));
            }
        }
        if (!result.immediateChanges().isEmpty()) {
            sender.sendMessage("§7Applied immediately: " + formatSettings(result.immediateChanges()));
        }
        if (!result.newSessionChanges().isEmpty()) {
            sender.sendMessage("§7Affects new recordings/replays only: " + formatSettings(result.newSessionChanges()));
        }
        if (!result.futureChanges().isEmpty()) {
            sender.sendMessage("§7Applies to future startup/manual checks: " + formatSettings(result.futureChanges()));
        }
        if (!result.restartRequiredChanges().isEmpty()) {
            sender.sendMessage("§7Still requires restart: " + formatSettings(result.restartRequiredChanges()));
        }
        if (!result.hasVisibleChanges()) {
            sender.sendMessage("§7No runtime-facing config value changes were detected.");
        }

        return true;
    }

    private String formatSettings(List<ReplayConfigSetting> settings) {
        return settings.stream()
                .map(ReplayConfigSetting::getKey)
                .collect(Collectors.joining(", "));
    }

    private String configuredDefaultReplayServer(Replay plugin) {
        String configured = ReplayConfigSetting.VELOCITY_DEFAULT_REPLAY_SERVER.getString(plugin.getConfig());
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return configured.trim();
    }

    private String formatReplayListName(ReplaySummary replay, String protectedHighlightColor) {
        if (replay.protectedFromDeletion()) {
            return protectedHighlightColor + replay.name();
        }
        return "§f" + replay.name();
    }

    private String resolveConfiguredColor(String configuredColor) {
        if (configuredColor == null || configuredColor.isBlank()) {
            return DEFAULT_LIST_PROTECTED_COLOR;
        }

        String translated = translateLegacyColorCodes(configuredColor.trim());
        return translated.indexOf(LEGACY_COLOR_CODE_CHAR) >= 0 ? translated : DEFAULT_LIST_PROTECTED_COLOR;
    }

    private String translateLegacyColorCodes(String input) {
        StringBuilder translated = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            if (current == '&' && index + 1 < input.length() && isLegacyColorCode(input.charAt(index + 1))) {
                translated.append(LEGACY_COLOR_CODE_CHAR).append(Character.toLowerCase(input.charAt(index + 1)));
                index++;
                continue;
            }
            translated.append(current);
        }
        return translated.toString();
    }

    private boolean isLegacyColorCode(char code) {
        return LEGACY_COLOR_CODES.indexOf(code) >= 0;
    }

    private boolean handleProtect(CommandSender sender, String[] args, String protectedBy) {
        if (!sender.hasPermission("replay.protect")) {
            sender.sendMessage("You do not have permission");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /replay protect <name>");
            return true;
        }

        String name = joinArgs(args, 1);
        java.util.Optional<String> invalidReplayName = ReplayNames.validateReplayName(name);
        if (invalidReplayName.isPresent()) {
            sender.sendMessage("§c" + invalidReplayName.get());
            return true;
        }
        replayManager.protectSavedReplay(name, protectedBy)
                .thenAccept(result -> sendMessageNextTick(sender, switch (result) {
                    case UPDATED -> "§aProtected replay: " + name;
                    case ALREADY_PROTECTED -> "§eReplay is already protected: " + name;
                    case ALREADY_UNPROTECTED, NOT_FOUND -> "§cReplay not found: " + name;
                }))
                .exceptionally(ex -> {
                    Replay.getInstance().getLogger().log(Level.SEVERE, "Failed to protect replay: " + name, ex);
                    sendMessageNextTick(sender, "§cFailed to protect replay: " + name);
                    return null;
                });
        return true;
    }

    private boolean handleUnprotect(CommandSender sender, String[] args) {
        if (!sender.hasPermission("replay.unprotect")) {
            sender.sendMessage("You do not have permission");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /replay unprotect <name>");
            return true;
        }

        String name = joinArgs(args, 1);
        java.util.Optional<String> invalidReplayName = ReplayNames.validateReplayName(name);
        if (invalidReplayName.isPresent()) {
            sender.sendMessage("§c" + invalidReplayName.get());
            return true;
        }
        replayManager.unprotectSavedReplay(name)
                .thenAccept(result -> sendMessageNextTick(sender, switch (result) {
                    case UPDATED -> "§aUnprotected replay: " + name;
                    case ALREADY_UNPROTECTED -> "§eReplay is already unprotected: " + name;
                    case ALREADY_PROTECTED, NOT_FOUND -> "§cReplay not found: " + name;
                }))
                .exceptionally(ex -> {
                    Replay.getInstance().getLogger().log(Level.SEVERE, "Failed to unprotect replay: " + name, ex);
                    sendMessageNextTick(sender, "§cFailed to unprotect replay: " + name);
                    return null;
                });
        return true;
    }

    private void sendMessageNextTick(CommandSender sender, String message) {
        Replay.getInstance().getFoliaLib().getScheduler().runNextTick(task -> ReplayMessages.send(sender, message));
    }

}
