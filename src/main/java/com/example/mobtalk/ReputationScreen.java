package com.example.mobtalk;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Окно репутации (клавиша P, «З» в русской раскладке): слева — расы мобов, справа — подробности выбранной расы
 * и журнал изменений репутации: когда, на сколько и за что.
 */
public class ReputationScreen extends Screen {
    private static final int LEFT_W = 164;
    private static final int ROW_H = 30;
    private static final int LINE_H = 13;

    private final List<String> factions = new ArrayList<>();
    private String selected;

    private int x0, y0, w, h;
    private int leftScroll;      // в пикселях
    private int histScroll;      // в строках
    private List<OrderedText> head = new ArrayList<>();
    private List<OrderedText> hist = new ArrayList<>();

    public ReputationScreen() {
        super(Fonts.ui("Репутация по расам"));
    }

    @Override
    protected void init() {
        w = Math.min(width - 20, 520);
        h = Math.min(height - 20, 280);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;

        if (factions.isEmpty()) {
            List<State.Entry> all = State.historySnapshot();
            Map<String, Integer> sp = State.speciesSnapshot();
            for (String f : Reputation.mainFactions()) factions.add(f);
            List<String> extra = new ArrayList<>();
            for (String f : sp.keySet()) if (!factions.contains(f) && !extra.contains(f)) extra.add(f);
            for (State.Entry e : all) if (!factions.contains(e.faction) && !extra.contains(e.faction)) extra.add(e.faction);
            factions.addAll(extra);
            if (!all.isEmpty()) selected = all.get(all.size() - 1).faction;
            if (selected == null) {
                for (String f : factions) {
                    if (State.species(f) != 0) {
                        selected = f;
                        break;
                    }
                }
            }
            if (selected == null) selected = factions.get(0);
        }
        rebuild();
    }

    private List<OrderedText> wrap(String s, int width) {
        return Fonts.wrap(textRenderer, s, width);
    }

    private int rightW() {
        return w - LEFT_W - 20;
    }

    /** Пересобирает заголовок и журнал выбранной расы. */
    private void rebuild() {
        int rw = rightW();
        int rep = State.species(selected);
        head = new ArrayList<>();
        head.addAll(wrap("§e" + Reputation.factionName(selected), rw));

        StringBuilder members = new StringBuilder();
        for (String t : Reputation.membersOf(selected)) {
            if (members.length() > 0) members.append(", ");
            members.append(Personalities.speciesName(t));
        }
        head.addAll(wrap("§7Виды: " + members, rw));
        head.addAll(wrap("§fРепутация: " + Reputation.color(rep) + Reputation.fmt(rep) + " §7(" + Reputation.tier(rep)
                + ") " + Reputation.bar(rep), rw));
        head.addAll(wrap("§7" + Reputation.effectText(rep), rw));

        List<State.Entry> mine = new ArrayList<>();
        for (State.Entry e : State.historySnapshot()) if (selected.equals(e.faction)) mine.add(e);
        int gained = 0, lost = 0;
        for (State.Entry e : mine) {
            if (e.factionDelta > 0) gained += e.factionDelta;
            else lost += -e.factionDelta;
        }
        head.addAll(wrap("§7Записей: " + mine.size() + " · получено §a+" + gained + "§7 · потеряно §c-" + lost, rw));
        head.addAll(wrap("§6История изменений:", rw));

        hist = new ArrayList<>();
        if (mine.isEmpty()) {
            hist.addAll(wrap("§8Пока ничего не менялось: поговорите с мобами этой расы, торгуйте, помогайте им.", rw));
        } else {
            for (int i = mine.size() - 1; i >= 0; i--) { // новые сверху, номера — по порядку событий
                State.Entry e = mine.get(i);
                StringBuilder sb = new StringBuilder("§7").append(i + 1).append(". ");
                if (e.factionDelta != 0) {
                    sb.append(Reputation.color(e.factionDelta)).append(Reputation.fmt(e.factionDelta)).append(" репутации расы");
                }
                if (e.personal != 0) {
                    if (e.factionDelta != 0) sb.append("§7 и ");
                    sb.append(Reputation.color(e.personal)).append(Reputation.fmt(e.personal)).append(" лично");
                }
                sb.append("§f — ").append(e.reason);
                if (!e.mob.isBlank()) sb.append(" §7(").append(e.mob).append(")");
                sb.append(" §8[").append(Reputation.gameClock(e.gameTime)).append("]");
                hist.addAll(wrap(sb.toString(), rw));
            }
        }
        histScroll = Math.min(histScroll, maxHistScroll());
    }

    private int histTop() {
        return y0 + 24 + head.size() * LINE_H + 4;
    }

    private int histVisible() {
        return Math.max(1, (y0 + h - 18 - histTop()) / LINE_H);
    }

    private int maxHistScroll() {
        return Math.max(0, hist.size() - histVisible());
    }

    private int leftTop() {
        return y0 + 20;
    }

    private int leftHeight() {
        return h - 20 - 16;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);
        ctx.fill(x0, y0, x0 + w, y0 + h, 0xD0101018);
        ctx.fill(x0, y0, x0 + w, y0 + 18, 0xFF202030);
        ctx.drawCenteredTextWithShadow(textRenderer, title, x0 + w / 2, y0 + 5, 0xFFD060);

        // левая колонка: расы
        int lt = leftTop(), lh = leftHeight();
        ctx.fill(x0 + 4, lt, x0 + LEFT_W, lt + lh, 0x80000000);
        ctx.enableScissor(x0 + 4, lt, x0 + LEFT_W, lt + lh);
        for (int i = 0; i < factions.size(); i++) {
            int ry = lt + i * ROW_H - leftScroll;
            if (ry + ROW_H < lt || ry > lt + lh) continue;
            String f = factions.get(i);
            int rep = State.species(f);
            if (f.equals(selected)) ctx.fill(x0 + 4, ry, x0 + LEFT_W, ry + ROW_H - 2, 0x50FFFFFF);
            else if (mouseX >= x0 + 4 && mouseX < x0 + LEFT_W && mouseY >= ry && mouseY < ry + ROW_H - 2
                    && mouseY >= lt && mouseY < lt + lh) ctx.fill(x0 + 4, ry, x0 + LEFT_W, ry + ROW_H - 2, 0x25FFFFFF);
            ctx.drawText(textRenderer, Fonts.fit(textRenderer, Reputation.factionName(f), LEFT_W - 14), x0 + 8, ry + 5, 0xFFFFFF, false);
            ctx.drawText(textRenderer, Fonts.ui(Reputation.color(rep) + Reputation.fmt(rep) + " §7" + Reputation.tier(rep)),
                    x0 + 8, ry + 17, 0xFFFFFF, false);
        }
        ctx.disableScissor();

        // правая часть: подробности и журнал
        int rx = x0 + LEFT_W + 10;
        int y = y0 + 24;
        for (OrderedText line : head) {
            ctx.drawText(textRenderer, line, rx, y, 0xFFFFFF, false);
            y += LINE_H;
        }
        int top = histTop(), vis = histVisible();
        ctx.enableScissor(rx - 2, top - 1, x0 + w - 6, top + vis * LINE_H + 1);
        for (int i = 0; i < vis; i++) {
            int idx = histScroll + i;
            if (idx >= hist.size()) break;
            ctx.drawText(textRenderer, hist.get(idx), rx, top + i * LINE_H, 0xFFFFFF, false);
        }
        ctx.disableScissor();
        if (hist.size() > vis) {
            int barH = vis * LINE_H;
            int thumb = Math.max(10, barH * vis / hist.size());
            int ty = top + (barH - thumb) * histScroll / Math.max(1, maxHistScroll());
            ctx.fill(x0 + w - 5, top, x0 + w - 3, top + barH, 0x40FFFFFF);
            ctx.fill(x0 + w - 5, ty, x0 + w - 3, ty + thumb, 0xA0FFFFFF);
        }

        ctx.drawText(textRenderer, Fonts.ui("§8Отношение моба = личное + 40% репутации расы · колесо — прокрутка · P/Esc — закрыть"),
                x0 + 6, y0 + h - 12, 0xFFFFFF, false);
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int lt = leftTop(), lh = leftHeight();
        if (button == 0 && mx >= x0 + 4 && mx < x0 + LEFT_W && my >= lt && my < lt + lh) {
            int idx = (int) ((my - lt + leftScroll) / ROW_H);
            if (idx >= 0 && idx < factions.size()) {
                selected = factions.get(idx);
                histScroll = 0;
                rebuild();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (mx < x0 + LEFT_W) {
            int max = Math.max(0, factions.size() * ROW_H - leftHeight());
            leftScroll = (int) Math.max(0, Math.min(max, leftScroll - amount * 18));
        } else {
            histScroll = (int) Math.max(0, Math.min(maxHistScroll(), histScroll - amount * 3));
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (MobTalkClient.isWindowKey(keyCode, scanCode) || keyCode == GLFW.GLFW_KEY_ESCAPE) {
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
