package com.example.mobtalk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Голоса Яндекс SpeechKit по видам мобов.
 * <p>
 * У каждого вида свой «базовый» голос (имя голоса + амплуа/role), высота и темп. Внутри вида каждый моб слегка
 * отличается: высота и темп сдвигаются на небольшое стабильное значение, выведенное из UUID моба (поэтому
 * один и тот же моб всегда звучит одинаково, ничего хранить на диске не нужно). У видов с несколькими голосами
 * в пуле (жители, пиллагеры…) моб получает ещё и свой голос из пула — например, мужской или женский.
 * <p>
 * Формат записи голоса: «имя» или «имя/role», напр. «masha/friendly», «jane/evil».
 */
public final class VoiceBank {
    private VoiceBank() {}

    public record Voice(String voice, String role, double pitch, double speed) {}

    /** voices — пул; pitch/speed — база вида; spread — разброс высоты между мобами вида (доля, ±). */
    private record Spec(String[] voices, double pitch, double speed, double spread) {}

    private static final Map<String, Spec> TABLE = new HashMap<>();

    /** Голоса, из которых выбираем для неизвестных (модовых) видов. Только без акцента. */
    private static final String[] FALLBACK = {"alexander", "kirill", "anton", "filipp", "ermil", "zahar",
            "marina", "masha", "dasha", "julia", "lera", "jane", "omazh"};

    private static void put(String type, double pitch, double speed, double spread, String... voices) {
        TABLE.put(type, new Spec(voices, pitch, speed, spread));
    }

    static {
        // ----- торговцы: у жителей и мужские, и женские голоса, разная высота -----
        put("villager", 1.0, 1.0, 0.10,
                "alexander", "anton/good", "ermil", "zahar/good", "kirill/good", "filipp",
                "masha/friendly", "dasha/good", "lera", "marina", "julia", "masha/strict");
        put("wandering_trader", 0.95, 0.95, 0.06, "zahar/good", "ermil/good");
        put("witch", 1.0, 1.0, 0.06, "omazh/evil", "jane/evil");
        put("piglin", 0.95, 1.12, 0.07, "filipp");

        // ----- нежить: низкие, медленные -----
        put("zombie", 0.86, 0.85, 0.07, "filipp");
        put("husk", 0.80, 0.82, 0.06, "filipp");
        put("drowned", 0.82, 0.85, 0.06, "ermil");
        put("zombie_villager", 0.90, 0.88, 0.10, "zahar", "dasha", "ermil");
        put("zombified_piglin", 0.80, 0.90, 0.06, "zahar");
        put("skeleton", 1.00, 1.00, 0.06, "kirill/strict");
        put("stray", 0.92, 0.90, 0.05, "kirill");
        put("wither_skeleton", 0.78, 0.85, 0.05, "kirill/good");
        put("phantom", 0.82, 0.95, 0.06, "jane/evil");

        // ----- прочие враждебные -----
        put("creeper", 1.12, 1.12, 0.07, "anton");
        put("spider", 0.95, 1.05, 0.06, "jane/evil");
        put("cave_spider", 1.18, 1.15, 0.06, "jane/evil");
        put("enderman", 0.78, 0.80, 0.05, "alexander/good");
        put("pillager", 0.92, 1.00, 0.07, "zahar", "ermil", "filipp");
        put("vindicator", 0.85, 0.95, 0.07, "ermil", "filipp", "zahar");
        put("evoker", 1.00, 0.95, 0.05, "alexander");
        put("ravager", 0.70, 0.80, 0.05, "kirill");
        put("vex", 1.25, 1.20, 0.06, "lera/friendly");
        put("blaze", 0.95, 1.20, 0.06, "anton/good");
        put("ghast", 1.15, 0.88, 0.05, "lera");
        put("slime", 1.20, 1.10, 0.10, "dasha");
        put("magma_cube", 0.95, 1.00, 0.08, "ermil/good");
        put("silverfish", 1.25, 1.25, 0.06, "anton");
        put("hoglin", 0.75, 0.85, 0.06, "filipp");
        put("guardian", 0.90, 0.90, 0.05, "julia/strict");

        // ----- мирные: домашние -----
        put("cow", 0.90, 0.88, 0.07, "marina/friendly");
        put("mooshroom", 1.00, 0.90, 0.07, "marina");
        put("pig", 1.05, 1.05, 0.08, "dasha/friendly");
        put("sheep", 1.08, 0.92, 0.07, "lera/friendly");
        put("chicken", 1.18, 1.20, 0.08, "masha/friendly");
        put("horse", 0.95, 1.00, 0.06, "alexander/good");
        put("donkey", 0.90, 0.90, 0.06, "ermil");
        put("llama", 0.98, 0.95, 0.06, "julia/strict");
        put("rabbit", 1.20, 1.20, 0.07, "lera");

        // ----- мирные: дикие и прочие -----
        put("wolf", 0.95, 1.00, 0.08, "anton/good");
        put("iron_golem", 0.65, 0.80, 0.04, "kirill/good");
        put("snow_golem", 1.15, 1.10, 0.06, "anton/good");
        put("cat", 1.05, 0.95, 0.07, "julia");
        put("ocelot", 1.10, 1.05, 0.07, "julia/strict");
        put("parrot", 1.30, 1.20, 0.06, "lera");
        put("fox", 1.10, 1.10, 0.06, "jane/good");
        put("bee", 1.25, 1.25, 0.06, "dasha/friendly");
        put("panda", 0.85, 0.80, 0.06, "marina");
        put("polar_bear", 0.80, 0.85, 0.05, "kirill");
        put("turtle", 0.78, 0.75, 0.05, "zahar");
        put("axolotl", 1.10, 1.05, 0.07, "dasha");
        put("goat", 1.05, 1.05, 0.07, "ermil");
        put("frog", 0.95, 0.95, 0.06, "jane");
        put("camel", 0.85, 0.85, 0.05, "zahar");
        put("sniffer", 0.80, 0.80, 0.05, "ermil");
        put("allay", 1.20, 1.10, 0.06, "marina/friendly");
        put("dolphin", 1.15, 1.15, 0.06, "anton");
        put("squid", 0.95, 0.90, 0.06, "lera");
        put("bat", 1.30, 1.25, 0.06, "lera");
        put("strider", 0.90, 0.90, 0.07, "ermil");
    }

    /** Подбирает голос для моба: вид задаёт базу, UUID — индивидуальные отличия. */
    public static Voice pick(String type, UUID id, boolean baby) {
        Config c = Config.INSTANCE;
        Spec s = TABLE.get(type);
        if (s == null) s = fallback(type);

        String[] pool = s.voices();
        String ov = c.speciesVoices != null ? c.speciesVoices.get(type) : null; // свой список из конфига
        if (ov != null && !ov.isBlank()) {
            String[] parts = ov.split("\\s*,\\s*");
            if (parts.length > 0 && !parts[0].isBlank()) pool = parts;
        }

        long h = mix(id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 17));
        String entry = pool[(int) Math.floorMod(h >>> 7, (long) pool.length)];
        String voice = entry.trim(), role = "";
        int slash = entry.indexOf('/');
        if (slash > 0) {
            voice = entry.substring(0, slash).trim();
            role = entry.substring(slash + 1).trim();
        }

        double k = Math.max(0.0, c.voiceVariation);
        double pitch = s.pitch() * (1.0 + s.spread() * k * unit(h >>> 20));
        double speed = s.speed() * (1.0 + 0.07 * k * unit(h >>> 40));
        if (baby) { // детёныши звонче и торопливее
            pitch *= 1.18;
            speed *= 1.08;
        }
        pitch = Math.max(0.65, Math.min(1.40, pitch));
        speed = Math.max(0.60, Math.min(1.50, speed));
        return new Voice(voice, role, pitch, speed);
    }

    /** Неизвестный (модовый) вид: голос и высота выводятся из названия вида, чтобы были стабильны. */
    private static Spec fallback(String type) {
        long h = mix(type.hashCode());
        String v = FALLBACK[(int) Math.floorMod(h >>> 5, (long) FALLBACK.length)];
        return new Spec(new String[]{v}, 0.90 + 0.20 * (unit(h >>> 30) + 1) / 2, 1.0, 0.07);
    }

    public static List<String> knownTypes() {
        return List.copyOf(TABLE.keySet());
    }

    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Число в диапазоне [−1; 1] из 20 бит хэша. */
    private static double unit(long x) {
        return ((x & 0xFFFFF) / (double) 0xFFFFF) * 2.0 - 1.0;
    }
}
