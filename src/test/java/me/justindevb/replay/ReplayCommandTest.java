package me.justindevb.replay;

import me.justindevb.replay.api.ReplayManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplayCommandTest {

    @Mock private ReplayManager replayManager;
    @Mock private Player player;
    @Mock private Command command;

    private ReplayCommand replayCommand;

    @BeforeEach
    void setUp() {
        replayCommand = new ReplayCommand(replayManager);
    }

    // ── Non-player sender ─────────────────────────────────────

    @Test
    void nonPlayerSender_rejected() {
        org.bukkit.command.CommandSender consoleSender = mock(org.bukkit.command.CommandSender.class);
        boolean result = replayCommand.onCommand(consoleSender, command, "replay", new String[]{});
        assertTrue(result);
        verify(consoleSender).sendMessage("Must be a player to execute this command");
    }

    // ── No args ───────────────────────────────────────────────

    @Test
    void noArgs_sendsHelp() {
        when(player.hasPermission(anyString())).thenReturn(false);
        replayCommand.onCommand(player, command, "replay", new String[]{});
        verify(player).sendMessage("§6§lBetterReplay Commands:");
    }

    // ── Start ─────────────────────────────────────────────────

    @Nested
    class Start {
        @Test
        void noPermission_rejected() {
            when(player.hasPermission("replay.start")).thenReturn(false);
            replayCommand.onCommand(player, command, "replay", new String[]{"start", "test", "Player1"});
            verify(player).sendMessage("You do not have permission");
        }

        @Test
        void missingArgs_showsUsage() {
            when(player.hasPermission("replay.start")).thenReturn(true);
            replayCommand.onCommand(player, command, "replay", new String[]{"start", "test"});
            verify(player).sendMessage("§cUsage: /replay start <name> <player1 player2 ...> [durationSeconds]");
        }

        @Test
        void validWithDuration_startsRecording() {
            when(player.hasPermission("replay.start")).thenReturn(true);
            Player target = mock(Player.class);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayerExact("Steve")).thenReturn(target);
                when(replayManager.startRecording(eq("mySession"), anyCollection(), eq(60))).thenReturn(true);

                replayCommand.onCommand(player, command, "replay",
                        new String[]{"start", "mySession", "Steve", "60"});

                verify(replayManager).startRecording(eq("mySession"), anyCollection(), eq(60));
            }
        }

        @Test
        void validWithoutDuration_defaultsToNegativeOne() {
            when(player.hasPermission("replay.start")).thenReturn(true);
            Player target = mock(Player.class);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayerExact("Steve")).thenReturn(target);
                when(replayManager.startRecording(eq("mySession"), anyCollection(), eq(-1))).thenReturn(true);

                replayCommand.onCommand(player, command, "replay",
                        new String[]{"start", "mySession", "Steve"});

                verify(replayManager).startRecording(eq("mySession"), anyCollection(), eq(-1));
            }
        }

        @Test
        void nonexistentPlayer_showsError() {
            when(player.hasPermission("replay.start")).thenReturn(true);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayerExact("Nobody")).thenReturn(null);

                replayCommand.onCommand(player, command, "replay",
                        new String[]{"start", "test", "Nobody"});

                verify(player).sendMessage("§cPlayer not found: Nobody");
                verify(player).sendMessage("§cNo valid players to record.");
            }
        }

        @Test
        void duplicateSessionName_showsError() {
            when(player.hasPermission("replay.start")).thenReturn(true);
            Player target = mock(Player.class);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayerExact("Steve")).thenReturn(target);
                when(replayManager.startRecording(eq("dup"), anyCollection(), eq(-1))).thenReturn(false);

                replayCommand.onCommand(player, command, "replay",
                        new String[]{"start", "dup", "Steve"});

                verify(player).sendMessage("§cSession with that name already exists!");
            }
        }
    }

    // ── Stop ──────────────────────────────────────────────────

    @Nested
    class Stop {
        @Test
        void noPermission_rejected() {
            when(player.hasPermission("replay.stop")).thenReturn(false);
            replayCommand.onCommand(player, command, "replay", new String[]{"stop", "test"});
            verify(player).sendMessage("You do not have permission");
        }

        @Test
        void missingArgs_showsUsage() {
            when(player.hasPermission("replay.stop")).thenReturn(true);
            replayCommand.onCommand(player, command, "replay", new String[]{"stop"});
            verify(player).sendMessage("§c/replay stop <name>");
        }

        @Test
        void validStop_stopsRecording() {
            when(player.hasPermission("replay.stop")).thenReturn(true);
            when(replayManager.stopRecording("test-session", true)).thenReturn(true);

            replayCommand.onCommand(player, command, "replay", new String[]{"stop", "test-session"});
            verify(player).sendMessage("§aStopped recording session: test-session");
        }

        @Test
        void nonExistentSession_showsError() {
            when(player.hasPermission("replay.stop")).thenReturn(true);
            when(replayManager.stopRecording("nope", true)).thenReturn(false);

            replayCommand.onCommand(player, command, "replay", new String[]{"stop", "nope"});
            verify(player).sendMessage("§cNo active session with that name!");
        }
    }

    // ── Play ──────────────────────────────────────────────────

    @Nested
    class Play {
        @Test
        void noPermission_rejected() {
            when(player.hasPermission("replay.play")).thenReturn(false);
            replayCommand.onCommand(player, command, "replay", new String[]{"play", "r"});
            verify(player).sendMessage("You do not have permission");
        }

        @Test
        void missingArgs_showsUsage() {
            when(player.hasPermission("replay.play")).thenReturn(true);
            replayCommand.onCommand(player, command, "replay", new String[]{"play"});
            verify(player).sendMessage("§c/replay play <name>");
        }

        @Test
        void validPlay_startsReplay() {
            when(player.hasPermission("replay.play")).thenReturn(true);
            when(replayManager.startReplay("test", player))
                    .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

            replayCommand.onCommand(player, command, "replay", new String[]{"play", "test"});
            verify(replayManager).startReplay("test", player);
        }
    }

    // ── Delete ────────────────────────────────────────────────

    @Nested
    class Delete {
        @Test
        void noPermission_rejected() {
            when(player.hasPermission("replay.delete")).thenReturn(false);
            replayCommand.onCommand(player, command, "replay", new String[]{"delete", "r"});
            verify(player).sendMessage("You do not have permission");
        }

        @Test
        void missingArgs_showsUsage() {
            when(player.hasPermission("replay.delete")).thenReturn(true);
            replayCommand.onCommand(player, command, "replay", new String[]{"delete"});
            // Sender gets usage message
            verify(player).sendMessage("Usage: /replay delete <name>");
        }
    }

    // ── List ──────────────────────────────────────────────────

    @Nested
    class ListCmd {
        @Test
        void noPermission_rejected() {
            when(player.hasPermission("replay.list")).thenReturn(false);
            replayCommand.onCommand(player, command, "replay", new String[]{"list"});
            verify(player).sendMessage("You do not have permission");
        }
    }

    // ── Unknown subcommand ────────────────────────────────────

    @Test
    void unknownSubcommand_showsError() {
        when(player.hasPermission(anyString())).thenReturn(false);
        replayCommand.onCommand(player, command, "replay", new String[]{"foobar"});
        verify(player).sendMessage("§cUnknown subcommand: §ffoobar");
    }

    // ── Tab completion ────────────────────────────────────────

    @Nested
    class TabComplete {
        @Test
        void firstArg_showsAvailableSubcommands() {
            when(player.hasPermission("replay.start")).thenReturn(true);
            when(player.hasPermission("replay.stop")).thenReturn(true);
            when(player.hasPermission("replay.play")).thenReturn(false);
            when(player.hasPermission("replay.delete")).thenReturn(false);
            when(player.hasPermission("replay.list")).thenReturn(false);

            List<String> completions = replayCommand.onTabComplete(player, command, "replay", new String[]{""});
            assertTrue(completions.contains("start"));
            assertTrue(completions.contains("stop"));
            assertFalse(completions.contains("play"));
        }

        @Test
        void firstArg_filtersPrefix() {
            when(player.hasPermission("replay.start")).thenReturn(true);
            when(player.hasPermission("replay.stop")).thenReturn(true);
            when(player.hasPermission("replay.play")).thenReturn(true);
            when(player.hasPermission("replay.delete")).thenReturn(true);
            when(player.hasPermission("replay.list")).thenReturn(true);

            List<String> completions = replayCommand.onTabComplete(player, command, "replay", new String[]{"st"});
            assertTrue(completions.contains("start"));
            assertTrue(completions.contains("stop"));
            assertFalse(completions.contains("play"));
        }

        @Test
        void stopSubcommand_suggestsActiveSessions() {
            when(player.hasPermission("replay.stop")).thenReturn(true);
            when(replayManager.getActiveRecordings()).thenReturn(List.of("session1", "session2"));

            List<String> completions = replayCommand.onTabComplete(player, command, "replay", new String[]{"stop", ""});
            assertTrue(completions.contains("session1"));
            assertTrue(completions.contains("session2"));
        }

        @Test
        void playSubcommand_suggestsCachedReplays() {
            when(player.hasPermission("replay.play")).thenReturn(true);
            when(replayManager.getCachedReplayNames()).thenReturn(List.of("replay1", "replay2"));

            List<String> completions = replayCommand.onTabComplete(player, command, "replay", new String[]{"play", ""});
            assertTrue(completions.contains("replay1"));
            assertTrue(completions.contains("replay2"));
        }

        @Test
        void deleteSubcommand_suggestsCachedReplays() {
            when(player.hasPermission("replay.delete")).thenReturn(true);
            when(replayManager.getCachedReplayNames()).thenReturn(List.of("r1", "r2"));

            List<String> completions = replayCommand.onTabComplete(player, command, "replay", new String[]{"delete", ""});
            assertTrue(completions.contains("r1"));
        }
    }
}
