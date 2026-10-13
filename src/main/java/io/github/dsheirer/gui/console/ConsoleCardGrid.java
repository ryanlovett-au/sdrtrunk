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

import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * Grid of console cards for a group: the cards in card order, row by row, in a number of equal-width columns (the
 * group's displayed column span), each row as tall as its tallest card.  With one column the cards are a vertical
 * list.  The horizontal gap is chosen by the group so that the cards of a group that spans several console columns
 * have the same width as (and line up with) the cards of single column groups.
 */
public class ConsoleCardGrid extends Pane
{
    private final double mHorizontalGap;
    private final double mVerticalGap;
    private int mColumns = 1;

    /**
     * Constructs an instance
     * @param horizontalGap between card columns
     * @param verticalGap between card rows
     */
    public ConsoleCardGrid(double horizontalGap, double verticalGap)
    {
        mHorizontalGap = horizontalGap;
        mVerticalGap = verticalGap;
    }

    /**
     * Sets the number of card columns.
     */
    public void setColumns(int columns)
    {
        int count = Math.max(1, columns);

        if(count != mColumns)
        {
            mColumns = count;
            requestLayout();
        }
    }

    /**
     * Number of card columns.
     */
    public int getColumns()
    {
        return mColumns;
    }

    @Override
    public Orientation getContentBias()
    {
        return Orientation.HORIZONTAL;
    }

    /**
     * Card width for the content width.
     */
    private double getCardWidth(double contentWidth)
    {
        return Math.max(0, (contentWidth - mHorizontalGap * (mColumns - 1)) / mColumns);
    }

    @Override
    protected double computeMinWidth(double height)
    {
        Insets insets = getInsets();
        return insets.getLeft() + insets.getRight();
    }

    @Override
    protected double computePrefWidth(double height)
    {
        double cardWidth = 0;

        for(Node child: getManagedChildren())
        {
            cardWidth = Math.max(cardWidth, child.prefWidth(-1));
        }

        Insets insets = getInsets();
        return insets.getLeft() + insets.getRight() + cardWidth * mColumns + mHorizontalGap * (mColumns - 1);
    }

    @Override
    protected double computePrefHeight(double width)
    {
        Insets insets = getInsets();
        double contentWidth = (width > 0 ? width : getWidth()) - insets.getLeft() - insets.getRight();
        return insets.getTop() + insets.getBottom() + layout(Math.max(0, contentWidth), false);
    }

    @Override
    protected void layoutChildren()
    {
        Insets insets = getInsets();
        layout(getWidth() - insets.getLeft() - insets.getRight(), true);
    }

    /**
     * Calculates (and optionally applies) the layout of the cards.
     * @param contentWidth available width
     * @param apply true to position the cards
     * @return content height
     */
    private double layout(double contentWidth, boolean apply)
    {
        List<Node> children = getManagedChildren();
        double cardWidth = snapSizeX(getCardWidth(contentWidth));
        Insets insets = getInsets();
        double y = 0;

        for(int rowStart = 0; rowStart < children.size(); rowStart += mColumns)
        {
            double rowHeight = 0;
            int rowEnd = Math.min(children.size(), rowStart + mColumns);

            for(int x = rowStart; x < rowEnd; x++)
            {
                Node child = children.get(x);

                //Cards without a content bias are measured without a width, which uses their cached preferred height
                rowHeight = Math.max(rowHeight, child.prefHeight(child.getContentBias() == null ? -1 : cardWidth));
            }

            if(apply)
            {
                for(int x = rowStart; x < rowEnd; x++)
                {
                    children.get(x).resizeRelocate(
                        snapPositionX(insets.getLeft() + (x - rowStart) * (cardWidth + mHorizontalGap)),
                        snapPositionY(insets.getTop() + y), cardWidth, snapSizeY(rowHeight));
                }
            }

            y += rowHeight;

            if(rowEnd < children.size())
            {
                y += mVerticalGap;
            }
        }

        return y;
    }
}
