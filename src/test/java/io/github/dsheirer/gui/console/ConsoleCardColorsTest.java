/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.gui.console;

import io.github.dsheirer.console.activity.CardActivityState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static io.github.dsheirer.gui.console.ConsoleCardColors.Palette;
import static io.github.dsheirer.gui.console.ConsoleCardColors.VisualState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for console card colour calculations and for the contrast of the console stylesheet's palettes (the looked-up
 * colours defined in console.css for the light and dark palettes).
 */
public class ConsoleCardColorsTest
{
    /**
     * Alias colours to check, including dark and saturated colours that need adjustment.
     */
    private static final int[] ALIAS_COLORS = {0xFF000000, 0x000000, 0xFF0000FF, 0x800000, 0x008000, 0xFFFF00,
        0xFFFFFF, 0xFF9447, 0x3FD6A6, 0x6A2BA8, 0x0F2440, 0x777777};
    /**
     * Additional very dark and very light alias colours.
     */
    private static final int[] EXTREME_ALIAS_COLORS = {0x050505, 0x101820, 0x000080, 0x1A0000, 0x002000, 0xFAFAFA,
        0xF0F8FF, 0xFFFFF0, 0xFFF0F5, 0xE0FFE0};
    private static final double TEXT_CONTRAST = 4.5;
    private static final double NON_TEXT_CONTRAST = 3.0;
    private static final String STYLESHEET = "console.css";
    private static final Pattern BLOCK = Pattern.compile("([^{}]+)\\{([^{}]*)\\}");
    private static final Pattern DECLARATION = Pattern.compile("(-?[a-zA-Z][\\w-]*)\\s*:\\s*([^;]+);");
    private static final Pattern TOKEN_SELECTOR = Pattern.compile(
        "\\.console-root((?:\\.[\\w-]+)*)(?:\\s+\\.card((?:\\.[\\w-]+)*))?");
    private static final Pattern RGBA = Pattern.compile(
        "rgba\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*([\\d.]+)\\s*\\)");

    /**
     * All alias colours to check
     */
    private static int[] allAliasColors()
    {
        int[] colors = Arrays.copyOf(ALIAS_COLORS, ALIAS_COLORS.length + EXTREME_ALIAS_COLORS.length);
        System.arraycopy(EXTREME_ALIAS_COLORS, 0, colors, ALIAS_COLORS.length, EXTREME_ALIAS_COLORS.length);
        return colors;
    }

    @Test
    void cardTextKeepsContrastInEveryState()
    {
        for(int aliasColor: ALIAS_COLORS)
        {
            int base = ConsoleCardColors.getBaseColor(aliasColor, "card");

            for(VisualState state: VisualState.values())
            {
                int fill = ConsoleCardColors.getPaint(base, state).fill();
                assertTrue(ConsoleCardColors.contrastRatio(fill, ConsoleCardColors.TEXT_SECONDARY_ACTIVE) >= 4.5,
                    "Expected 4.5:1 contrast for alias colour " + ConsoleCardColors.toHex(aliasColor) + " state " +
                        state + " fill " + ConsoleCardColors.toHex(fill));
            }

            int ink = ConsoleCardColors.getInkColor(base);
            assertTrue(ConsoleCardColors.contrastRatio(ink, ConsoleCardColors.WHITE) >= 4.5,
                "Expected ink colour to have 4.5:1 contrast on white for " + ConsoleCardColors.toHex(aliasColor));
        }
    }

    @Test
    void secondaryTextKeepsContrastOnCardFills()
    {
        //Idle and unlinked cards use the idle secondary text colour, all other states the active secondary colour
        for(VisualState state: List.of(VisualState.IDLE, VisualState.UNLINKED))
        {
            int fill = ConsoleCardColors.getPaint(ConsoleCardColors.getBaseColor(0, "card"), state).fill();
            assertTrue(ConsoleCardColors.contrastRatio(fill, ConsoleCardColors.TEXT_SECONDARY_IDLE) >= 4.5,
                "Expected 4.5:1 contrast for idle secondary text in state " + state);
        }

        for(int aliasColor: ALIAS_COLORS)
        {
            int base = ConsoleCardColors.getBaseColor(aliasColor, "card");

            for(VisualState state: VisualState.values())
            {
                if(state.isLive())
                {
                    int fill = ConsoleCardColors.getPaint(base, state).fill();
                    assertTrue(ConsoleCardColors.contrastRatio(fill, ConsoleCardColors.TEXT_SECONDARY_ACTIVE) >= 4.5,
                        "Expected 4.5:1 contrast for secondary text in state " + state + " fill " +
                            ConsoleCardColors.toHex(fill));
                }
            }
        }
    }

    @Test
    void lightPaletteIsUnchanged()
    {
        //The light palette is the original console palette: the palette overloads match the original methods
        for(int aliasColor: allAliasColors())
        {
            int base = ConsoleCardColors.getBaseColor(aliasColor, "card");
            assertEquals(base, ConsoleCardColors.getBaseColor(aliasColor, "card", Palette.LIGHT));
            assertEquals(ConsoleCardColors.getInkColor(base), ConsoleCardColors.getInkColor(base, Palette.LIGHT));

            for(VisualState state: VisualState.values())
            {
                ConsoleCardColors.CardPaint paint = ConsoleCardColors.getPaint(base, state, Palette.LIGHT);
                assertEquals(ConsoleCardColors.TEXT, paint.text(), "Expected dark card text in the light palette");
                assertEquals(state.isLive() ? ConsoleCardColors.TEXT_SECONDARY_ACTIVE :
                    ConsoleCardColors.TEXT_SECONDARY_IDLE, paint.secondaryText());
            }
        }

        int base = 0xFF9447;
        assertEquals(base, ConsoleCardColors.getPaint(base, VisualState.ACTIVE, Palette.LIGHT).fill());
        assertEquals(ConsoleCardColors.IDLE_FILL, ConsoleCardColors.getPaint(base, VisualState.IDLE).fill());
        assertEquals(ConsoleCardColors.ENCRYPTED_FILL, ConsoleCardColors.getPaint(base, VisualState.ENCRYPTED).fill());
    }

    @Test
    void cardColoursKeepContrastInBothPalettes()
    {
        for(Palette palette: Palette.values())
        {
            for(int aliasColor: allAliasColors())
            {
                int base = ConsoleCardColors.getBaseColor(aliasColor, "card", palette);
                String context = palette + " alias colour " + ConsoleCardColors.toHex(aliasColor) + " base " +
                    ConsoleCardColors.toHex(base);

                for(VisualState state: VisualState.values())
                {
                    ConsoleCardColors.CardPaint paint = ConsoleCardColors.getPaint(base, state, palette);
                    String stateContext = context + " state " + state + " fill " +
                        ConsoleCardColors.toHex(paint.fill());
                    assertContrast(paint.text(), paint.fill(), TEXT_CONTRAST, "card text: " + stateContext);
                    assertContrast(paint.secondaryText(), paint.fill(), TEXT_CONTRAST,
                        "secondary card text: " + stateContext);

                    if(palette.isDark() && state.isLive())
                    {
                        //The border (state indicator) against the group panel, and the collapsed group badge dot
                        assertContrast(paint.border(), 0x11161D, NON_TEXT_CONTRAST, "card border: " + stateContext);
                        assertContrast(paint.indicator(), paint.fill(), NON_TEXT_CONTRAST,
                            "badge indicator: " + stateContext);
                    }
                }

                int ink = ConsoleCardColors.getInkColor(base, palette);
                assertContrast(ink, palette.getInkBackground(), TEXT_CONTRAST, "activity log ink: " + context);
            }
        }
    }

    @Test
    void darkPaletteFollowsDesign()
    {
        int base = 0xFF9447;
        assertEquals(base, ConsoleCardColors.getBaseColor(base, "card", Palette.DARK),
            "Expected bright alias colour unchanged");

        ConsoleCardColors.CardPaint active = ConsoleCardColors.getPaint(base, VisualState.ACTIVE, Palette.DARK);
        assertEquals(base, active.fill(), "Expected alias colour fill");
        assertEquals(Palette.DARK.getActiveText(), active.text(), "Expected near-black text on the active fill");

        ConsoleCardColors.CardPaint muted = ConsoleCardColors.getPaint(base, VisualState.MUTED, Palette.DARK);
        assertTrue(muted.dashed(), "Expected dashed border for muted active card");
        assertEquals(base, muted.border(), "Expected alias colour border");
        assertEquals(ConsoleCardColors.mix(Palette.DARK.getIdleFill(), base, 0.30), muted.fill(),
            "Expected 30% alias colour mixed into the idle surface");

        ConsoleCardColors.CardPaint hang = ConsoleCardColors.getPaint(base, VisualState.HANG, Palette.DARK);
        assertEquals(ConsoleCardColors.mix(Palette.DARK.getIdleFill(), base, 0.13), hang.fill(),
            "Expected 13% alias colour tint");
        assertEquals(base, hang.border(), "Expected alias colour border");

        assertEquals(0xB794F6, ConsoleCardColors.getPaint(base, VisualState.ENCRYPTED, Palette.DARK).fill());
        assertEquals(Palette.DARK, Palette.of(true));
        assertEquals(Palette.LIGHT, Palette.of(false));
    }

    @Test
    void stylesheetPalettesKeepContrast() throws IOException
    {
        String css = readStylesheet();

        for(Palette palette: Palette.values())
        {
            Map<String,String> root = tokens(css, palette, Set.of());
            String name = palette.toString();

            //Text (4.5:1)
            String[][] textPairs = {
                {"-console-text", "-console-bg"},
                {"-console-text", "-console-strip-bg"},
                {"-console-text-secondary", "-console-strip-bg"},
                {"-console-strip-title", "-console-strip-bg"},
                {"-console-text", "-console-chip-bg"},
                {"-console-text-secondary", "-console-chip-bg"},
                {"-console-text-secondary", "-console-group-bg"},
                {"-console-text-secondary", "-console-drop-bg"},
                {"-console-header-text", "-console-header-bg"},
                {"-console-header-text", "-console-header-button-bg"},
                {"-console-header-text", "-console-header-button-hover"},
                {"-console-selected-text", "-console-selected-bg"},
                {"-console-selected-text", "-console-selected-hover"},
                {"-console-text", "-console-hint-bg"},
                {"-console-text", "-console-warning-bg"},
                {"-console-hint-button-text", "-console-hint-button-bg"},
                {"-console-hint-button-text", "-console-hint-button-hover"},
                {"-console-group-header-text", "-console-group-header-bg"},
                {"-console-group-header-text", "-console-group-button-hover"},
                {"-console-group-active", "-console-group-header-bg"},
                {"-console-group-summary-idle", "-console-group-header-bg"},
                {"-console-add-card-text", "-console-add-card-bg"},
                {"-console-add-card-text", "-console-add-card-hover"},
                {"-console-log-title-text", "-console-log-title-bg"},
                {"-console-log-mode-text", "-console-log-mode-bg"},
                {"-console-log-mode-text", "-console-log-mode-hover"},
                {"-console-log-mode-selected-text", "-console-log-mode-selected-bg"},
                {"-console-log-text", "-console-log-header-bg"},
                {"-console-log-text", "-console-log-bg"},
                {"-console-log-audio", "-console-log-bg"},
                {"-console-log-muted", "-console-log-bg"},
                {"-console-log-encrypted", "-console-log-bg"},
                {"-console-log-off-console", "-console-log-bg"},
                {"-console-log-ignored", "-console-log-bg"},
                {"-console-log-clear-text", "-console-log-clear-bg"}};

            for(String[] pair: textPairs)
            {
                assertContrast(color(root, pair[0]), color(root, pair[1]), TEXT_CONTRAST,
                    name + " " + pair[0] + " on " + pair[1]);
            }

            assertEquals(palette.getInkBackground(), color(root, palette.isDark() ? "-console-log-selected" :
                "-console-log-bg"), "Expected the ink background to match the stylesheet");
            assertEquals(palette.isDark() ? 0xE6EDF3 : ConsoleCardColors.TEXT, color(root, "-console-text"));

            if(palette.isDark())
            {
                //Selected activity log rows (the light palette's selected rows use the theme's selection colours)
                for(String text: List.of("-console-log-text", "-console-log-audio", "-console-log-muted",
                    "-console-log-encrypted", "-console-log-off-console", "-console-log-ignored"))
                {
                    assertContrast(color(root, text), color(root, "-console-log-selected"), TEXT_CONTRAST,
                        name + " " + text + " on the selected log row");
                }

                //Non-text indicators and control boundaries (3:1)
                String[][] nonTextPairs = {
                    {"-console-header-button-border", "-console-header-bg"},
                    {"-console-focus", "-console-header-bg"},
                    {"-console-switch-off", "-console-header-button-bg"},
                    {"-console-switch-on", "-console-header-button-bg"},
                    {"-console-switch-thumb", "-console-switch-off"},
                    {"-console-switch-thumb", "-console-switch-on"},
                    {"-console-dot-searching", "-console-chip-bg"},
                    {"-console-dot-locked", "-console-chip-bg"},
                    {"-console-dot-stopped", "-console-chip-bg"},
                    {"-console-hint-border", "-console-bg"},
                    {"-console-warning-border", "-console-bg"},
                    {"-console-group-button-border", "-console-group-header-bg"},
                    {"-console-group-mute-border", "-console-group-header-bg"},
                    {"-console-group-muted-bg", "-console-group-header-bg"},
                    {"-console-group-muted-icon", "-console-group-muted-bg"},
                    {"-console-drop-border", "-console-bg"},
                    {"-console-add-card-border", "-console-group-bg"},
                    {"-console-group-muted-card-accent", "-console-group-muted-card-bg"},
                    {"-console-mute-on-icon", "-console-mute-on-bg"},
                    {"-console-log-mode-border", "-console-log-title-bg"}};

                for(String[] pair: nonTextPairs)
                {
                    assertContrast(color(root, pair[0]), color(root, pair[1]), NON_TEXT_CONTRAST,
                        name + " " + pair[0] + " on " + pair[1]);
                }
            }
        }
    }

    @Test
    void stylesheetCardTokensKeepContrast() throws IOException
    {
        String css = readStylesheet();

        for(Palette palette: Palette.values())
        {
            for(VisualState state: VisualState.values())
            {
                for(boolean clearAlert: state.isLive() ? List.of(false, true) : List.of(false))
                {
                    List<String> classes = new ArrayList<>(List.of("card", state.getStyleClass()));

                    if(state.isLive())
                    {
                        classes.add("live");
                    }

                    if(clearAlert)
                    {
                        classes.add("clear-alert");
                    }

                    Map<String,String> card = tokens(css, palette, Set.copyOf(classes));

                    for(int aliasColor: allAliasColors())
                    {
                        int base = ConsoleCardColors.getBaseColor(aliasColor, "card", palette);
                        int fill = ConsoleCardColors.getPaint(base, state, palette).fill();
                        String context = palette + " " + state + (clearAlert ? " clear alert" : "") + " fill " +
                            ConsoleCardColors.toHex(fill) + ": ";

                        assertContrast(color(card, "-card-pill-text"), color(card, "-card-pill-bg"), TEXT_CONTRAST,
                            context + "status pill");

                        if(state == VisualState.UNLINKED)
                        {
                            assertContrast(color(card, "-card-edit-text"), color(card, "-card-edit-bg", fill),
                                TEXT_CONTRAST, context + "re-link and remove buttons");
                            continue;
                        }

                        int controlBackground = color(card, "-card-control-bg", fill);
                        assertContrast(color(card, "-card-control-text"), controlBackground, TEXT_CONTRAST,
                            context + "pan button text");
                        assertContrast(color(card, "-card-selected-text"), color(card, "-card-selected-bg"),
                            TEXT_CONTRAST, context + "selected pan button text");
                        assertContrast(color(card, "-card-edit-text"), color(card, "-card-edit-bg", fill),
                            TEXT_CONTRAST, context + "edit (remove) button text");
                        assertContrast(color(card, "-card-handle"), fill, NON_TEXT_CONTRAST, context + "drag handle");
                        assertContrast(color(card, "-card-lock"), fill, NON_TEXT_CONTRAST, context + "lock icon");

                        if(palette.isDark())
                        {
                            assertContrast(color(card, "-card-control-border", fill), fill, NON_TEXT_CONTRAST,
                                context + "control border");
                            assertContrast(color(card, "-card-thumb"), fill, NON_TEXT_CONTRAST,
                                context + "volume thumb");
                            assertContrast(color(card, "-card-selected-bg"), controlBackground, NON_TEXT_CONTRAST,
                                context + "selected pan segment");
                        }
                    }
                }
            }
        }
    }

    @Test
    void stylesheetAliasPickerKeepsContrast() throws IOException
    {
        Map<String,Map<String,String>> blocks = blocks(readStylesheet());
        int light = parseColor(blocks.get(".alias-picker .table-row-cell.assigned .text").get("-fx-fill"), 0);
        int dark = parseColor(blocks.get(".alias-picker.dark .table-row-cell.assigned .text").get("-fx-fill"), 0);

        //Light: the Modena/FlatLaf light table rows; dark: the application's dark stylesheet table rows
        assertContrast(light, 0xFFFFFF, TEXT_CONTRAST, "alias picker assigned row (light)");
        assertContrast(light, 0xF2F2F2, TEXT_CONTRAST, "alias picker assigned alternate row (light)");
        assertContrast(dark, 0x313335, TEXT_CONTRAST, "alias picker assigned row (dark)");
        assertContrast(dark, 0x353739, TEXT_CONTRAST, "alias picker assigned alternate row (dark)");
    }

    @Test
    void missingAliasColourUsesPalette()
    {
        int base = ConsoleCardColors.getBaseColor(0xFF000000, "card-1");
        assertNotEquals(0, base, "Expected palette colour for black alias colour");
        assertEquals(base, ConsoleCardColors.getBaseColor(0, "card-1"), "Expected stable palette colour per key");
        assertEquals(0xFF9447, ConsoleCardColors.getBaseColor(0xFFFF9447, "card-1"), "Expected alias colour unchanged");
    }

    @Test
    void visualStateCombinesMuteAndLink()
    {
        assertEquals(VisualState.ACTIVE, VisualState.of(CardActivityState.ACTIVE, false, true));
        assertEquals(VisualState.MUTED, VisualState.of(CardActivityState.ACTIVE, true, true));
        assertEquals(VisualState.ENCRYPTED, VisualState.of(CardActivityState.ENCRYPTED, true, true));
        assertEquals(VisualState.HANG, VisualState.of(CardActivityState.HANG, true, true));
        assertEquals(VisualState.IDLE, VisualState.of(CardActivityState.IDLE, true, true));
        assertEquals(VisualState.UNLINKED, VisualState.of(CardActivityState.ACTIVE, false, false));
    }

    @Test
    void mutedFillIsDashedTint()
    {
        int base = 0xFF9447;
        ConsoleCardColors.CardPaint muted = ConsoleCardColors.getPaint(base, VisualState.MUTED);
        assertTrue(muted.dashed(), "Expected dashed border for muted active card");
        assertEquals(ConsoleCardColors.mix(base, ConsoleCardColors.IDLE_FILL, 0.62), muted.fill());
    }

    /**
     * Asserts the minimum contrast ratio between two colours.
     */
    private static void assertContrast(int foreground, int background, double minimum, String description)
    {
        double ratio = ConsoleCardColors.contrastRatio(foreground, background);
        assertTrue(ratio >= minimum, String.format("Expected %.1f:1 contrast for %s, %s on %s is %.2f:1", minimum,
            description, ConsoleCardColors.toHex(foreground), ConsoleCardColors.toHex(background), ratio));
    }

    /**
     * Reads the console stylesheet (without comments).
     */
    private static String readStylesheet() throws IOException
    {
        try(InputStream in = ConsoleWindow.class.getResourceAsStream(STYLESHEET))
        {
            assertNotNull(in, "Expected the console stylesheet");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "");
        }
    }

    /**
     * Stylesheet rules: declarations by selector (each selector of a selector group), in stylesheet order.
     */
    private static Map<String,Map<String,String>> blocks(String css)
    {
        Map<String,Map<String,String>> blocks = new LinkedHashMap<>();
        Matcher block = BLOCK.matcher(css);

        while(block.find())
        {
            Map<String,String> declarations = new LinkedHashMap<>();
            Matcher declaration = DECLARATION.matcher(block.group(2));

            while(declaration.find())
            {
                declarations.put(declaration.group(1), declaration.group(2).trim());
            }

            for(String selector: block.group(1).split(","))
            {
                blocks.computeIfAbsent(selector.trim().replaceAll("\\s+", " "), key -> new LinkedHashMap<>())
                    .putAll(declarations);
            }
        }

        return blocks;
    }

    /**
     * Looked-up colours ('-console-*' and '-card-*' tokens) that apply to the console root (empty card classes) or a
     * card with the style classes, in the palette: the token rules that match, applied in stylesheet order (the
     * stylesheet orders its token rules so that later matching rules have equal or higher specificity).
     */
    private static Map<String,String> tokens(String css, Palette palette, Set<String> cardClasses)
    {
        Map<String,String> tokens = new LinkedHashMap<>();
        Set<String> rootClasses = palette.isDark() ? Set.of("console-root", "dark") : Set.of("console-root");

        for(Map.Entry<String,Map<String,String>> entry: blocks(css).entrySet())
        {
            Matcher selector = TOKEN_SELECTOR.matcher(entry.getKey());

            if(!selector.matches())
            {
                continue;
            }

            Set<String> root = classes("console-root" + selector.group(1));
            boolean cardRule = selector.group(2) != null;

            if(!rootClasses.containsAll(root) || (cardRule && (cardClasses.isEmpty() ||
                !cardClasses.containsAll(classes("card" + selector.group(2))))))
            {
                continue;
            }

            entry.getValue().forEach((key, value) -> {
                if(key.startsWith("-console-") || key.startsWith("-card-"))
                {
                    tokens.put(key, value);
                }
            });
        }

        return tokens;
    }

    private static Set<String> classes(String classes)
    {
        return Set.of(classes.split("\\."));
    }

    /**
     * Resolved RGB value of an opaque token.
     */
    private static int color(Map<String,String> tokens, String token)
    {
        return color(tokens, token, -1);
    }

    /**
     * Resolved RGB value of a token, with translucent (rgba) colours composited over the background.
     */
    private static int color(Map<String,String> tokens, String token, int background)
    {
        String value = tokens.get(token);
        assertNotNull(value, "Expected stylesheet token " + token);

        //Tokens that refer to other tokens
        while(value.startsWith("-"))
        {
            String reference = value;
            value = tokens.get(reference);
            assertNotNull(value, "Expected stylesheet token " + reference);
        }

        return parseColor(value, background);
    }

    /**
     * Parses a #RRGGBB or rgba() colour, compositing a translucent colour over the background.
     */
    private static int parseColor(String value, int background)
    {
        assertNotNull(value, "Expected a colour");

        if(value.startsWith("#"))
        {
            return Integer.parseInt(value.substring(1), 16);
        }

        Matcher rgba = RGBA.matcher(value);
        assertTrue(rgba.matches(), "Expected #RRGGBB or rgba() colour: " + value);
        assertTrue(background >= 0, "Expected a background for translucent colour " + value);
        int rgb = (Integer.parseInt(rgba.group(1)) << 16) | (Integer.parseInt(rgba.group(2)) << 8) |
            Integer.parseInt(rgba.group(3));
        return ConsoleCardColors.mix(background, rgb, Double.parseDouble(rgba.group(4)));
    }
}
