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

/**
 * Immutable snapshot of the call activity for a console card.  Records are compared by value, so the console window
 * can skip updating a card whose activity has not changed since the previous snapshot.
 *
 * @param cardId console card identifier
 * @param state activity state
 * @param talkerId formatted radio identifier of the current (or most recent) talker, or null
 * @param talkerName alias name (or talker alias) of the current talker, or null
 * @param site where the current (or most recent) call is received, or null
 * @param talkgroup formatted TO identifier of the current (or most recent) call, or null
 * @param recentTalkers distinct radios recently heard on the card, most recent first
 * @param lastActivity timestamp of the most recent call activity, milliseconds since epoch, or 0
 * @param clearAlert true while the card's current (or hanging) call raised a clear audio (encryption slip) alert
 */
public record ConsoleCardActivity(String cardId, CardActivityState state, String talkerId, String talkerName,
                                  String site, String talkgroup, List<RecentTalker> recentTalkers, long lastActivity,
                                  boolean clearAlert)
{
    /**
     * Constructs an instance
     */
    public ConsoleCardActivity
    {
        recentTalkers = recentTalkers != null ? List.copyOf(recentTalkers) : List.of();
    }

    /**
     * Creates an idle activity with no call history for the card.
     * @param cardId console card identifier
     * @return idle activity
     */
    public static ConsoleCardActivity idle(String cardId)
    {
        return new ConsoleCardActivity(cardId, CardActivityState.IDLE, null, null, null, null, List.of(), 0, false);
    }

    /**
     * Talker description: alias name and radio identifier when both are available, otherwise whichever is available.
     * @return talker description or an empty string.
     */
    public String getTalkerLabel()
    {
        if(talkerName != null && talkerId != null)
        {
            return talkerName + " " + talkerId;
        }
        else if(talkerName != null)
        {
            return talkerName;
        }
        else if(talkerId != null)
        {
            return talkerId;
        }

        return "";
    }
}
