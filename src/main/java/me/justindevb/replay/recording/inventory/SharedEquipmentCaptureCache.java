package me.justindevb.replay.recording.inventory;

import me.justindevb.replay.recording.inventory.InventoryCaptureService.CapturedEquipmentState;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SharedEquipmentCaptureCache {

    private final Map<UUID, CapturedEquipmentState> cachedEquipment = new HashMap<>();

    public void beginTick() {
        cachedEquipment.clear();
    }

    public CapturedEquipmentState captureEquipment(Player player, InventoryCaptureService captureService) {
        return cachedEquipment.computeIfAbsent(player.getUniqueId(), ignored -> captureService.captureEquipment(player));
    }
}