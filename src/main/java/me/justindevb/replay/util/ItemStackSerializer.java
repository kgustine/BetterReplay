package me.justindevb.replay.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

public final class ItemStackSerializer {
    private ItemStackSerializer() {}

    /**
     * Serialize an ItemStack to a Base64 string using the modern Paper API.
     */
    public static String serializeItem(ItemStack item) {
        if (item == null) return null;
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    /**
     * Deserialize an ItemStack from a Base64 string.
     * Automatically detects and handles legacy BukkitObjectOutputStream format
     * (used in recordings created before this change).
     */
    public static ItemStack deserializeItem(Object obj) {
        if (!(obj instanceof String data) || data.isEmpty()) return null;

        byte[] bytes = Base64.getDecoder().decode(data);

        // Legacy format detection: Java ObjectOutputStream writes magic bytes 0xAC 0xED
        if (bytes.length >= 2 && bytes[0] == (byte) 0xAC && bytes[1] == (byte) 0xED) {
            return deserializeLegacy(bytes);
        }

        return ItemStack.deserializeBytes(bytes);
    }

    @SuppressWarnings("deprecation")
    private static ItemStack deserializeLegacy(byte[] bytes) {
        try (
                ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
                BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)
        ) {
            Object read = dataInput.readObject();
            return read instanceof ItemStack item ? item : null;
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }
}
