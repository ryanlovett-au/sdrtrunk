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

import java.util.ArrayList;
import java.util.List;

/**
 * Parses and formats delimited text: tab separated values (TSV) as placed on the clipboard by Excel, Google Sheets and
 * LibreOffice, and comma separated values (CSV, RFC 4180).
 *
 * Parsing rules:
 * - Records are separated by CRLF, LF or CR.  A trailing record separator does not produce an empty record.
 * - A field that starts with a double quote is a quoted field: delimiters and record separators within the quotes are
 *   literal and a doubled quote ("") is a literal quote.  A quote within an unquoted field is literal.
 * - Records can have different field counts (ragged rows); no padding is applied.
 * - A leading byte order mark is ignored.
 *
 * Formatting quotes a field when it contains the delimiter, a double quote, CR or LF.
 */
public class DelimitedText
{
    public static final char TAB = '\t';
    public static final char COMMA = ',';
    private static final char QUOTE = '"';
    private static final char BYTE_ORDER_MARK = '﻿';

    private DelimitedText()
    {
        //Static utility class
    }

    /**
     * Parses tab separated values (e.g. clipboard contents copied from a spreadsheet).
     * @param text to parse
     * @return records, each containing a list of field values
     */
    public static List<List<String>> parseTsv(String text)
    {
        return parse(text, TAB);
    }

    /**
     * Parses comma separated values.
     * @param text to parse
     * @return records, each containing a list of field values
     */
    public static List<List<String>> parseCsv(String text)
    {
        return parse(text, COMMA);
    }

    /**
     * Parses the delimited text.
     * @param text to parse, null or empty for no records
     * @param delimiter field delimiter
     * @return records, each containing a list of field values
     */
    public static List<List<String>> parse(String text, char delimiter)
    {
        List<List<String>> records = new ArrayList<>();

        if(text == null || text.isEmpty())
        {
            return records;
        }

        int position = 0;
        int length = text.length();

        if(text.charAt(0) == BYTE_ORDER_MARK)
        {
            position++;
        }

        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;

        while(position < length)
        {
            char c = text.charAt(position);

            if(quoted)
            {
                if(c == QUOTE)
                {
                    if(position + 1 < length && text.charAt(position + 1) == QUOTE)
                    {
                        field.append(QUOTE);
                        position++;
                    }
                    else
                    {
                        quoted = false;
                    }
                }
                else
                {
                    field.append(c);
                }
            }
            else if(c == QUOTE && !fieldStarted)
            {
                quoted = true;
                fieldStarted = true;
            }
            else if(c == delimiter)
            {
                record.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
            }
            else if(c == '\r' || c == '\n')
            {
                record.add(field.toString());
                records.add(record);
                record = new ArrayList<>();
                field.setLength(0);
                fieldStarted = false;

                if(c == '\r' && position + 1 < length && text.charAt(position + 1) == '\n')
                {
                    position++;
                }
            }
            else
            {
                field.append(c);
                fieldStarted = true;
            }

            position++;
        }

        //Final record when the text does not end with a record separator
        if(fieldStarted || field.length() > 0 || !record.isEmpty())
        {
            record.add(field.toString());
            records.add(record);
        }

        return records;
    }

    /**
     * Formats the records as tab separated values with LF record separators, suitable for the clipboard.
     * @param records to format
     * @return formatted text
     */
    public static String formatTsv(List<List<String>> records)
    {
        return format(records, TAB, "\n");
    }

    /**
     * Formats the records as comma separated values with CRLF record separators (RFC 4180).
     * @param records to format
     * @return formatted text
     */
    public static String formatCsv(List<List<String>> records)
    {
        return format(records, COMMA, "\r\n");
    }

    /**
     * Formats the records as delimited text.  Each record is terminated by the record separator.
     * @param records to format
     * @param delimiter between fields
     * @param recordSeparator after each record
     * @return formatted text
     */
    public static String format(List<List<String>> records, char delimiter, String recordSeparator)
    {
        StringBuilder sb = new StringBuilder();

        for(List<String> record: records)
        {
            for(int x = 0; x < record.size(); x++)
            {
                if(x > 0)
                {
                    sb.append(delimiter);
                }

                appendField(sb, record.get(x), delimiter);
            }

            sb.append(recordSeparator);
        }

        return sb.toString();
    }

    /**
     * Appends the field value, quoting it when required.
     */
    private static void appendField(StringBuilder sb, String value, char delimiter)
    {
        if(value == null)
        {
            return;
        }

        boolean quote = value.indexOf(delimiter) >= 0 || value.indexOf(QUOTE) >= 0 || value.indexOf('\r') >= 0 ||
            value.indexOf('\n') >= 0;

        if(quote)
        {
            sb.append(QUOTE).append(value.replace("\"", "\"\"")).append(QUOTE);
        }
        else
        {
            sb.append(value);
        }
    }
}
