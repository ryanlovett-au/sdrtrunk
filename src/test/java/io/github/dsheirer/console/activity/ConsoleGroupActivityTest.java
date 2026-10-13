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
package io.github.dsheirer.console.activity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the collapsed console group activity summary: rows for the live cards in call start order, the "+N more
 * active" count, status labels and the idle text.
 */
public class ConsoleGroupActivityTest
{
    private static final List<String> CARDS = List.of("c1", "c2", "c3", "c4", "c5", "c6", "c7");
    private static final Map<String,String> NAMES = Map.of("c1", "23 NR Emergency", "c2", "Fire Dispatch",
        "c3", "Police Tac 2", "c4", "SWAT Ops", "c5", "EMS 1", "c6", "EMS 2", "c7", "Ops");

    private final Map<String,ConsoleCardActivity> mActivities = new HashMap<>();

    private void activity(String cardId, CardActivityState state, String talkerId, String talkerName, long start,
                          boolean clearAlert)
    {
        activity(cardId, state, null, talkerId, talkerName, start, clearAlert);
    }

    private void activity(String cardId, CardActivityState state, CardActivityState hangFrom, String talkerId,
                          String talkerName, long start, boolean clearAlert)
    {
        mActivities.put(cardId, new ConsoleCardActivity(cardId, state, talkerId, talkerName, "North Hill", "1001",
            List.of(), start + 500, clearAlert, state == CardActivityState.IDLE ? 0 : start, hangFrom));
    }

    private ConsoleGroupActivity summary(Set<String> muted)
    {
        ConsoleActivitySnapshot snapshot = new ConsoleActivitySnapshot(0, mActivities, List.of(), List.of(), 0);
        return ConsoleGroupActivity.of(CARDS, snapshot, NAMES::get, muted::contains);
    }

    private static List<String> rowCards(ConsoleGroupActivity summary)
    {
        List<String> cards = new ArrayList<>();

        for(ConsoleGroupActivity.Row row: summary.rows())
        {
            cards.add(row.cardId());
        }

        return cards;
    }

    @Test
    void idleGroupShowsCardCount()
    {
        //Cards with history but no live call
        activity("c1", CardActivityState.IDLE, "5710363", null, 1000, false);
        ConsoleGroupActivity summary = summary(Set.of());
        assertTrue(summary.isIdle(), "Idle");
        assertTrue(summary.rows().isEmpty(), "No rows");
        assertEquals(0, summary.activeCount(), "Active count");
        assertEquals("", summary.getMoreText(), "More text");
        assertEquals("7 talkgroups · no activity", summary.getIdleText(), "Idle text");
        assertEquals("7 talkgroups · no activity", summary.getDescription(), "Description");
        assertEquals("1 talkgroup · no activity", new ConsoleGroupActivity(List.of(), 0, 0, 1).getIdleText());
        assertEquals("202 talkgroups · no activity", new ConsoleGroupActivity(null, 0, 0, 202).getIdleText());
    }

    @Test
    void rowShowsNameTalkerAndStatus()
    {
        activity("c1", CardActivityState.ACTIVE, "5710363", null, 1000, false);
        ConsoleGroupActivity summary = summary(Set.of());
        assertEquals(1, summary.rows().size(), "Rows");
        ConsoleGroupActivity.Row row = summary.rows().getFirst();
        assertEquals("c1", row.cardId(), "Card");
        assertEquals("23 NR Emergency", row.name(), "Name");
        assertEquals("5710363", row.talker(), "Talker (radio identifier)");
        assertEquals("RX", row.getStatusLabel(), "Status");
        assertEquals(1, summary.activeCount(), "Active count");
        assertEquals(0, summary.moreCount(), "More");
        assertEquals("RX: 23 NR Emergency · 5710363", summary.getDescription(), "Description");
    }

    @Test
    void talkerAliasNameIsPreferredOverRadioId()
    {
        activity("c2", CardActivityState.ACTIVE, "1104523", "Pumper 12", 1000, false);
        assertEquals("Pumper 12", summary(Set.of()).rows().getFirst().talker(), "Talker");
    }

    @Test
    void cardWithoutTalkerHasEmptyTalker()
    {
        activity("c4", CardActivityState.ENCRYPTED, null, null, 1000, false);
        ConsoleGroupActivity.Row row = summary(Set.of()).rows().getFirst();
        assertEquals("", row.talker(), "Talker");
        assertEquals("ENCRYPTED", row.getStatusLabel(), "Status");
        assertEquals("ENCRYPTED: SWAT Ops", summary(Set.of()).getDescription(), "Description");
    }

    @Test
    void rowsAreOrderedByCallStartOldestFirst()
    {
        activity("c3", CardActivityState.ACTIVE, "3", null, 3000, false);
        activity("c1", CardActivityState.HANG, "1", null, 1000, false);
        activity("c2", CardActivityState.ENCRYPTED, "2", null, 2000, false);
        activity("c4", CardActivityState.IDLE, "4", null, 500, false);
        ConsoleGroupActivity summary = summary(Set.of());
        assertEquals(List.of("c1", "c2", "c3"), rowCards(summary), "Expected call start order");
        assertEquals(3, summary.liveCount(), "Live cards (hanging included)");
        assertEquals(2, summary.activeCount(), "Calls in progress (hanging excluded)");
    }

    @Test
    void rowsDoNotJumpWhileCallsProgress()
    {
        activity("c2", CardActivityState.ACTIVE, "2", null, 1000, false);
        activity("c1", CardActivityState.ACTIVE, "1", null, 2000, false);
        List<String> before = rowCards(summary(Set.of()));

        //More recent activity on the first row's card (a later lastActivity, talker change) does not reorder the rows
        mActivities.put("c2", new ConsoleCardActivity("c2", CardActivityState.ACTIVE, "9", null, "North Hill", "1001",
            List.of(), 9000, false, 1000, null));
        assertEquals(before, rowCards(summary(Set.of())), "Expected stable rows");
        assertEquals(List.of("c2", "c1"), before);

        //A new call is appended
        activity("c5", CardActivityState.ACTIVE, "5", null, 3000, false);
        assertEquals(List.of("c2", "c1", "c5"), rowCards(summary(Set.of())), "Expected the new call appended");
    }

    @Test
    void tieIsBrokenByDisplayOrder()
    {
        activity("c3", CardActivityState.ACTIVE, "3", null, 1000, false);
        activity("c2", CardActivityState.ACTIVE, "2", null, 1000, false);
        assertEquals(List.of("c2", "c3"), rowCards(summary(Set.of())), "Expected display order for equal starts");
    }

    @Test
    void atMostFourRowsWithMoreCount()
    {
        for(int x = 1; x <= 6; x++)
        {
            activity("c" + x, CardActivityState.ACTIVE, String.valueOf(x), null, x * 1000L, false);
        }

        ConsoleGroupActivity summary = summary(Set.of());
        assertEquals(List.of("c1", "c2", "c3", "c4"), rowCards(summary), "Expected the four oldest calls");
        assertEquals(6, summary.liveCount(), "Live");
        assertEquals(2, summary.moreCount(), "More");
        assertEquals("+2 more active", summary.getMoreText(), "More text");
        assertTrue(summary.getDescription().endsWith(", +2 more active"), "Description: " + summary.getDescription());
    }

    @Test
    void clearAudioAlertIsAlwaysShown()
    {
        for(int x = 1; x <= 6; x++)
        {
            activity("c" + x, CardActivityState.ACTIVE, String.valueOf(x), null, x * 1000L, x == 6);
        }

        ConsoleGroupActivity summary = summary(Set.of());
        assertEquals(List.of("c1", "c2", "c3", "c6"), rowCards(summary),
            "Expected the clear alert in place of the newest row");
        ConsoleGroupActivity.Row row = summary.rows().get(3);
        assertTrue(row.clearAlert(), "Clear alert");
        assertEquals("CLEAR", row.getStatusLabel(), "Status");
        assertEquals(2, summary.moreCount(), "More");
    }

    @Test
    void hangingRowsCarryTheCallState()
    {
        activity("c1", CardActivityState.HANG, CardActivityState.ENCRYPTED, "1", null, 1000, false);
        activity("c2", CardActivityState.HANG, null, "2", null, 2000, false);
        activity("c3", CardActivityState.ACTIVE, "3", null, 3000, false);
        List<ConsoleGroupActivity.Row> rows = summary(Set.of()).rows();
        assertEquals("HANG", rows.get(0).getStatusLabel(), "Status");
        assertEquals(CardActivityState.ENCRYPTED, rows.get(0).hangFrom(), "Hang after an encrypted call");
        assertEquals(CardActivityState.ACTIVE, rows.get(1).hangFrom(), "Hang after a clear call");
        assertNull(rows.get(2).hangFrom(), "No hang state for a call in progress");
    }

    @Test
    void mutedCardIsReported()
    {
        activity("c1", CardActivityState.ACTIVE, "1", null, 1000, false);
        activity("c2", CardActivityState.ACTIVE, "2", null, 2000, false);
        List<ConsoleGroupActivity.Row> rows = summary(Set.of("c1")).rows();
        assertTrue(rows.get(0).muted(), "Muted");
        assertEquals("RX MUTED", rows.get(0).getStatusLabel(), "Status");
        assertFalse(rows.get(1).muted(), "Expected muted to apply to the muted card only");
    }

    @Test
    void unknownCardNameFallsBackToTalkgroup()
    {
        activity("c1", CardActivityState.ACTIVE, "1", null, 1000, false);
        ConsoleActivitySnapshot snapshot = new ConsoleActivitySnapshot(0, mActivities, List.of(), List.of(), 0);
        assertEquals("1001", ConsoleGroupActivity.of(CARDS, snapshot, id -> null, id -> false).rows().getFirst()
            .name(), "Expected the talkgroup when the card name is unknown");
        assertEquals(ConsoleGroupActivity.of(CARDS, snapshot, NAMES::get, id -> false),
            ConsoleGroupActivity.of(CARDS, snapshot, NAMES::get, id -> false),
            "Expected value equality (used to skip summary updates)");
    }
}
