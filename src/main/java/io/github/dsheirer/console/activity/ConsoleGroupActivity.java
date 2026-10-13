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
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Activity summary for a collapsed console group: up to MAXIMUM_ROWS rows for the group's live cards (calls in
 * progress or hanging), the count of the other live cards ("+N more active"), the count of calls in progress (the
 * header's "N ACTIVE") and the number of cards (for "202 talkgroups · no activity").
 *
 * Rows are ordered by the start of each card's live period (oldest first, ties in display order), so that rows do not
 * jump while calls progress and new calls are appended.  When more cards are live than there are rows, the oldest are
 * shown, except that cards with a clear audio (encryption slip) alert are always shown (in place of the newest rows).
 *
 * Summaries are compared by value, so the console window can skip updating a group whose summary has not changed.
 *
 * @param rows shown, in display order
 * @param liveCount number of live cards in the group (including the rows)
 * @param activeCount number of cards with a call in progress (ACTIVE or ENCRYPTED)
 * @param cardCount number of cards in the group
 */
public record ConsoleGroupActivity(List<Row> rows, int liveCount, int activeCount, int cardCount)
{
    /**
     * Maximum number of rows shown for a collapsed group.
     */
    public static final int MAXIMUM_ROWS = 4;

    /**
     * Separator between parts of the summary text.
     */
    public static final String SEPARATOR = " · ";

    /**
     * Summary for a group without cards.
     */
    public static final ConsoleGroupActivity IDLE = new ConsoleGroupActivity(List.of(), 0, 0, 0);

    /**
     * Constructs an instance
     */
    public ConsoleGroupActivity
    {
        rows = rows != null ? List.copyOf(rows) : List.of();
    }

    /**
     * Creates the activity summary for a group.
     * @param cardIds identifiers of the group's cards, in display order
     * @param snapshot of console activity
     * @param cardNames provides the display name (alias name) of a card, or null when unknown
     * @param audioMuted indicates if a card's audio is muted (by the card or by its group)
     * @return summary
     */
    public static ConsoleGroupActivity of(List<String> cardIds, ConsoleActivitySnapshot snapshot,
                                          Function<String,String> cardNames, Predicate<String> audioMuted)
    {
        if(cardIds == null || snapshot == null)
        {
            return IDLE;
        }

        List<Live> live = new ArrayList<>();
        int active = 0;

        for(int x = 0; x < cardIds.size(); x++)
        {
            ConsoleCardActivity activity = snapshot.cards().get(cardIds.get(x));

            if(activity != null && activity.isLive())
            {
                live.add(new Live(activity, x));

                if(activity.state().isInProgress())
                {
                    active++;
                }
            }
        }

        if(live.isEmpty())
        {
            return new ConsoleGroupActivity(List.of(), 0, 0, cardIds.size());
        }

        Comparator<Live> byStart = Comparator.comparingLong((Live l) -> l.activity().callStart())
            .thenComparingInt(Live::index);
        live.sort(byStart);

        List<Live> shown = new ArrayList<>(live.subList(0, Math.min(MAXIMUM_ROWS, live.size())));

        //Clear audio alerts beyond the shown rows replace the newest rows that are not alerts
        for(Live candidate: live.subList(shown.size(), live.size()))
        {
            if(candidate.activity().clearAlert())
            {
                for(int x = shown.size() - 1; x >= 0; x--)
                {
                    if(!shown.get(x).activity().clearAlert())
                    {
                        shown.set(x, candidate);
                        break;
                    }
                }
            }
        }

        shown.sort(byStart);
        List<Row> rows = new ArrayList<>();

        for(Live entry: shown)
        {
            ConsoleCardActivity activity = entry.activity();
            String name = cardNames != null ? cardNames.apply(activity.cardId()) : null;
            String label = name != null && !name.isBlank() ? name.trim() :
                (activity.talkgroup() != null ? activity.talkgroup() : "Talkgroup");
            boolean muted = audioMuted != null && audioMuted.test(activity.cardId());
            rows.add(new Row(activity.cardId(), label, activity.getTalker(), activity.state(), activity.hangFrom(),
                muted, activity.clearAlert(), activity.callStart()));
        }

        return new ConsoleGroupActivity(rows, live.size(), active, cardIds.size());
    }

    /**
     * Indicates if no card in the group is live.
     */
    public boolean isIdle()
    {
        return liveCount == 0;
    }

    /**
     * Number of live cards that are not shown as rows.
     */
    public int moreCount()
    {
        return Math.max(0, liveCount - rows.size());
    }

    /**
     * Text of the row that counts the live cards that are not shown, e.g. "+2 more active", or an empty string when
     * all live cards are shown.
     */
    public String getMoreText()
    {
        return moreCount() > 0 ? "+" + moreCount() + " more active" : "";
    }

    /**
     * Text shown when no card is live, e.g. "202 talkgroups · no activity".
     */
    public String getIdleText()
    {
        return (cardCount == 1 ? "1 talkgroup" : cardCount + " talkgroups") + SEPARATOR + "no activity";
    }

    /**
     * Full description for tooltips and accessibility, e.g. "RX: Fire Dispatch · Pumper 12, ENCRYPTED: SWAT Ops,
     * +2 more active".
     */
    public String getDescription()
    {
        if(isIdle())
        {
            return getIdleText();
        }

        StringBuilder sb = new StringBuilder();

        for(Row row: rows)
        {
            if(!sb.isEmpty())
            {
                sb.append(", ");
            }

            sb.append(row.getStatusLabel()).append(": ").append(row.name());

            if(!row.talker().isBlank())
            {
                sb.append(SEPARATOR).append(row.talker());
            }
        }

        if(moreCount() > 0)
        {
            sb.append(", ").append(getMoreText());
        }

        return sb.toString();
    }

    /**
     * Live card with its display order index, for sorting.
     */
    private record Live(ConsoleCardActivity activity, int index)
    {
    }

    /**
     * Summary row for a live card.
     * @param cardId card identifier
     * @param name card name (alias name), or the talkgroup when the name is unknown
     * @param talker current talker: alias name or radio identifier, or an empty string
     * @param state activity state (ACTIVE, ENCRYPTED or HANG)
     * @param hangFrom while hanging: the state of the call before it ended (ACTIVE or ENCRYPTED), otherwise null
     * @param muted true when the card's audio is muted (by the card or by its group)
     * @param clearAlert true when the call raised a clear audio (encryption slip) alert
     * @param callStart start of the card's live period
     */
    public record Row(String cardId, String name, String talker, CardActivityState state, CardActivityState hangFrom,
                      boolean muted, boolean clearAlert, long callStart)
    {
        /**
         * Constructs an instance
         */
        public Row
        {
            talker = talker != null ? talker : "";
        }

        /**
         * Status, as shown on the card's status pill: CLEAR, ENCRYPTED, RX MUTED, RX or HANG.
         */
        public String getStatusLabel()
        {
            if(clearAlert)
            {
                return "CLEAR";
            }

            return switch(state)
            {
                case ACTIVE -> muted ? "RX MUTED" : "RX";
                case ENCRYPTED -> "ENCRYPTED";
                case HANG -> "HANG";
                default -> "";
            };
        }
    }
}
