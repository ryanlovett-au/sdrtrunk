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

package io.github.dsheirer.alias.sheet;

/**
 * Rectangular range of sheet cells, using inclusive zero-based row and column indices of the current (view) order.
 *
 * @param firstRow index of the first row
 * @param lastRow index of the last row (inclusive)
 * @param firstColumn index of the first column
 * @param lastColumn index of the last column (inclusive)
 */
public record SheetRange(int firstRow, int lastRow, int firstColumn, int lastColumn)
{
    /**
     * Constructs a range, ordering the row and column bounds.
     */
    public SheetRange
    {
        if(lastRow < firstRow)
        {
            int temp = firstRow;
            firstRow = lastRow;
            lastRow = temp;
        }

        if(lastColumn < firstColumn)
        {
            int temp = firstColumn;
            firstColumn = lastColumn;
            lastColumn = temp;
        }
    }

    /**
     * Range for a single cell.
     */
    public static SheetRange cell(int row, int column)
    {
        return new SheetRange(row, row, column, column);
    }

    /**
     * Number of rows in the range
     */
    public int rowCount()
    {
        return lastRow - firstRow + 1;
    }

    /**
     * Number of columns in the range
     */
    public int columnCount()
    {
        return lastColumn - firstColumn + 1;
    }
}
