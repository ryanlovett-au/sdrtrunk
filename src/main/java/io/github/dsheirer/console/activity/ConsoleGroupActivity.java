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

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Immutable summary of the call activity on the cards of a console group, shown in the header of a collapsed group.
 * Records are compared by value, so the console window only updates the header when the summary changes.
 *
 * The summary features one live card (active, encrypted, clear audio alert or hang).  When several cards are live, the
 * featured card is chosen by priority: a clear audio (encryption slip) alert first, then a call in progress, then a
 * hanging call, and within the same priority the card with the most recent activity (ties: the first card in display
 * order).  The other live cards are counted (e.g. "+2 more").
 *
 * @param cardId identifier of the featured card, or null when no card in the group is live
 * @param state activity state of the featured card (IDLE when no card is live)
 * @param clearAlert true when the featured card's call raised a clear audio alert
 * @param muted true when the featured card's audio is muted (by the card or by its group)
 * @param text compact description of the featured card's call: the card name and the talker, e.g.
 * "23 NR Emergency · 5710363", or an empty string when no card is live
 * @param otherCount number of other live cards in the group
 */
public record ConsoleGroupActivity(String cardId, CardActivityState state, boolean clearAlert, boolean muted,
                                   String text, int otherCount)
{
    /**
     * Separator between the card name and the talker in the summary text.
     */
    public static final String SEPARATOR = " · ";

    /**
     * Summary for a group without live cards.
     */
    public static final ConsoleGroupActivity IDLE = new ConsoleGroupActivity(null, CardActivityState.IDLE, false, false,
        "", 0);

    /**
     * Constructs an instance
     */
    public ConsoleGroupActivity
    {
        state = state != null ? state : CardActivityState.IDLE;
        text = text != null ? text : "";
    }

    /**
     * Creates the activity summary for a group.
     * @param cardIds identifiers of the group's cards, in display order
     * @param snapshot of console activity
     * @param cardNames provides the display name (alias name) of a card, or null when unknown
     * @param audioMuted indicates if a card's audio is muted (by the card or by its group)
     * @return summary, or IDLE when no card in the group is live.
     */
    public static ConsoleGroupActivity of(List<String> cardIds, ConsoleActivitySnapshot snapshot,
                                          Function<String,String> cardNames, Predicate<String> audioMuted)
    {
        if(cardIds == null || snapshot == null)
        {
            return IDLE;
        }

        ConsoleCardActivity featured = null;
        int live = 0;

        for(String cardId: cardIds)
        {
            ConsoleCardActivity activity = snapshot.getCard(cardId);

            if(isLive(activity))
            {
                live++;

                if(featured == null || compare(activity, featured) > 0)
                {
                    featured = activity;
                }
            }
        }

        if(featured == null)
        {
            return IDLE;
        }

        String name = cardNames != null ? cardNames.apply(featured.cardId()) : null;
        boolean muted = audioMuted != null && audioMuted.test(featured.cardId());
        return new ConsoleGroupActivity(featured.cardId(), featured.state(), featured.clearAlert(), muted,
            getText(name, featured), live - 1);
    }

    /**
     * Indicates if the card activity is live: a call in progress or hanging.
     */
    private static boolean isLive(ConsoleCardActivity activity)
    {
        return activity != null && (activity.state().isInProgress() || activity.state() == CardActivityState.HANG);
    }

    /**
     * Compares two live card activities by featuring priority, then by most recent activity.
     * @return a positive value when the first activity should be featured instead of the second.
     */
    private static int compare(ConsoleCardActivity a, ConsoleCardActivity b)
    {
        int priority = Integer.compare(getPriority(a), getPriority(b));
        return priority != 0 ? priority : Long.compare(a.lastActivity(), b.lastActivity());
    }

    /**
     * Featuring priority: clear audio alert, then a call in progress, then a hanging call.
     */
    private static int getPriority(ConsoleCardActivity activity)
    {
        if(activity.clearAlert())
        {
            return 3;
        }

        return activity.state().isInProgress() ? 2 : 1;
    }

    /**
     * Summary text: card name (or the talkgroup when the name is unknown) and the talker name (or radio identifier).
     */
    private static String getText(String name, ConsoleCardActivity activity)
    {
        String label = name != null && !name.isBlank() ? name.trim() :
            (activity.talkgroup() != null ? activity.talkgroup() : "Talkgroup");
        String talker = activity.talkerName() != null && !activity.talkerName().isBlank() ? activity.talkerName() :
            activity.talkerId();

        return talker != null && !talker.isBlank() ? label + SEPARATOR + talker : label;
    }

    /**
     * Indicates if no card in the group is live.
     */
    public boolean isIdle()
    {
        return cardId == null;
    }

    /**
     * Count of the other live cards, e.g. "+2 more", or an empty string when there are none.
     */
    public String getMoreText()
    {
        return otherCount > 0 ? "+" + otherCount + " more" : "";
    }

    /**
     * Status of the featured card, as shown on the card's status pill: CLEAR, ENCRYPTED, RX MUTED, RX or HANG, or an
     * empty string when no card is live.
     */
    public String getStatusLabel()
    {
        if(isIdle())
        {
            return "";
        }

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

    /**
     * Full description for tooltips and accessibility, e.g. "RX: 23 NR Emergency · 5710363, +2 more".
     */
    public String getDescription()
    {
        if(isIdle())
        {
            return "No activity";
        }

        return getStatusLabel() + ": " + text + (otherCount > 0 ? ", " + getMoreText() : "");
    }
}
