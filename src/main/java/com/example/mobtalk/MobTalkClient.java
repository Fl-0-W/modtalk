package com.example.mobtalk;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Box;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class MobTalkClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("MobTalk");

    static final ExecutorService WORK = pool(1, "MobTalk-Work");
    static final ExecutorService AMBIENT = pool(1, "MobTalk-Ambient");
    static final ExecutorService TTS = pool(3, "MobTalk-TTS");
    static final ExecutorService PLAYER = pool(1, "MobTalk-Audio");

    private static final Random RNG = new Random();
    private static KeyBinding talkKey;

    private static volatile boolean busy;
    private static volatile boolean ambientBusy;
    private static boolean recording;
    private static boolean locked;
    private static int chatterCountdown = 20 * 90;

    private static ExecutorService pool(int n, String name) {
        return Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onInitializeClient() {
        Config.reload();
        State.load();
        Memory.load();
        talkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mobtalk.talk", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.mobtalk"));

        ClientTickEvents.END_CLIENT_TICK.register(MobTalkClient::clientTick);
        ServerTickEvents.END_SERVER_TICK.register(MobTalkClient::serverTick);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (State.isTruce(entity.getUuid())) State.setTruce(entity.getUuid(), false); // ударил — перемирие сорвано
            return ActionResult.PASS;
        });
        LOG.info("MobTalk loaded");
    }

    // ---------- ввод ----------

    private static void clientTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        ambientTick(mc);

        boolean down = talkKey.isPressed();
        if (!down) {
            locked = false;
            if (recording) {
                recording = false;
                finishRecording(mc);
            }
            return;
        }
        if (recording || locked) return;
        locked = true;
        if (busy || mc.currentScreen != null) return;

        Config.reload();
        if (Config.INSTANCE.apiKey == null || Config.INSTANCE.apiKey.isBlank()) {
            chat(mc, "§c[MobTalk] Впиши apiKey в файл config/mobtalk.json и повтори.");
            return;
        }
        if (nearbyMobs(mc, 1).isEmpty()) {
            overlay(mc, "§7Рядом нет мобов (слышат в радиусе " + (int) Config.INSTANCE.listenRadius + " блоков)");
            return;
        }
        if (!Mic.start()) {
            chat(mc, "§c[MobTalk] Не удалось открыть микрофон. Проверь разрешения системы и устройство ввода по умолчанию.");
            return;
        }
        recording = true;
        overlay(mc, "§a🎤 Слушаю… отпусти клавишу, когда закончишь");
    }

    private static void finishRecording(MinecraftClient mc) {
        byte[] wav = Mic.stopWav();
        if (wav == null) {
            overlay(mc, "§7Слишком коротко — держи клавишу, пока говоришь");
            return;
        }
        List<Brain.Mob> mobs = nearbyMobs(mc, Math.max(1, Config.INSTANCE.maxListeners));
        if (mobs.isEmpty()) {
            overlay(mc, "§7Рядом никого нет");
            return;
        }
        long t = mc.world.getTimeOfDay() % 24000L;
        Brain.Scene scene = new Brain.Scene(mobs, inventoryString(mc), t < 13000L ? "день" : "ночь",
                mc.player.getName().getString(), mc.player.getUuid());
        busy = true;
        overlay(mc, "§e" + mobs.size() + " сущ. слушают…");
        WORK.submit(() -> {
            try {
                Brain.playerRound(mc, scene, wav);
            } catch (Exception e) {
                LOG.error("MobTalk error", e);
                chat(mc, "§c[MobTalk] Ошибка: " + e.getMessage());
            } finally {
                busy = false;
            }
        });
    }

    // ---------- сцена ----------

    private static Brain.Mob toMob(MobEntity e, MinecraftClient mc) {
        String type = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
        boolean hostile = Personalities.isHostile(type, e instanceof HostileEntity);
        return new Brain.Mob(e.getUuid(), type, Personalities.displayName(e), hostile, e.distanceTo(mc.player));
    }

    private static List<Brain.Mob> nearbyMobs(MinecraftClient mc, int max) {
        double r = Config.INSTANCE.listenRadius;
        List<MobEntity> list = mc.world.getEntitiesByClass(MobEntity.class,
                mc.player.getBoundingBox().expand(r), e -> e.isAlive() && e.distanceTo(mc.player) <= r);
        list.sort(Comparator.comparingDouble((MobEntity e) -> e.distanceTo(mc.player)));
        List<Brain.Mob> out = new ArrayList<>();
        for (MobEntity e : list) {
            if (out.size() >= max) break;
            out.add(toMob(e, mc));
        }
        return out;
    }

    private static String inventoryString(MinecraftClient mc) {
        Map<String, Integer> m = new TreeMap<>();
        for (ItemStack s : mc.player.getInventory().main)
            if (!s.isEmpty()) m.merge(Registries.ITEM.getId(s.getItem()).toString(), s.getCount(), Integer::sum);
        for (ItemStack s : mc.player.getInventory().offHand)
            if (!s.isEmpty()) m.merge(Registries.ITEM.getId(s.getItem()).toString(), s.getCount(), Integer::sum);
        if (m.isEmpty()) return "пусто";
        return m.entrySet().stream().limit(30).map(en -> en.getKey() + " × " + en.getValue())
                .collect(Collectors.joining(", "));
    }

    // ---------- редкие разговоры между мобами ----------

    private static void ambientTick(MinecraftClient mc) {
        Config c = Config.INSTANCE;
        if (!c.ambientChatter || recording || busy || ambientBusy) return;
        if (--chatterCountdown > 0) return;
        int base = Math.max(20, c.chatterIntervalSec) * 20;
        chatterCountdown = base / 2 + RNG.nextInt(base);
        if (c.apiKey == null || c.apiKey.isBlank()) return;

        double r = c.chatterRadius;
        List<MobEntity> near = mc.world.getEntitiesByClass(MobEntity.class,
                mc.player.getBoundingBox().expand(r), e -> e.isAlive() && e.distanceTo(mc.player) <= r);
        if (near.size() < 2) return;
        Collections.shuffle(near, RNG);
        for (MobEntity a : near) {
            MobEntity best = null;
            double bd = 6.0;
            for (MobEntity b : near) {
                if (b == a) continue;
                double d = a.distanceTo(b);
                if (d < bd) {
                    bd = d;
                    best = b;
                }
            }
            if (best != null) {
                Brain.Mob ma = toMob(a, mc), mb = toMob(best, mc);
                String pn = mc.player.getName().getString();
                ambientBusy = true;
                AMBIENT.submit(() -> {
                    try {
                        Brain.chatter(mc, ma, mb, pn);
                    } catch (Exception e) {
                        LOG.warn("Chatter error: {}", e.getMessage());
                    } finally {
                        ambientBusy = false;
                    }
                });
                return;
            }
        }
    }

    // ---------- «ходит за игроком» (одиночная игра) ----------

    private static void serverTick(MinecraftServer server) {
        Deals.tick(server);
        if (server.getTicks() % 5 != 0) return;
        Set<UUID> follow = State.followSnapshot();
        if (follow.isEmpty()) return;
        for (UUID id : follow) {
            for (ServerWorld w : server.getWorlds()) {
                Entity e = w.getEntity(id);
                if (e instanceof MobEntity mob && mob.isAlive()) {
                    PlayerEntity p = w.getClosestPlayer(mob, 48.0);
                    if (p == null) break;
                    double d = mob.distanceTo(p);
                    if (d > 3.5) {
                        mob.getNavigation().startMovingTo(p, 1.2);
                        mob.getLookControl().lookAt(p, 30f, 30f);
                    } else if (d < 2.5) {
                        mob.getNavigation().stop();
                    }
                    break;
                }
            }
        }
    }

    // ---------- вывод ----------

    static void chat(MinecraftClient mc, String s) {
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendMessage(Text.literal(s), false);
        });
    }

    static void overlay(MinecraftClient mc, String s) {
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendMessage(Text.literal(s), true);
        });
    }
}
