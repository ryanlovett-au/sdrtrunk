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

/**
 * Console card colour calculations (fill and border for each card state), using 24-bit RGB integer colours so that
 * the calculations do not depend on the JavaFX toolkit.
 *
 * The card's base colour is the alias colour, or an entry from the default palette when the alias has no colour
 * (zero or black).  Card text is always dark, so the base colour is lightened as needed so that the card's secondary
 * text keeps a contrast ratio of at least 4.5:1 (WCAG AA) on the active fill.  The border ('ink') colour is a darker
 * shade of the base colour that also keeps 4.5:1 contrast on white, so it can be used for text (e.g. activity log).
 */
public final class ConsoleCardColors
{
    public static final int TEXT = 0x10233A;
    public static final int TEXT_SECONDARY_ACTIVE = 0x1F2E3F;
    public static final int IDLE_FILL = 0xF5F7FA;
    public static final int IDLE_BORDER = 0xAEB7C2;
    public static final int ENCRYPTED_FILL = 0xC7B5FF;
    public static final int ENCRYPTED_BORDER = 0x5B3FB8;
    public static final int UNLINKED_FILL = 0xE4E8ED;
    public static final int UNLINKED_BORDER = 0x8A96A3;
    public static final int WHITE = 0xFFFFFF;
    public static final double MINIMUM_CONTRAST = 4.5;
    public static final double MUTED_TINT = 0.38;
    public static final double HANG_TINT = 0.16;

    /**
     * Default card colours for aliases that do not have a colour.
     */
    private static final int[] DEFAULT_PALETTE = {0xFF9447, 0x6CB4FF, 0x3FD6A6, 0xF5D547, 0xD9A8FF, 0x8EDB6B,
        0xFFBF5E, 0x9CCBFF};

    private ConsoleCardColors()
    {
    }

    /**
     * Visual state of a card: the activity state combined with the card's mute setting and link status.
     */
    public enum VisualState
    {
        IDLE("IDLE", "state-idle"),
        ACTIVE("RX", "state-active"),
        MUTED("RX MUTED", "state-muted"),
        ENCRYPTED("ENCRYPTED", "state-encrypted"),
        HANG("HANG", "state-hang"),
        UNLINKED("UNLINKED", "state-unlinked");

        private final String mLabel;
        private final String mStyleClass;

        VisualState(String label, String styleClass)
        {
            mLabel = label;
            mStyleClass = styleClass;
        }

        /**
         * Status pill label
         */
        public String getLabel()
        {
            return mLabel;
        }

        /**
         * CSS style class applied to the card for this state
         */
        public String getStyleClass()
        {
            return mStyleClass;
        }

        /**
         * Indicates if the card shows (or recently showed) call activity.
         */
        public boolean isLive()
        {
            return this == ACTIVE || this == MUTED || this == ENCRYPTED || this == HANG;
        }

        /**
         * Visual state for the activity state and card settings.
         * @param state of card activity
         * @param muted card setting
         * @param linked true when the card's alias exists
         * @return visual state
         */
        public static VisualState of(CardActivityState state, boolean muted, boolean linked)
        {
            if(!linked)
            {
                return UNLINKED;
            }

            if(state == null)
            {
                return IDLE;
            }

            return switch(state)
            {
                case ACTIVE -> muted ? MUTED : ACTIVE;
                case ENCRYPTED -> ENCRYPTED;
                case HANG -> HANG;
                default -> IDLE;
            };
        }
    }

    /**
     * Fill and border colours for a card.
     * @param fill RGB fill colour
     * @param border RGB border colour
     * @param dashed true for a dashed border
     */
    public record CardPaint(int fill, int border, boolean dashed)
    {
    }

    /**
     * Base (active) colour for a card.
     * @param aliasColor alias colour (RGB or ARGB), where zero or black means no colour
     * @param key used to select a default palette colour when the alias has no colour (e.g. the card identifier)
     * @return RGB base colour that keeps the minimum contrast with dark card text.
     */
    public static int getBaseColor(int aliasColor, String key)
    {
        int rgb = aliasColor & 0xFFFFFF;

        if(rgb == 0)
        {
            int index = key != null ? Math.floorMod(key.hashCode(), DEFAULT_PALETTE.length) : 0;
            rgb = DEFAULT_PALETTE[index];
        }

        return ensureContrast(rgb, TEXT_SECONDARY_ACTIVE, MINIMUM_CONTRAST);
    }

    /**
     * Darker shade of the base colour for borders and coloured text, with at least the minimum contrast on white.
     * @param base RGB colour
     * @return RGB ink colour
     */
    public static int getInkColor(int base)
    {
        double amount = 0.45;
        int ink = mix(base, 0x000000, amount);

        while(contrastRatio(ink, WHITE) < MINIMUM_CONTRAST && amount < 1.0)
        {
            amount = Math.min(1.0, amount + 0.05);
            ink = mix(base, 0x000000, amount);
        }

        return ink;
    }

    /**
     * Card fill and border for the visual state.
     * @param base RGB base colour (see getBaseColor())
     * @param state visual state
     * @return paint
     */
    public static CardPaint getPaint(int base, VisualState state)
    {
        int ink = getInkColor(base);

        return switch(state)
        {
            case ACTIVE -> new CardPaint(base, ink, false);
            case MUTED -> new CardPaint(mix(base, IDLE_FILL, 1.0 - MUTED_TINT), ink, true);
            case ENCRYPTED -> new CardPaint(ENCRYPTED_FILL, ENCRYPTED_BORDER, false);
            case HANG -> new CardPaint(mix(base, IDLE_FILL, 1.0 - HANG_TINT), ink, false);
            case UNLINKED -> new CardPaint(UNLINKED_FILL, UNLINKED_BORDER, true);
            default -> new CardPaint(IDLE_FILL, IDLE_BORDER, false);
        };
    }

    /**
     * Lightens the colour (mixes it with white) until it has at least the minimum contrast ratio with the text colour.
     * @param rgb colour to adjust
     * @param text RGB text colour (dark)
     * @param minimum contrast ratio
     * @return adjusted colour
     */
    public static int ensureContrast(int rgb, int text, double minimum)
    {
        int adjusted = rgb;
        double amount = 0.0;

        while(contrastRatio(adjusted, text) < minimum && amount < 1.0)
        {
            amount = Math.min(1.0, amount + 0.05);
            adjusted = mix(rgb, WHITE, amount);
        }

        return adjusted;
    }

    /**
     * Mixes two colours.
     * @param from RGB colour
     * @param to RGB colour
     * @param amount of the 'to' colour, 0.0 (all 'from') to 1.0 (all 'to')
     * @return mixed RGB colour
     */
    public static int mix(int from, int to, double amount)
    {
        int r = mixChannel((from >> 16) & 0xFF, (to >> 16) & 0xFF, amount);
        int g = mixChannel((from >> 8) & 0xFF, (to >> 8) & 0xFF, amount);
        int b = mixChannel(from & 0xFF, to & 0xFF, amount);
        return (r << 16) | (g << 8) | b;
    }

    private static int mixChannel(int from, int to, double amount)
    {
        return (int)Math.round(from + (to - from) * amount);
    }

    /**
     * WCAG contrast ratio between two colours.
     * @param a RGB colour
     * @param b RGB colour
     * @return contrast ratio in the range 1.0 - 21.0
     */
    public static double contrastRatio(int a, int b)
    {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /**
     * WCAG relative luminance of the colour.
     */
    private static double luminance(int rgb)
    {
        return 0.2126 * linear((rgb >> 16) & 0xFF) + 0.7152 * linear((rgb >> 8) & 0xFF) + 0.0722 * linear(rgb & 0xFF);
    }

    private static double linear(int channel)
    {
        double value = channel / 255.0;
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    /**
     * CSS hex colour string (e.g. #FF9447).
     */
    public static String toHex(int rgb)
    {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }
}
