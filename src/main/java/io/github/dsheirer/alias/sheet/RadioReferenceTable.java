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

package io.github.dsheirer.alias.sheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses a RadioReference.com talkgroup table that was copied from a web browser (tab separated values), e.g.
 *
 * <pre>
 * DEC   HEX  MODE  ALPHA TAG      DESCRIPTION              TAG
 * 749   2ed  D     01 Travel      01 Travel                Public Works
 * 753   2f1  D     19 Resource Pro 19 Resource Protection  Public Works
 * </pre>
 *
 * Recognition rules (see isRecognised()):
 * - Header rows: a row whose cells name a decimal (DEC) or hex (HEX) talkgroup column and an alpha tag or description
 *   column, plus at least one other known column (mode, tag, category).  Column names are matched ignoring case,
 *   spaces and punctuation, so additional or re-ordered columns (e.g. a Category or Group column) are supported.  A
 *   header row can be repeated (one per category table) and each header row redefines the column positions.
 * - Data rows after a header row: a decimal talkgroup (or a hex talkgroup when there is no decimal column).
 * - Data rows without a header row are recognised by pattern: an integer decimal talkgroup, followed by a hex column
 *   whose value equals the decimal value, followed by a mode code (see RadioReferenceMode), followed by the alpha tag,
 *   description and tag columns.  Columns before the decimal column are ignored.
 * - A row with a single non-empty cell (that is not a data row) is a category sub-heading that applies to the following
 *   data rows, until the next sub-heading.
 * - Blank rows are ignored.  Other rows are ignored and reported (see getIgnoredLines()).
 * - Cell values are trimmed, including non-breaking spaces.
 *
 * The text is recognised as a RadioReference table when at least one data row was found.
 */
public class RadioReferenceTable
{
    /**
     * Trailing 'Copy' button label that is included when a category heading is copied from a RadioReference page.
     */
    private static final Pattern COPY_BUTTON_LABEL = Pattern.compile("\\s+Copy\\s*$");
    private final List<Row> mRows;
    private final List<Integer> mIgnoredLines;
    private final boolean mHeaderFound;

    private RadioReferenceTable(List<Row> rows, List<Integer> ignoredLines, boolean headerFound)
    {
        mRows = Collections.unmodifiableList(rows);
        mIgnoredLines = Collections.unmodifiableList(ignoredLines);
        mHeaderFound = headerFound;
    }

    /**
     * Talkgroup rows, in table order.
     */
    public List<Row> getRows()
    {
        return mRows;
    }

    /**
     * One-based line numbers of non-blank rows that were not recognised as a header, a data row or a category
     * sub-heading.
     */
    public List<Integer> getIgnoredLines()
    {
        return mIgnoredLines;
    }

    /**
     * Indicates if a header row was found.
     */
    public boolean isHeaderFound()
    {
        return mHeaderFound;
    }

    /**
     * Indicates if the text was recognised as a RadioReference talkgroup table: at least one talkgroup row.
     */
    public boolean isRecognised()
    {
        return !mRows.isEmpty();
    }

    /**
     * Indicates if the text is recognised as a RadioReference talkgroup table.
     */
    public static boolean isRadioReferenceTable(String text)
    {
        return parse(text).isRecognised();
    }

    /**
     * Parses the tab separated text.
     * @param text copied from a RadioReference talkgroup table, or any other text
     * @return parsed table (check isRecognised())
     */
    public static RadioReferenceTable parse(String text)
    {
        return parse(DelimitedText.parseTsv(text));
    }

    /**
     * Parses the records (e.g. from DelimitedText.parseTsv()).
     */
    public static RadioReferenceTable parse(List<List<String>> records)
    {
        List<Row> rows = new ArrayList<>();
        List<Integer> ignored = new ArrayList<>();
        Map<Field,Integer> columns = null;
        boolean headerFound = false;
        String category = null;

        if(records == null)
        {
            return new RadioReferenceTable(rows, ignored, false);
        }

        for(int x = 0; x < records.size(); x++)
        {
            int line = x + 1;
            List<String> cells = new ArrayList<>();
            int nonEmpty = 0;
            String firstNonEmpty = null;

            for(String cell: records.get(x))
            {
                String value = clean(cell);
                cells.add(value);

                if(!value.isEmpty())
                {
                    nonEmpty++;

                    if(firstNonEmpty == null)
                    {
                        firstNonEmpty = value;
                    }
                }
            }

            if(nonEmpty == 0)
            {
                continue;
            }

            Map<Field,Integer> header = parseHeader(cells);

            if(header != null)
            {
                columns = header;
                headerFound = true;
                continue;
            }

            Row row = null;

            //A data row has at least two values (a single value is a category sub-heading)
            if(nonEmpty > 1)
            {
                if(columns != null)
                {
                    row = parseWithColumns(line, cells, columns, category);
                }

                if(row == null)
                {
                    row = parseByPattern(line, cells, category);
                }
            }

            if(row != null)
            {
                rows.add(row);
            }
            else if(nonEmpty == 1)
            {
                //Category headings copied from RadioReference include the label of the table's 'Copy' button
                category = COPY_BUTTON_LABEL.matcher(firstNonEmpty).replaceFirst("").strip();
            }
            else
            {
                ignored.add(line);
            }
        }

        return new RadioReferenceTable(rows, ignored, headerFound);
    }

    /**
     * Cleans a cell value: non-breaking spaces are converted to spaces and leading/trailing whitespace is removed.
     */
    static String clean(String value)
    {
        return value == null ? "" : value.replace(' ', ' ').strip();
    }

    /**
     * Parses the cells as a header row.
     * @return column indexes for the fields, or null if the cells are not a RadioReference table header.
     */
    private static Map<Field,Integer> parseHeader(List<String> cells)
    {
        Map<Field,Integer> columns = new EnumMap<>(Field.class);

        for(int x = 0; x < cells.size(); x++)
        {
            Field field = Field.fromHeader(cells.get(x));

            if(field != null && !columns.containsKey(field))
            {
                columns.put(field, x);
            }
        }

        boolean hasTalkgroup = columns.containsKey(Field.DECIMAL) || columns.containsKey(Field.HEX);
        boolean hasName = columns.containsKey(Field.ALPHA_TAG) || columns.containsKey(Field.DESCRIPTION);

        return hasTalkgroup && hasName && columns.size() >= 3 ? columns : null;
    }

    /**
     * Parses a data row using the column positions from a header row.
     * @return row or null if the cells don't contain a valid talkgroup
     */
    private static Row parseWithColumns(int line, List<String> cells, Map<Field,Integer> columns, String category)
    {
        Integer value = null;
        String decimal = get(cells, columns, Field.DECIMAL);
        String hex = get(cells, columns, Field.HEX);

        if(columns.containsKey(Field.DECIMAL))
        {
            value = parseDecimal(decimal);
        }
        else
        {
            value = parseHex(hex);
        }

        if(value == null)
        {
            return null;
        }

        String modeText = get(cells, columns, Field.MODE);
        String rowCategory = get(cells, columns, Field.CATEGORY);

        return new Row(line, value, RadioReferenceMode.parse(modeText), modeText, get(cells, columns, Field.ALPHA_TAG),
            get(cells, columns, Field.DESCRIPTION), get(cells, columns, Field.TAG),
            rowCategory.isEmpty() ? nonNull(category) : rowCategory);
    }

    /**
     * Parses a data row without a header: decimal, hex (same value), mode, alpha tag, description, tag.
     * @return row or null if the cells don't match the pattern
     */
    private static Row parseByPattern(int line, List<String> cells, String category)
    {
        for(int x = 0; x + 2 < cells.size(); x++)
        {
            Integer value = parseDecimal(cells.get(x));

            if(value == null)
            {
                continue;
            }

            Integer hex = parseHex(cells.get(x + 1));
            RadioReferenceMode mode = RadioReferenceMode.parse(cells.get(x + 2));

            if(hex != null && hex.equals(value) && mode != null)
            {
                return new Row(line, value, mode, cells.get(x + 2), get(cells, x + 3), get(cells, x + 4),
                    get(cells, x + 5), nonNull(category));
            }
        }

        return null;
    }

    private static String get(List<String> cells, Map<Field,Integer> columns, Field field)
    {
        Integer index = columns.get(field);
        return index != null ? get(cells, index) : "";
    }

    private static String get(List<String> cells, int index)
    {
        return index < cells.size() ? cells.get(index) : "";
    }

    private static String nonNull(String value)
    {
        return value != null ? value : "";
    }

    /**
     * Parses a non-negative decimal integer.
     * @return value or null
     */
    static Integer parseDecimal(String text)
    {
        if(text == null || text.isEmpty() || text.length() > 9)
        {
            return null;
        }

        for(int x = 0; x < text.length(); x++)
        {
            if(text.charAt(x) < '0' || text.charAt(x) > '9')
            {
                return null;
            }
        }

        return Integer.parseInt(text);
    }

    /**
     * Parses a non-negative hexadecimal integer, with or without a 0x prefix.
     * @return value or null
     */
    static Integer parseHex(String text)
    {
        if(text == null)
        {
            return null;
        }

        String value = text.toLowerCase(Locale.ROOT);

        if(value.startsWith("0x"))
        {
            value = value.substring(2);
        }

        if(value.isEmpty() || value.length() > 7)
        {
            return null;
        }

        for(int x = 0; x < value.length(); x++)
        {
            if(Character.digit(value.charAt(x), 16) < 0)
            {
                return null;
            }
        }

        return Integer.parseInt(value, 16);
    }

    /**
     * Table fields
     */
    enum Field
    {
        DECIMAL("dec", "decimal", "decid", "dectg", "tgdec", "decimalid"),
        HEX("hex", "hexid", "hextg", "tghex"),
        MODE("mode"),
        ALPHA_TAG("alphatag", "alpha", "alphanumerictag"),
        DESCRIPTION("description", "desc"),
        TAG("tag", "tags", "servicetag"),
        CATEGORY("category", "group", "talkgroupcategory", "tgcategory", "talkgroupgroup", "agency");

        private final String[] mNames;

        Field(String... names)
        {
            mNames = names;
        }

        /**
         * Field for the header cell text, or null.
         */
        static Field fromHeader(String text)
        {
            String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");

            for(Field field: values())
            {
                for(String name: field.mNames)
                {
                    if(name.equals(normalized))
                    {
                        return field;
                    }
                }
            }

            return null;
        }
    }

    /**
     * Talkgroup row from a RadioReference table.  Text values are trimmed and never null.
     * @param lineNumber one-based line number in the pasted text
     * @param talkgroup decimal talkgroup value
     * @param mode parsed mode, or null if there is no mode column or the mode code is not recognised
     * @param modeText mode text as shown in the table
     * @param alphaTag alpha tag
     * @param description description
     * @param tag service tag, e.g. 'Public Works'
     * @param category from a category/group column or the preceding category sub-heading row
     */
    public record Row(int lineNumber, int talkgroup, RadioReferenceMode mode, String modeText, String alphaTag,
                      String description, String tag, String category)
    {
    }
}
