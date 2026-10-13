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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the collapsed console group activity summary: featured card selection and summary text.
 */
public class ConsoleGroupActivityTest
{
    private static final List<String> CARDS = List.of("c1", "c2", "c3", "c4");
    private static final Map<String,String> NAMES = Map.of("c1", "23 NR Emergency", "c2", "Fire Dispatch",
        "c3", "Police Tac 2", "c4", "SWAT Ops");

    private final Map<String,ConsoleCardActivity> mActivities = new HashMap<>();

    private void activity(String cardId, CardActivityState state, String talkerId, String talkerName, long last,
                          boolean clearAlert)
    {
        mActivities.put(cardId, new ConsoleCardActivity(cardId, state, talkerId, talkerName, "North Hill", "1001",
            List.of(), last, clearAlert));
    }

    private ConsoleGroupActivity summary(Set<String> muted)
    {
        ConsoleActivitySnapshot snapshot = new ConsoleActivitySnapshot(0, mActivities, List.of(), List.of(), 0);
        return ConsoleGroupActivity.of(CARDS, snapshot, NAMES::get, muted::contains);
    }

    @Test
    void idleGroupHasNoSummary()
    {
        //Cards with history but no live call
        activity("c1", CardActivityState.IDLE, "5710363", null, 1000, false);
        ConsoleGroupActivity summary = summary(Set.of());
        assertSame(ConsoleGroupActivity.IDLE, summary, "Expected idle summary");
        assertTrue(summary.isIdle(), "Idle");
        assertEquals("", summary.getMoreText(), "More text");
        assertEquals("No activity", summary.getDescription(), "Description");
    }

    @Test
    void singleActiveCardShowsNameAndTalker()
    {
        activity("c1", CardActivityState.ACTIVE, "5710363", null, 1000, false);
        ConsoleGroupActivity summary = summary(Set.of());
        assertEquals("c1", summary.cardId(), "Featured card");
        assertEquals("23 NR Emergency \u00B7 5710363", summary.text(), "Summary text");
        assertEquals(0, summary.otherCount(), "Other live cards");
        assertEquals("", summary.getMoreText(), "More text");
        assertEquals("RX", summary.getStatusLabel(), "Status");
        assertEquals("RX: 23 NR Emergency \u00B7 5710363", summary.getDescription(), "Description");
    }

    @Test
    void talkerAliasNameIsPreferredOverRadioId()
    {
        activity("c2", CardActivityState.ACTIVE, "1104523", "Pumper 12", 1000, false);
        assertEquals("Fire Dispatch \u00B7 Pumper 12", summary(Set.of()).text(), "Summary text");
    }

    @Test
    void cardWithoutTalkerShowsNameOnly()
    {
        activity("c4", CardActivityState.ENCRYPTED, null, null, 1000, false);
        ConsoleGroupActivity summary = summary(Set.of());
        assertEquals("SWAT Ops", summary.text(), "Summary text");
        assertEquals("ENCRYPTED", summary.getStatusLabel(), "Status");
    }

    @Test
    void mostRecentActiveCardIsFeaturedWithOtherLiveCardsCounted()
    {
        activity("c1", CardActivityState.ACTIVE, "1", null, 1000, false);
        activity("c2", CardActivityState.ACTIVE, "2", null, 3000, false);
        activity("c3", CardActivityState.ENCRYPTED, "3", null, 2000, false);
        activity("c4", CardActivityState.IDLE, "4", null, 5000, false);
        ConsoleGroupActivity summary = summary(Set.of());
        assertEquals("c2", summary.cardId(), "Expected the most recently active card");
        assertEquals(2, summary.otherCount(), "Other live cards");
        assertEquals("+2 more", summary.getMoreText(), "More text");
        assertEquals("RX: Fire Dispatch \u00B7 2, +2 more", summary.getDescription(), "Description");
    }

    @Test
    void callInProgressIsFeaturedBeforeMoreRecentHang()
    {
        activity("c1", CardActivityState.ACTIVE, "1", null, 1000, false);
        activity("c2", CardActivityState.HANG, "2", null, 3000, false);
        ConsoleGroupActivity summary = summary(Set.of());
        assertEquals("c1", summary.cardId(), "Expected the call in progress");
        assertEquals(1, summary.otherCount(), "Expected the hanging card to be counted");

        mActivities.remove("c1");
        summary = summary(Set.of());
        assertEquals("c2", summary.cardId(), "Expected the hanging card when no call is in progress");
        assertEquals("HANG", summary.getStatusLabel(), "Status");
    }

    @Test
    void clearAudioAlertIsFeaturedFirst()
    {
        activity("c1", CardActivityState.ACTIVE, "1", null, 5000, false);
        activity("c3", CardActivityState.HANG, "3", null, 1000, true);
        ConsoleGroupActivity summary = summary(Set.of());
        assertEquals("c3", summary.cardId(), "Expected the clear audio alert card");
        assertTrue(summary.clearAlert(), "Clear alert");
        assertEquals("CLEAR", summary.getStatusLabel(), "Status");
    }

    @Test
    void tieIsBrokenByDisplayOrder()
    {
        activity("c3", CardActivityState.ACTIVE, "3", null, 1000, false);
        activity("c2", CardActivityState.ACTIVE, "2", null, 1000, false);
        assertEquals("c2", summary(Set.of()).cardId(), "Expected the first card in display order");
    }

    @Test
    void mutedFeaturedCardIsReported()
    {
        activity("c1", CardActivityState.ACTIVE, "1", null, 1000, false);
        ConsoleGroupActivity summary = summary(Set.of("c1"));
        assertTrue(summary.muted(), "Muted");
        assertEquals("RX MUTED", summary.getStatusLabel(), "Status");
        assertFalse(summary(Set.of("c2")).muted(), "Expected muted to apply to the featured card");
    }

    @Test
    void unknownCardNameFallsBackToTalkgroup()
    {
        activity("c1", CardActivityState.ACTIVE, "1", null, 1000, false);
        ConsoleActivitySnapshot snapshot = new ConsoleActivitySnapshot(0, mActivities, List.of(), List.of(), 0);
        assertEquals("1001 \u00B7 1", ConsoleGroupActivity.of(CARDS, snapshot, id -> null, id -> false).text(),
            "Expected the talkgroup when the card name is unknown");
        assertEquals(ConsoleGroupActivity.of(CARDS, snapshot, NAMES::get, id -> false),
            ConsoleGroupActivity.of(CARDS, snapshot, NAMES::get, id -> false),
            "Expected value equality (used to skip header updates)");
    }
}
