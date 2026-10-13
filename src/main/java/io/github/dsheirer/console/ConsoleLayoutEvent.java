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
package io.github.dsheirer.console;

/**
 * Console layout change event broadcast by the ConsoleLayoutManager.
 *
 * Events carry the identifier of the affected group and/or a snapshot (copy) of the affected card.  Listeners that
 * need the full current state can request a snapshot of the layout from the manager.
 */
public class ConsoleLayoutEvent
{
    /**
     * Type of layout change
     */
    public enum Type
    {
        /**
         * Layout was (re)loaded and listeners should refresh from a full layout snapshot.
         */
        LAYOUT_LOADED,
        /**
         * Console mode, activity hang time or talkgroup filter setting changed.
         */
        SETTINGS_CHANGED,
        GROUP_ADDED,
        /**
         * Group was removed.  CARD_REMOVED events are broadcast for each of the group's cards prior to this event.
         */
        GROUP_REMOVED,
        /**
         * Group name changed.
         */
        GROUP_CHANGED,
        /**
         * Group collapsed state, muted state or column span changed.  Listeners can update the group in place (no
         * rebuild is needed).
         */
        GROUP_STATE_CHANGED,
        /**
         * Group display order changed.
         */
        GROUPS_REORDERED,
        CARD_ADDED,
        CARD_REMOVED,
        /**
         * Card volume, mute or pan changed.
         */
        CARD_CHANGED,
        /**
         * Card moved within a group or to another group.  The group identifier is the destination group.
         */
        CARD_MOVED,
        /**
         * Card was re-bound (re-linked) by the user to a different alias.  The card keeps its identifier and settings.
         */
        CARD_REBOUND
    }

    private final Type mType;
    private final String mGroupId;
    private final ConsoleCard mCard;

    /**
     * Constructs an instance
     * @param type of event
     * @param groupId of the affected group, or null
     * @param card snapshot of the affected card, or null
     */
    public ConsoleLayoutEvent(Type type, String groupId, ConsoleCard card)
    {
        mType = type;
        mGroupId = groupId;
        mCard = card;
    }

    /**
     * Event type
     */
    public Type getType()
    {
        return mType;
    }

    /**
     * Identifier of the affected group, or null when the event does not apply to a group.
     */
    public String getGroupId()
    {
        return mGroupId;
    }

    /**
     * Snapshot (copy) of the affected card, or null when the event does not apply to a card.
     */
    public ConsoleCard getCard()
    {
        return mCard;
    }

    /**
     * Indicates if this event has a card
     */
    public boolean hasCard()
    {
        return mCard != null;
    }

    @Override
    public String toString()
    {
        return "Console Layout Event [" + mType + "] group [" + mGroupId + "] card [" + mCard + "]";
    }
}
