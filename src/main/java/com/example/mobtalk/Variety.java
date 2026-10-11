package com.example.mobtalk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Разнообразие реплик: помним, что мобы уже говорили, подмешиваем в запрос «заходы» (тема/манера) и просим
 * модель не повторяться; если ответ всё же слишком похож на недавние — Brain просит переформулировать.
 */
public final class Variety {
    private Variety() {}

    private static final int PER_MOB = 8;
    private static final int GLOBAL = 16;
    private static final Random RNG = new Random();

    private static final Map<UUID, ArrayDeque<String>> MINE = new HashMap<>();
    private static final ArrayDeque<String> ALL = new ArrayDeque<>();

    private static final String[] TOPICS = {
            "погода и время суток", "то, что ты ел или хочешь съесть", "твоя маленькая причуда или привычка",
            "слух или сплетня о соседях", "странный звук или запах поблизости", "воспоминание из прошлого",
            "то, что игрок несёт или носит", "ночь, монстры и страх", "мечта или планы на завтра",
            "жалоба на что-то мелкое", "неожиданный вопрос к игроку", "шутка или каламбур",
            "то, что ты видишь вокруг прямо сейчас", "совет игроку", "твоё настроение сегодня",
            "дом, место, где ты обычно бываешь", "древняя легенда или байка", "сравнение игрока с кем-то или чем-то",
            "новость, которую ты якобы слышал", "философская мысль на ровном месте"};

    private static final String[] STYLES = {
            "говори коротко и отрывисто", "говори мечтательно и с паузами", "говори с иронией",
            "говори сухо и деловито", "говори с энтузиазмом", "говори ворчливо", "говори тихо и по секрету",
            "говори с юмором, но без злости", "говори чуть церемонно", "говори просто, как с приятелем"};

    private static final String[] BANNED_OPENERS = {"О,", "Ох,", "Ах,", "Эй,", "Привет", "Здравствуй", "Приветствую",
            "Ну что", "Слушай,"};

    /** Блок для системного промпта: что уже было сказано и с какого захода говорить сейчас. */
    public static synchronized String prompt(UUID id) {
        StringBuilder sb = new StringBuilder("\nУникальность: каждая твоя реплика должна отличаться от предыдущих "
                + "по словам, началу и строю фразы; не используй заготовки и штампы; не начинай подряд с одного и того же слова");
        sb.append(" (особенно избегай начал: ").append(String.join(" ", BANNED_OPENERS)).append("). ");
        sb.append("Сейчас, если уместно, затронь тему: «").append(TOPICS[RNG.nextInt(TOPICS.length)])
                .append("»; манера — ").append(STYLES[RNG.nextInt(STYLES.length)]).append(". ");
        ArrayDeque<String> mine = MINE.get(id);
        if (mine != null && !mine.isEmpty()) {
            sb.append("\nТвои последние реплики (НЕ повторяй их и не перефразируй): ");
            for (String s : mine) sb.append("«").append(s).append("» ");
        }
        List<String> others = new ArrayList<>();
        for (String s : ALL) {
            if (mine == null || !mine.contains(s)) others.add(s);
        }
        if (!others.isEmpty()) {
            sb.append("\nНедавно в округе уже говорили (так не говори): ");
            int from = Math.max(0, others.size() - 6);
            for (int i = from; i < others.size(); i++) sb.append("«").append(others.get(i)).append("» ");
        }
        return sb.toString();
    }

    public static synchronized void remember(UUID id, String text) {
        if (text == null || text.isBlank()) return;
        ArrayDeque<String> q = MINE.computeIfAbsent(id, k -> new ArrayDeque<>());
        q.addLast(text);
        while (q.size() > PER_MOB) q.removeFirst();
        ALL.addLast(text);
        while (ALL.size() > GLOBAL) ALL.removeFirst();
    }

    /** Слишком ли реплика похожа на недавние (этого моба или соседей). */
    public static synchronized boolean tooSimilar(UUID id, String text) {
        if (text == null || text.isBlank()) return false;
        Set<String> a = stems(text);
        if (a.size() < 3) return false;
        String open = opener(text);
        List<String> pool = new ArrayList<>(ALL);
        ArrayDeque<String> mine = MINE.get(id);
        if (mine != null) pool.addAll(mine);
        for (String s : pool) {
            Set<String> b = stems(s);
            if (b.isEmpty()) continue;
            int common = 0;
            for (String w : a) if (b.contains(w)) common++;
            double jac = common / (double) (a.size() + b.size() - common);
            if (jac >= 0.5) return true;
            if (mine != null && mine.contains(s) && !open.isEmpty() && open.equals(opener(s)) && jac >= 0.25) return true;
        }
        return false;
    }

    private static String opener(String s) {
        String[] w = s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\s]", " ").trim().split("\\s+");
        if (w.length == 0 || w[0].isEmpty()) return "";
        return w.length > 1 ? w[0] + " " + w[1] : w[0];
    }

    private static Set<String> stems(String s) {
        Set<String> out = new HashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\s]", " ").split("\\s+")) {
            if (w.length() < 4) continue; // союзы и предлоги не считаем
            out.add(w.length() > 5 ? w.substring(0, 5) : w);
        }
        return out;
    }
}
