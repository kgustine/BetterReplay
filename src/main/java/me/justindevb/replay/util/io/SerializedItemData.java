package me.justindevb.replay.util.io;

import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Base64;

public final class SerializedItemData {

    private static final SerializedItemData EMPTY = new SerializedItemData(new byte[0], true);

    private final byte[] bytes;
    private final boolean empty;
    private final int hashCode;

    private SerializedItemData(byte[] bytes, boolean empty) {
        this.bytes = bytes;
        this.empty = empty;
        this.hashCode = 31 * Boolean.hashCode(empty) + Arrays.hashCode(bytes);
    }

    public static SerializedItemData empty() {
        return EMPTY;
    }

    public static SerializedItemData fromItemStack(ItemStack item) {
        byte[] bytes = ItemStackSerializer.serializeItemBytes(item);
        return bytes == null || bytes.length == 0 ? EMPTY : new SerializedItemData(bytes, false);
    }

    public static SerializedItemData fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return EMPTY;
        }
        return new SerializedItemData(Arrays.copyOf(bytes, bytes.length), false);
    }

    public static SerializedItemData fromBase64(String data) {
        if (data == null || data.isEmpty()) {
            return EMPTY;
        }
        return fromBytes(Base64.getDecoder().decode(data));
    }

    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    public int length() {
        return bytes.length;
    }

    public int fingerprint() {
        return Arrays.hashCode(bytes);
    }

    public boolean isEmpty() {
        return empty;
    }

    public String toBase64() {
        return empty ? null : Base64.getEncoder().encodeToString(bytes);
    }

    public ItemStack toItemStack() {
        return empty ? null : ItemStackSerializer.deserializeItemBytes(bytes);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SerializedItemData that)) {
            return false;
        }
        return empty == that.empty && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}