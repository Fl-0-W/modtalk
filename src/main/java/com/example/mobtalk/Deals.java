package com.example.mobtalk;

import com.google.gson.JsonObject;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentLevelEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.EnchantedBookItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Физические сделки: моб договорился -> игрок БРОСАЕТ оплату на землю рядом с мобом ->
 * моб выбрасывает товар в сторону игрока.
 */
public class Deals {
    private static final int TTL_TICKS = 20 * 60 * 10; // 10 минут
    private static final double PAY_RADIUS = 2.5;

    private static class Deal {
        UUID mob;
        String mobName;
        Item take;
        int takeN;
        int paid;
        ItemStack give;
        String giveName;
        int expire;
    }

    private static final Map<UUID, Deal> DEALS = new ConcurrentHashMap<>();

    public static void cancel(UUID mobId) {
        DEALS.remove(mobId);
    }

    /** Вызывается на серверном потоке. Возвращает «OK:...» или «ERR:...». */
    public static String create(MinecraftServer srv, UUID mobId, String mobName, JsonObject a) {
        Item give = item(str(a, "give_item"));
        if (give == null) return "ERR:Неизвестный предмет «" + str(a, "give_item") + "».";
        int giveN = Math.max(1, Math.min(num(a, "give_count"), give.getMaxCount()));
        int takeN = Math.max(0, Math.min(num(a, "take_count"), 576));

        ItemStack giveStack;
        String giveName;
        if (give == Items.ENCHANTED_BOOK) {
            Identifier eid = Identifier.tryParse(str(a, "enchantment"));
            Enchantment en = eid == null ? null : Registries.ENCHANTMENT.getOrEmpty(eid).orElse(null);
            if (en == null) return "ERR:Неизвестное зачарование «" + str(a, "enchantment") + "».";
            int lvl = Math.max(1, Math.min(num(a, "level"), en.getMaxLevel()));
            giveStack = EnchantedBookItem.forEnchantment(new EnchantmentLevelEntry(en, lvl));
            giveN = 1;
            giveName = "Зачарованная книга (" + en.getName(lvl).getString() + ")";
        } else {
            giveStack = new ItemStack(give, giveN);
            giveName = give.getName().getString() + (giveN > 1 ? " × " + giveN : "");
        }

        Item take = null;
        String takeName = "";
        if (takeN > 0) {
            take = item(str(a, "take_item"));
            if (take == null) return "ERR:Неизвестный предмет оплаты «" + str(a, "take_item") + "».";
            takeName = take.getName().getString();
        }

        Deal d = new Deal();
        d.mob = mobId;
        d.mobName = mobName;
        d.take = take;
        d.takeN = takeN;
        d.give = giveStack;
        d.giveName = giveName;

        if (takeN == 0) { // подарок
            MobEntity mob = findMob(srv, mobId);
            if (mob == null) return "ERR:Моб сейчас недоступен.";
            payout(mob, d, false);
            return "OK:" + mobName + " выбросил подарок: " + giveName;
        }

        d.expire = srv.getTicks() + TTL_TICKS;
        DEALS.put(mobId, d);
        return "OK:Условия: бросьте на землю рядом с «" + mobName + "» " + takeN + " × " + takeName
                + " — взамен он выбросит: " + giveName + " (предложение действует 10 минут).";
    }

    /** Вызывается каждый серверный тик. */
    public static void tick(MinecraftServer srv) {
        if (DEALS.isEmpty() || srv.getTicks() % 5 != 0) return;
        for (Iterator<Deal> it = DEALS.values().iterator(); it.hasNext(); ) {
            Deal d = it.next();
            if (srv.getTicks() > d.expire) {
                it.remove();
                Memory.add(d.mob, "user", "[Система] Игрок так и не заплатил, договор о сделке отменён.");
                continue;
            }
            MobEntity mob = findMob(srv, d.mob);
            if (mob == null || !(mob.getWorld() instanceof ServerWorld w)) continue;

            final Item take = d.take;
            List<ItemEntity> items = w.getEntitiesByClass(ItemEntity.class,
                    mob.getBoundingBox().expand(PAY_RADIUS), e -> e.isAlive() && e.getStack().isOf(take));
            for (ItemEntity ie : items) {
                int need = d.takeN - d.paid;
                if (need <= 0) break;
                ItemStack s = ie.getStack().copy();
                int n = Math.min(need, s.getCount());
                s.decrement(n);
                d.paid += n;
                if (s.isEmpty()) ie.discard(); else ie.setStack(s);
            }
            if (d.paid >= d.takeN) {
                it.remove();
                payout(mob, d, true);
            }
        }
    }

    private static void payout(MobEntity mob, Deal d, boolean paid) {
        if (!(mob.getWorld() instanceof ServerWorld w)) return;
        PlayerEntity p = w.getClosestPlayer(mob, 24.0);
        ItemEntity ie = new ItemEntity(w, mob.getX(), mob.getEyeY() - 0.3, mob.getZ(), d.give.copy());
        Vec3d v = p != null
                ? p.getPos().subtract(mob.getPos()).multiply(1, 0, 1).normalize().multiply(0.25)
                : Vec3d.ZERO;
        ie.setVelocity(v.x, 0.2, v.z);
        ie.setPickupDelay(20);
        w.spawnEntity(ie);
        if (mob instanceof VillagerEntity) mob.playSound(SoundEvents.ENTITY_VILLAGER_YES, 1.0f, 1.0f);
        if (paid) {
            if (p instanceof ServerPlayerEntity sp)
                sp.sendMessage(Text.literal("§a[Сделка] " + d.mobName + " выбросил вам: " + d.giveName), false);
            Memory.add(d.mob, "user", "[Система] Игрок бросил оплату, и ты выбросил ему товар. Сделка завершена.");
        }
    }

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
