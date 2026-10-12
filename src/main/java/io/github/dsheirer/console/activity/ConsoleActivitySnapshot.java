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
import java.util.Map;

/**
 * Immutable snapshot of console activity, produced by the ConsoleActivityRouter for the console window.
 *
 * @param timestamp when the snapshot was created, milliseconds since epoch
 * @param cards activity for each card that has call history, keyed by card identifier.  Cards without an entry are
 * idle and have no call history.
 * @param log activity log entries, most recent first
 * @param logVersion incremented each time the activity log changes, so that the log view is only refreshed when needed
 */
public record ConsoleActivitySnapshot(long timestamp, Map<String,ConsoleCardActivity> cards,
                                      List<ConsoleActivityLogEntry> log, long logVersion)
{
    /**
     * Constructs an instance
     */
    public ConsoleActivitySnapshot
    {
        cards = cards != null ? Map.copyOf(cards) : Map.of();
        log = log != null ? List.copyOf(log) : List.of();
    }

    /**
     * Activity for the card.
     * @param cardId to look up
     * @return activity for the card, or an idle activity when the card has no call history.
     */
    public ConsoleCardActivity getCard(String cardId)
    {
        ConsoleCardActivity activity = cardId != null ? cards.get(cardId) : null;
        return activity != null ? activity : ConsoleCardActivity.idle(cardId);
    }
}
