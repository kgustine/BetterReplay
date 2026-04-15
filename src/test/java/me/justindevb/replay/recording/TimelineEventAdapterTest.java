package me.justindevb.replay.recording;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimelineEventAdapterTest {

    private final Gson gson = new GsonBuilder()
            .registerTypeHierarchyAdapter(TimelineEvent.class, new TimelineEventAdapter())
            .create();

    private TimelineEvent roundtrip(TimelineEvent event) {
        String json = gson.toJson(event, TimelineEvent.class);
        return gson.fromJson(json, TimelineEvent.class);
    }

    private JsonObject toJson(TimelineEvent event) {
        String json = gson.toJson(event, TimelineEvent.class);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ── PlayerMove ────────────────────────────────────────────

    @Nested
    class PlayerMoveTests {
        @Test
        void roundtrip() {
            var original = new TimelineEvent.PlayerMove(
                    10, "uuid-1", "Steve", "world", 1.5, 64.0, -3.2, 90f, -45f, "STANDING");
            var restored = (TimelineEvent.PlayerMove) TimelineEventAdapterTest.this.roundtrip(original);

            assertEquals(original.tick(), restored.tick());
            assertEquals(original.uuid(), restored.uuid());
            assertEquals(original.name(), restored.name());
            assertEquals(original.world(), restored.world());
            assertEquals(original.x(), restored.x(), 0.001);
            assertEquals(original.y(), restored.y(), 0.001);
            assertEquals(original.z(), restored.z(), 0.001);
            assertEquals(original.yaw(), restored.yaw(), 0.001);
            assertEquals(original.pitch(), restored.pitch(), 0.001);
            assertEquals(original.pose(), restored.pose());
        }

        @Test
        void jsonContainsTypeDiscriminator() {
            var event = new TimelineEvent.PlayerMove(0, "u", "n", "w", 0, 0, 0, 0, 0, "STANDING");
            assertEquals("player_move", toJson(event).get("type").getAsString());
        }

        @Test
        void deserialize_missingPose_defaultsToNull() {
            String json = """
                    {"type":"player_move","tick":5,"uuid":"u","name":"n","world":"w","x":0,"y":0,"z":0,"yaw":0,"pitch":0}
                    """;
            TimelineEvent.PlayerMove event = (TimelineEvent.PlayerMove) gson.fromJson(json, TimelineEvent.class);
            assertNull(event.pose());
        }
    }

    // ── EntityMove ────────────────────────────────────────────

    @Nested
    class EntityMoveTests {
        @Test
        void roundtrip() {
            var original = new TimelineEvent.EntityMove(20, "uuid-2", "ZOMBIE", "world", 5.0, 70.0, 8.0, 180f, 0f);
            var restored = (TimelineEvent.EntityMove) TimelineEventAdapterTest.this.roundtrip(original);

            assertEquals(original.tick(), restored.tick());
            assertEquals(original.uuid(), restored.uuid());
            assertEquals(original.etype(), restored.etype());
            assertEquals(original.x(), restored.x(), 0.001);
        }

        @Test
        void jsonType() {
            var event = new TimelineEvent.EntityMove(0, "u", "ZOMBIE", "w", 0, 0, 0, 0, 0);
            assertEquals("entity_move", toJson(event).get("type").getAsString());
        }
    }

    // ── InventoryUpdate ───────────────────────────────────────

    @Nested
    class InventoryUpdateTests {
        @Test
        void roundtrip() {
            var original = new TimelineEvent.InventoryUpdate(
                    5, "uuid-3", "main-hand-data", "off-hand-data",
                    List.of("boots", "legs", "chest", "helmet"),
                    List.of("slot0", "slot1", "slot2"));
            var restored = (TimelineEvent.InventoryUpdate) TimelineEventAdapterTest.this.roundtrip(original);

            assertEquals(original.mainHand(), restored.mainHand());
            assertEquals(original.offHand(), restored.offHand());
            assertEquals(original.armor(), restored.armor());
            assertEquals(original.contents(), restored.contents());
        }

        @Test
        void deserialize_missingArmorAndContents() {
            String json = """
                    {"type":"inventory_update","tick":0,"uuid":"u","mainHand":"mh","offHand":"oh"}
                    """;
            TimelineEvent.InventoryUpdate event = (TimelineEvent.InventoryUpdate) gson.fromJson(json, TimelineEvent.class);
            assertTrue(event.armor().isEmpty());
            assertTrue(event.contents().isEmpty());
        }
    }

    // ── BlockBreak ────────────────────────────────────────────

    @Nested
    class BlockBreakTests {
        @Test
        void roundtrip() {
            var original = new TimelineEvent.BlockBreak(15, "uuid-4", "world", 10, 64, -5, "minecraft:stone");
            var restored = (TimelineEvent.BlockBreak) TimelineEventAdapterTest.this.roundtrip(original);

            assertEquals(original.world(), restored.world());
            assertEquals(original.x(), restored.x());
            assertEquals(original.y(), restored.y());
            assertEquals(original.z(), restored.z());
            assertEquals(original.blockData(), restored.blockData());
        }
    }

    // ── BlockBreakComplete ────────────────────────────────────

    @Nested
    class BlockBreakCompleteTests {
        @Test
        void roundtrip() {
            var original = new TimelineEvent.BlockBreakComplete(3, "uuid-5", "world", 1, 2, 3);
            var restored = (TimelineEvent.BlockBreakComplete) TimelineEventAdapterTest.this.roundtrip(original);

            assertEquals(original.x(), restored.x());
        }

        @ParameterizedTest
        @ValueSource(strings = {"block_break_complete", "block_break_start"})
        void backwardCompatAliases(String type) {
            String json = "{\"type\":\"" + type + "\",\"tick\":0,\"uuid\":\"u\",\"world\":\"w\",\"x\":1,\"y\":2,\"z\":3}";
            TimelineEvent event = gson.fromJson(json, TimelineEvent.class);
            assertInstanceOf(TimelineEvent.BlockBreakComplete.class, event);
        }
    }

    // ── BlockBreakStage ───────────────────────────────────────

    @Nested
    class BlockBreakStageTests {
        @Test
        void roundtrip() {
            var original = new TimelineEvent.BlockBreakStage(7, "uuid-6", "world", 5, 6, 7, 4);
            var restored = (TimelineEvent.BlockBreakStage) TimelineEventAdapterTest.this.roundtrip(original);

            assertEquals(original.stage(), restored.stage());
        }

        @Test
        void nullUuid_serializedCorrectly() {
            var event = new TimelineEvent.BlockBreakStage(0, null, "w", 0, 0, 0, 3);
            JsonObject json = toJson(event);
            assertFalse(json.has("uuid"));

            TimelineEvent restored = gson.fromJson(json, TimelineEvent.class);
            assertNull(restored.uuid());
        }
    }

    // ── BlockPlace ────────────────────────────────────────────

    @Nested
    class BlockPlaceTests {
        @Test
        void roundtrip() {
            var original = new TimelineEvent.BlockPlace(
                    8, "uuid-7", "world", 10, 65, 20, "minecraft:cobblestone", "minecraft:air");
            var restored = (TimelineEvent.BlockPlace) TimelineEventAdapterTest.this.roundtrip(original);

            assertEquals(original.blockData(), restored.blockData());
            assertEquals(original.replacedBlockData(), restored.replacedBlockData());
        }

        @Test
        void deserialize_missingReplacedBlockData() {
            String json = """
                    {"type":"block_place","tick":0,"uuid":"u","world":"w","x":0,"y":0,"z":0,"blockData":"minecraft:stone"}
                    """;
            TimelineEvent.BlockPlace event = (TimelineEvent.BlockPlace) gson.fromJson(json, TimelineEvent.class);
            assertNull(event.replacedBlockData());
        }
    }

    // ── ItemDrop ──────────────────────────────────────────────

    @Nested
    class ItemDropTests {
        @Test
        void roundtrip() {
            var original = new TimelineEvent.ItemDrop(
                    12, "uuid-8", "item-data", "world", 1.0, 2.0, 3.0, 45f, 30f);
            var restored = (TimelineEvent.ItemDrop) TimelineEventAdapterTest.this.roundtrip(original);

            assertEquals(original.item(), restored.item());
            assertEquals(original.locWorld(), restored.locWorld());
            assertEquals(original.locX(), restored.locX(), 0.001);
        }

        @Test
        void deserialize_legacyLocationKey() {
            String json = """
                    {"type":"item_drop","tick":0,"uuid":"u","item":"i",
                     "location":{"world":"w","x":1,"y":2,"z":3,"yaw":0,"pitch":0}}
                    """;
            TimelineEvent.ItemDrop event = (TimelineEvent.ItemDrop) gson.fromJson(json, TimelineEvent.class);
            assertEquals("w", event.locWorld());
            assertEquals(1.0, event.locX(), 0.001);
        }

        @Test
        void deserialize_missingLocation() {
            String json = """
                    {"type":"item_drop","tick":0,"uuid":"u","item":"i"}
                    """;
            TimelineEvent.ItemDrop event = (TimelineEvent.ItemDrop) gson.fromJson(json, TimelineEvent.class);
            assertNull(event.locWorld());
            assertEquals(0.0, event.locX(), 0.001);
        }
    }

    // ── Attack ────────────────────────────────────────────────

    @Nested
    class AttackTests {
        @Test
        void roundtrip() {
            var original = new TimelineEvent.Attack(9, "uuid-9", "target-uuid", "entity-uuid", "ZOMBIE");
            var restored = (TimelineEvent.Attack) TimelineEventAdapterTest.this.roundtrip(original);

            assertEquals(original.targetUuid(), restored.targetUuid());
            assertEquals(original.entityUuid(), restored.entityUuid());
            assertEquals(original.entityType(), restored.entityType());
        }

        @Test
        void nullTargetUuid() {
            var event = new TimelineEvent.Attack(0, "u", null, "eu", "ZOMBIE");
            JsonObject json = toJson(event);
            assertFalse(json.has("targetUuid"));

            var restored = (TimelineEvent.Attack) gson.fromJson(json, TimelineEvent.class);
            assertNull(restored.targetUuid());
        }
    }

    // ── Swing ─────────────────────────────────────────────────

    @Nested
    class SwingTests {
        @Test
        void roundtrip() {
            var original = new TimelineEvent.Swing(11, "uuid-10", "ARM_SWING");
            var restored = (TimelineEvent.Swing) TimelineEventAdapterTest.this.roundtrip(original);
            assertEquals(original.hand(), restored.hand());
        }
    }

    // ── SprintToggle ──────────────────────────────────────────

    @Nested
    class SprintToggleTests {
        @Test
        void roundtripStart() {
            var original = new TimelineEvent.SprintToggle(13, "uuid-11", true);
            var json = toJson(original);
            assertEquals("sprint_start", json.get("type").getAsString());

            var restored = (TimelineEvent.SprintToggle) gson.fromJson(json, TimelineEvent.class);
            assertTrue(restored.sprinting());
        }

        @Test
        void roundtripStop() {
            var original = new TimelineEvent.SprintToggle(14, "uuid-12", false);
            var json = toJson(original);
            assertEquals("sprint_stop", json.get("type").getAsString());

            var restored = (TimelineEvent.SprintToggle) gson.fromJson(json, TimelineEvent.class);
            assertFalse(restored.sprinting());
        }
    }

    // ── SneakToggle ───────────────────────────────────────────

    @Nested
    class SneakToggleTests {
        @Test
        void roundtripStart() {
            var original = new TimelineEvent.SneakToggle(15, "uuid-13", true);
            var json = toJson(original);
            assertEquals("sneak_start", json.get("type").getAsString());

            var restored = (TimelineEvent.SneakToggle) gson.fromJson(json, TimelineEvent.class);
            assertTrue(restored.sneaking());
        }

        @Test
        void roundtripStop() {
            var original = new TimelineEvent.SneakToggle(16, "uuid-14", false);
            var json = toJson(original);
            assertEquals("sneak_stop", json.get("type").getAsString());

            var restored = (TimelineEvent.SneakToggle) gson.fromJson(json, TimelineEvent.class);
            assertFalse(restored.sneaking());
        }
    }

    // ── Damaged ───────────────────────────────────────────────

    @Nested
    class DamagedTests {
        @Test
        void roundtrip() {
            var original = new TimelineEvent.Damaged(17, "uuid-15", "PLAYER", "FALL", 5.5);
            var restored = (TimelineEvent.Damaged) TimelineEventAdapterTest.this.roundtrip(original);

            assertEquals(original.entityType(), restored.entityType());
            assertEquals(original.cause(), restored.cause());
            assertEquals(original.finalDamage(), restored.finalDamage(), 0.001);
        }
    }

    // ── EntitySpawn ───────────────────────────────────────────

    @Nested
    class EntitySpawnTests {
        @Test
        void roundtrip() {
            var original = new TimelineEvent.EntitySpawn(18, "uuid-16", "SKELETON", "world", 100, 65, 200);
            var restored = (TimelineEvent.EntitySpawn) TimelineEventAdapterTest.this.roundtrip(original);

            assertEquals(original.etype(), restored.etype());
            assertEquals(original.x(), restored.x(), 0.001);
        }

        @Test
        void legacyMobSpawnType() {
            String json = """
                    {"type":"mob_spawn","tick":0,"uuid":"u","etype":"ZOMBIE","world":"w","x":0,"y":0,"z":0}
                    """;
            TimelineEvent event = gson.fromJson(json, TimelineEvent.class);
            assertInstanceOf(TimelineEvent.EntitySpawn.class, event);
        }
    }

    // ── EntityDeath ───────────────────────────────────────────

    @Nested
    class EntityDeathTests {
        @Test
        void roundtrip() {
            var original = new TimelineEvent.EntityDeath(19, "uuid-17", "CREEPER", "world", 50, 60, 70);
            var restored = (TimelineEvent.EntityDeath) TimelineEventAdapterTest.this.roundtrip(original);

            assertEquals(original.etype(), restored.etype());
        }
    }

    // ── PlayerQuit ────────────────────────────────────────────

    @Nested
    class PlayerQuitTests {
        @Test
        void roundtrip() {
            var original = new TimelineEvent.PlayerQuit(20, "uuid-18");
            var restored = (TimelineEvent.PlayerQuit) TimelineEventAdapterTest.this.roundtrip(original);

            assertEquals(original.tick(), restored.tick());
            assertEquals(original.uuid(), restored.uuid());
        }
    }

    // ── Error handling ────────────────────────────────────────

    @Nested
    class ErrorHandling {
        @Test
        void unknownType_throwsJsonParseException() {
            String json = """
                    {"type":"unknown_event","tick":0,"uuid":"u"}
                    """;
            assertThrows(com.google.gson.JsonParseException.class,
                    () -> gson.fromJson(json, TimelineEvent.class));
        }

        @Test
        void missingType_throwsJsonParseException() {
            String json = """
                    {"tick":0,"uuid":"u"}
                    """;
            assertThrows(com.google.gson.JsonParseException.class,
                    () -> gson.fromJson(json, TimelineEvent.class));
        }

        @Test
        void missingTick_defaultsToZero() {
            String json = """
                    {"type":"player_quit","uuid":"u"}
                    """;
            TimelineEvent event = gson.fromJson(json, TimelineEvent.class);
            assertEquals(0, event.tick());
        }
    }
}
