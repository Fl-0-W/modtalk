package com.example.mobtalk;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MobTalkClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("MobTalk");

    private static final Pattern TAG = Pattern.compile("\\[(TRUCE|ANGRY|DEAL)\\]");
    private static final double RANGE = 10.0;

    /** Мобы, заключившие перемирие (только в одиночной игре). */
    private static final Set<UUID> TRUCE = ConcurrentHashMap.newKeySet();

    private static KeyBinding talkKey;
    private static final ExecutorService WORK = single("MobTalk-Work");
    private static final ExecutorService PLAYER = single("MobTalk-Audio");

    private static volatile boolean busy;
    private static boolean recording;
    private static boolean locked; // ждём отпускания клавиши

    private static UUID tId;
    private static String tType;
    private static String tName;
    private static boolean tHostile;

    private static ExecutorService single(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onInitializeClient() {
        Memory.load();
        talkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mobtalk.talk", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.mobtalk"));

        ClientTickEvents.END_CLIENT_TICK.register(MobTalkClient::clientTick);
        ServerTickEvents.END_SERVER_TICK.register(MobTalkClient::serverTick);
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> TRUCE.clear());
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            TRUCE.remove(entity.getUuid()); // ударил — перемирие сорвано
            return ActionResult.PASS;
        });
        LOG.info("MobTalk loaded");
    }

    // ---------- ввод ----------

    private static void clientTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
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
        if (busy || mc.currentScreen != null) {
            locked = true;
            return;
        }

        locked = true; // если что-то не так — не спамим, пока клавиша зажата
        Config.reload();
        if (Config.INSTANCE.apiKey == null || Config.INSTANCE.apiKey.isBlank()) {
            chat(mc, "§c[MobTalk] Впиши apiKey в файл config/mobtalk.json и повтори.");
            return;
        }
        Entity target = findTarget(mc);
        if (target == null) {
            overlay(mc, "§7Наведи прицел на моба (до " + (int) RANGE + " блоков)");
            return;
        }
        if (!Mic.start()) {
            chat(mc, "§c[MobTalk] Не удалось открыть микрофон. Проверь разрешения системы и устройство ввода по умолчанию.");
            return;
        }
        recording = true;
        tId = target.getUuid();
        tType = Registries.ENTITY_TYPE.getId(target.getType()).getPath();
        tName = target.getName().getString();
        tHostile = target instanceof HostileEntity;
        overlay(mc, "§a🎤 Слушаю " + tName + "… (отпусти клавишу, когда закончишь)");
    }

    private static Entity findTarget(MinecraftClient mc) {
        Entity cam = mc.getCameraEntity();
        if (cam == null) return null;
        Vec3d start = cam.getCameraPosVec(1.0f);
        Vec3d dir = cam.getRotationVec(1.0f);
        Vec3d end = start.add(dir.multiply(RANGE));
        Box box = cam.getBoundingBox().stretch(dir.multiply(RANGE)).expand(1.0);
        EntityHitResult r = ProjectileUtil.raycast(cam, start, end, box,
                e -> e instanceof LivingEntity && e.isAlive() && !(e instanceof PlayerEntity) && !e.isSpectator(),
                RANGE * RANGE);
        return r == null ? null : r.getEntity();
    }

    private static void finishRecording(MinecraftClient mc) {
        byte[] wav = Mic.stopWav();
        if (wav == null) {
            overlay(mc, "§7Слишком коротко — держи клавишу, пока говоришь");
            return;
        }
        busy = true;
        final UUID id = tId;
        final String type = tType, name = tName;
        final boolean hostile = tHostile;
        final UUID playerId = mc.player.getUuid();
        final String playerName = mc.player.getName().getString();
        overlay(mc, "§e" + name + " думает…");
        WORK.submit(() -> {
            try {
                process(mc, id, type, name, hostile, playerId, playerName, wav);
            } catch (Exception e) {
                LOG.error("MobTalk error", e);
                chat(mc, "§c[MobTalk] Ошибка: " + e.getMessage());
            } finally {
                busy = false;
            }
        });
    }

    // ---------- основная логика ----------

    private static void process(MinecraftClient mc, UUID id, String type, String name, boolean hostile,
                                UUID playerId, String playerName, byte[] wav) throws Exception {
        String heard = Api.transcribe(wav);
        if (heard.isBlank()) {
            overlay(mc, "§7Не расслышал, повтори");
            return;
        }
        chat(mc, "§7Вы: §f" + heard);

        List<Memory.Msg> history = Memory.get(id);
        history.add(new Memory.Msg("user", heard));
        String raw = Api.chat(Personalities.systemPrompt(type, hostile, playerName, name), history);

        Memory.add(id, "user", heard);
        Memory.add(id, "assistant", raw);

        String clean = TAG.matcher(raw).replaceAll("").trim();
        chat(mc, "§e" + name + "§f: " + clean);

        applyTags(mc, raw, id, type, name, playerId);

        Personalities.Profile p = Personalities.profile(type, hostile);
        byte[] pcm = Api.tts(clean, p.voice(), p.speed());
        PLAYER.submit(() -> Mic.play(pcm));
    }

    private static void applyTags(MinecraftClient mc, String raw, UUID id, String type, String name, UUID playerId) {
        Set<String> tags = new HashSet<>();
        Matcher m = TAG.matcher(raw);
        while (m.find()) tags.add(m.group(1));
        MinecraftServer srv = mc.getServer();
        if (tags.isEmpty() || srv == null) return; // на чужих серверах эффекты недоступны

        srv.execute(() -> {
            if (tags.contains("TRUCE")) {
                TRUCE.add(id);
                chat(mc, "§a[Перемирие: " + name + " не тронет вас, пока вы его не ударите]");
            }
            if (tags.contains("ANGRY") && TRUCE.remove(id)) {
                chat(mc, "§c[Перемирие нарушено: " + name + " снова враждебен]");
            }
            if (tags.contains("DEAL") && type.equals("villager")) {
                ServerPlayerEntity sp = srv.getPlayerManager().getPlayer(playerId);
                if (sp != null) {
                    sp.addStatusEffect(new StatusEffectInstance(StatusEffects.HERO_OF_THE_VILLAGE, 6000, 0));
                    chat(mc, "§a[Сделка заключена: скидки у жителей на 5 минут]");
                }
            }
        });
    }

    /** Мобы с перемирием перестают целиться в игрока. Работает на встроенном сервере (одиночная игра). */
    private static void serverTick(MinecraftServer server) {
        if (TRUCE.isEmpty()) return;
        for (UUID id : TRUCE) {
            for (ServerWorld w : server.getWorlds()) {
                Entity e = w.getEntity(id);
                if (e instanceof MobEntity mob) {
                    if (mob.getTarget() instanceof PlayerEntity) mob.setTarget(null);
                    break;
                }
            }
        }
    }

    // ---------- вывод ----------

    private static void chat(MinecraftClient mc, String s) {
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendMessage(Text.literal(s), false);
        });
    }

    private static void overlay(MinecraftClient mc, String s) {
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendMessage(Text.literal(s), true);
        });
    }
}
