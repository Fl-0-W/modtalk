package com.example.mobtalk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Память диалогов: у каждого моба (по UUID) своя история, хранится в config/mobtalk_memory.json */
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

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, List<Msg>> DATA = new HashMap<>();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("mobtalk_memory.json");
    }

    public static synchronized void load() {
        try {
            Path p = file();
            if (!Files.exists(p)) return;
            Map<String, List<Msg>> read = GSON.fromJson(Files.readString(p),
                    new TypeToken<Map<String, List<Msg>>>() {}.getType());
            if (read != null) {
                DATA.clear();
                DATA.putAll(read);
            }
        } catch (Exception e) {
            MobTalkClient.LOG.error("Memory load error", e);
        }
    }

    public static synchronized List<Msg> get(UUID id) {
        return new ArrayList<>(DATA.getOrDefault(id.toString(), List.of()));
    }

    public static synchronized void add(UUID id, String role, String content) {
        List<Msg> list = DATA.computeIfAbsent(id.toString(), k -> new ArrayList<>());
        list.add(new Msg(role, content));
        int max = Math.max(4, Config.INSTANCE.maxHistory);
        while (list.size() > max) list.remove(0);
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
