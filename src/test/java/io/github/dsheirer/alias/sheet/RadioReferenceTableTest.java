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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for recognising and parsing talkgroup tables copied from RadioReference.com.
 */
public class RadioReferenceTableTest
{
    static final String TABLE = """
        DEC\tHEX\tMODE\tALPHA TAG\tDESCRIPTION\tTAG
        749\t2ed\tD\t01 Travel\t01 Travel\tPublic Works
        753\t2f1\tD\t19 Resource Pro\t19 Resource Protection\tPublic Works
        767\t2ff\tD\t05 Mt Lofty 2 -\t05 Mt Lofty 2 - Cleland\tPublic Works
        2585\ta19\tD\tUnknown\tUnknown\tPublic Works
        """;

    @Test
    void parsesHeaderTable()
    {
        RadioReferenceTable table = RadioReferenceTable.parse(TABLE);
        assertTrue(table.isRecognised());
        assertTrue(table.isHeaderFound());
        assertEquals(4, table.getRows().size());
        assertTrue(table.getIgnoredLines().isEmpty());

        RadioReferenceTable.Row row = table.getRows().get(2);
        assertEquals(767, row.talkgroup());
        assertSame(RadioReferenceMode.DIGITAL, row.mode());
        assertEquals("05 Mt Lofty 2 -", row.alphaTag());
        assertEquals("05 Mt Lofty 2 - Cleland", row.description());
        assertEquals("Public Works", row.tag());
        assertEquals("", row.category());
        assertEquals(4, row.lineNumber());
    }

    @Test
    void parsesHeaderWithExtraAndReorderedColumnsAndWhitespace()
    {
        String text = " Category \tDEC\tHEX\tMode\tAlpha Tag\tDescription\tTag\tPriority\n" +
            "Fire\t 101 \t65\tTE\t  FD Disp \t Fire Dispatch \tFire Dispatch\t1\r\n" +
            "EMS\t102\t66\tA\tEMS 1\tEMS Ops\tEMS-Tac\t\r\n";
        RadioReferenceTable table = RadioReferenceTable.parse(text);
        assertTrue(table.isHeaderFound());
        assertEquals(2, table.getRows().size());

        RadioReferenceTable.Row fire = table.getRows().getFirst();
        assertEquals(101, fire.talkgroup());
        assertSame(RadioReferenceMode.TDMA_ENCRYPTED, fire.mode());
        assertEquals("FD Disp", fire.alphaTag());
        assertEquals("Fire Dispatch", fire.description());
        assertEquals("Fire Dispatch", fire.tag());
        assertEquals("Fire", fire.category());
        assertEquals("EMS", table.getRows().get(1).category());
        assertSame(RadioReferenceMode.ANALOG, table.getRows().get(1).mode());
    }

    @Test
    void categorySubHeadingsAndRepeatedHeaders()
    {
        //Several category tables selected at once: caption row, header row and rows for each category
        String text = """
            Public Works
            DEC\tHEX\tMode\tAlpha Tag\tDescription\tTag
            749\t2ed\tD\t01 Travel\t01 Travel\tPublic Works

            Police
            DEC\tHEX\tMode\tAlpha Tag\tDescription\tTag
            1001\t3e9\tE\tPD Tac\tPolice Tactical\tLaw Tac
            1002\t3ea\tDe\tPD Disp\tPolice Dispatch\tLaw Dispatch
            """;
        RadioReferenceTable table = RadioReferenceTable.parse(text);
        assertTrue(table.isRecognised());
        assertEquals(3, table.getRows().size());
        assertTrue(table.getIgnoredLines().isEmpty());
        assertEquals("Public Works", table.getRows().get(0).category());
        assertEquals("Police", table.getRows().get(1).category());
        assertEquals("Police", table.getRows().get(2).category());
        assertSame(RadioReferenceMode.DIGITAL_PARTIALLY_ENCRYPTED, table.getRows().get(2).mode());
    }

    @Test
    void subHeadingWithinSingleTable()
    {
        String text = "DEC\tHEX\tMode\tAlpha Tag\tDescription\tTag\n" +
            "\t\tCounty Fire\t\t\t\n" +
            "201\tc9\tD\tCF 1\tCounty Fire 1\tFire Dispatch\n";
        RadioReferenceTable table = RadioReferenceTable.parse(text);
        assertEquals(1, table.getRows().size());
        assertEquals("County Fire", table.getRows().getFirst().category());
    }

    @Test
    void detectsHeaderlessTableByPattern()
    {
        String text = """
            749\t2ed\tD\t01 Travel\t01 Travel\tPublic Works
            Something else\twith\tcells
            2585\ta19\tT\tUnknown\tUnknown\tPublic Works
            """;
        RadioReferenceTable table = RadioReferenceTable.parse(text);
        assertTrue(table.isRecognised());
        assertFalse(table.isHeaderFound());
        assertEquals(2, table.getRows().size());
        assertEquals(List.of(2), table.getIgnoredLines());
        assertEquals(2585, table.getRows().get(1).talkgroup());
        assertSame(RadioReferenceMode.TDMA, table.getRows().get(1).mode());
        assertEquals("Public Works", table.getRows().get(1).tag());
    }

    @Test
    void headerlessPatternWithLeadingColumn()
    {
        RadioReferenceTable table = RadioReferenceTable.parse("Law\t1001\t3e9\tTE\tPD Tac\tPolice Tactical\tLaw Tac\n");
        assertEquals(1, table.getRows().size());
        assertEquals(1001, table.getRows().getFirst().talkgroup());
        assertEquals("PD Tac", table.getRows().getFirst().alphaTag());
    }

    @Test
    void headerlessRequiresMatchingHexAndMode()
    {
        //Hex value differs from the decimal value
        assertFalse(RadioReferenceTable.isRadioReferenceTable("749\t2ee\tD\tA\tB\tC"));
        //Not a mode code
        assertFalse(RadioReferenceTable.isRadioReferenceTable("749\t2ed\tX\tA\tB\tC"));
    }

    @Test
    void ordinarySpreadsheetPasteIsNotDetected()
    {
        //Rows copied from the alias sheet itself, or other spreadsheet data
        assertFalse(RadioReferenceTable.isRadioReferenceTable(
            "Metro\tFire\tFire Dispatch\tAPCO-25\t1001\t#FF0000\t\tDefault\tNo\t\t\n" +
            "Metro\tFire\tFire Tac\tAPCO-25\t1002\t\t\tDefault\tNo\t\t\n"));
        assertFalse(RadioReferenceTable.isRadioReferenceTable("Fire Dispatch"));
        assertFalse(RadioReferenceTable.isRadioReferenceTable("1001"));
        assertFalse(RadioReferenceTable.isRadioReferenceTable("1\t2\t3\n4\t5\t6"));
        assertFalse(RadioReferenceTable.isRadioReferenceTable(""));
        assertFalse(RadioReferenceTable.isRadioReferenceTable(null));
        //Alias sheet CSV-style header is not a RadioReference header
        assertFalse(RadioReferenceTable.isRadioReferenceTable("Alias list\tGroup\tName\tProtocol\tTalkgroup\n" +
            "Metro\tFire\tFire Dispatch\tAPCO-25\t1001"));
        //A header without data rows
        assertFalse(RadioReferenceTable.isRadioReferenceTable("DEC\tHEX\tMode\tAlpha Tag\tDescription\tTag\n"));
    }

    @Test
    void headerWithHexOnly()
    {
        RadioReferenceTable table = RadioReferenceTable.parse("HEX\tAlpha Tag\tDescription\n2ed\tTravel\tTravel ops\n");
        assertEquals(1, table.getRows().size());
        assertEquals(749, table.getRows().getFirst().talkgroup());
        assertNull(table.getRows().getFirst().mode());
    }

    @Test
    void parsesModes()
    {
        assertSame(RadioReferenceMode.ANALOG, RadioReferenceMode.parse("A"));
        assertSame(RadioReferenceMode.DIGITAL, RadioReferenceMode.parse(" d "));
        assertSame(RadioReferenceMode.TDMA, RadioReferenceMode.parse("T"));
        assertSame(RadioReferenceMode.MIXED, RadioReferenceMode.parse("M"));

        //Uppercase E: full time encryption, lowercase e: part time encryption
        assertSame(RadioReferenceMode.DIGITAL_ENCRYPTED, RadioReferenceMode.parse("DE"));
        assertSame(RadioReferenceMode.DIGITAL_PARTIALLY_ENCRYPTED, RadioReferenceMode.parse("De"));
        assertSame(RadioReferenceMode.TDMA_ENCRYPTED, RadioReferenceMode.parse("TE"));
        assertSame(RadioReferenceMode.TDMA_PARTIALLY_ENCRYPTED, RadioReferenceMode.parse("Te"));

        //Current RadioReference site format with a separate ENC token
        assertSame(RadioReferenceMode.DIGITAL_ENCRYPTED, RadioReferenceMode.parse("D ENC"));
        assertSame(RadioReferenceMode.DIGITAL_PARTIALLY_ENCRYPTED, RadioReferenceMode.parse("D enc"));
        assertSame(RadioReferenceMode.TDMA_ENCRYPTED, RadioReferenceMode.parse("T\u00A0ENC"));

        //Legacy forms
        assertSame(RadioReferenceMode.DIGITAL_ENCRYPTED, RadioReferenceMode.parse("E"));
        assertSame(RadioReferenceMode.TDMA_ENCRYPTED, RadioReferenceMode.parse("TDE"));
        assertSame(RadioReferenceMode.TDMA_PARTIALLY_ENCRYPTED, RadioReferenceMode.parse("TDe"));

        assertNull(RadioReferenceMode.parse("X"));
        assertNull(RadioReferenceMode.parse("A ENC"));
        assertNull(RadioReferenceMode.parse("D FOO"));
        assertNull(RadioReferenceMode.parse(""));
        assertNull(RadioReferenceMode.parse(null));

        assertTrue(RadioReferenceMode.DIGITAL_ENCRYPTED.isEncrypted());
        assertTrue(RadioReferenceMode.TDMA_ENCRYPTED.isEncrypted());
        assertFalse(RadioReferenceMode.DIGITAL_PARTIALLY_ENCRYPTED.isEncrypted());
        assertTrue(RadioReferenceMode.TDMA_PARTIALLY_ENCRYPTED.isPartiallyEncrypted());
        assertFalse(RadioReferenceMode.DIGITAL.isEncrypted());
        assertTrue(RadioReferenceMode.TDMA.isTdma());
    }

    @Test
    void parsesCurrentSiteFormatWithoutHeader()
    {
        RadioReferenceTable table = RadioReferenceTable.parse("""
            South Australian Ambulance Service (SAAS)  Copy \s
            3028\tbd4\tD ENC\tD99 ADMIN 5\tD99 ADMIN 5\tEMS-Tac
            3022\tbce\tD ENC\tD81 NW TASK 1 (4\tD81 NW TASK 1 (48352)\tEMS-Tac
            2222\t8ae\tD ENC\tD01 METRO 1\tD01 METRO 1\tEMS Dispatch
            159\t09f\tD ENC\tD193 ST JOHN\tD193 ST JOHN\tEMS-Tac
            """);

        assertTrue(table.isRecognised());
        assertEquals(4, table.getRows().size());
        assertTrue(table.getIgnoredLines().isEmpty());

        RadioReferenceTable.Row row = table.getRows().get(1);
        assertEquals(3022, row.talkgroup());
        assertSame(RadioReferenceMode.DIGITAL_ENCRYPTED, row.mode());
        assertEquals("D81 NW TASK 1 (48352)", row.description());
        assertEquals("EMS-Tac", row.tag());
        assertEquals("South Australian Ambulance Service (SAAS)", row.category());
        assertEquals(159, table.getRows().get(3).talkgroup());
    }

    @Test
    void unknownModeWithHeaderIsKept()
    {
        RadioReferenceTable table = RadioReferenceTable.parse("DEC\tHEX\tMode\tAlpha Tag\tDescription\tTag\n" +
            "749\t2ed\t?\tTravel\tTravel ops\tPublic Works\n");
        assertEquals(1, table.getRows().size());
        assertNull(table.getRows().getFirst().mode());
        assertEquals("?", table.getRows().getFirst().modeText());
    }
}
