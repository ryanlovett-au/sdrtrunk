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
 * Note: this is a plain data object that is serialized as JSON.  Modifications should be made via the
 * ConsoleLayoutManager so that constraints are enforced and listeners are notified.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "name", "displayOrder", "cards"})
public class ConsoleGroup
{
    private String mId;
    private String mName;
    private int mDisplayOrder;
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
        return "Console Group [" + getName() + "] id [" + getId() + "] cards [" + mCards.size() + "]";
    }
}
