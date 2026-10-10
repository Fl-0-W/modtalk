package com.example.mobtalk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Постоянное состояние мобов: перемирие, «ходит за игроком», назначенные голоса. */
public class State {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<UUID> TRUCE = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> FOLLOW = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Integer> VOICES = new ConcurrentHashMap<>();

    private static class Disk {
        List<String> truce = new ArrayList<>();
        List<String> follow = new ArrayList<>();
        Map<String, Integer> voices = new HashMap<>();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("mobtalk_state.json");
    }

    public static boolean isTruce(UUID id) {
        return !TRUCE.isEmpty() && TRUCE.contains(id);
    }

    public static boolean isFollow(UUID id) {
        return FOLLOW.contains(id);
    }

    public static Set<UUID> followSnapshot() {
        return new HashSet<>(FOLLOW);
    }

    public static synchronized void setTruce(UUID id, boolean on) {
        if (on) TRUCE.add(id); else TRUCE.remove(id);
        save();
    }

    public static synchronized void setFollow(UUID id, boolean on) {
        if (on) FOLLOW.add(id); else FOLLOW.remove(id);
        save();
    }

    /** Каждому мобу один раз назначается голос: сначала выбираются наименее занятые. */
    public static synchronized int voiceIndex(UUID id, int n) {
        Integer v = VOICES.get(id);
        if (v != null && v < n) return v;
        int[] counts = new int[n];
        for (int x : VOICES.values()) if (x < n) counts[x]++;
        int start = Math.floorMod(id.hashCode(), n);
        int best = start;
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % n;
            if (counts[idx] < counts[best]) best = idx;
        }
        VOICES.put(id, best);
        save();
        return best;
    }

    public static synchronized void load() {
        try {
            Path p = file();
            if (!Files.exists(p)) return;
            Disk d = GSON.fromJson(Files.readString(p), Disk.class);
            if (d == null) return;
            for (String s : d.truce) TRUCE.add(UUID.fromString(s));
            for (String s : d.follow) FOLLOW.add(UUID.fromString(s));
            d.voices.forEach((k, v) -> VOICES.put(UUID.fromString(k), v));
        } catch (Exception e) {
            MobTalkClient.LOG.error("State load error", e);
        }
    }

    private static void save() {
        try {
            Disk d = new Disk();
            TRUCE.forEach(u -> d.truce.add(u.toString()));
            FOLLOW.forEach(u -> d.follow.add(u.toString()));
            VOICES.forEach((k, v) -> d.voices.put(k.toString(), v));
            Files.writeString(file(), GSON.toJson(d));
        } catch (Exception e) {
            MobTalkClient.LOG.error("State save error", e);
        }
    }
}
