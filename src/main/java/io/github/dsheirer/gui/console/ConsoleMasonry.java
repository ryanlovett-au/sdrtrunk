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

import java.util.ArrayList;
import java.util.List;

/**
 * Masonry (column packing) placement for the console groups, independent of the JavaFX toolkit.
 *
 * Items are placed in order.  Each item spans one or more adjacent columns: a single column item is placed in the
 * currently shortest column, and an item that spans S columns is placed at the leftmost position whose S adjacent
 * columns have the lowest maximum height.  Ties go to the leftmost position.  The spanned columns' heights then become
 * that maximum plus the item height (plus the gap), so the columns pack vertically without the gaps that a row based
 * layout leaves under the shorter items of a row.
 *
 * Spans are requested spans: FULL_WIDTH spans all columns, and spans wider than the number of columns are clamped to
 * the number of columns (the requested span is unchanged, so the item expands again when there is room).
 */
public final class ConsoleMasonry
{
    /**
     * Requested span for an item that spans all columns.
     */
    public static final int FULL_WIDTH = 0;

    private ConsoleMasonry()
    {
    }

    /**
     * Number of columns that fit in the content width.
     * @param contentWidth available width
     * @param minimumColumnWidth minimum width of each column
     * @param gap between columns
     * @return number of columns, at least one
     */
    public static int getColumnCount(double contentWidth, double minimumColumnWidth, double gap)
    {
        return Math.max(1, (int)Math.floor((contentWidth + gap) / (minimumColumnWidth + gap)));
    }

    /**
     * Column width for the content width and column count.
     */
    public static double getColumnWidth(double contentWidth, int columns, double gap)
    {
        return Math.max(0, (contentWidth - gap * (columns - 1)) / columns);
    }

    /**
     * Width of an item that spans the columns (including the gaps between the spanned columns).
     */
    public static double getSpanWidth(double columnWidth, int span, double gap)
    {
        return columnWidth * span + gap * (span - 1);
    }

    /**
     * Effective (displayed) span for a requested span: FULL_WIDTH (or any value less than one) spans all columns, and
     * wider spans are clamped to the number of columns.
     * @param requestedSpan requested span (FULL_WIDTH, or the number of columns)
     * @param columns number of columns
     * @return effective span in the range 1 - columns
     */
    public static int getEffectiveSpan(int requestedSpan, int columns)
    {
        int count = Math.max(1, columns);
        return requestedSpan < 1 ? count : Math.min(requestedSpan, count);
    }

    /**
     * Snaps a width to a whole number of columns (e.g. while resizing an item).
     * @param width requested width
     * @param columnWidth column width
     * @param gap between columns
     * @param columns number of columns
     * @return nearest span in the range 1 - columns
     */
    public static int getSnappedSpan(double width, double columnWidth, double gap, int columns)
    {
        if(columnWidth <= 0)
        {
            return 1;
        }

        int span = (int)Math.round((width + gap) / (columnWidth + gap));
        return Math.clamp(span, 1, Math.max(1, columns));
    }

    /**
     * Places the items.
     * @param columns number of columns
     * @param spans effective span of each item (see getEffectiveSpan()), in placement order
     * @param heights height of each item at its spanned width, in placement order
     * @param gap vertical gap between items in a column
     * @return placement of each item, in placement order
     */
    public static List<Placement> place(int columns, int[] spans, double[] heights, double gap)
    {
        int count = Math.max(1, columns);
        double[] columnHeights = new double[count];
        List<Placement> placements = new ArrayList<>(spans.length);

        for(int x = 0; x < spans.length; x++)
        {
            int span = Math.clamp(spans[x], 1, count);
            int bestColumn = 0;
            double bestTop = Double.MAX_VALUE;

            for(int column = 0; column + span <= count; column++)
            {
                double top = 0;

                for(int spanned = column; spanned < column + span; spanned++)
                {
                    top = Math.max(top, columnHeights[spanned]);
                }

                //Strictly lower, so that ties go to the leftmost position
                if(top < bestTop)
                {
                    bestTop = top;
                    bestColumn = column;
                }
            }

            double height = Math.max(0, heights[x]);

            for(int spanned = bestColumn; spanned < bestColumn + span; spanned++)
            {
                columnHeights[spanned] = bestTop + height + gap;
            }

            placements.add(new Placement(bestColumn, span, bestTop, height));
        }

        return placements;
    }

    /**
     * Total height of the placed items: the bottom of the lowest item.
     * @param placements from place()
     * @return content height, or zero when there are no items
     */
    public static double getHeight(List<Placement> placements)
    {
        double height = 0;

        for(Placement placement: placements)
        {
            height = Math.max(height, placement.y() + placement.height());
        }

        return height;
    }

    /**
     * Placement of an item.
     * @param column index of the first (leftmost) spanned column
     * @param span number of spanned columns
     * @param y top of the item
     * @param height of the item
     */
    public record Placement(int column, int span, double y, double height)
    {
    }
}
