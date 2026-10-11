package com.example.mobtalk;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Отдельное состояние для каждого мира: репутация, память мобов и задания хранятся в
 * config/mobtalk_worlds/&lt;мир&gt;/. Одиночный мир определяется по папке сохранения, сервер — по адресу.
 * Смена измерения (Незер, Энд) мир не меняет.
 */
public final class Worlds {
    private Worlds() {}

    private static String key = null;

    private static final String[] LEGACY = {"mobtalk_state.json", "mobtalk_memory_v3.json", "mobtalk_memory_v2.json",
            "mobtalk_quests.json"};

    public static synchronized String key() {
        return key == null ? "default" : key;
    }

    private static Path root() {
        return FabricLoader.getInstance().getConfigDir().resolve("mobtalk_worlds");
    }

    public static synchronized Path dir() {
        String k = key();
        String safe = k.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (safe.length() > 60) safe = safe.substring(0, 60);
        Path d = root().resolve(safe + "_" + Integer.toHexString(k.hashCode()));
        try {
            Files.createDirectories(d);
        } catch (Exception e) {
            MobTalkClient.LOG.error("Worlds dir error", e);
        }
        return d;
    }

    public static Path file(String name) {
        return dir().resolve(name);
    }

    private static String compute(MinecraftClient mc) {
        MinecraftServer s = mc.getServer();
        if (s != null) {
            try {
                Path r = s.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
                if (r.getFileName() != null) return "sp_" + r.getFileName();
            } catch (Exception ignored) {
            }
        }
        ServerInfo si = mc.getCurrentServerEntry();
        if (si != null && si.address != null) return "mp_" + si.address.toLowerCase();
        return "other";
    }

    /** Вызывается, когда клиент зашёл в мир: при смене мира подгружает его собственное состояние. */
    public static synchronized void enter(MinecraftClient mc) {
        String k = compute(mc);
        if (k.equals(key)) return;
        key = k;
        State.reset();
        Memory.reset();
        Quests.reset();
        migrateLegacy();
        State.load();
        Memory.load();
        Quests.load();
        MobTalkClient.LOG.info("MobTalk: мир «{}», состояние загружено", k);
    }

    /** Старые общие файлы (до 1.16) достаются первому миру, в который зайдёт игрок. */
    private static void migrateLegacy() {
        try {
            Path marker = root().resolve(".legacy_migrated");
            if (Files.exists(marker)) return;
            Path cfg = FabricLoader.getInstance().getConfigDir();
            Path d = dir();
            for (String n : LEGACY) {
                Path src = cfg.resolve(n);
                if (Files.exists(src)) Files.move(src, d.resolve(n));
            }
            Files.createDirectories(root());
            Files.writeString(marker, "первый мир после обновления: " + key());
        } catch (Exception e) {
            MobTalkClient.LOG.error("Legacy migrate error", e);
        }
    }
}
