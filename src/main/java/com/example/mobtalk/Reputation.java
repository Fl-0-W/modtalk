package com.example.mobtalk;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.*;

/** Репутация: личное отношение каждого моба + общая репутация среди вида. */
public class Reputation {
    private static final Map<UUID, Long> LAST_HIT = new HashMap<>();

    public static String tier(int s) {
        if (s <= -60) return "Враждебность";
        if (s <= -25) return "Недоверие";
        if (s < 25) return "Нейтрально";
        if (s < 60) return "Симпатия";
        return "Дружба";
    }

    public static String color(int s) {
        if (s <= -25) return "§c";
        if (s < 25) return "§7";
        return "§a";
    }

    public static String fmt(int v) {
        return (v > 0 ? "+" : "") + v;
    }

    /** Подсказка для моба, как вести себя при таком отношении к игроку. */
    public static String guidance(int personal, boolean trader) {
        if (personal <= -60)
            return "Ты ненавидишь игрока: резок, грубишь, ни на что не соглашаешься, кроме искреннего извинения; "
                    + "сделок и услуг не будет.";
        if (personal <= -25)
            return "Ты не доверяешь игроку: холоден и подозрителен"
                    + (trader ? ", цены завышаешь на 30–50 процентов." : ".");
        if (personal < 25)
            return "Ты относишься к игроку нейтрально, присматриваешься к нему.";
        if (personal < 60)
            return "Ты симпатизируешь игроку: говоришь теплее, шутишь"
                    + (trader ? ", даёшь скидку до 20 процентов." : ".");
        return "Ты считаешь игрока другом: доверяешь секреты, охотно идёшь за ним"
                + (trader ? ", даёшь скидку 30–50 процентов." : ".");
    }

    private static String speciesName(String type) {
        Identifier id = Identifier.tryParse("minecraft:" + type);
        if (id != null) {
            Optional<EntityType<?>> t = Registries.ENTITY_TYPE.getOrEmpty(id);
            if (t.isPresent()) return t.get().getName().getString();
        }
        return type;
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

        if (!hostile) {
            int np = State.addPersonal(id, -25);
            State.addSpecies(type, -8);
            p.sendMessage(Text.literal("§c[Вы ударили «" + name + "»: его отношение −25 (" + tier(np)
                    + "). Слух разошёлся: репутация среди «" + speciesName(type) + "» −8]"), false);
        } else if (wasTruce || State.personal(id) > 0) {
            int np = State.addPersonal(id, -40);
            State.addSpecies(type, -5);
            p.sendMessage(Text.literal("§c[Предательство! «" + name + "» вам доверял: отношение −40 (" + tier(np) + ")]"), false);
        }
    }

    public static void onKill(ServerPlayerEntity p, MobEntity mob) {
        String type = Registries.ENTITY_TYPE.getId(mob.getType()).getPath();
        boolean hostile = Personalities.isHostile(type, mob instanceof HostileEntity);
        if (hostile) return;
        int ns = State.addSpecies(type, -12);
        p.sendMessage(Text.literal("§c[Вы убили «" + Personalities.displayName(mob) + "». Репутация среди «"
                + speciesName(type) + "» −12 (" + tier(ns) + ")]"), false);
    }

    // ---------- показ репутации (клиент) ----------

    public static void show(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        List<String> lines = new ArrayList<>();
        lines.add("§6§l=== Репутация и задания ===");

        Map<String, Integer> sp = State.speciesSnapshot();
        if (sp.isEmpty()) {
            lines.add("§7Среди видов: пока никто о вас не слышал.");
        } else {
            lines.add("§7Среди видов:");
            sp.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(en -> lines.add("  §e" + speciesName(en.getKey()) + " " + color(en.getValue())
                            + fmt(en.getValue()) + " §7(" + tier(en.getValue()) + ")"));
        }

        double r = Math.max(10.0, Config.INSTANCE.listenRadius * 1.5);
        List<MobEntity> near = mc.world.getEntitiesByClass(MobEntity.class,
                mc.player.getBoundingBox().expand(r), e -> e.isAlive() && e.distanceTo(mc.player) <= r);
        near.sort(Comparator.comparingDouble((MobEntity e) -> e.distanceTo(mc.player)));
        lines.add("§7Отношение мобов рядом:");
        int shown = 0;
        for (MobEntity e : near) {
            if (shown >= 8) break;
            int v = State.personal(e.getUuid());
            lines.add("  §e" + Personalities.displayName(e) + " " + color(v) + fmt(v) + " §7(" + tier(v) + ")");
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
