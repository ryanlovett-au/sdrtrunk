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

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for duplicate talkgroup and overlapping talkgroup range detection.
 */
public class TalkgroupConflictDetectorTest
{
    private static Alias alias(String list, String name, AliasID... ids)
    {
        Alias alias = new Alias(name);
        alias.setAliasListName(list);

        for(AliasID id: ids)
        {
            alias.addAliasID(id);
        }

        return alias;
    }

    private static Talkgroup p25(int value)
    {
        return new Talkgroup(Protocol.APCO25, value);
    }

    @Test
    void duplicateTalkgroupInSameListIsAConflict()
    {
        Alias south = alias("Metro", "Police Ops South", p25(3002));
        Alias alt = alias("Metro", "Police South Alt", p25(3002));
        Alias north = alias("Metro", "Police Ops North", p25(3001));

        TalkgroupConflictDetector.Report report = TalkgroupConflictDetector.findConflicts(List.of(south, alt, north));

        assertEquals(1, report.getConflicts().size());
        TalkgroupConflict conflict = report.getConflicts().getFirst();
        assertEquals("TG 3002", conflict.description());
        assertEquals(List.of(south, alt), conflict.aliases());
        assertEquals("TG 3002 (APCO-25) is used by 2 aliases: Police Ops South, Police South Alt",
            conflict.getMessage());
        assertTrue(report.hasConflict(south));
        assertFalse(report.hasConflict(north));
        assertEquals(1, report.getConflictsForList("Metro").size());
        assertEquals(0, report.getConflictsForList("Regional").size());
    }

    @Test
    void differentListOrProtocolIsNotAConflict()
    {
        Alias metro = alias("Metro", "A", p25(100));
        Alias regional = alias("Regional", "B", p25(100));
        Alias dmr = alias("Metro", "C", new Talkgroup(Protocol.DMR, 100));

        assertTrue(TalkgroupConflictDetector.findConflicts(List.of(metro, regional, dmr)).getConflicts().isEmpty());
    }

    @Test
    void sameAliasWithDuplicateTalkgroupIsNotAConflict()
    {
        Alias alias = alias("Metro", "A", p25(100), p25(100));
        assertTrue(TalkgroupConflictDetector.findConflicts(List.of(alias)).getConflicts().isEmpty());
    }

    @Test
    void overlappingRangesAreAConflict()
    {
        Alias a = alias("Metro", "A", new TalkgroupRange(Protocol.APCO25, 100, 200));
        Alias b = alias("Metro", "B", new TalkgroupRange(Protocol.APCO25, 150, 250));
        Alias c = alias("Metro", "C", new TalkgroupRange(Protocol.APCO25, 300, 400));

        TalkgroupConflictDetector.Report report = TalkgroupConflictDetector.findConflicts(List.of(a, b, c));

        assertEquals(1, report.getConflicts().size());
        assertEquals("TG ranges 100-200 and 150-250", report.getConflicts().getFirst().description());
        assertTrue(report.hasConflict(a));
        assertTrue(report.hasConflict(b));
        assertFalse(report.hasConflict(c));
    }

    @Test
    void talkgroupWithinRangeIsNotAConflict()
    {
        //An exact talkgroup match takes precedence over a range, so a talkgroup inside another alias's range is allowed
        Alias range = alias("Metro", "All Fire", new TalkgroupRange(Protocol.APCO25, 1000, 1999));
        Alias specific = alias("Metro", "Fire Dispatch", p25(1001));

        assertTrue(TalkgroupConflictDetector.findConflicts(List.of(range, specific)).getConflicts().isEmpty());
    }

    @Test
    void aliasesWithoutListShareTheEmptyList()
    {
        Alias a = alias(null, "A", p25(5));
        Alias b = alias("", "B", p25(5));
        assertEquals(1, TalkgroupConflictDetector.findConflicts(List.of(a, b)).getConflicts().size());
    }
}
