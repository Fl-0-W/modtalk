package com.example.mobtalk;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Вся логика разговора: сцена, репутация, действия, поручения, память, озвучка. */
public class Brain {
    public record Mob(UUID id, String type, String name, boolean hostile, double dist, String persona, boolean baby) {}

    public record Scene(List<Mob> mobs, String inventory, String context, String playerName, UUID playerId) {}

    record Reply(String say, List<JsonObject> actions, String raw, int rep) {}

    private static final Set<UUID> COMPRESSING = ConcurrentHashMap.newKeySet();

    // ---------- реплика игрока: слышат все мобы рядом ----------

    static void playerRound(MinecraftClient mc, Scene sc, byte[] wav) throws Exception {
        String heard = Api.transcribe(wav);
        if (heard.isBlank()) {
            MobTalkClient.overlay(mc, "§7Не расслышал, повтори");
            return;
        }
        MobTalkClient.chat(mc, "§7Вы: §f" + heard);

        List<String> said = new ArrayList<>();
        boolean any = false;
        for (Mob m : sc.mobs()) {
            List<String> neighbors = new ArrayList<>();
            for (Mob o : sc.mobs()) if (o != m) neighbors.add(o.name());

            StringBuilder u = new StringBuilder();
            u.append("Игрок ").append(sc.playerName())
                    .append(" говорит вслух (слышат все мобы рядом): «").append(heard).append("»");
            if (!said.isEmpty()) u.append("\nУже ответили: ").append(String.join(" | ", said));

            Reply r = converse(mc, m, sc.playerName(), neighbors, sc.inventory(), sc.context(), u.toString(), true);
            applyRep(mc, m, r.rep());
            applyActions(mc, m, r);
            if (!r.say().isBlank()) {
                any = true;
                said.add("[" + m.name() + "]: " + r.say());
                say(mc, m, r.say(), 1.0, null, true);
            }
        }
        if (!any) MobTalkClient.overlay(mc, "§7Никто не ответил");
    }

    // ---------- моб заговаривает первым ----------

    static void greet(MinecraftClient mc, Mob m, String inv, String context, String playerName) throws Exception {
        String prompt = "[Система] Игрок " + playerName + " только что подошёл к тебе. Заговори с ним первым: коротко "
                + "(до 20 слов), в своём характере, с учётом твоего отношения к нему и памяти о прошлых встречах. "
                + "Если уместно — предложи поручение или сделку.";
        Reply r = converse(mc, m, playerName, List.of(), inv, context, prompt, true);
        applyActions(mc, m, r);
        if (!r.say().isBlank()) say(mc, m, r.say(), 1.0, null, false);
    }

    // ---------- реакция на событие (ночь, гроза, рейд, урон, гибель соседа) ----------

    static void reactRound(MinecraftClient mc, List<Mob> mobs, String inv, String context,
                           String playerName, String eventText) throws Exception {
        List<String> said = new ArrayList<>();
        for (Mob m : mobs) {
            List<String> neighbors = new ArrayList<>();
            for (Mob o : mobs) if (o != m) neighbors.add(o.name());
            String u = "[Событие] " + eventText + " Отреагируй коротко (до 20 слов) в своём характере — игроку или самому "
                    + "себе; если реагировать нечем, say — пустая строка."
                    + (said.isEmpty() ? "" : "\nУже отреагировали: " + String.join(" | ", said));
            Reply r = converse(mc, m, playerName, neighbors, inv, context, u, true);
            applyActions(mc, m, r);
            if (!r.say().isBlank()) {
                said.add("[" + m.name() + "]: " + r.say());
                say(mc, m, r.say(), 1.0, null, false);
            }
        }
    }

    // ---------- редкие разговоры мобов между собой ----------

    static void chatter(MinecraftClient mc, Mob a, Mob b, String playerName) throws Exception {
        double r = Math.max(1.0, Config.INSTANCE.chatterRadius);
        Reply ra = converse(mc, a, playerName, List.of(b.name()), null, "",
                "[Система] Рядом с тобой стоит «" + b.name() + "». Скажи ему одну очень короткую реплику (до 12 слов) "
                        + "в своём характере — про жизнь, погоду, свою причуду или игрока " + playerName
                        + ", который неподалёку. Игроку ты сейчас не отвечаешь.", false);
        if (ra.say().isBlank()) return;
        say(mc, a, ra.say(), gainFor(a.dist(), r), b.name(), false);

        Reply rb = converse(mc, b, playerName, List.of(a.name()), null, "",
                "«" + a.name() + "» говорит тебе: «" + ra.say() + "». Ответь одной короткой репликой (до 12 слов) "
                        + "или промолчи (say — пустая строка).", false);
        if (rb.say().isBlank()) return;
        say(mc, b, rb.say(), gainFor(b.dist(), r), a.name(), false);
    }

    private static double gainFor(double dist, double radius) {
        return Math.max(0.3, 1.0 - dist / (radius * 1.5));
    }

    // ---------- запрос к модели ----------

    static Reply converse(MinecraftClient mc, Mob m, String playerName, List<String> neighbors,
                          String inv, String context, String userContent, boolean allowActions) throws Exception {
        String system = Personalities.systemPrompt(m, playerName, neighbors, inv, context, allowActions);
        List<Memory.Msg> hist = Memory.get(m.id());
        hist.add(new Memory.Msg("user", userContent));
        Reply r = parse(Api.chat(system, hist));
        Memory.add(m.id(), "user", userContent);
        Memory.add(m.id(), "assistant", r.raw());
        maybeCompress(m);
        if (!allowActions) return new Reply(r.say(), List.of(), r.raw(), 0);

        // сделка и поручение проверяются игрой ДО того, как моб произнесёт реплику
        String err = null;
        JsonObject trade = find(r.actions(), "TRADE");
        if (trade != null && Personalities.profile(m.type(), m.hostile()).trader()) {
            String res = onServer(mc, srv -> Deals.create(srv, m.id(), m.name(), trade));
            if (res.startsWith("OK:")) MobTalkClient.chat(mc, "§a[Сделка] " + res.substring(3));
            else err = res.substring(4);
        }
        JsonObject quest = find(r.actions(), "QUEST");
        if (err == null && quest != null) {
            String res = onServer(mc, srv -> Quests.create(srv, m.id(), m.name(), m.type(), quest));
            if (res.startsWith("OK:")) MobTalkClient.chat(mc, "§e[Задание] " + res.substring(3));
            else err = res.substring(4);
        }
        if (err != null) {
            String note = "[Система] Не оформлено: " + err
                    + " Коротко объясни это игроку в образе. Действия TRADE и QUEST не используй.";
            Memory.add(m.id(), "user", note);
            Reply r2 = parse(Api.chat(system, Memory.get(m.id())));
            Memory.add(m.id(), "assistant", r2.raw());
            List<JsonObject> acts = new ArrayList<>();
            for (JsonObject a : r2.actions()) {
                String t = type(a);
                if (!"TRADE".equals(t) && !"QUEST".equals(t)) acts.add(a);
            }
            return new Reply(r2.say(), acts, r2.raw(), r2.rep());
        }
        return r;
    }

    static Reply parse(String raw) {
        String s = raw == null ? "" : raw.trim();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        try {
            if (a >= 0 && b > a) {
                String json = s.substring(a, b + 1);
                JsonObject o = JsonParser.parseString(json).getAsJsonObject();
                String say = o.has("say") && !o.get("say").isJsonNull() ? o.get("say").getAsString().trim() : "";
                List<JsonObject> acts = new ArrayList<>();
                if (o.has("actions") && o.get("actions").isJsonArray()) {
                    for (JsonElement e : o.getAsJsonArray("actions")) if (e.isJsonObject()) acts.add(e.getAsJsonObject());
                }
                return new Reply(say, acts, json, num(o, "rep"));
            }
        } catch (Exception ignored) {
        }
        return new Reply(s, List.of(), s, 0);
    }

    private static JsonObject find(List<JsonObject> acts, String t) {
        for (JsonObject a : acts) if (t.equals(type(a))) return a;
        return null;
    }

    private static String type(JsonObject a) {
        try {
            return a.has("type") && !a.get("type").isJsonNull() ? a.get("type").getAsString().toUpperCase(Locale.ROOT) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static int num(JsonObject a, String k) {
        try {
            return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- долгая память: сжатие старых реплик в резюме ----------

    static void maybeCompress(Mob m) {
        if (!Config.INSTANCE.memorySummaries || !Memory.needsCompression(m.id())) return;
        if (!COMPRESSING.add(m.id())) return;
        MobTalkClient.MEMORY.submit(() -> {
            try {
                List<Memory.Msg> old = Memory.oldest(m.id(), 10);
                if (old.isEmpty()) return;
                StringBuilder sb = new StringBuilder();
                String prev = Memory.summary(m.id());
                if (!prev.isBlank()) sb.append("Прежнее краткое резюме: ").append(prev).append("\n\n");
                sb.append("Новые реплики, которые нужно включить в резюме:\n");
                for (Memory.Msg x : old) {
                    boolean mine = "assistant".equals(x.role);
                    String text = mine ? parse(x.content).say() : x.content;
                    if (text == null || text.isBlank()) continue;
                    sb.append(mine ? "Ты: " : "").append(text).append("\n");
                }
                String system = "Ты помогаешь мобу из Minecraft по имени «" + m.name() + "» сжимать память о разговорах с игроком. "
                        + "Напиши краткое резюме от первого лица (до 120 слов, по-русски): что ты узнал об игроке (имя, привычки, "
                        + "обещания, сделки, задания, ссоры, шутки), важные события и как ты к нему относишься. Объедини прежнее "
                        + "резюме и новые реплики, ничего не выдумывай. Верни ТОЛЬКО JSON: {\"summary\": \"...\"}.";
                String raw = Api.chat(system, List.of(new Memory.Msg("user", sb.toString())));
                String summary = "";
                int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
                if (a >= 0 && b > a) {
                    JsonObject o = JsonParser.parseString(raw.substring(a, b + 1)).getAsJsonObject();
                    if (o.has("summary") && !o.get("summary").isJsonNull()) summary = o.get("summary").getAsString().trim();
                }
                if (!summary.isBlank()) Memory.applySummary(m.id(), summary, old.size());
            } catch (Exception e) {
                MobTalkClient.LOG.warn("Memory summary error: {}", e.getMessage());
            } finally {
                COMPRESSING.remove(m.id());
            }
        });
    }

    // ---------- репутация ----------

    static void applyRep(MinecraftClient mc, Mob m, int rep) {
        long day = mc.world != null ? mc.world.getTimeOfDay() / 24000L : 0L;
        Reputation.TalkResult r = Reputation.applyTalk(m.id(), m.type(), rep, day);
        if (r == null) return;
        int after = r.attitude();
        if (r.delta() == 0) { // дневной лимит исчерпан: моб уже «наговорился» на сегодня
            MobTalkClient.overlay(mc, "§7" + m.name() + ": сегодня отношение уже не улучшить словами");
            return;
        }
        MobTalkClient.overlay(mc, "§7" + m.name() + ": " + (r.delta() > 0 ? "§a+" : "§c") + r.delta() + " §7· "
                + Reputation.color(after) + Reputation.tier(after) + " (" + Reputation.fmt(after) + ")");

        if (m.hostile()) { // друзья не нападают; потеря доверия снимает перемирие
            if (after >= 60 && !State.isTruce(m.id())) {
                State.setTruce(m.id(), true);
                MobTalkClient.chat(mc, "§a[" + m.name() + " считает вас другом и больше не нападает]");
            } else if (after <= -25 && State.isTruce(m.id())) {
                State.setTruce(m.id(), false);
                MobTalkClient.chat(mc, "§c[" + m.name() + " потерял к вам доверие и снова враждебен]");
            }
        }
    }

    // ---------- действия (следование, перемирие, отмены) ----------

    static void applyActions(MinecraftClient mc, Mob m, Reply r) {
        if (r.actions().isEmpty()) return;
        MinecraftServer srv = mc.getServer();
        boolean relevant = false;
        for (JsonObject a : r.actions()) {
            String t = type(a);
            if (!"TRADE".equals(t) && !"QUEST".equals(t)) relevant = true;
        }
        if (!relevant) return;
        if (srv == null) {
            MobTalkClient.chat(mc, "§7[Действия мобов работают только в одиночной игре]");
            return;
        }
        for (JsonObject a : r.actions()) {
            switch (type(a)) {
                case "FOLLOW" -> {
                    State.setFollow(m.id(), true);
                    MobTalkClient.chat(mc, "§a[" + m.name() + " теперь ходит за вами]");
                }
                case "STOP_FOLLOW" -> {
                    if (State.isFollow(m.id())) {
                        State.setFollow(m.id(), false);
                        MobTalkClient.chat(mc, "§7[" + m.name() + " больше не ходит за вами]");
                    }
                }
                case "CANCEL_TRADE" -> {
                    Deals.cancel(m.id());
                    MobTalkClient.chat(mc, "§7[Сделка с " + m.name() + " отменена]");
                }
                case "CANCEL_QUEST" -> {
                    Quests.cancel(m.id());
                    MobTalkClient.chat(mc, "§7[Задание от " + m.name() + " отменено]");
                }
                case "TRUCE" -> {
                    if (m.hostile()) {
                        State.setTruce(m.id(), true);
                        srv.execute(() -> clearTarget(srv, m.id()));
                        MobTalkClient.chat(mc, "§a[Перемирие: " + m.name() + " не тронет вас, пока вы его не ударите]");
                    }
                }
                case "ANGRY" -> {
                    if (State.isTruce(m.id())) {
                        State.setTruce(m.id(), false);
                        MobTalkClient.chat(mc, "§c[Перемирие нарушено: " + m.name() + " снова враждебен]");
                    }
                }
                default -> { }
            }
        }
    }

    private static void clearTarget(MinecraftServer srv, UUID id) {
        for (ServerWorld w : srv.getWorlds()) {
            Entity e = w.getEntity(id);
            if (e instanceof MobEntity mob) {
                mob.setTarget(null);
                return;
            }
        }
    }

    /** Выполняет задание на серверном потоке и ждёт результат («OK:...» или «ERR:...»). */
    private static String onServer(MinecraftClient mc, Function<MinecraftServer, String> job) {
        MinecraftServer srv = mc.getServer();
        if (srv == null) return "ERR:Это работает только в одиночной игре.";
        CompletableFuture<String> f = new CompletableFuture<>();
        srv.execute(() -> {
            try {
                f.complete(job.apply(srv));
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "ERR:Не удалось оформить.";
        }
    }

    // ---------- реплика: чат + озвучка ----------

    static void say(MinecraftClient mc, Mob m, String text, double gain, String toName, boolean reportErr) {
        MobTalkClient.chat(mc, "§e" + m.name() + (toName != null ? "§7 → " + toName : "") + "§f: " + text);

        Config c = Config.INSTANCE;
        final boolean yandex = "yandex".equalsIgnoreCase(c.ttsProvider);
        final String voice, role, instr;
        final double pitch, speed;
        if (yandex) {
            // голос по виду моба; отличия внутри вида (голос из пула, высота, темп) выводятся из UUID
            VoiceBank.Voice v = VoiceBank.pick(m.type(), m.id(), m.baby());
            voice = v.voice();
            role = v.role();
            pitch = v.pitch();
            speed = v.speed();
            instr = null;
        } else {
            List<String> vs = Api.voiceList();
            if (vs.isEmpty()) {
                if (reportErr)
                    MobTalkClient.chat(mc, "§c[MobTalk] Нет голосов для озвучки. Проверь ttsProvider и ключ в config/mobtalk.json.");
                return;
            }
            List<Double> variants = c.pitchVariants;
            int idx = State.voiceIndex(m.id(), vs.size(), variants.size());
            voice = vs.get(idx % vs.size());
            role = "";
            pitch = variants.get((idx / vs.size()) % variants.size());

            Personalities.Profile p = Personalities.profile(m.type(), m.hostile());
            double sp = p.speed() * (0.9 + Math.floorMod(m.id().getLeastSignificantBits(), 21L) / 100.0);
            speed = Math.max(0.7, Math.min(1.4, sp));
            instr = Personalities.voiceInstructions(m.type(), m.id());
        }

        CompletableFuture<byte[]> f = CompletableFuture.supplyAsync(() -> {
            try {
                // повышение высоты через resample ускоряет речь, поэтому темп у Яндекса просим с поправкой
                if (yandex) return Api.ttsYandex(text, voice, role, speed / Math.max(0.5, pitch));
                return Api.tts(text, voice, speed, instr);
            } catch (Exception e) {
                MobTalkClient.LOG.warn("TTS error: {}", e.getMessage());
                if (reportErr) {
                    String msg = String.valueOf(e.getMessage());
                    MobTalkClient.chat(mc, "§c[MobTalk] Озвучка: " + (msg.length() > 160 ? msg.substring(0, 160) : msg));
                }
                return null;
            }
        }, MobTalkClient.TTS);

        final float g = (float) gain;
        MobTalkClient.PLAYER.submit(() -> {
            try {
                byte[] pcm = f.get(90, TimeUnit.SECONDS);
                if (pcm != null) {
                    if (Math.abs(pitch - 1.0) > 0.01) pcm = Mic.resample(pcm, pitch);
                    Mic.play(pcm, g);
                }
            } catch (Exception ignored) {
            }
        });
    }
}
