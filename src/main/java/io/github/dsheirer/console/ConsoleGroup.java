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
package io.github.dsheirer.console;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Named group of console cards.  Cards are displayed in list order and groups are displayed in display order.
 *
 * A collapsed group is displayed as its header and a summary of the activity on its cards.  The column span is the
 * number of console columns that the group spans (FULL_WIDTH for all columns); the console window clamps it to the
 * number of columns that fit, without changing the setting.  A muted group
 * silences the audio of all of its cards: a card's effective audio mute is its own mute setting OR its group's mute
 * setting, and the two settings are independent, so unmuting the group restores each card's own setting.
 *
 * Note: this is a plain data object that is serialized as JSON.  Modifications should be made via the
 * ConsoleLayoutManager so that constraints are enforced and listeners are notified.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "name", "displayOrder", "collapsed", "muted", "columnSpan", "cards"})
public class ConsoleGroup
{
    /**
     * Column span of a group that spans all console columns.
     */
    public static final int FULL_WIDTH = 0;
    /**
     * Largest column span (other than full width) that can be set.
     */
    public static final int MAXIMUM_COLUMN_SPAN = 12;

    private String mId;
    private String mName;
    private int mDisplayOrder;
    private boolean mCollapsed;
    private boolean mMuted;
    private int mColumnSpan = 1;
    private List<ConsoleCard> mCards = new ArrayList<>();

    /**
     * Constructs an instance.  Used by Jackson for deserialization.
     */
    public ConsoleGroup()
    {
    }

    /**
     * Constructs an instance with a new unique identifier and the specified name.
     * @param name of the group
     */
    public ConsoleGroup(String name)
    {
        mId = UUID.randomUUID().toString();
        mName = name;
    }

    /**
     * Creates a deep copy of this group, including the unique identifier and copies of each card.
     */
    public ConsoleGroup copyOf()
    {
        ConsoleGroup copy = new ConsoleGroup();
        copy.setId(getId());
        copy.setName(getName());
        copy.setDisplayOrder(getDisplayOrder());
        copy.setCollapsed(isCollapsed());
        copy.setMuted(isMuted());
        copy.setColumnSpan(getColumnSpan());

        for(ConsoleCard card: mCards)
        {
            copy.getCards().add(card.copyOf());
        }

        return copy;
    }

    /**
     * Unique identifier for this group.
     */
    @JsonProperty("id")
    public String getId()
    {
        return mId;
    }

    public void setId(String id)
    {
        mId = id;
    }

    /**
     * Group name
     */
    @JsonProperty("name")
    public String getName()
    {
        return mName;
    }

    public void setName(String name)
    {
        mName = name;
    }

    /**
     * Display order of this group relative to other groups (ascending, zero-based).
     */
    @JsonProperty("displayOrder")
    public int getDisplayOrder()
    {
        return mDisplayOrder;
    }

    public void setDisplayOrder(int displayOrder)
    {
        mDisplayOrder = displayOrder;
    }

    /**
     * Indicates if the group is collapsed: the console window shows the group header only.
     */
    @JsonProperty("collapsed")
    public boolean isCollapsed()
    {
        return mCollapsed;
    }

    public void setCollapsed(boolean collapsed)
    {
        mCollapsed = collapsed;
    }

    /**
     * Indicates if the group is muted: no audio is played for any of the group's cards, independent of each card's own
     * mute setting.
     */
    @JsonProperty("muted")
    public boolean isMuted()
    {
        return mMuted;
    }

    public void setMuted(boolean muted)
    {
        mMuted = muted;
    }

    /**
     * Number of console columns that the group spans: 1 (default) or more, or FULL_WIDTH for all columns.  Layout
     * files saved before column spans were added load with a span of 1.
     */
    @JsonProperty("columnSpan")
    public int getColumnSpan()
    {
        return mColumnSpan;
    }

    /**
     * Sets the column span, constrained to FULL_WIDTH or 1 - MAXIMUM_COLUMN_SPAN (negative values are treated as 1).
     */
    public void setColumnSpan(int columnSpan)
    {
        mColumnSpan = normalizeColumnSpan(columnSpan);
    }

    /**
     * Constrains a column span to FULL_WIDTH or 1 - MAXIMUM_COLUMN_SPAN (negative values are treated as 1).
     */
    public static int normalizeColumnSpan(int columnSpan)
    {
        if(columnSpan == FULL_WIDTH)
        {
            return FULL_WIDTH;
        }

        return Math.clamp(columnSpan, 1, MAXIMUM_COLUMN_SPAN);
    }

    /**
     * Cards in this group, in display order.
     */
    @JsonProperty("cards")
    public List<ConsoleCard> getCards()
    {
        return mCards;
    }

    public void setCards(List<ConsoleCard> cards)
    {
        mCards = cards != null ? new ArrayList<>(cards) : new ArrayList<>();
    }

    /**
     * Finds the card with the specified card identifier.
     * @param cardId to find
     * @return matching card or null.
     */
    public ConsoleCard getCard(String cardId)
    {
        if(cardId != null)
        {
            for(ConsoleCard card: mCards)
            {
                if(cardId.equals(card.getId()))
                {
                    return card;
                }
            }
        }

        return null;
    }

    @Override
    public String toString()
    {
        return "Console Group [" + getName() + "] id [" + getId() + "] collapsed [" + isCollapsed() + "] muted [" +
            isMuted() + "] span [" + getColumnSpan() + "] cards [" + mCards.size() + "]";
    }
}
