package org.xsaitama1.nolifetracker.util;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Small formatting helpers shared by the commands, the tab list and the kill announcements. */
public final class TextUtil {

    /** Colour and style codes accepted after {@code &} in configurable text. */
    private static final String LEGACY_CODES = "0123456789abcdefklmnor";

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
    public static MutableText legacy(String input) {
        MutableText root = Text.empty();
        if (input == null || input.isEmpty()) {
            return root;
        }

        StringBuilder buffer = new StringBuilder();
        Set<Formatting> styles = new LinkedHashSet<>();
        Formatting colour = null;

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

            Formatting format = Formatting.byCode(Character.toLowerCase(input.charAt(++i)));
            if (format == null) {
                continue;
            }
            if (format == Formatting.RESET) {
                colour = null;
                styles.clear();
            } else if (format.isColor()) {
                colour = format;
                styles.clear();
            } else {
                styles.add(format);
            }
        }

        flush(root, buffer, colour, styles);
        return root;
    }

    private static void flush(MutableText root, StringBuilder buffer, Formatting colour, Set<Formatting> styles) {
        if (buffer.isEmpty()) {
            return;
        }

        MutableText part = Text.literal(buffer.toString());
        if (colour != null) {
            part.formatted(colour);
        }
        for (Formatting style : styles) {
            part.formatted(style);
        }

        root.append(part);
        buffer.setLength(0);
    }
}
