package org.xsaitama1.nolifetracker.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Small formatting helpers shared by the commands, the tab list and the kill announcements. */
public final class TextUtil {

    /**
     * Colour and style codes accepted after {@code &} in configurable text.
     *
     * <p>Split by kind because {@code ChatFormatting} no longer exposes {@code isColor()} --
     * 26.2 trimmed the enum down to the code character and nothing else -- so which codes
     * reset the active styles has to be stated here rather than asked of the enum.
     */
    private static final String LEGACY_COLOUR_CODES = "0123456789abcdef";
    private static final String LEGACY_STYLE_CODES = "klmno";
    private static final String LEGACY_CODES = LEGACY_COLOUR_CODES + LEGACY_STYLE_CODES + "r";

    private TextUtil() {
    }

    /**
     * Turns a registry id into something readable: {@code minecraft:zombie_villager}
     * or {@code zombie_villager} both become {@code Zombie Villager}.
     */
    public static String prettify(String rawId) {
        if (rawId == null || rawId.isEmpty()) {
            return rawId;
        }

        String path = rawId.substring(rawId.indexOf(':') + 1);
        StringBuilder out = new StringBuilder(path.length());
        for (String word : path.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /**
     * Vanilla records play time in ticks. Renders it the way a player expects to read it.
     */
    public static String formatPlayTime(int ticks) {
        long totalSeconds = Math.max(0, ticks) / 20L;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;

        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m " + (totalSeconds % 60) + "s";
    }

    /**
     * Parses the {@code &}-code colour syntax server admins expect in config strings.
     *
     * <p>Following vanilla's legacy behaviour, a colour code clears any active styles
     * and {@code &r} resets everything. Unknown codes are left as literal text.
     */
    public static MutableComponent legacy(String input) {
        MutableComponent root = Component.empty();
        if (input == null || input.isEmpty()) {
            return root;
        }

        StringBuilder buffer = new StringBuilder();
        Set<ChatFormatting> styles = new LinkedHashSet<>();
        ChatFormatting colour = null;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            boolean isCode = (c == '&' || c == '§')
                    && i + 1 < input.length()
                    && LEGACY_CODES.indexOf(Character.toLowerCase(input.charAt(i + 1))) >= 0;

            if (!isCode) {
                buffer.append(c);
                continue;
            }

            flush(root, buffer, colour, styles);

            char code = Character.toLowerCase(input.charAt(++i));
            ChatFormatting format = ChatFormatting.getByCode(code);
            if (format == null) {
                continue;
            }
            if (format == ChatFormatting.RESET) {
                colour = null;
                styles.clear();
            } else if (LEGACY_COLOUR_CODES.indexOf(code) >= 0) {
                colour = format;
                styles.clear();
            } else {
                styles.add(format);
            }
        }

        flush(root, buffer, colour, styles);
        return root;
    }

    private static void flush(MutableComponent root, StringBuilder buffer,
                              ChatFormatting colour, Set<ChatFormatting> styles) {
        if (buffer.isEmpty()) {
            return;
        }

        MutableComponent part = Component.literal(buffer.toString());
        if (colour != null) {
            part.withStyle(colour);
        }
        for (ChatFormatting style : styles) {
            part.withStyle(style);
        }

        root.append(part);
        buffer.setLength(0);
    }
}
