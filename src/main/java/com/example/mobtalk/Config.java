package com.example.mobtalk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** "openai" (по умолчанию) или "elevenlabs" — самый живой русский голос. */
    public String ttsProvider = "openai";
    public String elevenApiKey = "";
    public String elevenModel = "eleven_multilingual_v2";
    /** Оставь пустым — мод возьмёт голоса из твоего аккаунта ElevenLabs. Или впиши ID голосов. */
    public List<String> elevenVoices = new ArrayList<>();
    public double elevenStability = 0.4;
    public double elevenStyle = 0.35;

    /** Яндекс SpeechKit (ttsProvider = "yandex"): русские голоса, в т.ч. премиум. */
    public String yandexApiKey = "";
    public String yandexFolderId = "";
    public List<String> yandexVoices = new ArrayList<>(List.of(
            "alexander", "marina", "kirill", "masha", "anton", "dasha", "filipp", "julia", "lera", "alena"));

    /**
     * Голоса по видам (только Яндекс). Ключ — вид моба, значение — голоса через запятую (в формате «имя» или
     * «имя/role»), из них моб выбирает свой. Пример: {"skeleton": "kirill/strict,anton"}. Что не указано —
     * берётся из встроенной таблицы (см. VoiceBank).
     */
    public Map<String, String> speciesVoices = new HashMap<>();
    /** Насколько мобы одного вида различаются по высоте и темпу: 0 — одинаково, 1 — по умолчанию, 2 — сильнее. */
    public double voiceVariation = 1.0;
    /** Передавать Яндексу амплуа голоса (role: good, evil, friendly…). Выключи, если голос не поддерживает. */
    public boolean yandexRoles = true;

    /**
     * Клавиша P по умолчанию занята ванильным «Социальным взаимодействием» и перехватывает нажатие у окна репутации.
     * Если true, мод один раз освобождает эту клавишу (снимает привязку у ванильного действия).
     */
    public boolean unbindSocialKey = true;

    /** Список голосов для озвучки. Каждый моб получает свой голос из этого списка. */
    public List<String> voices = new ArrayList<>(DEFAULT_VOICES);

    /**
     * Варианты высоты голоса. Каждый голос из списка умножается на эти варианты:
     * 3 голоса x 3 варианта = 9 разных на слух голосов.
     */
    public List<Double> pitchVariants = new ArrayList<>(List.of(1.0, 0.92, 1.08));

    /** Радиус, в котором мобы слышат игрока (блоков). */
    public double listenRadius = 10.0;
    /** Сколько ближайших мобов отвечают одновременно. */
    public int maxListeners = 3;

    /** Долгая память: старые реплики сжимаются в краткое резюме. */
    public boolean memorySummaries = true;

    /** Мобы заговаривают первыми, когда вы подходите. */
    public boolean greetings = true;
    public double greetRadius = 5.0;
    public double greetChance = 0.6;
    public int greetCooldownSec = 240;

    /** Мобы реагируют на события: ночь, дождь, гроза, рейд, урон игроку, гибель соседа. */
    public boolean reactions = true;

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
        if (c.elevenApiKey == null || c.elevenApiKey.isBlank()) {
            String env = System.getenv("ELEVENLABS_API_KEY");
            c.elevenApiKey = env != null ? env : "";
        }
        if (c.elevenVoices == null) c.elevenVoices = new ArrayList<>();
        if (c.pitchVariants == null || c.pitchVariants.isEmpty()) c.pitchVariants = new ArrayList<>(List.of(1.0));
        if (c.speciesVoices == null) c.speciesVoices = new HashMap<>();
        if (c.yandexApiKey == null) c.yandexApiKey = "";
        if (c.yandexFolderId == null) c.yandexFolderId = "";
        if (c.yandexVoices == null || c.yandexVoices.isEmpty())
            c.yandexVoices = new ArrayList<>(List.of("alexander", "marina", "kirill", "masha", "anton", "dasha",
                    "filipp", "julia", "lera", "alena"));
        if (c.baseUrl.endsWith("/")) c.baseUrl = c.baseUrl.substring(0, c.baseUrl.length() - 1);
        return c;
    }
}
