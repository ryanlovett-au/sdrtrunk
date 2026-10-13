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

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the console group masonry placement: shortest column assignment in display order, ties, column spans,
 * clamping and full width.
 */
public class ConsoleMasonryTest
{
    private static final double GAP = 10;

    private static List<ConsoleMasonry.Placement> place(int columns, int[] spans, double... heights)
    {
        return ConsoleMasonry.place(columns, spans, heights, GAP);
    }

    private static int[] ones(int count)
    {
        int[] spans = new int[count];
        java.util.Arrays.fill(spans, 1);
        return spans;
    }

    private static void assertPlacement(ConsoleMasonry.Placement placement, int column, int span, double y)
    {
        assertEquals(column, placement.column(), "Column of " + placement);
        assertEquals(span, placement.span(), "Span of " + placement);
        assertEquals(y, placement.y(), 0.001, "Top of " + placement);
    }

    @Test
    void firstRowFillsColumnsLeftToRight()
    {
        List<ConsoleMasonry.Placement> placements = place(3, ones(3), 100, 300, 50);
        assertPlacement(placements.get(0), 0, 1, 0);
        assertPlacement(placements.get(1), 1, 1, 0);
        assertPlacement(placements.get(2), 2, 1, 0);
    }

    @Test
    void itemsGoToTheShortestColumnWithoutRowGaps()
    {
        //Three nearly empty groups, then a tall group, then more groups: the tall group starts directly below the
        //shortest of the first row instead of below the tallest group of the row
        List<ConsoleMasonry.Placement> placements = place(3, ones(6), 40, 60, 50, 900, 40, 40);
        assertPlacement(placements.get(3), 0, 1, 50);
        assertPlacement(placements.get(4), 2, 1, 60);
        assertPlacement(placements.get(5), 1, 1, 70);
        assertEquals(950, ConsoleMasonry.getHeight(placements), 0.001, "Content height");
    }

    @Test
    void tiesGoToTheLeftmostColumn()
    {
        List<ConsoleMasonry.Placement> placements = place(3, ones(5), 100, 100, 100, 20, 20);
        assertPlacement(placements.get(3), 0, 1, 110);
        assertPlacement(placements.get(4), 1, 1, 110);
    }

    @Test
    void displayOrderDefinesPlacementOrder()
    {
        //Same items in a different order are placed differently: placement follows the given (display) order
        List<ConsoleMasonry.Placement> a = place(2, ones(3), 500, 100, 100);
        List<ConsoleMasonry.Placement> b = place(2, ones(3), 100, 500, 100);
        assertPlacement(a.get(2), 1, 1, 110);
        assertPlacement(b.get(2), 0, 1, 110);
    }

    @Test
    void collapsingAGroupRepacksTheColumns()
    {
        //Expanded: the tall first group pushes the later groups to the second column
        List<ConsoleMasonry.Placement> expanded = place(2, ones(4), 800, 100, 100, 100);
        assertPlacement(expanded.get(2), 1, 1, 110);
        assertPlacement(expanded.get(3), 1, 1, 220);

        //Collapsed (header and summary only): the later groups use both columns again
        List<ConsoleMasonry.Placement> collapsed = place(2, ones(4), 60, 100, 100, 100);
        assertPlacement(collapsed.get(2), 0, 1, 70);
        assertPlacement(collapsed.get(3), 1, 1, 110);
    }

    @Test
    void singleColumnStacksInOrder()
    {
        List<ConsoleMasonry.Placement> placements = place(1, ones(3), 10, 20, 30);
        assertPlacement(placements.get(0), 0, 1, 0);
        assertPlacement(placements.get(1), 0, 1, 20);
        assertPlacement(placements.get(2), 0, 1, 50);
        assertEquals(80, ConsoleMasonry.getHeight(placements), 0.001);
        assertEquals(0, ConsoleMasonry.getHeight(place(3, new int[0])), 0.001, "Empty");
    }

    @Test
    void spanningItemUsesLowestAdjacentColumns()
    {
        //Columns after the first row: 100+gap, 300+gap, 50+gap, 60+gap.  A two column item fits lowest over columns
        //2-3 (max 70), not 0-1 (max 310) or 1-2 (310)
        List<ConsoleMasonry.Placement> placements = place(4, new int[]{1, 1, 1, 1, 2}, 100, 300, 50, 60, 40);
        assertPlacement(placements.get(4), 2, 2, 70);

        //The spanned columns are now both at 120: the next items go to column 0 (110), then column 2 (120, leftmost
        //of the tie with column 3)
        placements = place(4, new int[]{1, 1, 1, 1, 2, 1, 1}, 100, 300, 50, 60, 40, 10, 10);
        assertPlacement(placements.get(5), 0, 1, 110);
        assertPlacement(placements.get(6), 2, 1, 120);
    }

    @Test
    void spanningItemTiesGoLeft()
    {
        List<ConsoleMasonry.Placement> placements = place(3, new int[]{2, 2}, 100, 100);
        assertPlacement(placements.get(0), 0, 2, 0);
        //Positions 0-1 (110) and 1-2 (110) tie
        assertPlacement(placements.get(1), 0, 2, 110);
    }

    @Test
    void fullWidthSpansAllColumnsBelowEverything()
    {
        int[] spans = {1, 1, 1, ConsoleMasonry.getEffectiveSpan(ConsoleMasonry.FULL_WIDTH, 3), 1};
        List<ConsoleMasonry.Placement> placements = place(3, spans, 100, 400, 50, 80, 30);
        assertPlacement(placements.get(3), 0, 3, 410);
        assertPlacement(placements.get(4), 0, 1, 500);
    }

    @Test
    void spansClampToTheColumnCount()
    {
        assertEquals(1, ConsoleMasonry.getEffectiveSpan(1, 4));
        assertEquals(2, ConsoleMasonry.getEffectiveSpan(2, 4));
        assertEquals(2, ConsoleMasonry.getEffectiveSpan(3, 2), "Expected a 3 column span clamped to 2 columns");
        assertEquals(1, ConsoleMasonry.getEffectiveSpan(3, 1), "Expected a single column when narrow");
        assertEquals(4, ConsoleMasonry.getEffectiveSpan(ConsoleMasonry.FULL_WIDTH, 4), "Full width");
        assertEquals(1, ConsoleMasonry.getEffectiveSpan(ConsoleMasonry.FULL_WIDTH, 1), "Full width when narrow");

        //An unclamped span passed to place() is clamped as well
        List<ConsoleMasonry.Placement> placements = place(2, new int[]{3, 1}, 100, 20);
        assertPlacement(placements.get(0), 0, 2, 0);
        assertPlacement(placements.get(1), 0, 1, 110);
    }

    @Test
    void narrowingTheWindowReflowsSpans()
    {
        //A 3 column group in a 4 column window, then the window narrows to 2 columns and widens again
        int requested = 3;
        List<ConsoleMasonry.Placement> wide = place(4, new int[]{1, ConsoleMasonry.getEffectiveSpan(requested, 4)},
            50, 100);
        assertPlacement(wide.get(1), 1, 3, 0);

        List<ConsoleMasonry.Placement> narrow = place(2, new int[]{1, ConsoleMasonry.getEffectiveSpan(requested, 2)},
            50, 100);
        assertPlacement(narrow.get(1), 0, 2, 60);

        assertEquals(3, ConsoleMasonry.getEffectiveSpan(requested, 5), "Expected the span to expand again");
    }

    @Test
    void columnCountWidthAndSnapping()
    {
        assertEquals(1, ConsoleMasonry.getColumnCount(200, 300, 12), "At least one column");
        assertEquals(1, ConsoleMasonry.getColumnCount(611, 300, 12));
        assertEquals(2, ConsoleMasonry.getColumnCount(612, 300, 12));
        assertEquals(4, ConsoleMasonry.getColumnCount(1400, 300, 12));
        assertEquals(341, ConsoleMasonry.getColumnWidth(1400, 4, 12), 0.001);
        assertEquals(694, ConsoleMasonry.getSpanWidth(341, 2, 12), 0.001);

        assertEquals(1, ConsoleMasonry.getSnappedSpan(400, 341, 12, 4), "Snap down below the midpoint");
        assertEquals(2, ConsoleMasonry.getSnappedSpan(560, 341, 12, 4), "Snap up past the midpoint");
        assertEquals(4, ConsoleMasonry.getSnappedSpan(5000, 341, 12, 4), "Clamp to the column count");
        assertEquals(1, ConsoleMasonry.getSnappedSpan(-50, 341, 12, 4), "At least one column");
    }
}
