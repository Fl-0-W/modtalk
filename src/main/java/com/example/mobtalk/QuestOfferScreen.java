package com.example.mobtalk;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Окно, которое всплывает, когда житель предлагает поручение: принять или отказаться. */
public class QuestOfferScreen extends Screen {
    private final Quests.Quest quest;
    private final UUID giver;
    private boolean decided;
    private int x0, y0, w, h;
    private List<OrderedText> lines = new ArrayList<>();

    public QuestOfferScreen(Quests.Quest quest, UUID giver) {
        super(Fonts.ui("Поручение"));
        this.quest = quest;
        this.giver = giver;
    }

    @Override
    protected void init() {
        w = Math.min(width - 20, 320);
        int inner = w - 24;
        lines = new ArrayList<>();
        lines.addAll(Fonts.wrap(textRenderer, "§7Предлагает: §f" + quest.giverName, inner));
        lines.addAll(Fonts.wrap(textRenderer, "§7Задание: §f" + Quests.goal(quest), inner));
        lines.addAll(Fonts.wrap(textRenderer, "§7Награда: §a" + Quests.reward(quest), inner));
        lines.addAll(Fonts.wrap(textRenderer, "§8Принятые задания видны в окне заданий (N / Т).", inner));
        h = 44 + lines.size() * 14 + 34;
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;

        int by = y0 + h - 28;
        addDrawableChild(ButtonWidget.builder(Fonts.ui("§aПринять"), b -> decide(true))
                .dimensions(x0 + 12, by, (w - 32) / 2, 20).build());
        addDrawableChild(ButtonWidget.builder(Fonts.ui("§cОтказаться"), b -> decide(false))
                .dimensions(x0 + 20 + (w - 32) / 2, by, (w - 32) / 2, 20).build());
    }

    private void decide(boolean accept) {
        if (decided) return;
        decided = true;
        if (accept) {
            if (Quests.accept(giver)) {
                if (client != null && client.player != null)
                    client.player.sendMessage(Text.literal("§a[Задание принято] «" + quest.title + "» — смотрите окно заданий (N)"), false);
            } else if (client != null && client.player != null) {
                client.player.sendMessage(Text.literal("§c[Не удалось принять: слишком много активных заданий]"), false);
            }
        } else {
            Quests.decline(giver);
        }
        close();
    }

    /** Закрытие окна без выбора (Esc) считается отказом. */
    @Override
    public void removed() {
        if (!decided) {
            decided = true;
            Quests.decline(giver);
        }
        super.removed();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);
        ctx.fill(x0, y0, x0 + w, y0 + h, 0xE0101018);
        ctx.fill(x0, y0, x0 + w, y0 + 18, 0xFF202030);
        ctx.drawCenteredTextWithShadow(textRenderer, Fonts.ui("Поручение: «" + quest.title + "»"),
                x0 + w / 2, y0 + 5, 0xFFD060);
        int y = y0 + 26;
        for (OrderedText l : lines) {
            ctx.drawText(textRenderer, l, x0 + 12, y, 0xFFFFFF, false);
            y += 14;
        }
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
