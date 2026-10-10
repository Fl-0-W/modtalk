package com.example.mobtalk;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentLevelEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.EnchantedBookItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Вся логика разговора: сцена с несколькими мобами, действия, озвучка. */
public class Brain {
    public record Mob(UUID id, String type, String name, boolean hostile, double dist) {}

    public record Scene(List<Mob> mobs, String inventory, String time, String playerName, UUID playerId) {}

    record Reply(String say, List<JsonObject> actions, String raw) {}

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

            Reply r = converse(mc, m, sc.playerName(), sc.playerId(), neighbors,
                    sc.inventory(), sc.time(), u.toString(), true);
            applyActions(mc, m, r);
            if (!r.say().isBlank()) {
                any = true;
                said.add("[" + m.name() + "]: " + r.say());
                say(mc, m, r.say(), 1.0, null, true);
            }
        }
        if (!any) MobTalkClient.overlay(mc, "§7Никто не ответил");
    }

    // ---------- редкие разговоры мобов между собой ----------

    static void chatter(MinecraftClient mc, Mob a, Mob b, String playerName) throws Exception {
        double r = Math.max(1.0, Config.INSTANCE.chatterRadius);
        Reply ra = converse(mc, a, playerName, null, List.of(b.name()), null, "",
                "[Система] Рядом с тобой стоит «" + b.name() + "». Скажи ему одну очень короткую реплику (до 12 слов) "
                        + "в своём характере — про жизнь, погоду или игрока " + playerName
                        + ", который неподалёку. Игроку ты сейчас не отвечаешь.", false);
        if (ra.say().isBlank()) return;
        say(mc, a, ra.say(), gainFor(a.dist(), r), b.name(), false);

        Reply rb = converse(mc, b, playerName, null, List.of(a.name()), null, "",
                "«" + a.name() + "» говорит тебе: «" + ra.say() + "». Ответь одной короткой репликой (до 12 слов) "
                        + "или промолчи (say — пустая строка).", false);
        if (rb.say().isBlank()) return;
        say(mc, b, rb.say(), gainFor(b.dist(), r), a.name(), false);
    }

    private static double gainFor(double dist, double radius) {
        return Math.max(0.3, 1.0 - dist / (radius * 1.5));
    }

    // ---------- запрос к модели ----------

    static Reply converse(MinecraftClient mc, Mob m, String playerName, UUID playerId, List<String> neighbors,
                          String inv, String time, String userContent, boolean allowActions) throws Exception {
        String system = Personalities.systemPrompt(m, playerName, neighbors, inv, time, allowActions);
        List<Memory.Msg> hist = Memory.get(m.id());
        hist.add(new Memory.Msg("user", userContent));
        Reply r = parse(Api.chat(system, hist));
        Memory.add(m.id(), "user", userContent);
        Memory.add(m.id(), "assistant", r.raw());
        if (!allowActions) return new Reply(r.say(), List.of(), r.raw());

        JsonObject trade = find(r.actions(), "TRADE");
        if (trade != null && Personalities.profile(m.type(), m.hostile()).trader()) {
            String res = runTrade(mc, m.id(), m.name(), trade);
            if (res.startsWith("OK:")) {
                MobTalkClient.chat(mc, "§a[Сделка] " + res.substring(3));
            } else {
                // сделка не прошла — моб сам объясняет почему, а не врёт, что отдал вещь
                String note = "[Система] Сделка не оформлена: " + res.substring(4)
                        + " Коротко объясни это игроку в образе. Действие TRADE не используй.";
                Memory.add(m.id(), "user", note);
                Reply r2 = parse(Api.chat(system, Memory.get(m.id())));
                Memory.add(m.id(), "assistant", r2.raw());
                List<JsonObject> acts = new ArrayList<>();
                for (JsonObject a : r2.actions()) if (!"TRADE".equals(type(a))) acts.add(a);
                return new Reply(r2.say(), acts, r2.raw());
            }
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
                return new Reply(say, acts, json);
            }
        } catch (Exception ignored) {
        }
        return new Reply(s, List.of(), s);
    }

    private static JsonObject find(List<JsonObject> acts, String t) {
        for (JsonObject a : acts) if (t.equals(type(a))) return a;
        return null;
    }

    private static String type(JsonObject a) {
        return str(a, "type").toUpperCase(Locale.ROOT);
    }

    private static String str(JsonObject a, String k) {
        try {
            return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsString() : "";
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

    // ---------- действия (следование, перемирие) ----------

    static void applyActions(MinecraftClient mc, Mob m, Reply r) {
        if (r.actions().isEmpty()) return;
        MinecraftServer srv = mc.getServer();
        boolean relevant = false;
        for (JsonObject a : r.actions()) if (!"TRADE".equals(type(a))) relevant = true;
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

    // ---------- сделка: регистрация договора (оплату игрок бросает на землю) ----------

    private static String runTrade(MinecraftClient mc, UUID mobId, String mobName, JsonObject a) {
        MinecraftServer srv = mc.getServer();
        if (srv == null) return "ERR:Сделки работают только в одиночной игре.";
        CompletableFuture<String> f = new CompletableFuture<>();
        srv.execute(() -> {
            try {
                f.complete(Deals.create(srv, mobId, mobName, a));
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "ERR:Не удалось оформить сделку.";
        }
    }

    // ---------- реплика: чат + озвучка ----------

    static void say(MinecraftClient mc, Mob m, String text, double gain, String toName, boolean reportErr) {
        MobTalkClient.chat(mc, "§e" + m.name() + (toName != null ? "§7 → " + toName : "") + "§f: " + text);

        Config c = Config.INSTANCE;
        final String voice = c.voices.get(State.voiceIndex(m.id(), c.voices.size()));
        Personalities.Profile p = Personalities.profile(m.type(), m.hostile());
        double sp = p.speed() * (0.9 + Math.floorMod(m.id().getLeastSignificantBits(), 21L) / 100.0);
        final double speed = Math.max(0.7, Math.min(1.4, sp));

        CompletableFuture<byte[]> f = CompletableFuture.supplyAsync(() -> {
            try {
                return Api.tts(text, voice, speed);
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
                if (pcm != null) Mic.play(pcm, g);
            } catch (Exception ignored) {
            }
        });
    }
}
