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
import javafx.scene.layout.Region;

/**
 * Responsive masonry column layout for the console groups: as many equal-width columns as fit with at least the
 * minimum column width, with the children placed in order, each in the currently shortest column (or, for a child that
 * spans several columns, at the leftmost position whose spanned columns are lowest), at its own preferred height for
 * its width (see ConsoleMasonry).  The columns pack vertically, without the gaps that a row based layout leaves under
 * the shorter children of a row.
 *
 * Children that implement Spanned request a column span (clamped to the number of columns, or all columns for full
 * width) and are told the span that they are displayed with before they are measured, e.g. to lay out their cards in
 * the same number of columns.
 *
 * The layout is recalculated by JavaFX whenever the pane is resized or a child's preferred size changes (e.g. a group
 * is collapsed or expanded, or cards are added or removed), and when children are added, removed or reordered.
 *
 * A snap preview (an outline of a child at another span) can be shown while a child is being resized.
 */
public class ConsoleColumnPane extends Pane
{
    private final double mMinimumColumnWidth;
    private final double mGap;
    private final Region mSpanPreview = new Region();
    private int mColumnCount = 1;
    private double mColumnWidth;

    /**
     * Constructs an instance
     * @param minimumColumnWidth minimum width of each column
     * @param gap between columns and between the children in a column
     */
    public ConsoleColumnPane(double minimumColumnWidth, double gap)
    {
        mMinimumColumnWidth = minimumColumnWidth;
        mGap = gap;
        mSpanPreview.getStyleClass().add("span-preview");
        mSpanPreview.setManaged(false);
        mSpanPreview.setMouseTransparent(true);
        mSpanPreview.setVisible(false);
    }

    @Override
    public Orientation getContentBias()
    {
        return Orientation.HORIZONTAL;
    }

    /**
     * Number of columns in the current layout.
     */
    public int getColumnCount()
    {
        return mColumnCount;
    }

    /**
     * Column width in the current layout.
     */
    public double getColumnWidth()
    {
        return mColumnWidth;
    }

    /**
     * Gap between columns.
     */
    public double getGap()
    {
        return mGap;
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
        int columns = ConsoleMasonry.getColumnCount(contentWidth, mMinimumColumnWidth, mGap);
        double columnWidth = ConsoleMasonry.getColumnWidth(contentWidth, columns, mGap);

        if(apply)
        {
            mColumnCount = columns;
            mColumnWidth = columnWidth;
        }

        List<Node> children = getManagedChildren();

        if(children.isEmpty())
        {
            return 0;
        }

        int[] spans = new int[children.size()];
        double[] heights = new double[children.size()];

        for(int x = 0; x < children.size(); x++)
        {
            Node child = children.get(x);
            int span = 1;

            if(child instanceof Spanned spanned)
            {
                span = ConsoleMasonry.getEffectiveSpan(spanned.getRequestedColumnSpan(), columns);
                spanned.setDisplayedColumnSpan(span);
            }

            spans[x] = span;
            heights[x] = child.prefHeight(snapSizeX(ConsoleMasonry.getSpanWidth(columnWidth, span, mGap)));
        }

        List<ConsoleMasonry.Placement> placements = ConsoleMasonry.place(columns, spans, heights, mGap);

        if(apply)
        {
            Insets insets = getInsets();

            for(int x = 0; x < children.size(); x++)
            {
                ConsoleMasonry.Placement placement = placements.get(x);
                children.get(x).resizeRelocate(
                    snapPositionX(insets.getLeft() + placement.column() * (columnWidth + mGap)),
                    snapPositionY(insets.getTop() + placement.y()),
                    snapSizeX(ConsoleMasonry.getSpanWidth(columnWidth, placement.span(), mGap)),
                    snapSizeY(placement.height()));
            }
        }

        return ConsoleMasonry.getHeight(placements);
    }

    /**
     * Shows the snap preview: an outline of the child at the specified span, from the child's current position
     * (clamped to the columns to the right of the child's first column).
     * @param child being resized
     * @param span to preview
     */
    public void showSpanPreview(Node child, int span)
    {
        if(!getChildren().contains(mSpanPreview))
        {
            getChildren().add(mSpanPreview);
        }

        int column = mColumnWidth > 0 ? (int)Math.round((child.getLayoutX() - getInsets().getLeft()) /
            (mColumnWidth + mGap)) : 0;
        int displayed = Math.clamp(span, 1, Math.max(1, mColumnCount - column));
        mSpanPreview.resizeRelocate(child.getLayoutX(), child.getLayoutY(),
            snapSizeX(ConsoleMasonry.getSpanWidth(mColumnWidth, displayed, mGap)),
            child.getLayoutBounds().getHeight());
        mSpanPreview.setVisible(true);
        mSpanPreview.toFront();
    }

    /**
     * Hides the snap preview.
     */
    public void hideSpanPreview()
    {
        mSpanPreview.setVisible(false);
        getChildren().remove(mSpanPreview);
    }

    /**
     * A child that spans one or more columns.
     */
    public interface Spanned
    {
        /**
         * Requested column span: the number of columns (clamped to the columns that fit), or
         * ConsoleMasonry.FULL_WIDTH for all columns.
         */
        int getRequestedColumnSpan();

        /**
         * Sets the span that the child is displayed with (1 - number of columns).  Invoked before the child is
         * measured and laid out.
         */
        void setDisplayedColumnSpan(int span);
    }
}
