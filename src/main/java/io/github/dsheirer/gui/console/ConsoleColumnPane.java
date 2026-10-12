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
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * Responsive column layout for console groups, equivalent to a CSS grid with
 * 'grid-template-columns: repeat(auto-fill, minmax(MIN, 1fr))' and 'align-items: start': as many equal-width columns
 * as fit with at least the minimum column width, children placed in rows (left to right), each child at its own
 * preferred height and each row as tall as its tallest child.
 */
public class ConsoleColumnPane extends Pane
{
    private final double mMinimumColumnWidth;
    private final double mGap;

    /**
     * Constructs an instance
     * @param minimumColumnWidth minimum width of each column
     * @param gap between columns and rows
     */
    public ConsoleColumnPane(double minimumColumnWidth, double gap)
    {
        mMinimumColumnWidth = minimumColumnWidth;
        mGap = gap;
    }

    @Override
    public Orientation getContentBias()
    {
        return Orientation.HORIZONTAL;
    }

    /**
     * Number of columns that fit in the content width.
     */
    private int getColumnCount(double contentWidth)
    {
        return Math.max(1, (int)Math.floor((contentWidth + mGap) / (mMinimumColumnWidth + mGap)));
    }

    /**
     * Column width for the content width and column count.
     */
    private double getColumnWidth(double contentWidth, int columns)
    {
        return Math.max(0, (contentWidth - mGap * (columns - 1)) / columns);
    }

    @Override
    protected double computeMinWidth(double height)
    {
        Insets insets = getInsets();
        return insets.getLeft() + insets.getRight() + Math.min(mMinimumColumnWidth, 200);
    }

    @Override
    protected double computePrefWidth(double height)
    {
        Insets insets = getInsets();
        return insets.getLeft() + insets.getRight() + mMinimumColumnWidth;
    }

    @Override
    protected double computePrefHeight(double width)
    {
        Insets insets = getInsets();
        double contentWidth = (width > 0 ? width : getWidth()) - insets.getLeft() - insets.getRight();

        if(contentWidth <= 0)
        {
            contentWidth = mMinimumColumnWidth;
        }

        return insets.getTop() + insets.getBottom() + layout(contentWidth, false);
    }

    @Override
    protected void layoutChildren()
    {
        Insets insets = getInsets();
        layout(getWidth() - insets.getLeft() - insets.getRight(), true);
    }

    /**
     * Calculates (and optionally applies) the layout of the managed children.
     * @param contentWidth available width
     * @param apply true to position the children
     * @return content height
     */
    private double layout(double contentWidth, boolean apply)
    {
        List<Node> children = getManagedChildren();

        if(children.isEmpty())
        {
            return 0;
        }

        Insets insets = getInsets();
        int columns = getColumnCount(contentWidth);
        double columnWidth = getColumnWidth(contentWidth, columns);
        double y = 0;

        for(int rowStart = 0; rowStart < children.size(); rowStart += columns)
        {
            double rowHeight = 0;

            for(int column = 0; column < columns && rowStart + column < children.size(); column++)
            {
                Node child = children.get(rowStart + column);
                double height = child.prefHeight(columnWidth);
                rowHeight = Math.max(rowHeight, height);

                if(apply)
                {
                    child.resizeRelocate(snapPositionX(insets.getLeft() + column * (columnWidth + mGap)),
                        snapPositionY(insets.getTop() + y), snapSizeX(columnWidth), snapSizeY(height));
                }
            }

            y += rowHeight;

            if(rowStart + columns < children.size())
            {
                y += mGap;
            }
        }

        return y;
    }
}
