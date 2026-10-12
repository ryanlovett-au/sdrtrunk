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

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for tab and comma separated value parsing and formatting.
 */
public class DelimitedTextTest
{
    @Test
    void parsesSimpleTsv()
    {
        assertEquals(List.of(List.of("a", "b"), List.of("c", "d")), DelimitedText.parseTsv("a\tb\nc\td\n"));
    }

    @Test
    void parsesCrlfAndCr()
    {
        assertEquals(List.of(List.of("a", "b"), List.of("c", "d")), DelimitedText.parseTsv("a\tb\r\nc\td\r\n"));
        assertEquals(List.of(List.of("a"), List.of("b")), DelimitedText.parseTsv("a\rb"));
    }

    @Test
    void parsesWithoutTrailingSeparator()
    {
        assertEquals(List.of(List.of("1001")), DelimitedText.parseTsv("1001"));
        assertEquals(List.of(List.of("a", "b")), DelimitedText.parseTsv("a\tb"));
    }

    @Test
    void parsesQuotedFields()
    {
        //Excel quotes cells that contain tabs, line breaks or quotes and doubles embedded quotes
        String text = "\"x\ty\"\t\"he said \"\"hi\"\"\"\t\"line1\nline2\"\r\nplain\t\"\"\tend\r\n";
        List<List<String>> records = DelimitedText.parseTsv(text);
        assertEquals(2, records.size());
        assertEquals(List.of("x\ty", "he said \"hi\"", "line1\nline2"), records.get(0));
        assertEquals(List.of("plain", "", "end"), records.get(1));
    }

    @Test
    void quoteWithinUnquotedFieldIsLiteral()
    {
        assertEquals(List.of(List.of("6\" speaker", "b")), DelimitedText.parseTsv("6\" speaker\tb"));
    }

    @Test
    void parsesRaggedRowsAndEmptyFields()
    {
        List<List<String>> records = DelimitedText.parseTsv("a\tb\tc\nd\n\te\nf\t\n");
        assertEquals(List.of("a", "b", "c"), records.get(0));
        assertEquals(List.of("d"), records.get(1));
        assertEquals(List.of("", "e"), records.get(2));
        assertEquals(List.of("f", ""), records.get(3));
        assertEquals(4, records.size());
    }

    @Test
    void blankLineIsAnEmptyRecord()
    {
        assertEquals(List.of(List.of("a"), List.of(""), List.of("b")), DelimitedText.parseTsv("a\n\nb\n"));
    }

    @Test
    void emptyTextHasNoRecords()
    {
        assertTrue(DelimitedText.parseTsv("").isEmpty());
        assertTrue(DelimitedText.parseTsv(null).isEmpty());
    }

    @Test
    void ignoresByteOrderMark()
    {
        assertEquals(List.of(List.of("Name", "Talkgroup")), DelimitedText.parseCsv("﻿Name,Talkgroup\r\n"));
    }

    @Test
    void parsesCsvWithQuotedCommas()
    {
        assertEquals(List.of(List.of("Fire, Rescue", "1001", "")),
            DelimitedText.parseCsv("\"Fire, Rescue\",1001,\r\n"));
    }

    @Test
    void formatsTsvWithQuoting()
    {
        List<List<String>> records = List.of(List.of("a", "b\tc"), List.of("say \"hi\"", "line1\nline2"));
        String text = DelimitedText.formatTsv(records);
        assertEquals("a\t\"b\tc\"\n\"say \"\"hi\"\"\"\t\"line1\nline2\"\n", text);
        assertEquals(records, DelimitedText.parseTsv(text));
    }

    @Test
    void formatsCsvRoundTrip()
    {
        List<List<String>> records = List.of(List.of("Name", "Stream"), List.of("Fire, Rescue", "A; B"),
            List.of("", "x"));
        String text = DelimitedText.formatCsv(records);
        assertTrue(text.endsWith("\r\n"), "CSV records end with CRLF");
        assertEquals(records, DelimitedText.parseCsv(text));
    }
}
