package com.example.mobtalk;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Клиент OpenAI-совместимого API: распознавание речи, чат, озвучка. */
public class Api {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    public static String transcribe(byte[] wav) throws Exception {
        Config c = Config.INSTANCE;
        String boundary = "----MobTalk" + System.nanoTime();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        field(o, boundary, "model", c.sttModel);
        field(o, boundary, "language", c.language);
        field(o, boundary, "response_format", "json");
        o.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        o.write(wav);
        o.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder(URI.create(c.baseUrl + "/audio/transcriptions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + c.apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(o.toByteArray()))
                .build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(r.statusCode(), r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject().get("text").getAsString().trim();
    }

    public static String chat(String system, List<Memory.Msg> history) throws Exception {
        Config c = Config.INSTANCE;
        JsonObject body = new JsonObject();
        body.addProperty("model", c.chatModel);
        body.addProperty("temperature", 0.9);
        body.addProperty("max_tokens", 350);
        if (c.jsonMode) {
            JsonObject rf = new JsonObject();
            rf.addProperty("type", "json_object");
            body.add("response_format", rf);
        }
        JsonArray arr = new JsonArray();
        arr.add(msg("system", system));
        for (Memory.Msg m : history) arr.add(msg(m.role, m.content));
        body.add("messages", arr);

        HttpRequest req = HttpRequest.newBuilder(URI.create(c.baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + c.apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        check(r.statusCode(), r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject()
                .getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString().trim();
    }

    /** Возвращает сырой PCM 24 кГц, 16 бит, моно. */
    public static byte[] tts(String text, String voice, double speed, String instructions) throws Exception {
        Config c = Config.INSTANCE;
        JsonObject body = new JsonObject();
        body.addProperty("model", c.ttsModel);
        body.addProperty("input", text);
        body.addProperty("voice", voice);
        if (c.ttsModel.startsWith("gpt-4o")) {
            // новая модель: манера речи задаётся текстом, а не числовой скоростью
            if (instructions != null && !instructions.isBlank()) body.addProperty("instructions", instructions);
        } else {
            body.addProperty("speed", speed);
        }
        body.addProperty("response_format", "pcm");

        HttpRequest req = HttpRequest.newBuilder(URI.create(c.baseUrl + "/audio/speech"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + c.apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> r = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        check(r.statusCode(), new String(r.body(), StandardCharsets.UTF_8));
        return r.body();
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static void field(ByteArrayOutputStream o, String boundary, String name, String value) throws Exception {
        o.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void check(int status, String body) {
        if (status != 200) {
            String b = body.length() > 300 ? body.substring(0, 300) : body;
            throw new RuntimeException("HTTP " + status + ": " + b);
        }
    }
}
