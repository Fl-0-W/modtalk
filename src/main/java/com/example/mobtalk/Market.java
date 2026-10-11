package com.example.mobtalk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Баланс торговли: что торговец может ПРОДАТЬ (выдать игроку) и что готов ПРИНЯТЬ в оплату.
 * Ограничения применяются в коде (Deals.create), а не только в промпте, так что модель не может их обойти.
 * <p>
 * Шаблоны: «слово» — точный предмет, «*_ingot» — оканчивается, «wool_*» — начинается; id без «minecraft:».
 * Изумруды принимают все жители с ремеслом.
 */
public final class Market {
    private Market() {}

    /** Правила одного торговца: что продаёт, что покупает, и описания для промпта. */
    public record Rules(List<String> sells, List<String> buys, String sellsText, String buysText) {}

    private static final Map<String, Rules> BY_PROF = new HashMap<>();
    private static final Map<String, Rules> BY_TYPE = new HashMap<>();

    private static final List<String> TOOLS = List.of("*_pickaxe", "*_axe", "*_shovel", "*_hoe");
    private static final List<String> ARMOR = List.of("*_helmet", "*_chestplate", "*_leggings", "*_boots");
    private static final List<String> SWORDS = List.of("*_sword");

    @SafeVarargs
    private static List<String> join(List<String>... parts) {
        java.util.ArrayList<String> r = new java.util.ArrayList<>();
        for (List<String> p : parts) r.addAll(p);
        return r;
    }

    private static void prof(String prof, List<String> sells, List<String> buys, String sellsText, String buysText) {
        BY_PROF.put(prof, new Rules(sells, join(List.of("emerald"), buys), sellsText, "изумруды, " + buysText));
    }

    static {
        prof("farmer",
                List.of("wheat", "carrot", "potato", "baked_potato", "beetroot", "pumpkin", "melon_slice", "melon",
                        "bread", "apple", "wheat_seeds", "beetroot_seeds", "pumpkin_seeds", "melon_seeds", "pumpkin_pie",
                        "cookie", "hay_block", "golden_carrot", "bone_meal", "sweet_berries", "cake"),
                List.of("wheat", "wheat_seeds", "carrot", "potato", "beetroot", "pumpkin", "melon_slice", "bread",
                        "bone_meal", "hay_block"),
                "урожай и еду: пшеницу, овощи, хлеб, пироги, семена",
                "урожай, семена, костную муку");
        prof("librarian",
                List.of("book", "paper", "bookshelf", "enchanted_book", "ink_sac", "writable_book", "glass", "compass",
                        "clock", "name_tag", "lantern", "lectern", "feather"),
                List.of("paper", "book", "ink_sac", "writable_book", "feather", "lapis_lazuli", "leather", "glass",
                        "bookshelf"),
                "книги, зачарованные книги, книжные полки, бумагу, чернила, компасы и часы",
                "бумагу, книги, чернила, перья, лазурит");
        prof("cleric",
                List.of("redstone", "lapis_lazuli", "ender_pearl", "glowstone", "glowstone_dust", "experience_bottle",
                        "rabbit_foot", "nether_wart", "glistering_melon_slice", "golden_carrot", "glass_bottle",
                        "blaze_powder", "potion*"),
                List.of("redstone", "lapis_lazuli", "rotten_flesh", "gold_ingot", "nether_wart", "glowstone_dust",
                        "rabbit_foot", "scute", "blaze_powder"),
                "зелья и их составляющие: красную пыль, лазурит, жемчуг Края, светопыль, пузырьки опыта",
                "красную пыль, лазурит, гнилую плоть, золото, адский нарост");
        prof("armorer",
                join(ARMOR, List.of("shield", "iron_ingot", "coal", "charcoal", "bell", "diamond", "chainmail_*",
                        "iron_block", "lava_bucket")),
                List.of("iron_ingot", "coal", "charcoal", "diamond", "lava_bucket", "iron_block"),
                "броню всех видов, щиты, железные слитки, уголь, алмазы",
                "железо, уголь, алмазы");
        prof("weaponsmith",
                join(SWORDS, List.of("*_axe", "iron_ingot", "coal", "charcoal", "bell", "diamond", "stick")),
                List.of("iron_ingot", "coal", "charcoal", "diamond", "stick", "flint"),
                "мечи, топоры, железные слитки, уголь, алмазы",
                "железо, уголь, алмазы, палки");
        prof("toolsmith",
                join(TOOLS, List.of("iron_ingot", "coal", "charcoal", "bell", "diamond", "flint", "stick", "shears")),
                List.of("iron_ingot", "coal", "charcoal", "diamond", "flint", "stick", "iron_block"),
                "кирки, лопаты, мотыги, топоры, железные слитки, уголь, алмазы",
                "железо, уголь, алмазы, кремень, палки");
        prof("butcher",
                List.of("beef", "porkchop", "chicken", "mutton", "rabbit", "cooked_*", "rabbit_stew", "coal",
                        "charcoal", "dried_kelp", "sweet_berries", "bone", "leather"),
                List.of("beef", "porkchop", "chicken", "mutton", "rabbit", "coal", "charcoal", "dried_kelp",
                        "sweet_berries"),
                "сырое и жареное мясо, рагу, уголь",
                "мясо, уголь, сушёные водоросли, ягоды");
        prof("leatherworker",
                join(List.of("leather", "rabbit_hide", "saddle", "leather_*", "item_frame", "flint", "string",
                        "scute", "bundle"), List.of()),
                List.of("leather", "rabbit_hide", "flint", "string", "scute", "coal"),
                "кожу, кожаную броню и сбрую, сёдла, рамки",
                "кожу, шкурки, кремень, нитки");
        prof("mason",
                List.of("stone", "cobblestone", "granite", "diorite", "andesite", "stone_bricks", "bricks", "brick",
                        "clay_ball", "terracotta", "*_terracotta", "quartz", "quartz_block", "quartz_pillar",
                        "polished_*", "chiseled_*", "*_bricks", "sandstone", "smooth_*", "*_slab", "*_stairs"),
                List.of("clay_ball", "stone", "cobblestone", "granite", "diorite", "andesite", "quartz", "brick",
                        "sandstone", "gravel"),
                "камень, кирпичи, глину, терракоту, кварц и строительные блоки",
                "глину, камень, кварц, кирпич");
        prof("fisherman",
                List.of("cod", "salmon", "cooked_cod", "cooked_salmon", "fishing_rod", "bucket", "*_boat", "campfire",
                        "string", "stick", "kelp", "dried_kelp", "pufferfish", "tropical_fish", "water_bucket",
                        "lily_pad", "nautilus_shell"),
                List.of("cod", "salmon", "string", "stick", "coal", "kelp", "dried_kelp", "pufferfish",
                        "tropical_fish"),
                "рыбу, удочки, лодки, вёдра, нитки, водоросли",
                "рыбу, нитки, палки, уголь, водоросли");
        prof("shepherd",
                List.of("*_wool", "*_carpet", "*_bed", "*_banner", "shears", "*_dye", "painting", "string", "stick",
                        "white_wool", "wheat"),
                List.of("*_wool", "*_dye", "string", "stick", "wheat", "shears"),
                "шерсть, ковры, кровати, баннеры, краски, ножницы, картины",
                "шерсть, краски, нитки, палки, пшеницу");
        prof("fletcher",
                List.of("arrow", "bow", "crossbow", "flint", "stick", "feather", "string", "tripwire_hook",
                        "tipped_arrow", "spectral_arrow"),
                List.of("stick", "feather", "flint", "string", "gravel", "arrow", "iron_ingot"),
                "стрелы, луки, арбалеты, кремень, перья, палки",
                "палки, перья, кремень, нитки, гравий");
        prof("cartographer",
                List.of("map", "filled_map", "paper", "glass_pane", "glass", "compass", "item_frame", "*_banner",
                        "empty_map", "spyglass", "clock", "name_tag"),
                List.of("paper", "ink_sac", "glass_pane", "glass", "compass", "feather", "book", "map"),
                "карты, бумагу, стекло, компасы, подзорные трубы, рамки",
                "бумагу, чернила, стекло, компасы");
        // без ремесла — торговать нечем
        Rules none = new Rules(List.of(), List.of(), "ничего", "ничего");
        BY_PROF.put("none", none);
        BY_PROF.put("nitwit", none);
        BY_PROF.put("", none);

        // не-жители
        BY_TYPE.put("witch", new Rules(
                List.of("glass_bottle", "redstone", "glowstone_dust", "sugar", "spider_eye", "gunpowder", "stick",
                        "potion*", "splash_potion*", "fermented_spider_eye", "blaze_powder", "nether_wart"),
                List.of("emerald", "gold_ingot", "gold_nugget", "redstone", "glowstone_dust", "spider_eye", "nether_wart",
                        "rotten_flesh", "bone", "gunpowder", "sugar", "ender_pearl"),
                "зелья и ведьмины ингредиенты: пузырьки, красную пыль, сахар, паучьи глаза, порох",
                "изумруды, золото и редкие ингредиенты"));
        BY_TYPE.put("piglin", new Rules(
                List.of("ender_pearl", "obsidian", "crying_obsidian", "fire_charge", "gravel", "leather", "string",
                        "iron_nugget", "soul_sand", "nether_brick", "splash_potion*", "potion*", "quartz",
                        "enchanted_book"),
                List.of("gold_ingot", "gold_nugget", "gold_block", "raw_gold", "golden_*"),
                "добычу Нижнего мира: жемчуг Края, обсидиан, кожу, нитки, душевый песок",
                "ТОЛЬКО золото (слитки, самородки, золотые вещи) — изумруды для тебя пустая зелёная стекляшка"));
        // странствующий торговец без ограничений: у него «редкие товары со всего света»
    }

    /** Правила торговца или null, если ограничений нет (странствующий торговец, неизвестный вид). */
    public static Rules rules(String type, String profession) {
        if ("villager".equals(type)) {
            Rules r = BY_PROF.get(profession == null ? "" : profession);
            return r != null ? r : BY_PROF.get("none");
        }
        return BY_TYPE.get(type);
    }

    /** Совпадает ли id предмета (без пространства имён) с одним из шаблонов. */
    public static boolean matches(List<String> patterns, String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        for (String p : patterns) {
            if (p.startsWith("*") && path.endsWith(p.substring(1))) return true;
            if (p.endsWith("*") && path.startsWith(p.substring(0, p.length() - 1))) return true;
            if (p.equals(path)) return true;
        }
        return false;
    }

    /** Строка для промпта: что этот торговец вообще может продать и принять. */
    public static String prompt(String type, String profession) {
        Rules r = rules(type, profession);
        if (r == null) return "";
        if (r.sells().isEmpty()) {
            return "ОГРАНИЧЕНИЯ ТОРГОВЛИ: у тебя нет ремесла и товара — ты ничего не продаёшь и не обмениваешь "
                    + "(даже за изумруды). Если игрок просит что-то, честно скажи, что тебе нечего предложить. "
                    + "Действие TRADE не используй. ";
        }
        return "ОГРАНИЧЕНИЯ ТОРГОВЛИ (строго): ты можешь выдать игроку ТОЛЬКО то, что связано с твоим ремеслом: "
                + r.sellsText() + ". Всё остальное (например, чужие товары) у тебя просто нет — откажи в образе. "
                + "В оплату ты берёшь только: " + r.buysText() + ". Остальное тебе не нужно, даже если оно ценное "
                + "(например, ненужные тебе зачарованные книги или чужие редкости) — вежливо или грубо откажись и предложи "
                + "изумруды или то, что тебе действительно нужно. Игра сама проверит сделку и отклонит нарушение. ";
    }

    /** Проверка сделки. Возвращает null, если всё допустимо, иначе текст причины для моба. */
    public static String check(String type, String profession, String giveId, String giveName, String takeId,
                               String takeName, int takeCount) {
        Rules r = rules(type, profession);
        if (r == null) return null;
        if (r.sells().isEmpty()) {
            return "У тебя нет ремесла и товара: ты ничего не продаёшь и не меняешь.";
        }
        if (!matches(r.sells(), giveId)) {
            return "Ты не можешь дать «" + giveName + "»: это не из твоего ремесла. Твой товар: " + r.sellsText() + ".";
        }
        if (takeCount > 0 && !matches(r.buys(), takeId)) {
            return "Тебе не нужен «" + takeName + "» в оплату. Ты берёшь только: " + r.buysText() + ".";
        }
        return null;
    }
}
