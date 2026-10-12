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
import java.util.List;
import org.junit.jupiter.api.Test;

import static io.github.dsheirer.gui.console.ConsoleCardColors.VisualState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for console card colour calculations.
 */
public class ConsoleCardColorsTest
{
    /**
     * Alias colours to check, including dark and saturated colours that need adjustment.
     */
    private static final int[] ALIAS_COLORS = {0xFF000000, 0x000000, 0xFF0000FF, 0x800000, 0x008000, 0xFFFF00,
        0xFFFFFF, 0xFF9447, 0x3FD6A6, 0x6A2BA8, 0x0F2440, 0x777777};

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
}
