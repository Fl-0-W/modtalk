package com.example.mobtalk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Постоянное состояние: перемирие, следование, голоса, репутация. */
public class State {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<UUID> TRUCE = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> FOLLOW = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Integer> VOICES = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PERSONAL = new ConcurrentHashMap<>();
    private static final Map<String, Integer> SPECIES = new ConcurrentHashMap<>();

    /** Запись журнала репутации: когда, у какой расы, на сколько и за что. */
    public static class Entry {
        public long real;          // реальное время (мс), для порядка
        public long gameTime;      // игровое время (тики мира), для «День N, ЧЧ:ММ»
        public String faction = "";
        public String mob = "";    // кто именно (подпись моба), может быть пустым
        public int personal;       // изменение личного отношения этого моба
        public int factionDelta;   // изменение репутации расы
        public String reason = "";
    }

    private static final int HISTORY_MAX = 500;
    private static final List<Entry> HISTORY = new ArrayList<>();

    private static class Disk {
        List<String> truce = new ArrayList<>();
        List<String> follow = new ArrayList<>();
        Map<String, Integer> voices = new HashMap<>();
        Map<String, Integer> personal = new HashMap<>();
        Map<String, Integer> species = new HashMap<>();
        int version = 0;
        List<Entry> history = new ArrayList<>();
    }

    private static long lastDay = -1;

    private static Path file() {
        return Worlds.file("mobtalk_state.json");
    }

    /** Сброс состояния при смене мира (дальше Worlds подгрузит данные нового мира). */
    public static synchronized void reset() {
        TRUCE.clear();
        FOLLOW.clear();
        VOICES.clear();
        PERSONAL.clear();
        SPECIES.clear();
        HISTORY.clear();
        lastDay = -1;
    }

    // ----- перемирие / следование -----

    public static boolean isTruce(UUID id) {
        return !TRUCE.isEmpty() && TRUCE.contains(id);
    }

    public static boolean isFollow(UUID id) {
        return FOLLOW.contains(id);
    }

    public static Set<UUID> followSnapshot() {
        return new HashSet<>(FOLLOW);
    }

    public static synchronized void setTruce(UUID id, boolean on) {
        boolean changed = on ? TRUCE.add(id) : TRUCE.remove(id);
        if (changed) save();
    }

    public static synchronized void setFollow(UUID id, boolean on) {
        boolean changed = on ? FOLLOW.add(id) : FOLLOW.remove(id);
        if (changed) save();
    }

    // ----- репутация -----

    public static int personal(UUID id) {
        return PERSONAL.getOrDefault(id, 0);
    }

    public static int species(String type) {
        return SPECIES.getOrDefault(type, 0);
    }

    public static synchronized int addPersonal(UUID id, int delta) {
        int v = Math.max(-100, Math.min(100, PERSONAL.getOrDefault(id, 0) + delta));
        PERSONAL.put(id, v);
        save();
        return v;
    }

    public static synchronized int addSpecies(String type, int delta) {
        int v = Math.max(-100, Math.min(100, SPECIES.getOrDefault(type, 0) + delta));
        SPECIES.put(type, v);
        save();
        return v;
    }

    public static synchronized void addHistory(Entry e) {
        HISTORY.add(e);
        while (HISTORY.size() > HISTORY_MAX) HISTORY.remove(0);
        save();
    }

    public static synchronized List<Entry> historySnapshot() {
        return new ArrayList<>(HISTORY);
    }

    public static Map<String, Integer> speciesSnapshot() {
        return new HashMap<>(SPECIES);
    }

    /** Раз в игровые сутки репутация фракций остывает к нулю на 1 пункт (отношение отдельных мобов не меняется). */
    public static synchronized void decayDay(long day) {
        if (lastDay < 0 || day < lastDay) { // первый вызов или другой мир: просто запоминаем день
            lastDay = day;
            return;
        }
        if (day == lastDay) return;
        int steps = (int) Math.min(30, day - lastDay);
        lastDay = day;
        boolean changed = false;
        for (Map.Entry<String, Integer> en : SPECIES.entrySet()) {
            int v = en.getValue();
            int nv = v > 0 ? Math.max(0, v - steps) : Math.min(0, v + steps);
            if (nv != v) {
                en.setValue(nv);
                changed = true;
            }
        }
        if (changed) save();
    }

    // ----- голоса: (голос, вариант высоты) закрепляются за мобом навсегда -----

    public static synchronized int voiceIndex(UUID id, int nVoices, int nVariants) {
        int total = nVoices * nVariants;
        Integer v = VOICES.get(id);
        if (v != null && v < total) return v;

        int[] perVoice = new int[nVoices];
        int[] perCombo = new int[total];
        for (int x : VOICES.values()) {
            if (x < total) {
                perCombo[x]++;
                perVoice[x % nVoices]++;
            }
        }
        int startV = Math.floorMod(id.hashCode(), nVoices);
        int bestV = startV;
        for (int i = 0; i < nVoices; i++) {
            int idx = (startV + i) % nVoices;
            if (perVoice[idx] < perVoice[bestV]) bestV = idx;
        }
        int startVar = Math.floorMod(id.hashCode() >>> 3, nVariants);
        int bestVar = startVar;
        for (int j = 0; j < nVariants; j++) {
            int var = (startVar + j) % nVariants;
            if (perCombo[bestV + var * nVoices] < perCombo[bestV + bestVar * nVoices]) bestVar = var;
        }
        int idx = bestV + bestVar * nVoices;
        VOICES.put(id, idx);
        save();
        return idx;
    }

    // ----- диск -----

    public static synchronized void load() {
        try {
            Path p = file();
            if (!Files.exists(p)) return;
            Disk d = GSON.fromJson(Files.readString(p), Disk.class);
            if (d == null) return;
            for (String s : d.truce) TRUCE.add(UUID.fromString(s));
            for (String s : d.follow) FOLLOW.add(UUID.fromString(s));
            d.voices.forEach((k, x) -> VOICES.put(UUID.fromString(k), x));
            d.personal.forEach((k, x) -> PERSONAL.put(UUID.fromString(k), x));
            if (d.history != null) HISTORY.addAll(d.history);
            if (d.version < 2) { // старые записи были по видам, теперь — по фракциям: сливаем с ослаблением
                for (Map.Entry<String, Integer> en : d.species.entrySet())
                    SPECIES.merge(Reputation.factionOf(en.getKey()), en.getValue() / 2,
                            (a, b) -> Math.max(-100, Math.min(100, a + b)));
            } else {
                SPECIES.putAll(d.species);
            }
        } catch (Exception e) {
            MobTalkClient.LOG.error("State load error", e);
        }
    }

    private static void save() {
        try {
            Disk d = new Disk();
            TRUCE.forEach(u -> d.truce.add(u.toString()));
            FOLLOW.forEach(u -> d.follow.add(u.toString()));
            VOICES.forEach((k, x) -> d.voices.put(k.toString(), x));
            PERSONAL.forEach((k, x) -> d.personal.put(k.toString(), x));
            d.species.putAll(SPECIES);
            d.version = 2;
            synchronized (State.class) {
                d.history.addAll(HISTORY);
            }
            Files.writeString(file(), GSON.toJson(d));
        } catch (Exception e) {
            MobTalkClient.LOG.error("State save error", e);
        }
    }
}
