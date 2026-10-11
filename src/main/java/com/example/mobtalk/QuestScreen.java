package com.example.mobtalk;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Окно заданий (клавиша N, «Т» в русской раскладке): все действующие задания, кто их дал, где он находится,
 * прогресс и награда. Задание можно сдать прямо отсюда или лично, бросив предметы рядом с заказчиком.
 */
public class QuestScreen extends Screen {
    private static final int LEFT_W = 140;
    private static final int ROW_H = 26;
    private static final int LINE_H = 11;
    private static final String[] DIRS = {"север", "северо-восток", "восток", "юго-восток", "юг", "юго-запад", "запад",
            "северо-запад"};

    private List<Quests.Quest> quests = new ArrayList<>();
    private String selected;
    private int x0, y0, w, h;
    private int leftScroll;
    private boolean confirmAbandon;
    private String status = "";
    private ButtonWidget handInBtn, abandonBtn;

    public QuestScreen() {
        super(Fonts.ui("Задания"));
    }

    @Override
    protected void init() {
        w = Math.min(width - 20, 460);
        h = Math.min(height - 20, 270);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        quests = Quests.snapshot();
        if (selected == null || find(selected) == null) selected = quests.isEmpty() ? null : quests.get(0).giver;

        int bx = x0 + LEFT_W + 10;
        int bw = (w - LEFT_W - 28) / 2;
        int by = y0 + h - 40;
        handInBtn = addDrawableChild(ButtonWidget.builder(Fonts.ui("Сдать задание"), b -> handIn())
                .dimensions(bx, by, bw, 20).build());
        abandonBtn = addDrawableChild(ButtonWidget.builder(Fonts.ui("Отказаться"), b -> abandon())
                .dimensions(bx + bw + 8, by, bw, 20).build());
    }

    private Quests.Quest find(String giver) {
        for (Quests.Quest q : quests) if (q.giver.equals(giver)) return q;
        return null;
    }

    // ---------- действия ----------

    private void handIn() {
        Quests.Quest q = find(selected);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (q == null || mc.player == null) return;
        MinecraftServer srv = mc.getServer();
        if (srv == null) {
            status = "§cСдача через окно работает только в одиночной игре.";
            return;
        }
        UUID pid = mc.player.getUuid();
        String giver = q.giver;
        srv.execute(() -> {
            ServerPlayerEntity sp = srv.getPlayerManager().getPlayer(pid);
            if (sp == null) return;
            String msg = Quests.handIn(sp, giver);
            sp.sendMessage(Text.literal(msg), false);
            mc.execute(() -> {
                status = msg;
                quests = Quests.snapshot();
                if (find(selected) == null) selected = quests.isEmpty() ? null : quests.get(0).giver;
            });
        });
    }

    private void abandon() {
        if (selected == null) return;
        if (!confirmAbandon) { // защита от случайного клика: нужно нажать дважды
            confirmAbandon = true;
            return;
        }
        confirmAbandon = false;
        Quests.abandon(selected);
        status = "§7Вы отказались от задания.";
        quests = Quests.snapshot();
        selected = quests.isEmpty() ? null : quests.get(0).giver;
    }

    // ---------- отрисовка ----------

    private String where(Quests.Quest q) {
        MinecraftClient mc = MinecraftClient.getInstance();
        StringBuilder sb = new StringBuilder("§fX " + Math.round(q.x) + "  Y " + Math.round(q.y) + "  Z " + Math.round(q.z));
        if (mc.player != null && mc.world != null) {
            String dim = mc.world.getRegistryKey().getValue().toString();
            if (!dim.equals(q.dim)) {
                sb.append(" §7(другое измерение: ").append(q.dim).append(")");
            } else {
                double dx = q.x - mc.player.getX(), dy = q.y - mc.player.getY(), dz = q.z - mc.player.getZ();
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double ang = Math.toDegrees(Math.atan2(dx, -dz)); // 0° — север (−Z), по часовой стрелке
                int idx = (int) Math.floorMod(Math.round(ang / 45.0), 8L);
                sb.append(" §7— ").append(Math.round(dist)).append(" блоков, на ").append(DIRS[idx]);
            }
        }
        if (!q.live) sb.append(" §8(последняя известная позиция)");
        return sb.toString();
    }

    private List<OrderedText> wrap(String s, int width) {
        return Fonts.wrap(textRenderer, s, width);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);
        ctx.fill(x0, y0, x0 + w, y0 + h, 0xD0101018);
        ctx.fill(x0, y0, x0 + w, y0 + 18, 0xFF202030);
        ctx.drawCenteredTextWithShadow(textRenderer, Fonts.ui("Задания (" + quests.size() + ")"),
                x0 + w / 2, y0 + 5, 0xFFD060);

        int lt = y0 + 20, lh = h - 20 - 16;
        ctx.fill(x0 + 4, lt, x0 + LEFT_W, lt + lh, 0x80000000);
        ctx.enableScissor(x0 + 4, lt, x0 + LEFT_W, lt + lh);
        for (int i = 0; i < quests.size(); i++) {
            Quests.Quest q = quests.get(i);
            int ry = lt + i * ROW_H - leftScroll;
            if (ry + ROW_H < lt || ry > lt + lh) continue;
            if (q.giver.equals(selected)) ctx.fill(x0 + 4, ry, x0 + LEFT_W, ry + ROW_H - 2, 0x50FFFFFF);
            ctx.drawText(textRenderer, Fonts.fit(textRenderer, "§e" + q.title, LEFT_W - 14), x0 + 8, ry + 3, 0xFFFFFF, false);
            String sub = "§7" + q.giverName + (q.ready || q.progress >= q.need ? " §aготово" : " " + q.progress + "/" + q.need);
            ctx.drawText(textRenderer, Fonts.fit(textRenderer, sub, LEFT_W - 14), x0 + 8, ry + 14, 0xFFFFFF, false);
        }
        ctx.disableScissor();

        Quests.Quest q = selected == null ? null : find(selected);
        int rx = x0 + LEFT_W + 10, rw = w - LEFT_W - 20;
        if (q == null) {
            ctx.drawText(textRenderer, Fonts.ui("§7Нет активных заданий."), rx, y0 + 26, 0xFFFFFF, false);
            for (OrderedText l : wrap("§8Жители деревни сами предлагают поручения по своему ремеслу — "
                    + "поговорите с ними (клавиша V).", rw)) {
                ctx.drawText(textRenderer, l, rx, y0 + 40, 0xFFFFFF, false);
            }
            handInBtn.active = false;
            abandonBtn.active = false;
        } else {
            List<OrderedText> info = new ArrayList<>();
            info.addAll(wrap("§e§l" + q.title, rw));
            info.addAll(wrap("§7Заказчик: §f" + q.giverName, rw));
            info.addAll(wrap("§7Где: " + where(q), rw));
            info.addAll(wrap("§7Задание: §f" + Quests.goal(q), rw));
            info.addAll(wrap("§7Прогресс: §f" + q.progress + "/" + q.need
                    + (q.ready ? " §a— выполнено, можно сдавать" : ""), rw));
            info.addAll(wrap("§7Награда: §a" + Quests.reward(q), rw));
            int have = MinecraftClient.getInstance().player != null
                    ? Quests.have(MinecraftClient.getInstance().player, q) : 0;
            if ("FETCH".equals(q.kind)) info.addAll(wrap("§7У вас в инвентаре: §f" + have, rw));
            info.addAll(wrap("§8Сдать можно здесь или лично: бросьте предметы на землю рядом с заказчиком.", rw));
            int y = y0 + 24;
            for (OrderedText l : info) {
                if (y > y0 + h - 64) break;
                ctx.drawText(textRenderer, l, rx, y, 0xFFFFFF, false);
                y += LINE_H;
            }
            handInBtn.active = "FETCH".equals(q.kind) ? have > 0 : q.ready;
            abandonBtn.setMessage(Fonts.ui(confirmAbandon ? "§cТочно отказаться?" : "Отказаться"));
        }
        if (!status.isBlank()) ctx.drawText(textRenderer, Fonts.fit(textRenderer, status, w - 16), x0 + 8, y0 + h - 12, 0xFFFFFF, false);
        else ctx.drawText(textRenderer, Fonts.ui("§8N / Esc — закрыть · колесо — прокрутка списка"), x0 + 8, y0 + h - 12, 0xFFFFFF, false);
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int lt = y0 + 20, lh = h - 20 - 16;
        if (button == 0 && mx >= x0 + 4 && mx < x0 + LEFT_W && my >= lt && my < lt + lh) {
            int idx = (int) ((my - lt + leftScroll) / ROW_H);
            if (idx >= 0 && idx < quests.size()) {
                selected = quests.get(idx).giver;
                confirmAbandon = false;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        int max = Math.max(0, quests.size() * ROW_H - (h - 36));
        leftScroll = (int) Math.max(0, Math.min(max, leftScroll - amount * 18));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (MobTalkClient.isQuestKey(keyCode, scanCode) || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
