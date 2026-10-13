/*
 * *****************************************************************************
 * Copyright (C) 2026 Ryan Lovett
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Console card colour calculations (fill, border and text colours for each card state) in the light or dark console
 * palette, using 24-bit RGB integer colours so that the calculations do not depend on the JavaFX toolkit.
 *
 * The card's base colour is the alias colour, or an entry from the default palette when the alias has no colour
 * (zero or black).  Active (receiving) and encrypted cards are filled with a bright colour and always have dark text,
 * so the base colour is lightened as needed so that the card's secondary text keeps a contrast ratio of at least 4.5:1
 * (WCAG AA) on the active fill.
 *
 * Hang cards follow the colour of the call before it ended: a tint of the base colour (active or muted call), of the
 * encrypted colour with the encrypted border (encrypted call, HANG_ENCRYPTED), or of the clear audio alert red with a
 * red border (call with a clear audio alert, HANG_CLEAR).
 *
 * Light palette: all card text is dark, muted and hang cards are light tints of the base colour, and the border ('ink')
 * colour is a darker shade of the base colour that also keeps 4.5:1 contrast on white, so it can be used for text
 * (e.g. activity log).
 *
 * Dark palette: idle, muted, hang and unlinked cards are dark surfaces with light text.  Muted and hang cards mix the
 * base colour into the idle surface (reduced as needed to keep 4.5:1 contrast with the light text) with a base colour
 * border, and the ink colour is the base colour, lightened as needed to keep 4.5:1 contrast on the dark activity log.
 *
 * The other console colours (window, header, groups, controls, status pills) are JavaFX looked-up colours defined in
 * console.css for each palette (style class 'dark' on the console root for the dark palette).
 */
public final class ConsoleCardColors
{
    public static final int TEXT = 0x10233A;
    /**
     * Secondary card text (talkgroup, recent talkers, site, volume value) on idle cards (see console.css '.sub').
     */
    public static final int TEXT_SECONDARY_IDLE = 0x4A5868;
    /**
     * Secondary card text on active, muted, encrypted and hang cards (see console.css '.card.live .sub').
     */
    public static final int TEXT_SECONDARY_ACTIVE = 0x1F2E3F;
    public static final int IDLE_FILL = 0xF5F7FA;
    public static final int IDLE_BORDER = 0xAEB7C2;
    public static final int ENCRYPTED_FILL = 0xC7B5FF;
    public static final int ENCRYPTED_BORDER = 0x5B3FB8;
    /**
     * Clear audio (encryption slip) alert colour: card border, and the tint of a hanging card whose call raised the
     * alert.
     */
    public static final int CLEAR_ALERT = 0xD92D20;
    /**
     * Clear audio alert status pill (and collapsed group status) fill, with white text.
     */
    public static final int CLEAR_ALERT_FILL = 0xB42318;
    public static final int UNLINKED_FILL = 0xE4E8ED;
    public static final int UNLINKED_BORDER = 0x8A96A3;
    public static final int WHITE = 0xFFFFFF;
    public static final double MINIMUM_CONTRAST = 4.5;
    public static final double MUTED_TINT = 0.38;
    public static final double HANG_TINT = 0.16;

    /**
     * Default card colours for aliases that do not have a colour.
     */
    /**
     * Calculated paints by palette, visual state and base colour (paints are requested for every card state change).
     */
    private static final Map<Long,CardPaint> PAINT_CACHE = new ConcurrentHashMap<>();
    private static final int[] DEFAULT_PALETTE = {0xFF9447, 0x6CB4FF, 0x3FD6A6, 0xF5D547, 0xD9A8FF, 0x8EDB6B,
        0xFFBF5E, 0x9CCBFF};

    private ConsoleCardColors()
    {
    }

    /**
     * Console colour palette, selected by the active sdrtrunk theme (light or dark).  The values that are shared with
     * console.css (card surfaces and card text) must match the stylesheet's looked-up colours for the palette.
     */
    public enum Palette
    {
        LIGHT(false, TEXT, TEXT_SECONDARY_IDLE, TEXT, TEXT_SECONDARY_ACTIVE, TEXT, TEXT_SECONDARY_ACTIVE, IDLE_FILL,
            IDLE_BORDER, ENCRYPTED_FILL, ENCRYPTED_BORDER, UNLINKED_FILL, UNLINKED_BORDER, MUTED_TINT, HANG_TINT,
            WHITE),
        DARK(true, 0xE6EDF3, 0x9FB0C0, 0x0B0E13, 0x262B31, 0xE6EDF3, 0xC9D4DE, 0x161C24, 0x283140, 0xB794F6,
            0xB794F6, 0x1A2029, 0x5C6B7E, 0.30, 0.13, 0x1E2A3A);

        /**
         * Style class of the console root (and console dialogs) for the dark palette.
         */
        public static final String DARK_STYLE_CLASS = "dark";

        private final boolean mDark;
        private final int mIdleText;
        private final int mIdleSecondaryText;
        private final int mActiveText;
        private final int mActiveSecondaryText;
        private final int mTintText;
        private final int mTintSecondaryText;
        private final int mIdleFill;
        private final int mIdleBorder;
        private final int mEncryptedFill;
        private final int mEncryptedBorder;
        private final int mUnlinkedFill;
        private final int mUnlinkedBorder;
        private final double mMutedTint;
        private final double mHangTint;
        private final int mInkBackground;

        /**
         * Constructs an instance
         * @param dark palette
         * @param idleText card text on idle and unlinked cards
         * @param idleSecondaryText secondary text on idle and unlinked cards
         * @param activeText card text on active and encrypted cards
         * @param activeSecondaryText secondary text on active and encrypted cards
         * @param tintText card text on muted and hang cards
         * @param tintSecondaryText secondary text on muted and hang cards
         * @param idleFill idle card fill
         * @param idleBorder idle card border
         * @param encryptedFill encrypted card fill
         * @param encryptedBorder encrypted card border
         * @param unlinkedFill unlinked card fill
         * @param unlinkedBorder unlinked card border
         * @param mutedTint amount of the base colour in the muted card fill
         * @param hangTint amount of the base colour in the hang card fill
         * @param inkBackground activity log background (the lowest contrast one) for the ink colour
         */
        Palette(boolean dark, int idleText, int idleSecondaryText, int activeText, int activeSecondaryText,
                int tintText, int tintSecondaryText, int idleFill, int idleBorder, int encryptedFill,
                int encryptedBorder, int unlinkedFill, int unlinkedBorder, double mutedTint, double hangTint,
                int inkBackground)
        {
            mDark = dark;
            mIdleText = idleText;
            mIdleSecondaryText = idleSecondaryText;
            mActiveText = activeText;
            mActiveSecondaryText = activeSecondaryText;
            mTintText = tintText;
            mTintSecondaryText = tintSecondaryText;
            mIdleFill = idleFill;
            mIdleBorder = idleBorder;
            mEncryptedFill = encryptedFill;
            mEncryptedBorder = encryptedBorder;
            mUnlinkedFill = unlinkedFill;
            mUnlinkedBorder = unlinkedBorder;
            mMutedTint = mutedTint;
            mHangTint = hangTint;
            mInkBackground = inkBackground;
        }

        /**
         * Palette for the theme.
         * @param dark true when the active sdrtrunk theme is dark
         */
        public static Palette of(boolean dark)
        {
            return dark ? DARK : LIGHT;
        }

        /**
         * Indicates if this is the dark palette.
         */
        public boolean isDark()
        {
            return mDark;
        }

        /**
         * Card text on active and encrypted cards (the bright card fills).
         */
        public int getActiveText()
        {
            return mActiveText;
        }

        /**
         * Secondary card text on active and encrypted cards.
         */
        public int getActiveSecondaryText()
        {
            return mActiveSecondaryText;
        }

        /**
         * Idle card fill
         */
        public int getIdleFill()
        {
            return mIdleFill;
        }

        /**
         * Background against which the ink colour keeps the minimum contrast (activity log rows).
         */
        public int getInkBackground()
        {
            return mInkBackground;
        }
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
        /**
         * Hanging after an encrypted call.
         */
        HANG_ENCRYPTED("HANG", "state-hang"),
        /**
         * Hanging after a call that raised a clear audio (encryption slip) alert.
         */
        HANG_CLEAR("HANG", "state-hang"),
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
            return this == ACTIVE || this == MUTED || this == ENCRYPTED || isHang();
        }

        /**
         * Indicates if this is a hang state (HANG, HANG_ENCRYPTED or HANG_CLEAR).
         */
        public boolean isHang()
        {
            return this == HANG || this == HANG_ENCRYPTED || this == HANG_CLEAR;
        }

        /**
         * Visual state for the activity state, the state of a hanging call and the card settings.
         * @param state of card activity
         * @param hangFrom while hanging: the state of the call before it ended (ENCRYPTED or ACTIVE), or null
         * @param clearAlert true when the (hanging) call raised a clear audio alert
         * @param muted card setting
         * @param linked true when the card's alias exists
         * @return visual state
         */
        public static VisualState of(CardActivityState state, CardActivityState hangFrom, boolean clearAlert,
                                     boolean muted, boolean linked)
        {
            VisualState visualState = of(state, muted, linked);

            if(visualState == HANG)
            {
                if(clearAlert)
                {
                    return HANG_CLEAR;
                }

                if(hangFrom == CardActivityState.ENCRYPTED)
                {
                    return HANG_ENCRYPTED;
                }
            }

            return visualState;
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
     * Colours for a card (and for the collapsed group activity badge that features the card).
     * @param fill RGB fill colour
     * @param border RGB border colour
     * @param dashed true for a dashed border
     * @param text RGB card text colour (name, talker)
     * @param secondaryText RGB secondary card text colour (talkgroup, site, recent talkers, volume)
     */
    public record CardPaint(int fill, int border, boolean dashed, int text, int secondaryText)
    {
        /**
         * Colour of an indicator dot drawn on the fill (e.g. the collapsed group badge dot): the border colour, or the
         * text colour when the border colour does not have 3:1 contrast with the fill (e.g. dark palette active
         * cards, where the border is the fill colour).
         */
        public int indicator()
        {
            return contrastRatio(border, fill) >= 3.0 ? border : text;
        }
    }

    /**
     * Colours for a collapsed group summary row (on the group panel background).
     * @param dot RGB fill of the colour dot: the colour of the card's call (alias colour, encrypted colour, or red for
     * a clear audio alert)
     * @param dotOutline RGB outline of the colour dot, with at least 3:1 contrast on the group panel
     * @param statusFill RGB status pill fill: the card's fill for its state (red for a clear audio alert)
     * @param statusBorder RGB status pill border: the card's border for its state
     * @param statusText RGB status pill text, with at least 4.5:1 contrast on the status fill
     */
    public record SummaryPaint(int dot, int dotOutline, int statusFill, int statusBorder, int statusText)
    {
    }

    /**
     * Colours for a collapsed group summary row: the dot follows the colour of the card's call (the alias colour for
     * active, muted and hanging calls, the encrypted colour for encrypted calls and hanging encrypted calls, red for a
     * clear audio alert), and the status pill is drawn like the card for its state.
     * @param base RGB base colour of the card (see getBaseColor())
     * @param state visual state of the card
     * @param clearAlert true when the card's call raised a clear audio alert
     * @param palette console palette
     * @return paint
     */
    public static SummaryPaint getSummaryPaint(int base, VisualState state, boolean clearAlert, Palette palette)
    {
        if(clearAlert || state == VisualState.HANG_CLEAR)
        {
            return new SummaryPaint(CLEAR_ALERT, CLEAR_ALERT, CLEAR_ALERT_FILL, CLEAR_ALERT_FILL, WHITE);
        }

        CardPaint paint = getPaint(base, state, palette);
        int dot;
        int outline;

        if(state == VisualState.ENCRYPTED || state == VisualState.HANG_ENCRYPTED)
        {
            dot = palette.mEncryptedFill;
            outline = palette.mEncryptedBorder;
        }
        else
        {
            dot = base;
            outline = palette.isDark() ? base : getInkColor(base);
        }

        return new SummaryPaint(dot, outline, paint.fill(), paint.border(), paint.text());
    }

    /**
     * Base (active) colour for a card in the light palette.
     * @param aliasColor alias colour (RGB or ARGB), where zero or black means no colour
     * @param key used to select a default palette colour when the alias has no colour (e.g. the card identifier)
     * @return RGB base colour that keeps the minimum contrast with dark card text.
     */
    public static int getBaseColor(int aliasColor, String key)
    {
        return getBaseColor(aliasColor, key, Palette.LIGHT);
    }

    /**
     * Base (active) colour for a card.
     * @param aliasColor alias colour (RGB or ARGB), where zero or black means no colour
     * @param key used to select a default palette colour when the alias has no colour (e.g. the card identifier)
     * @param palette console palette
     * @return RGB base colour that keeps the minimum contrast with the palette's active card text.
     */
    public static int getBaseColor(int aliasColor, String key, Palette palette)
    {
        int rgb = aliasColor & 0xFFFFFF;

        if(rgb == 0)
        {
            int index = key != null ? Math.floorMod(key.hashCode(), DEFAULT_PALETTE.length) : 0;
            rgb = DEFAULT_PALETTE[index];
        }

        return ensureContrast(rgb, palette.mActiveSecondaryText, MINIMUM_CONTRAST);
    }

    /**
     * Light palette ink colour: a darker shade of the base colour for borders and coloured text, with at least the
     * minimum contrast on white.
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
     * Ink colour for coloured text (activity log talkgroup) with at least the minimum contrast on the palette's
     * activity log background: a darker shade of the base colour (light palette), or the base colour, lightened as
     * needed (dark palette).
     * @param base RGB colour (see getBaseColor())
     * @param palette console palette
     * @return RGB ink colour
     */
    public static int getInkColor(int base, Palette palette)
    {
        if(!palette.isDark())
        {
            return getInkColor(base);
        }

        return ensureContrast(base, palette.mInkBackground, MINIMUM_CONTRAST);
    }

    /**
     * Light palette card fill, border and text colours for the visual state.
     * @param base RGB base colour (see getBaseColor())
     * @param state visual state
     * @return paint
     */
    public static CardPaint getPaint(int base, VisualState state)
    {
        return getPaint(base, state, Palette.LIGHT);
    }

    /**
     * Card fill, border and text colours for the visual state.
     * @param base RGB base colour (see getBaseColor())
     * @param state visual state
     * @param palette console palette
     * @return paint
     */
    public static CardPaint getPaint(int base, VisualState state, Palette palette)
    {
        long key = ((long)palette.ordinal() << 40) | ((long)state.ordinal() << 32) | (base & 0xFFFFFFFFL);
        return PAINT_CACHE.computeIfAbsent(key, k -> computePaint(base, state, palette));
    }

    /**
     * Calculates the card fill, border and text colours for the visual state.
     */
    private static CardPaint computePaint(int base, VisualState state, Palette palette)
    {
        if(!palette.isDark())
        {
            int ink = getInkColor(base);
            int secondary = state.isLive() ? TEXT_SECONDARY_ACTIVE : TEXT_SECONDARY_IDLE;

            return switch(state)
            {
                case ACTIVE -> new CardPaint(base, ink, false, TEXT, secondary);
                case MUTED -> new CardPaint(mix(base, IDLE_FILL, 1.0 - MUTED_TINT), ink, true, TEXT, secondary);
                case ENCRYPTED -> new CardPaint(ENCRYPTED_FILL, ENCRYPTED_BORDER, false, TEXT, secondary);
                case HANG -> new CardPaint(mix(base, IDLE_FILL, 1.0 - HANG_TINT), ink, false, TEXT, secondary);
                case HANG_ENCRYPTED -> new CardPaint(mix(ENCRYPTED_BORDER, IDLE_FILL, 1.0 - HANG_TINT),
                    ENCRYPTED_BORDER, false, TEXT, secondary);
                case HANG_CLEAR -> new CardPaint(mix(CLEAR_ALERT, IDLE_FILL, 1.0 - HANG_TINT), CLEAR_ALERT, false,
                    TEXT, secondary);
                case UNLINKED -> new CardPaint(UNLINKED_FILL, UNLINKED_BORDER, true, TEXT, secondary);
                default -> new CardPaint(IDLE_FILL, IDLE_BORDER, false, TEXT, secondary);
            };
        }

        return switch(state)
        {
            case ACTIVE -> new CardPaint(base, base, false, palette.mActiveText, palette.mActiveSecondaryText);
            case MUTED -> new CardPaint(getTint(base, palette.mMutedTint, palette), base, true, palette.mTintText,
                palette.mTintSecondaryText);
            case ENCRYPTED -> new CardPaint(palette.mEncryptedFill, palette.mEncryptedBorder, false,
                palette.mActiveText, palette.mActiveSecondaryText);
            case HANG -> new CardPaint(getTint(base, palette.mHangTint, palette), base, false, palette.mTintText,
                palette.mTintSecondaryText);
            case HANG_ENCRYPTED -> new CardPaint(getTint(palette.mEncryptedFill, palette.mHangTint, palette),
                palette.mEncryptedBorder, false, palette.mTintText, palette.mTintSecondaryText);
            case HANG_CLEAR -> new CardPaint(getTint(CLEAR_ALERT, palette.mHangTint, palette), CLEAR_ALERT, false,
                palette.mTintText, palette.mTintSecondaryText);
            case UNLINKED -> new CardPaint(palette.mUnlinkedFill, palette.mUnlinkedBorder, true, palette.mIdleText,
                palette.mIdleSecondaryText);
            default -> new CardPaint(palette.mIdleFill, palette.mIdleBorder, false, palette.mIdleText,
                palette.mIdleSecondaryText);
        };
    }

    /**
     * Dark palette tinted fill: the base colour mixed into the idle card fill, with the amount of the base colour
     * reduced as needed so that the light (tint) card text and secondary text keep the minimum contrast.
     * @param base RGB colour
     * @param tint amount of the base colour, 0.0 - 1.0
     * @param palette (dark) console palette
     * @return RGB fill colour
     */
    private static int getTint(int base, double tint, Palette palette)
    {
        double amount = tint;
        int fill = mix(palette.mIdleFill, base, amount);

        while(amount > 0.0 && (contrastRatio(fill, palette.mTintSecondaryText) < MINIMUM_CONTRAST ||
            contrastRatio(fill, palette.mTintText) < MINIMUM_CONTRAST))
        {
            amount = Math.max(0.0, amount - 0.02);
            fill = mix(palette.mIdleFill, base, amount);
        }

        return fill;
    }

    /**
     * Lightens the colour (mixes it with white) until it has at least the minimum contrast ratio with the (dark) text
     * or background colour.
     * @param rgb colour to adjust
     * @param text RGB dark text or background colour
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
