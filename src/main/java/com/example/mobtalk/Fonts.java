package com.example.mobtalk;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;

import java.util.List;

/**
 * Шрифт окон мода: пиксельный Pixelify Sans (SIL OFL) с кириллицей, лежит в assets/mobtalk/font.
 * Цветовые коды «§» в строках работают как обычно. Чтобы вернуть ванильный шрифт, поставьте USE_CUSTOM = false.
 */
public final class Fonts {
    private Fonts() {}

    public static final boolean USE_CUSTOM = true;
    public static final Identifier UI = new Identifier("mobtalk", "ui");

    /** Текст шрифтом мода. */
    public static Text ui(String s) {
        Text t = Text.literal(s);
        return USE_CUSTOM ? ((net.minecraft.text.MutableText) t).setStyle(Style.EMPTY.withFont(UI)) : t;
    }

    public static List<OrderedText> wrap(TextRenderer tr, String s, int width) {
        return tr.wrapLines(ui(s), width);
    }

    /** Строка, обрезанная по ширине с учётом шрифта мода. */
    public static OrderedText fit(TextRenderer tr, String s, int width) {
        return Language.getInstance().reorder(tr.trimToWidth(ui(s), width));
    }
}
