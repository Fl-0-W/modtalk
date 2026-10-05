package com.example.mobtalk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String apiKey = "";
    public String baseUrl = "https://api.openai.com/v1";
    public String sttModel = "whisper-1";
    public String chatModel = "gpt-4o-mini";
    public String ttsModel = "tts-1";
    public String language = "ru";
    public int maxHistory = 24;

    public static Config INSTANCE = load();

    public static void reload() {
        INSTANCE = load();
    }

    private static Config load() {
        Config c = new Config();
        try {
            Path p = FabricLoader.getInstance().getConfigDir().resolve("mobtalk.json");
            if (Files.exists(p)) {
                Config read = GSON.fromJson(Files.readString(p), Config.class);
                if (read != null) c = read;
            } else {
                Files.createDirectories(p.getParent());
                Files.writeString(p, GSON.toJson(c));
            }
        } catch (Exception e) {
            MobTalkClient.LOG.error("Config error", e);
        }
        if (c.apiKey == null || c.apiKey.isBlank()) {
            String env = System.getenv("OPENAI_API_KEY");
            if (env != null) c.apiKey = env;
        }
        if (c.baseUrl.endsWith("/")) c.baseUrl = c.baseUrl.substring(0, c.baseUrl.length() - 1);
        return c;
    }
}
