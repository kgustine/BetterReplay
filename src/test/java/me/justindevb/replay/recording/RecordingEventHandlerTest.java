package me.justindevb.replay.recording;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecordingEventHandlerTest {

    @Mock private EntityTracker tracker;
    private TimelineBuilder builder;
    private int tick = 10;
    private RecordingEventHandler handler;

    @BeforeEach
    void setUp() {
        builder = new TimelineBuilder();
        handler = new RecordingEventHandler(tracker, builder, () -> tick);
    }

    private Player mockPlayer(UUID uuid) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(uuid);
        return p;
    }

    private Block mockBlock(String worldName, int x, int y, int z, String blockDataStr) {
        Block block = mock(Block.class);
        World world = mock(World.class);
        BlockData blockData = mock(BlockData.class);
        when(world.getName()).thenReturn(worldName);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        if (blockDataStr != null) {
            when(block.getBlockData()).thenReturn(blockData);
            when(blockData.getAsString()).thenReturn(blockDataStr);
        }
        return block;
    }

    // ── Guard clause: untracked players ───────────────────────

    @Test
    void untrackedPlayer_noEventRecorded_blockBreak() {
        UUID uuid = UUID.randomUUID();
        Player p = mockPlayer(uuid);
        when(tracker.isTrackedPlayer(uuid)).thenReturn(false);

        Block block = mockBlock("world", 0, 0, 0, "minecraft:stone");
        BlockBreakEvent event = new BlockBreakEvent(block, p);
        handler.onBlockBreak(event);

        assertTrue(builder.getTimeline().isEmpty());
    }

    // ── BlockBreak ────────────────────────────────────────────

    @Test
    void onBlockBreak_recordsBlockBreakEvent() {
        UUID uuid = UUID.randomUUID();
        Player p = mockPlayer(uuid);
        when(tracker.isTrackedPlayer(uuid)).thenReturn(true);

        Block block = mockBlock("world", 10, 64, -5, "minecraft:stone");
        BlockBreakEvent event = new BlockBreakEvent(block, p);
        handler.onBlockBreak(event);

        assertEquals(1, builder.getTimeline().size());
        TimelineEvent.BlockBreak bb = (TimelineEvent.BlockBreak) builder.getTimeline().get(0);
        assertEquals(tick, bb.tick());
        assertEquals(uuid.toString(), bb.uuid());
        assertEquals("world", bb.world());
        assertEquals(10, bb.x());
        assertEquals(64, bb.y());
        assertEquals(-5, bb.z());
        assertEquals("minecraft:stone", bb.blockData());
    }

    // ── BlockDamage ───────────────────────────────────────────

    @Test
    void onBlockDamage_recordsBlockBreakComplete() {
        UUID uuid = UUID.randomUUID();
        Player p = mockPlayer(uuid);
        when(tracker.isTrackedPlayer(uuid)).thenReturn(true);

        Block block = mockBlock("world", 5, 60, 8, null);
        ItemStack tool = mock(ItemStack.class);
        BlockDamageEvent event = new BlockDamageEvent(p, block, tool, false);
        handler.onBlockDamage(event);

        assertEquals(1, builder.getTimeline().size());
        assertInstanceOf(TimelineEvent.BlockBreakComplete.class, builder.getTimeline().get(0));
    }

    // ── BlockPlace ────────────────────────────────────────────

    @Test
    void onBlockPlace_recordsBlockPlaceEvent() {
        UUID uuid = UUID.randomUUID();
        Player p = mockPlayer(uuid);
        when(tracker.isTrackedPlayer(uuid)).thenReturn(true);

        Block block = mockBlock("world", 7, 65, 3, "minecraft:cobblestone");
        BlockState replacedState = mock(BlockState.class);
        BlockData replacedBlockData = mock(BlockData.class);
        when(replacedState.getBlockData()).thenReturn(replacedBlockData);
        when(replacedBlockData.getAsString()).thenReturn("minecraft:air");

        BlockPlaceEvent event = new BlockPlaceEvent(block, replacedState, block, mock(ItemStack.class), p, true);
        handler.onBlockPlace(event);

        assertEquals(1, builder.getTimeline().size());
        TimelineEvent.BlockPlace bp = (TimelineEvent.BlockPlace) builder.getTimeline().get(0);
        assertEquals("minecraft:cobblestone", bp.blockData());
        assertEquals("minecraft:air", bp.replacedBlockData());
    }

    // ── ItemDrop ──────────────────────────────────────────────

    @Test
    void onItemDrop_recordsItemDropEvent() {
        UUID uuid = UUID.randomUUID();
        Player p = mockPlayer(uuid);
        when(tracker.isTrackedPlayer(uuid)).thenReturn(true);

        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Location loc = new Location(world, 1, 2, 3, 45f, 30f);
        when(p.getLocation()).thenReturn(loc);

        Item itemEntity = mock(Item.class);
        ItemStack stack = mock(ItemStack.class);
        when(stack.isEmpty()).thenReturn(true);
        when(itemEntity.getItemStack()).thenReturn(stack);

        PlayerDropItemEvent event = new PlayerDropItemEvent(p, itemEntity);
        handler.onItemDrop(event);

        assertEquals(1, builder.getTimeline().size());
        assertInstanceOf(TimelineEvent.ItemDrop.class, builder.getTimeline().get(0));
    }

    // ── Attack ────────────────────────────────────────────────

    @Nested
    class AttackTests {
        @Test
        void onAttack_playerVsEntity_recordsAndTracksEntity() {
            UUID playerUuid = UUID.randomUUID();
            UUID entityUuid = UUID.randomUUID();
            Player p = mockPlayer(playerUuid);
            when(tracker.isTrackedPlayer(playerUuid)).thenReturn(true);

            Entity target = mock(Entity.class);
            when(target.getUniqueId()).thenReturn(entityUuid);
            when(target.getType()).thenReturn(EntityType.ZOMBIE);
            World targetWorld = mock(World.class);
            when(targetWorld.getName()).thenReturn("world");
            when(target.getWorld()).thenReturn(targetWorld);
            Location targetLoc = new Location(targetWorld, 10, 65, 20);
            when(target.getLocation()).thenReturn(targetLoc);

            when(tracker.isEntityTracked(entityUuid)).thenReturn(false);

            EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
            when(event.getDamager()).thenReturn(p);
            when(event.getEntity()).thenReturn(target);

            handler.onAttack(event);

            // onAttack adds Attack + EntitySpawn for newly-tracked entities
            assertEquals(2, builder.getTimeline().size());
            TimelineEvent.Attack attack = (TimelineEvent.Attack) builder.getTimeline().get(0);
            assertEquals(playerUuid.toString(), attack.uuid());
            assertNull(attack.targetUuid()); // not a Player target
            assertEquals(entityUuid.toString(), attack.entityUuid());
            assertEquals("ZOMBIE", attack.entityType());

            verify(tracker).trackEntity(entityUuid, EntityType.ZOMBIE);
        }

        @Test
        void onAttack_playerVsPlayer_recordsTargetUuid() {
            UUID attackerUuid = UUID.randomUUID();
            UUID targetUuid = UUID.randomUUID();
            Player attacker = mockPlayer(attackerUuid);
            Player target = mockPlayer(targetUuid);
            when(tracker.isTrackedPlayer(attackerUuid)).thenReturn(true);
            when(target.getType()).thenReturn(EntityType.PLAYER);

            EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
            when(event.getDamager()).thenReturn(attacker);
            when(event.getEntity()).thenReturn(target);

            handler.onAttack(event);

            TimelineEvent.Attack attack = (TimelineEvent.Attack) builder.getTimeline().get(0);
            assertEquals(targetUuid.toString(), attack.targetUuid());
        }

        @Test
        void onAttack_nonPlayerDamager_ignored() {
            Entity damager = mock(Entity.class);
            Entity target = mock(Entity.class);

            EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
            when(event.getDamager()).thenReturn(damager);

            handler.onAttack(event);

            assertTrue(builder.getTimeline().isEmpty());
        }
    }

    // ── PlayerAnimation (Swing) ───────────────────────────────

    @Test
    void onPlayerAnimation_recordsSwing() {
        UUID uuid = UUID.randomUUID();
        Player p = mockPlayer(uuid);
        when(tracker.isTrackedPlayer(uuid)).thenReturn(true);

        PlayerAnimationEvent event = mock(PlayerAnimationEvent.class);
        when(event.getPlayer()).thenReturn(p);
        when(event.getAnimationType()).thenReturn(PlayerAnimationType.ARM_SWING);

        handler.onPlayerAnimation(event);

        assertEquals(1, builder.getTimeline().size());
        TimelineEvent.Swing swing = (TimelineEvent.Swing) builder.getTimeline().get(0);
        assertEquals("ARM_SWING", swing.hand());
    }

    // ── SprintToggle ──────────────────────────────────────────

    @Test
    void onSprintToggle_recordsSprintStart() {
        UUID uuid = UUID.randomUUID();
        Player p = mockPlayer(uuid);
        when(tracker.isTrackedPlayer(uuid)).thenReturn(true);

        PlayerToggleSprintEvent event = new PlayerToggleSprintEvent(p, true);
        handler.onSprintToggle(event);

        TimelineEvent.SprintToggle st = (TimelineEvent.SprintToggle) builder.getTimeline().get(0);
        assertTrue(st.sprinting());
    }

    @Test
    void onSprintToggle_recordsSprintStop() {
        UUID uuid = UUID.randomUUID();
        Player p = mockPlayer(uuid);
        when(tracker.isTrackedPlayer(uuid)).thenReturn(true);

        PlayerToggleSprintEvent event = new PlayerToggleSprintEvent(p, false);
        handler.onSprintToggle(event);

        TimelineEvent.SprintToggle st = (TimelineEvent.SprintToggle) builder.getTimeline().get(0);
        assertFalse(st.sprinting());
    }

    // ── SneakToggle ───────────────────────────────────────────

    @Test
    void onSneak_recordsSneakStart() {
        UUID uuid = UUID.randomUUID();
        Player p = mockPlayer(uuid);
        when(tracker.isTrackedPlayer(uuid)).thenReturn(true);

        PlayerToggleSneakEvent event = new PlayerToggleSneakEvent(p, true);
        handler.onSneak(event);

        TimelineEvent.SneakToggle st = (TimelineEvent.SneakToggle) builder.getTimeline().get(0);
        assertTrue(st.sneaking());
    }

    // ── EntityDamaged ─────────────────────────────────────────

    @Test
    void onEntityDamaged_trackedPlayer_records() {
        UUID uuid = UUID.randomUUID();
        Player p = mockPlayer(uuid);
        when(p.getType()).thenReturn(EntityType.PLAYER);
        when(tracker.isTrackedPlayer(uuid)).thenReturn(true);

        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(p);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);
        when(event.getFinalDamage()).thenReturn(3.5);

        handler.onEntityDamaged(event);

        assertEquals(1, builder.getTimeline().size());
        TimelineEvent.Damaged dmg = (TimelineEvent.Damaged) builder.getTimeline().get(0);
        assertEquals("PLAYER", dmg.entityType());
        assertEquals("FALL", dmg.cause());
        assertEquals(3.5, dmg.finalDamage(), 0.001);
    }

    @Test
    void onEntityDamaged_untrackedPlayer_ignored() {
        UUID uuid = UUID.randomUUID();
        Player p = mockPlayer(uuid);
        when(tracker.isTrackedPlayer(uuid)).thenReturn(false);

        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(p);

        handler.onEntityDamaged(event);

        assertTrue(builder.getTimeline().isEmpty());
    }

    // ── EntityDeath ───────────────────────────────────────────

    @Test
    void onEntityDeath_trackedEntity_records() {
        UUID uuid = UUID.randomUUID();
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.getUniqueId()).thenReturn(uuid);
        when(entity.getType()).thenReturn(EntityType.ZOMBIE);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Location loc = new Location(world, 5, 60, 10);
        when(entity.getLocation()).thenReturn(loc);

        when(tracker.isEntityTracked(uuid)).thenReturn(true);

        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(entity);
        when(event.getEntityType()).thenReturn(EntityType.ZOMBIE);

        handler.onEntityDeath(event);

        assertEquals(1, builder.getTimeline().size());
        assertInstanceOf(TimelineEvent.EntityDeath.class, builder.getTimeline().get(0));
        verify(tracker).removeEntity(uuid);
    }

    @Test
    void onEntityDeath_untrackedEntity_ignored() {
        UUID uuid = UUID.randomUUID();
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.getUniqueId()).thenReturn(uuid);
        when(tracker.isEntityTracked(uuid)).thenReturn(false);

        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(entity);

        handler.onEntityDeath(event);

        assertTrue(builder.getTimeline().isEmpty());
    }

    // ── PlayerDeath ───────────────────────────────────────────

    @Test
    void onPlayerDeath_trackedPlayer_records() {
        UUID uuid = UUID.randomUUID();
        Player p = mockPlayer(uuid);
        when(p.getType()).thenReturn(EntityType.PLAYER);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Location loc = new Location(world, 1, 2, 3);
        when(p.getLocation()).thenReturn(loc);
        when(p.getWorld()).thenReturn(world);
        when(tracker.isTrackedPlayer(uuid)).thenReturn(true);

        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getPlayer()).thenReturn(p);
        when(event.getEntityType()).thenReturn(EntityType.PLAYER);

        handler.onPlayerDeath(event);

        assertEquals(1, builder.getTimeline().size());
        TimelineEvent.EntityDeath death = (TimelineEvent.EntityDeath) builder.getTimeline().get(0);
        assertEquals(uuid.toString(), death.uuid());
        assertEquals("PLAYER", death.etype());
    }

    // ── PlayerQuit ────────────────────────────────────────────

    @Test
    void onQuit_trackedPlayer_recordsAndRemoves() {
        UUID uuid = UUID.randomUUID();
        Player p = mockPlayer(uuid);
        when(tracker.isTrackedPlayer(uuid)).thenReturn(true);

        PlayerQuitEvent event = new PlayerQuitEvent(p, (net.kyori.adventure.text.Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED);
        handler.onQuit(event);

        assertEquals(1, builder.getTimeline().size());
        assertInstanceOf(TimelineEvent.PlayerQuit.class, builder.getTimeline().get(0));
        verify(tracker).removePlayer(uuid);
    }

    @Test
    void onQuit_untrackedPlayer_noop() {
        UUID uuid = UUID.randomUUID();
        Player p = mockPlayer(uuid);
        when(tracker.isTrackedPlayer(uuid)).thenReturn(false);

        PlayerQuitEvent event = new PlayerQuitEvent(p, (net.kyori.adventure.text.Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED);
        handler.onQuit(event);

        assertTrue(builder.getTimeline().isEmpty());
    }

    // ── EntitySpawn ───────────────────────────────────────────

    @Test
    void onEntitySpawn_nearbyTrackedPlayer_tracks() {
        UUID entityUuid = UUID.randomUUID();
        Entity entity = mock(Entity.class);
        when(entity.getUniqueId()).thenReturn(entityUuid);
        when(entity.getType()).thenReturn(EntityType.CREEPER);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Location loc = new Location(world, 10, 64, 10);
        when(entity.getLocation()).thenReturn(loc);

        when(tracker.isNearbyTrackedPlayer(loc)).thenReturn(true);
        when(tracker.isEntityTracked(entityUuid)).thenReturn(false);

        EntitySpawnEvent event = mock(EntitySpawnEvent.class);
        when(event.getEntity()).thenReturn(entity);
        when(event.getEntityType()).thenReturn(EntityType.CREEPER);
        when(event.getLocation()).thenReturn(loc);

        handler.onEntitySpawn(event);

        assertEquals(1, builder.getTimeline().size());
        assertInstanceOf(TimelineEvent.EntitySpawn.class, builder.getTimeline().get(0));
        verify(tracker).trackEntity(entityUuid, EntityType.CREEPER);
    }

    @Test
    void onEntitySpawn_notNearby_ignored() {
        Entity entity = mock(Entity.class);
        Location loc = new Location(mock(World.class), 500, 64, 500);
        when(entity.getLocation()).thenReturn(loc);
        when(tracker.isNearbyTrackedPlayer(loc)).thenReturn(false);

        EntitySpawnEvent event = mock(EntitySpawnEvent.class);
        when(event.getEntity()).thenReturn(entity);

        handler.onEntitySpawn(event);

        assertTrue(builder.getTimeline().isEmpty());
    }

    @Test
    void onEntitySpawn_alreadyTracked_ignored() {
        UUID entityUuid = UUID.randomUUID();
        Entity entity = mock(Entity.class);
        when(entity.getUniqueId()).thenReturn(entityUuid);
        Location loc = new Location(mock(World.class), 10, 64, 10);
        when(entity.getLocation()).thenReturn(loc);

        when(tracker.isNearbyTrackedPlayer(loc)).thenReturn(true);
        when(tracker.isEntityTracked(entityUuid)).thenReturn(true);

        EntitySpawnEvent event = mock(EntitySpawnEvent.class);
        when(event.getEntity()).thenReturn(entity);

        handler.onEntitySpawn(event);

        assertTrue(builder.getTimeline().isEmpty());
    }
}
