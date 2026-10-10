package com.example.mobtalk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> DEFAULT_VOICES = List.of("marin", "cedar", "coral", "ash", "sage", "ballad", "verse", "alloy", "echo", "fable", "onyx", "nova", "shimmer");

    public String apiKey = "";
    public String baseUrl = "https://api.openai.com/v1";
    public String sttModel = "whisper-1";
    public String chatModel = "gpt-4o-mini";
    public String ttsModel = "gpt-4o-mini-tts";
    public String language = "ru";
    public int maxHistory = 24;
    public boolean jsonMode = true;
    public int configVersion = 0;

    /** Список голосов для озвучки. Каждый моб получает свой голос из этого списка. */
    public List<String> voices = new ArrayList<>(DEFAULT_VOICES);

    /** Радиус, в котором мобы слышат игрока (блоков). */
    public double listenRadius = 10.0;
    /** Сколько ближайших мобов отвечают одновременно. */
    public int maxListeners = 3;

    /** Редкие короткие разговоры мобов между собой. */
    public boolean ambientChatter = true;
    public int chatterIntervalSec = 120;
    public double chatterRadius = 14.0;

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
            }
            if (c.configVersion < 2) { // миграция на более живую озвучку (только для OpenAI)
                if (c.baseUrl != null && c.baseUrl.contains("api.openai.com")) {
                    c.ttsModel = "gpt-4o-mini-tts";
                    c.voices = new ArrayList<>(DEFAULT_VOICES);
                }
                c.configVersion = 2;
            }
            if (c.voices == null || c.voices.isEmpty()) c.voices = new ArrayList<>(DEFAULT_VOICES);
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(c)); // дописывает новые поля в старый конфиг
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
