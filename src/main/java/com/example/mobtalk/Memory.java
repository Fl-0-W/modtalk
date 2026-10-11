package com.example.mobtalk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Память мобов: недавние реплики + краткое резюме более старых разговоров. */
public class Memory {
    public static class Msg {
        public String role;
        public String content;

        public Msg() {}

        public Msg(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private static class Entry {
        String summary = "";
        List<Msg> msgs = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Entry> DATA = new HashMap<>();

    private static Path file() {
        return Worlds.file("mobtalk_memory_v3.json");
    }

    private static Path oldFile() {
        return Worlds.file("mobtalk_memory_v2.json");
    }

    public static synchronized void reset() {
        DATA.clear();
    }

    public static synchronized void load() {
        try {
            Path p = file();
            if (Files.exists(p)) {
                Map<String, Entry> read = GSON.fromJson(Files.readString(p),
                        new TypeToken<Map<String, Entry>>() {}.getType());
                if (read != null) {
                    DATA.clear();
                    DATA.putAll(read);
                }
            } else if (Files.exists(oldFile())) { // перенос старой памяти
                Map<String, List<Msg>> old = GSON.fromJson(Files.readString(oldFile()),
                        new TypeToken<Map<String, List<Msg>>>() {}.getType());
                if (old != null) {
                    old.forEach((k, v) -> {
                        Entry e = new Entry();
                        e.msgs = new ArrayList<>(v);
                        DATA.put(k, e);
                    });
                    save();
                }
            }
        } catch (Exception e) {
            MobTalkClient.LOG.error("Memory load error", e);
        }
    }

    private static Entry entry(UUID id) {
        Entry e = DATA.computeIfAbsent(id.toString(), k -> new Entry());
        if (e.msgs == null) e.msgs = new ArrayList<>();
        if (e.summary == null) e.summary = "";
        return e;
    }

    public static synchronized List<Msg> get(UUID id) {
        return new ArrayList<>(entry(id).msgs);
    }

    public static synchronized String summary(UUID id) {
        Entry e = DATA.get(id.toString());
        return e == null || e.summary == null ? "" : e.summary;
    }

    public static synchronized void add(UUID id, String role, String content) {
        Entry e = entry(id);
        e.msgs.add(new Msg(role, content));
        int cap = Math.max(4, Config.INSTANCE.maxHistory) + 20; // страховка, если сжатие не сработало
        while (e.msgs.size() > cap) e.msgs.remove(0);
        save();
    }

    public static synchronized boolean needsCompression(UUID id) {
        return entry(id).msgs.size() > Math.max(8, Config.INSTANCE.maxHistory);
    }

    /** Самые старые реплики, кроме последних keep штук. */
    public static synchronized List<Msg> oldest(UUID id, int keep) {
        List<Msg> m = entry(id).msgs;
        int n = Math.max(0, m.size() - keep);
        return new ArrayList<>(m.subList(0, n));
    }

    public static synchronized void applySummary(UUID id, String summary, int removeCount) {
        Entry e = entry(id);
        e.summary = summary;
        for (int i = 0; i < removeCount && !e.msgs.isEmpty(); i++) e.msgs.remove(0);
        save();
    }

    private static void save() {
        try {
            Files.writeString(file(), GSON.toJson(DATA));
        } catch (Exception e) {
            MobTalkClient.LOG.error("Memory save error", e);
        }
    }
}
