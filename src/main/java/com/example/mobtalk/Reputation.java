package com.example.mobtalk;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Репутация v2.
 * <p>
 * Два слоя: личное отношение конкретного моба (−100..100) и репутация игрока среди фракции — группы родственных
 * видов (деревня, нежить, пауки…). Итоговое «отношение» = личное + 40% фракционного: слух о ваших делах
 * разносится по всей фракции, но личная дружба весит больше.
 * <p>
 * Правила разговоров: за один ответ отношение меняется не более чем на ±5; плюсы за болтовню ограничены
 * дневным лимитом на моба; чем выше доверие, тем труднее его нарастить; потерять доверие легче, чем заслужить.
 * Репутация фракций медленно «остывает» к нулю со временем.
 */
public class Reputation {
    private static final Map<UUID, Long> LAST_HIT = new HashMap<>();

    /** Лимит прибавки отношения за разговоры с одним мобом за игровые сутки. */
    public static final int TALK_DAILY_CAP = 12;
    /** Максимальное изменение за одну реплику. */
    public static final int TALK_STEP_MAX = 5;
    /** Доля фракционной репутации в итоговом отношении. */
    public static final double FACTION_WEIGHT = 0.4;

    // ---------- фракции ----------

    private static final Map<String, String> FACTION_OF = new HashMap<>();
    private static final Map<String, String> FACTION_NAME = new LinkedHashMap<>();
    private static final Map<String, List<String>> FACTION_MEMBERS = new HashMap<>();

    private static void faction(String id, String name, String... types) {
        FACTION_NAME.put(id, name);
        FACTION_MEMBERS.put(id, List.of(types));
        for (String t : types) FACTION_OF.put(t, id);
    }

    static {
        faction("village", "Жители деревень", "villager", "wandering_trader", "iron_golem", "snow_golem");
        faction("undead", "Нежить", "zombie", "husk", "drowned", "zombie_villager", "skeleton", "stray",
                "wither_skeleton", "phantom");
        faction("illagers", "Разбойники и маги", "pillager", "vindicator", "evoker", "ravager", "vex", "witch");
        faction("nether", "Жители Незера", "piglin", "zombified_piglin", "hoglin", "blaze", "ghast",
                "magma_cube", "strider");
        faction("spiders", "Пауки", "spider", "cave_spider");
        faction("farm", "Домашний скот", "cow", "mooshroom", "pig", "sheep", "chicken", "horse", "donkey", "llama",
                "rabbit");
        faction("wild", "Лесные звери", "wolf", "fox", "cat", "ocelot", "bee", "panda", "polar_bear", "goat",
                "frog", "camel", "sniffer", "turtle", "parrot", "bat");
        faction("sea", "Морские жители", "dolphin", "squid", "axolotl", "guardian");
    }

    /** Фракция вида; у видов вне групп (крипер, странник Края…) фракция — сам вид. */
    public static String factionOf(String type) {
        return FACTION_OF.getOrDefault(type, type);
    }

    /** Виды, входящие в фракцию (у «одиночных» видов — он сам). */
    public static List<String> membersOf(String factionId) {
        List<String> m = FACTION_MEMBERS.get(factionId);
        return m != null ? m : List.of(factionId);
    }

    /** Основные фракции в порядке показа. */
    public static List<String> mainFactions() {
        return new ArrayList<>(FACTION_NAME.keySet());
    }

    // ---------- журнал ----------

    public static void record(String type, String mob, int personal, int factionDelta, String reason, long gameTime) {
        if (personal == 0 && factionDelta == 0) return;
        State.Entry e = new State.Entry();
        e.real = System.currentTimeMillis();
        e.gameTime = gameTime;
        e.faction = factionOf(type);
        e.mob = mob == null ? "" : mob;
        e.personal = personal;
        e.factionDelta = factionDelta;
        e.reason = reason == null ? "" : reason;
        State.addHistory(e);
    }

    /** «День 5, 14:20» из тиков мира (0 тиков = 6:00 утра). */
    public static String gameClock(long t) {
        long day = t / 24000L + 1;
        long in = Math.floorMod(t, 24000L);
        long h = (in / 1000L + 6) % 24, m = (in % 1000L) * 60 / 1000L;
        return String.format("день %d, %02d:%02d", day, h, m);
    }

    public static String factionName(String factionId) {
        String n = FACTION_NAME.get(factionId);
        return n != null ? n : Personalities.speciesName(factionId);
    }

    public static int factionRep(String type) {
        return State.species(factionOf(type));
    }

    public static int addFaction(String type, int delta) {
        return State.addSpecies(factionOf(type), delta);
    }

    // ---------- итоговое отношение ----------

    /** Итоговое отношение моба к игроку: личное + доля репутации его фракции. */
    public static int attitude(UUID id, String type) {
        int v = State.personal(id) + (int) Math.round(factionRep(type) * FACTION_WEIGHT);
        return Math.max(-100, Math.min(100, v));
    }

    public static String tier(int s) {
        if (s <= -70) return "Ненависть";
        if (s <= -40) return "Враждебность";
        if (s <= -15) return "Недоверие";
        if (s < 15) return "Нейтрально";
        if (s < 40) return "Расположение";
        if (s < 70) return "Дружба";
        return "Преданность";
    }

    /** Что значит такая репутация расы для её представителей. */
    public static String effectText(int rep) {
        if (rep <= -70) return "Ненавидят вас: отказывают в сделках и услугах, враждебны.";
        if (rep <= -40) return "Враждебны: торговцы завышают цены, помощи не ждите.";
        if (rep <= -15) return "Не доверяют вам: холодны и подозрительны, цены завышены.";
        if (rep < 15) return "Относятся нейтрально: присматриваются к вам.";
        if (rep < 40) return "Расположены к вам: приветливее, небольшие скидки.";
        if (rep < 70) return "Дружелюбны: говорят тепло, скидки до 25%.";
        return "Преданы вам: доверяют секреты, скидки 30–50%.";
    }

    public static String color(int s) {
        if (s <= -40) return "§4";
        if (s <= -15) return "§c";
        if (s < 15) return "§7";
        if (s < 40) return "§2";
        return "§a";
    }

    public static String fmt(int v) {
        return (v > 0 ? "+" : "") + v;
    }

    /** Полоска от −100 до +100: 10 клеток, цвет зависит от знака. */
    public static String bar(int v) {
        int filled = Math.max(0, Math.min(10, (int) Math.round((v + 100) / 20.0)));
        String c = v <= -15 ? "§c" : v < 15 ? "§7" : "§a";
        return c + "▮".repeat(filled) + "§8" + "▮".repeat(10 - filled);
    }

    /** Подсказка для моба, как вести себя при таком отношении к игроку. */
    public static String guidance(int attitude, boolean trader) {
        if (attitude <= -70)
            return "Ты ненавидишь игрока: резок, грубишь, ни на что не соглашаешься, кроме искреннего извинения; "
                    + "сделок и услуг не будет.";
        if (attitude <= -40)
            return "Ты враждебен к игроку: холоден, огрызаешься, помогать не хочешь"
                    + (trader ? ", торгуешь только по цене выше рыночной на 50 процентов." : ".");
        if (attitude <= -15)
            return "Ты не доверяешь игроку: подозрителен и немногословен"
                    + (trader ? ", цены завышаешь на 20–30 процентов." : ".");
        if (attitude < 15)
            return "Ты относишься к игроку нейтрально, присматриваешься к нему.";
        if (attitude < 40)
            return "Ты расположен к игроку: говоришь приветливее, иногда шутишь"
                    + (trader ? ", даёшь небольшую скидку (до 10 процентов)." : ".");
        if (attitude < 70)
            return "Ты считаешь игрока другом: говоришь тепло, шутишь, делишься мыслями"
                    + (trader ? ", даёшь скидку до 25 процентов." : ".");
        return "Ты предан игроку: доверяешь секреты, охотно идёшь за ним и готов рискнуть ради него"
                + (trader ? ", даёшь скидку 30–50 процентов." : ".");
    }

    // ---------- изменения за разговоры ----------

    public record TalkResult(int delta, int attitude, boolean capped) {}

    /** {день, сколько уже прибавлено за этот день} по мобам. */
    private static final Map<UUID, long[]> TALK_DAY = new ConcurrentHashMap<>();

    /**
     * Применяет сдвиг отношения, который назвала модель после реплики игрока.
     * Возвращает null, если ничего не изменилось и сообщать нечего.
     */
    public static TalkResult applyTalk(UUID id, String type, String label, int raw, String why, long gameTime) {
        long day = gameTime / 24000L;
        int d = Math.max(-TALK_STEP_MAX, Math.min(TALK_STEP_MAX, raw));
        if (d == 0) return null;
        int cur = State.personal(id);
        boolean capped = false;

        if (d > 0) {
            // чем выше доверие, тем труднее его нарастить
            double scale = cur >= 80 ? 0.25 : cur >= 50 ? 0.5 : 1.0;
            d = Math.max(1, (int) Math.round(d * scale));
            // дневной лимит на моба: комплиментами дружбу не накачать
            long[] t = TALK_DAY.compute(id, (k, old) -> old == null || old[0] != day ? new long[]{day, 0} : old);
            int allow = (int) (TALK_DAILY_CAP - t[1]);
            if (allow <= 0) return new TalkResult(0, attitude(id, type), true);
            if (d > allow) {
                d = allow;
                capped = true;
            }
            t[1] += d;
        } else {
            // доверие теряется быстрее, чем зарабатывается
            d = -(int) Math.round(-d * 1.5);
        }
        State.addPersonal(id, d);
        int sd = d / 4; // слух разносится только после заметных поступков
        if (sd != 0) addFaction(type, sd);
        String reason = why != null && !why.isBlank() ? why.trim()
                : d > 0 ? "приятный разговор" : "неприятный разговор";
        record(type, label, d, sd, reason, gameTime);
        return new TalkResult(d, attitude(id, type), capped);
    }

    // ---------- события (серверный поток, одиночная игра) ----------

    public static void onAttack(ServerPlayerEntity p, MobEntity mob) {
        UUID id = mob.getUuid();
        boolean wasTruce = State.isTruce(id);
        if (wasTruce) State.setTruce(id, false); // ударил — перемирие сорвано

        long now = System.currentTimeMillis();
        Long last = LAST_HIT.get(id);
        if (last != null && now - last < 1200) return;
        LAST_HIT.put(id, now);

        String type = Registries.ENTITY_TYPE.getId(mob.getType()).getPath();
        boolean hostile = Personalities.isHostile(type, mob instanceof HostileEntity);
        String name = Personalities.displayName(mob);
        long gt = mob.getWorld().getTimeOfDay();

        if (!hostile) {
            State.addPersonal(id, -25);
            int nf = addFaction(type, -6);
            record(type, name, -25, -6, "удар по мирному существу", gt);
            p.sendMessage(Text.literal("§c[Вы ударили: " + name + ". Отношение −25 (" + tier(attitude(id, type))
                    + "). Слух разошёлся: " + factionName(factionOf(type)) + " " + fmt(nf) + "]"), false);
        } else if (wasTruce || State.personal(id) > 0) {
            State.addPersonal(id, -40);
            addFaction(type, -4);
            record(type, name, -40, -4, "предательство: удар по тому, кто доверял", gt);
            p.sendMessage(Text.literal("§c[Предательство! " + name + " вам доверял: отношение −40 ("
                    + tier(attitude(id, type)) + ")]"), false);
        }
    }

    public static void onKill(ServerPlayerEntity p, MobEntity mob) {
        String type = Registries.ENTITY_TYPE.getId(mob.getType()).getPath();
        boolean hostile = Personalities.isHostile(type, mob instanceof HostileEntity);
        if (hostile) return;
        int nf = addFaction(type, -12);
        record(type, Personalities.displayName(mob), 0, -12, "убийство существа этой расы", mob.getWorld().getTimeOfDay());
        p.sendMessage(Text.literal("§c[Вы убили: " + Personalities.displayName(mob) + ". " + factionName(factionOf(type))
                + " " + fmt(nf) + " (" + tier(nf) + ")]"), false);
    }

    // ---------- показ репутации (клиент) ----------

    public static void show(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        List<String> lines = new ArrayList<>();
        lines.add("§6§l=== Репутация и задания ===");

        Map<String, Integer> sp = State.speciesSnapshot();
        sp.values().removeIf(v -> v == 0);
        if (sp.isEmpty()) {
            lines.add("§7Фракции: о вас пока никто не слышал.");
        } else {
            lines.add("§7Фракции:");
            sp.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(en -> lines.add("  §e" + factionName(en.getKey()) + " " + bar(en.getValue()) + " "
                            + color(en.getValue()) + fmt(en.getValue()) + " §7(" + tier(en.getValue()) + ")"));
        }

        double r = Math.max(10.0, Config.INSTANCE.listenRadius * 1.5);
        List<MobEntity> near = mc.world.getEntitiesByClass(MobEntity.class,
                mc.player.getBoundingBox().expand(r), e -> e.isAlive() && e.distanceTo(mc.player) <= r);
        near.sort(Comparator.comparingDouble((MobEntity e) -> e.distanceTo(mc.player)));
        lines.add("§7Отношение мобов рядом:");
        List<MobEntity> top = near.size() > 8 ? near.subList(0, 8) : near;
        // одинаковые мобы нумеруются (№ совпадает с номером над головой, который высветится на 15 секунд)
        Map<UUID, String> labels = MobTalkClient.labelAll(top, true);
        int shown = 0;
        for (MobEntity e : top) {
            String type = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
            int v = attitude(e.getUuid(), type);
            lines.add("  §e" + labels.get(e.getUuid()) + " " + bar(v) + " " + color(v) + fmt(v)
                    + " §7(" + tier(v) + ")");
            shown++;
        }
        if (shown == 0) lines.add("  §7никого нет");
        lines.add("§7Задания:");
        List<String> ql = Quests.describeAll();
        if (ql.isEmpty()) lines.add("  §7нет активных заданий");
        else lines.addAll(ql);
        for (String l : lines) mc.player.sendMessage(Text.literal(l), false);
    }
}
