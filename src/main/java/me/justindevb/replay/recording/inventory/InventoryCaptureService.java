package me.justindevb.replay.recording.inventory;

import me.justindevb.replay.recording.TimelineEvent;
import me.justindevb.replay.util.io.SerializedItemData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;

public final class InventoryCaptureService {

    public CapturedInventoryStorageSnapshot captureStorage(Player player) {
        return captureStorage(player.getInventory());
    }

    public CapturedInventoryStorageSnapshot captureStorage(PlayerInventory inventory) {
        ItemStack[] storageContents = inventory.getStorageContents();
        if (storageContents == null) {
            storageContents = new ItemStack[36];
        }
        List<SerializedItemData> storage = captureItems(storageContents);
        return new CapturedInventoryStorageSnapshot(storage, computeHash(storage));
    }

    public CapturedEquipmentState captureEquipment(Player player) {
        return captureEquipment(player.getInventory());
    }

    public CapturedEquipmentState captureEquipment(PlayerInventory inventory) {
        List<SerializedItemData> armor = List.of(
                SerializedItemData.fromItemStack(inventory.getBoots()),
                SerializedItemData.fromItemStack(inventory.getLeggings()),
                SerializedItemData.fromItemStack(inventory.getChestplate()),
                SerializedItemData.fromItemStack(inventory.getHelmet())
        );
        SerializedItemData mainHand = SerializedItemData.fromItemStack(inventory.getItemInMainHand());
        SerializedItemData offHand = SerializedItemData.fromItemStack(inventory.getItemInOffHand());
        long stateHash = computeHash(armor, inventory.getHeldItemSlot(), mainHand, offHand);
        return new CapturedEquipmentState(inventory.getHeldItemSlot(), mainHand, offHand, armor, stateHash);
    }

    public boolean hasStorageChanged(CapturedInventoryStorageSnapshot current, CapturedInventoryStorageSnapshot previous) {
        if (previous == null) {
            return true;
        }
        if (current.snapshotHash() != previous.snapshotHash()) {
            return true;
        }
        return !current.storage().equals(previous.storage());
    }

    public boolean hasEquipmentChanged(CapturedEquipmentState current, CapturedEquipmentState previous) {
        if (previous == null) {
            return true;
        }
        if (current.heldSlot() != previous.heldSlot()) {
            return true;
        }
        if (current.stateHash() != previous.stateHash()) {
            return true;
        }
        if (!current.mainHand().equals(previous.mainHand())) {
            return true;
        }
        if (!current.offHand().equals(previous.offHand())) {
            return true;
        }
        return !current.armor().equals(previous.armor());
    }

    public TimelineEvent.InventoryStorageUpdate toStorageEvent(int tick, String uuid, CapturedInventoryStorageSnapshot snapshot) {
        return new TimelineEvent.InventoryStorageUpdate(tick, uuid, snapshot.storage());
    }

    public TimelineEvent.EquipmentStateUpdate toEquipmentEvent(int tick, String uuid, CapturedEquipmentState state) {
        return new TimelineEvent.EquipmentStateUpdate(tick, uuid, state.heldSlot(), state.mainHand(), state.offHand(), state.armor());
    }

    private static List<SerializedItemData> captureItems(ItemStack[] items) {
        if (items == null) {
            return List.of();
        }
        List<SerializedItemData> captured = new ArrayList<>(items.length);
        for (ItemStack item : items) {
            captured.add(SerializedItemData.fromItemStack(item));
        }
        return List.copyOf(captured);
    }

    private static long computeHash(List<SerializedItemData> items) {
        long hash = 1L;
        for (SerializedItemData item : items) {
            hash = 31L * hash + item.hashCode();
        }
        return hash;
    }

    private static long computeHash(List<SerializedItemData> armor, int heldSlot, SerializedItemData mainHand, SerializedItemData offHand) {
        long hash = computeHash(armor);
        hash = 31L * hash + heldSlot;
        hash = 31L * hash + mainHand.hashCode();
        hash = 31L * hash + offHand.hashCode();
        return hash;
    }

    public record CapturedInventoryStorageSnapshot(List<SerializedItemData> storage, long snapshotHash) {
        public CapturedInventoryStorageSnapshot {
            storage = List.copyOf(storage);
        }
    }

    public record CapturedEquipmentState(
            int heldSlot,
            SerializedItemData mainHand,
            SerializedItemData offHand,
            List<SerializedItemData> armor,
            long stateHash
    ) {
        public CapturedEquipmentState {
            armor = List.copyOf(armor);
        }
    }
}