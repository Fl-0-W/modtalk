package com.example.mobtalk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Поручения от мобов. Два вида, оба проверяются самой игрой:
 * FETCH — принести предметы (игрок бросает их на землю рядом с мобом, моб выбрасывает награду);
 * KILL — убить N мобов указанного вида, затем подойти к заказчику за наградой.
 */
public class Quests {
    public static class Quest {
        String giver = "";
        String giverName = "";
        String giverType = "";
        String kind = "";
        String target = "";
        String targetName = "";
        int need;
        int progress;
        String rewardItem = "";
        int rewardCount;
        String title = "";
        boolean ready;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Quest> LIST = new ArrayList<>();
    private static final int MAX_ACTIVE = 5;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("mobtalk_quests.json");
    }

    public static synchronized void load() {
        try {
            Path p = file();
            if (!Files.exists(p)) return;
            List<Quest> read = GSON.fromJson(Files.readString(p), new TypeToken<List<Quest>>() {}.getType());
            if (read != null) {
                LIST.clear();
                LIST.addAll(read);
            }
        } catch (Exception e) {
            MobTalkClient.LOG.error("Quests load error", e);
        }
    }

    private static void save() {
        try {
            Files.writeString(file(), GSON.toJson(LIST));
        } catch (Exception e) {
            MobTalkClient.LOG.error("Quests save error", e);
        }
    }

    // ---------- описание ----------

    private static String goal(Quest q) {
        if ("FETCH".equals(q.kind))
            return "принести " + q.need + " × " + q.targetName + " (бросить на землю рядом с «" + q.giverName + "»)";
        return "убить " + q.need + " × " + q.targetName + " и вернуться к «" + q.giverName + "»";
    }

    private static String reward(Quest q) {
        if (q.rewardItem.isBlank()) return "без награды, только благодарность";
        Item it = item(q.rewardItem);
        return q.rewardCount + " × " + (it != null ? it.getName().getString() : q.rewardItem);
    }

    /** Для промпта моба: его собственное активное задание. */
    public static synchronized String describeFor(UUID giver) {
        for (Quest q : LIST) {
            if (!q.giver.equals(giver.toString())) continue;
            String s = "Твоё активное задание для игрока: «" + q.title + "» — " + goal(q) + "; выполнено "
                    + q.progress + " из " + q.need + "; награда: " + reward(q) + ".";
            if (q.ready) s += " Игрок уже всё выполнил: поблагодари его.";
            return s;
        }
        return "";
    }

    /** Для журнала игрока (клавиша H). */
    public static synchronized List<String> describeAll() {
        List<String> out = new ArrayList<>();
        for (Quest q : LIST) {
            out.add("  §e«" + q.title + "» §7(" + q.giverName + "): " + goal(q) + " — " + q.progress + "/" + q.need
                    + (q.ready ? " §aГОТОВО, вернитесь к заказчику" : "") + "§7; награда: " + reward(q));
        }
        return out;
    }

    public static synchronized void cancel(UUID giver) {
        if (LIST.removeIf(q -> q.giver.equals(giver.toString()))) save();
    }

    // ---------- создание (серверный поток) ----------

    public static synchronized String create(MinecraftServer srv, UUID giverId, String giverName, String giverType, JsonObject a) {
        if (LIST.size() >= MAX_ACTIVE) return "ERR:У игрока уже слишком много активных заданий.";
        for (Quest x : LIST)
            if (x.giver.equals(giverId.toString())) return "ERR:От тебя уже есть незавершённое задание.";

        Quest q = new Quest();
        q.giver = giverId.toString();
        q.giverName = giverName;
        q.giverType = giverType;
        q.kind = str(a, "kind").toUpperCase(Locale.ROOT);
        q.title = str(a, "title").isBlank() ? "Поручение" : str(a, "title");

        if ("FETCH".equals(q.kind)) {
            Item it = item(str(a, "item"));
            if (it == null) return "ERR:Неизвестный предмет «" + str(a, "item") + "».";
            q.need = Math.max(1, Math.min(num(a, "count"), 128));
            q.target = Registries.ITEM.getId(it).toString();
            q.targetName = it.getName().getString();
        } else if ("KILL".equals(q.kind)) {
            EntityType<?> t = entityType(str(a, "target"));
            if (t == null) return "ERR:Неизвестный вид моба «" + str(a, "target") + "».";
            q.need = Math.max(1, Math.min(num(a, "count"), 15));
            q.target = Registries.ENTITY_TYPE.getId(t).getPath();
            q.targetName = t.getName().getString();
        } else {
            return "ERR:Неизвестный вид задания (нужны FETCH или KILL).";
        }

        String ri = str(a, "reward_item");
        int rc = Math.max(0, Math.min(num(a, "reward_count"), 64));
        if (!ri.isBlank() && rc > 0) {
            Item r = item(ri);
            if (r == null) return "ERR:Неизвестный предмет награды «" + ri + "».";
            q.rewardItem = Registries.ITEM.getId(r).toString();
            q.rewardCount = Math.min(rc, r.getMaxCount());
        }
        LIST.add(q);
        save();
        return "OK:«" + q.title + "» — " + goal(q) + "; награда: " + reward(q) + ".";
    }

    // ---------- прогресс ----------

    public static void onKill(ServerPlayerEntity sp, MobEntity mob) {
        String type = Registries.ENTITY_TYPE.getId(mob.getType()).getPath();
        synchronized (Quests.class) {
            boolean changed = false;
            for (Quest q : LIST) {
                if (!"KILL".equals(q.kind) || q.ready || !q.target.equals(type)) continue;
                q.progress++;
                changed = true;
                if (q.progress >= q.need) {
                    q.ready = true;
                    sp.sendMessage(Text.literal("§a[Задание «" + q.title + "» выполнено! Вернитесь к «" + q.giverName
                            + "» за наградой]"), false);
                } else {
                    sp.sendMessage(Text.literal("§7[Задание «" + q.title + "»: " + q.progress + "/" + q.need + "]"), false);
                }
            }
            if (changed) save();
        }
    }

    public static void tick(MinecraftServer srv) {
        if (srv.getTicks() % 5 != 0) return;
        List<Quest> snapshot;
        synchronized (Quests.class) {
            if (LIST.isEmpty()) return;
            snapshot = new ArrayList<>(LIST);
        }
        for (Quest q : snapshot) {
            MobEntity giver = findMob(srv, UUID.fromString(q.giver));
            if (giver == null || !(giver.getWorld() instanceof ServerWorld w)) continue;

            if ("FETCH".equals(q.kind) && !q.ready) {
                Item take = item(q.target);
                if (take == null) continue;
                List<ItemEntity> items = w.getEntitiesByClass(ItemEntity.class,
                        giver.getBoundingBox().expand(2.5), e -> e.isAlive() && e.getStack().isOf(take));
                boolean changed = false;
                for (ItemEntity ie : items) {
                    int need = q.need - q.progress;
                    if (need <= 0) break;
                    ItemStack s = ie.getStack().copy();
                    int n = Math.min(need, s.getCount());
                    s.decrement(n);
                    q.progress += n;
                    changed = true;
                    if (s.isEmpty()) ie.discard(); else ie.setStack(s);
                }
                if (q.progress >= q.need) complete(giver, q);
                else if (changed) synchronized (Quests.class) { save(); }
            } else if (q.ready && srv.getTicks() % 20 == 0) {
                PlayerEntity p = w.getClosestPlayer(giver, 8.0);
                if (p != null) complete(giver, q);
            }
        }
    }

    private static void complete(MobEntity giver, Quest q) {
        synchronized (Quests.class) {
            if (!LIST.remove(q)) return;
            save();
        }
        PlayerEntity p = giver.getWorld() instanceof ServerWorld w ? w.getClosestPlayer(giver, 24.0) : null;
        if (!q.rewardItem.isBlank()) {
            Item r = item(q.rewardItem);
            if (r != null) Deals.toss(giver, new ItemStack(r, q.rewardCount), p);
        }
        State.addPersonal(giver.getUuid(), 10);
        Reputation.addFaction(q.giverType, 3);
        Reputation.record(q.giverType, q.giverName, 10, 3, "выполнено задание «" + q.title + "»",
                giver.getWorld().getTimeOfDay());
        int after = Reputation.attitude(giver.getUuid(), q.giverType);
        if (p instanceof ServerPlayerEntity sp) {
            sp.sendMessage(Text.literal("§a[Задание «" + q.title + "» сдано] " + q.giverName
                    + (q.rewardItem.isBlank() ? " благодарит вас" : " выдал награду: " + reward(q))
                    + " §7(отношение +10 · " + Reputation.tier(after) + ")"), false);
        }
        Memory.add(giver.getUuid(), "user", "[Система] Игрок выполнил твоё задание «" + q.title + "»; ты выдал награду.");
    }

    // ---------- вспомогательное ----------

    private static MobEntity findMob(MinecraftServer srv, UUID id) {
        for (ServerWorld w : srv.getWorlds()) {
            Entity e = w.getEntity(id);
            if (e instanceof MobEntity m && m.isAlive()) return m;
        }
        return null;
    }

    private static Item item(String id) {
        Identifier ident = Identifier.tryParse(id);
        if (ident == null) return null;
        Item it = Registries.ITEM.getOrEmpty(ident).orElse(null);
        return it == null || it == Items.AIR ? null : it;
    }

    private static EntityType<?> entityType(String id) {
        Identifier ident = Identifier.tryParse(id);
        if (ident == null) return null;
        return Registries.ENTITY_TYPE.getOrEmpty(ident).orElse(null);
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
}
