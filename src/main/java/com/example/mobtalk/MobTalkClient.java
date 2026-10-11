package com.example.mobtalk;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Box;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class MobTalkClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("MobTalk");

    static final ExecutorService WORK = pool(1, "MobTalk-Work");
    static final ExecutorService AMBIENT = pool(1, "MobTalk-Ambient");
    static final ExecutorService MEMORY = pool(1, "MobTalk-Memory");
    static final ExecutorService TTS = pool(3, "MobTalk-TTS");
    static final ExecutorService PLAYER = pool(1, "MobTalk-Audio");

    private static final Random RNG = new Random();
    private static KeyBinding talkKey;
    private static KeyBinding repKey;

    private static volatile boolean busy;
    private static volatile boolean ambientBusy;
    private static boolean recording;
    private static boolean locked;
    private static int chatterCountdown = 20 * 90;

    // --- спонтанная речь: события, приветствия ---
    private record GameEvent(String type, String text, boolean positional, double x, double y, double z, long time) {}

    private static final Queue<GameEvent> EVENTS = new ConcurrentLinkedQueue<>();
    private static final Map<String, Long> EVENT_LAST = new HashMap<>();
    private static final Map<UUID, Long> GREETED = new HashMap<>();
    private static final Set<UUID> NEAR = new HashSet<>();
    private static boolean nearSeeded;
    private static Object lastWorld;
    private static long lastSpontaneous;
    private static int greetTimer;
    private static long prevDayTime = -1;
    private static boolean prevRain, prevThunder;
    private static float prevHealth = -1;
    private static boolean raidActive;

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
        Quests.load();
        talkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mobtalk.talk", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.mobtalk"));
        repKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mobtalk.rep", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "category.mobtalk"));

        ClientTickEvents.END_CLIENT_TICK.register(MobTalkClient::clientTick);
        ServerTickEvents.END_SERVER_TICK.register(MobTalkClient::serverTick);

        // репутация: удар мирного моба (одиночная игра, серверный поток)
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient() && player instanceof ServerPlayerEntity sp && entity instanceof MobEntity mob) {
                Reputation.onAttack(sp, mob);
            }
            return ActionResult.PASS;
        });
        // убийства: репутация, прогресс заданий, реакция соседей
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof MobEntity mob)) return;
            if (source.getAttacker() instanceof ServerPlayerEntity sp) {
                Reputation.onKill(sp, mob);
                Quests.onKill(sp, mob);
            }
            pushDeathEvent(mob, source);
        });
        LOG.info("MobTalk loaded");
    }

    // ---------- ввод ----------

    private static void clientTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        while (repKey.wasPressed()) Reputation.show(mc);
        spontaneousTick(mc);

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
        Brain.Scene scene = new Brain.Scene(mobs, inventoryString(mc), contextString(mc),
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
        return new Brain.Mob(e.getUuid(), type, Personalities.displayName(e), hostile,
                e.distanceTo(mc.player), Personalities.persona(e), e.isBaby());
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
        // одинаковые подписи («Корова», «Корова») различаем номерами, чтобы мобы не путались друг с другом
        Map<String, Integer> total = new HashMap<>(), seen = new HashMap<>();
        for (Brain.Mob m : out) total.merge(m.name(), 1, Integer::sum);
        for (int i = 0; i < out.size(); i++) {
            Brain.Mob m = out.get(i);
            if (total.get(m.name()) > 1) {
                int n = seen.merge(m.name(), 1, Integer::sum);
                out.set(i, new Brain.Mob(m.id(), m.type(), m.name() + " №" + n, m.hostile(), m.dist(), m.persona(), m.baby()));
            }
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

    /** Обстановка: время, погода, состояние и предмет в руке игрока — мобы могут это заметить. */
    private static String contextString(MinecraftClient mc) {
        long t = mc.world.getTimeOfDay() % 24000L;
        StringBuilder sb = new StringBuilder(t < 13000L ? "день" : "ночь");
        if (mc.world.isRaining()) sb.append(", идёт дождь");
        sb.append(". Здоровье игрока ").append(Math.round(mc.player.getHealth())).append(" из 20");
        ItemStack h = mc.player.getMainHandStack();
        if (!h.isEmpty()) sb.append(", в руке у него ").append(h.getName().getString());
        return sb.toString();
    }

    private static boolean canSpeak(String type, UUID id, boolean hostileFallback) {
        boolean hostile = Personalities.isHostile(type, hostileFallback);
        return !hostile || State.isTruce(id) || Reputation.attitude(id, type) >= 20; // враждебные молчат, пока не подружитесь
    }

    // ---------- спонтанная речь: события, приветствия, болтовня ----------

    private static void spontaneousTick(MinecraftClient mc) {
        if (mc.world != lastWorld) { // новый мир: не здороваться со всеми сразу
            lastWorld = mc.world;
            NEAR.clear();
            nearSeeded = false;
            prevDayTime = -1;
            prevHealth = -1;
            EVENTS.clear();
        }
        Config c = Config.INSTANCE;
        State.decayDay(mc.world.getTimeOfDay() / 24000L); // фракции медленно остывают
        detectEvents(mc);
        long now = System.currentTimeMillis();
        EVENTS.removeIf(e -> now - e.time() > 20000);

        boolean free = !recording && !busy && !ambientBusy && c.apiKey != null && !c.apiKey.isBlank();
        if (!free || now - lastSpontaneous < 12000) {
            greetTimer = 0;
            return;
        }
        if (c.reactions && processEvent(mc)) return;
        if (c.greetings && greetingTick(mc)) return;
        chatterTick(mc);
    }

    // --- события ---

    private static void pushEvent(String type, String text, boolean positional, double x, double y, double z) {
        EVENTS.add(new GameEvent(type, text, positional, x, y, z, System.currentTimeMillis()));
    }

    private static void pushDeathEvent(MobEntity mob, DamageSource source) {
        String type = Registries.ENTITY_TYPE.getId(mob.getType()).getPath();
        boolean hostile = Personalities.isHostile(type, mob instanceof HostileEntity);
        if (hostile && !State.isTruce(mob.getUuid()) && State.personal(mob.getUuid()) == 0) return; // рядовые монстры — не событие
        Entity attacker = source.getAttacker();
        String by = attacker instanceof PlayerEntity ? "игрок"
                : attacker != null ? attacker.getType().getName().getString() : "несчастный случай";
        pushEvent("DEATH", "Рядом погиб «" + Personalities.displayName(mob) + "» (причина: " + by + ").",
                true, mob.getX(), mob.getY(), mob.getZ());
    }

    private static void detectEvents(MinecraftClient mc) {
        long t = mc.world.getTimeOfDay() % 24000L;
        boolean rain = mc.world.isRaining(), thunder = mc.world.isThundering();
        float hp = mc.player.getHealth();
        if (prevDayTime >= 0) {
            if (prevDayTime < 13000 && t >= 13000 && t - prevDayTime < 200)
                pushEvent("NIGHT", "Наступает ночь: стемнело, скоро выйдут монстры.", false, 0, 0, 0);
            if (prevDayTime > 20000 && t < 2000)
                pushEvent("MORNING", "Наступило утро, рассвело.", false, 0, 0, 0);
            if (rain && !prevRain)
                pushEvent("RAIN", "Пошёл дождь.", false, 0, 0, 0);
            if (thunder && !prevThunder)
                pushEvent("STORM", "Началась гроза: гремит гром и сверкают молнии.", false, 0, 0, 0);
            if (prevHealth > 0 && prevHealth - hp >= 4f)
                pushEvent("HURT", "Игрок только что получил сильный урон: здоровье упало до " + Math.round(hp)
                        + " из 20.", false, 0, 0, 0);
        }
        prevDayTime = t;
        prevRain = rain;
        prevThunder = thunder;
        prevHealth = hp;
    }

    private static long cooldownMs(String type) {
        return switch (type) {
            case "NIGHT", "MORNING" -> 120_000L;
            case "RAIN", "STORM" -> 90_000L;
            case "HURT" -> 45_000L;
            case "RAID", "RAID_END" -> 60_000L;
            default -> 30_000L; // DEATH
        };
    }

    private static boolean processEvent(MinecraftClient mc) {
        GameEvent ev = EVENTS.poll();
        if (ev == null) return false;
        long now = System.currentTimeMillis();
        Long last = EVENT_LAST.get(ev.type());
        if (last != null && now - last < cooldownMs(ev.type())) return false;

        Box box = ev.positional()
                ? new Box(ev.x() - 12, ev.y() - 6, ev.z() - 12, ev.x() + 12, ev.y() + 6, ev.z() + 12)
                : mc.player.getBoundingBox().expand(16.0);
        List<MobEntity> list = mc.world.getEntitiesByClass(MobEntity.class, box, e -> {
            if (!e.isAlive() || e.distanceTo(mc.player) > 20) return false;
            String type = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
            return canSpeak(type, e.getUuid(), e instanceof HostileEntity);
        });
        list.sort(Comparator.comparingDouble((MobEntity e) -> e.distanceTo(mc.player)));
        if (list.isEmpty()) return false;

        List<Brain.Mob> reactors = new ArrayList<>();
        for (MobEntity e : list) {
            if (reactors.size() >= 2) break;
            reactors.add(toMob(e, mc));
        }
        EVENT_LAST.put(ev.type(), now);
        lastSpontaneous = now;
        String inv = inventoryString(mc), ctx = contextString(mc), pn = mc.player.getName().getString();
        ambientBusy = true;
        AMBIENT.submit(() -> {
            try {
                Brain.reactRound(mc, reactors, inv, ctx, pn, ev.text());
            } catch (Exception e) {
                LOG.warn("Reaction error: {}", e.getMessage());
            } finally {
                ambientBusy = false;
            }
        });
        return true;
    }

    // --- приветствия ---

    private static boolean greetingTick(MinecraftClient mc) {
        if (++greetTimer < 10) return false;
        greetTimer = 0;
        Config c = Config.INSTANCE;
        double r = c.greetRadius;
        List<MobEntity> list = mc.world.getEntitiesByClass(MobEntity.class,
                mc.player.getBoundingBox().expand(r), e -> e.isAlive() && e.distanceTo(mc.player) <= r);
        Set<UUID> now = new HashSet<>();
        for (MobEntity e : list) now.add(e.getUuid());
        Set<UUID> entered = new HashSet<>(now);
        entered.removeAll(NEAR);
        NEAR.clear();
        NEAR.addAll(now);
        if (!nearSeeded) {
            nearSeeded = true;
            return false;
        }
        list.sort(Comparator.comparingDouble((MobEntity e) -> e.distanceTo(mc.player)));
        long ms = System.currentTimeMillis();
        for (MobEntity e : list) {
            UUID id = e.getUuid();
            if (!entered.contains(id)) continue;
            Long last = GREETED.get(id);
            if (last != null && ms - last < c.greetCooldownSec * 1000L) continue;
            String type = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
            if (!canSpeak(type, id, e instanceof HostileEntity)) continue;
            GREETED.put(id, ms); // и удачная попытка, и неудачная включают паузу
            if (RNG.nextDouble() > c.greetChance) continue;

            Brain.Mob m = toMob(e, mc);
            String inv = inventoryString(mc), ctx = contextString(mc), pn = mc.player.getName().getString();
            lastSpontaneous = ms;
            ambientBusy = true;
            AMBIENT.submit(() -> {
                try {
                    Brain.greet(mc, m, inv, ctx, pn);
                } catch (Exception ex) {
                    LOG.warn("Greeting error: {}", ex.getMessage());
                } finally {
                    ambientBusy = false;
                }
            });
            return true;
        }
        return false;
    }

    // --- болтовня мобов между собой ---

    private static void chatterTick(MinecraftClient mc) {
        Config c = Config.INSTANCE;
        if (!c.ambientChatter) return;
        if (--chatterCountdown > 0) return;
        int base = Math.max(20, c.chatterIntervalSec) * 20;
        chatterCountdown = base / 2 + RNG.nextInt(base);

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
                lastSpontaneous = System.currentTimeMillis();
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

    // ---------- сервер: следование, сделки, задания, рейды ----------

    private static void serverTick(MinecraftServer server) {
        Deals.tick(server);
        Quests.tick(server);

        if (server.getTicks() % 40 == 0) { // рейд рядом с игроком
            boolean active = false;
            for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) {
                if (sp.getWorld() instanceof ServerWorld w && w.hasRaidAt(sp.getBlockPos())) active = true;
            }
            if (active && !raidActive)
                pushEvent("RAID", "На деревню напали: начался рейд разбойников!", false, 0, 0, 0);
            else if (!active && raidActive)
                pushEvent("RAID_END", "Рейд закончился, шум битвы стих.", false, 0, 0, 0);
            raidActive = active;
        }

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
