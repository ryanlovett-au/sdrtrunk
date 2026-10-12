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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Console layout.  Contains the console settings and the ordered set of console groups, each containing console cards.
 *
 * Note: this is a plain data object that is serialized as JSON.  Modifications should be made via the
 * ConsoleLayoutManager so that constraints are enforced and listeners are notified.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"version", "consoleModeEnabled", "activityHangTimeMs", "talkgroupFilterEnabled", "groups"})
public class ConsoleLayout
{
    public static final int CURRENT_VERSION = 1;
    public static final long DEFAULT_ACTIVITY_HANG_TIME_MS = 2000;
    public static final boolean DEFAULT_TALKGROUP_FILTER_ENABLED = true;
    public static final boolean DEFAULT_CONSOLE_MODE_ENABLED = false;

    private int mVersion = CURRENT_VERSION;
    private boolean mConsoleModeEnabled = DEFAULT_CONSOLE_MODE_ENABLED;
    private long mActivityHangTimeMs = DEFAULT_ACTIVITY_HANG_TIME_MS;
    private boolean mTalkgroupFilterEnabled = DEFAULT_TALKGROUP_FILTER_ENABLED;
    private List<ConsoleGroup> mGroups = new ArrayList<>();

    /**
     * Constructs an empty layout with default settings.
     */
    public ConsoleLayout()
    {
    }

    /**
     * Creates a deep copy of this layout.
     */
    public ConsoleLayout copyOf()
    {
        ConsoleLayout copy = new ConsoleLayout();
        copy.setVersion(getVersion());
        copy.setConsoleModeEnabled(isConsoleModeEnabled());
        copy.setActivityHangTimeMs(getActivityHangTimeMs());
        copy.setTalkgroupFilterEnabled(isTalkgroupFilterEnabled());

        for(ConsoleGroup group: mGroups)
        {
            copy.getGroups().add(group.copyOf());
        }

        return copy;
    }

    /**
     * Layout file format version.
     */
    @JsonProperty("version")
    public int getVersion()
    {
        return mVersion;
    }

    public void setVersion(int version)
    {
        mVersion = version;
    }

    /**
     * Indicates if console mode is enabled.  When enabled, the console audio mixer replaces the traditional audio
     * playback and the talkgroup filter (when enabled) is applied.  When disabled, the application behaves exactly as
     * it does without a console.
     */
    @JsonProperty("consoleModeEnabled")
    public boolean isConsoleModeEnabled()
    {
        return mConsoleModeEnabled;
    }

    public void setConsoleModeEnabled(boolean consoleModeEnabled)
    {
        mConsoleModeEnabled = consoleModeEnabled;
    }

    /**
     * Time in milliseconds that a card continues to show activity after a call ends.
     */
    @JsonProperty("activityHangTimeMs")
    public long getActivityHangTimeMs()
    {
        return mActivityHangTimeMs;
    }

    public void setActivityHangTimeMs(long activityHangTimeMs)
    {
        mActivityHangTimeMs = activityHangTimeMs;
    }

    /**
     * Indicates if the console talkgroup (grant) filter is enabled.  The filter is only applied while console mode
     * is enabled.
     */
    @JsonProperty("talkgroupFilterEnabled")
    public boolean isTalkgroupFilterEnabled()
    {
        return mTalkgroupFilterEnabled;
    }

    public void setTalkgroupFilterEnabled(boolean talkgroupFilterEnabled)
    {
        mTalkgroupFilterEnabled = talkgroupFilterEnabled;
    }

    /**
     * Console groups, in display order.
     */
    @JsonProperty("groups")
    public List<ConsoleGroup> getGroups()
    {
        return mGroups;
    }

    public void setGroups(List<ConsoleGroup> groups)
    {
        mGroups = groups != null ? new ArrayList<>(groups) : new ArrayList<>();
    }

    /**
     * Finds the group with the specified group identifier.
     * @param groupId to find
     * @return matching group or null.
     */
    public ConsoleGroup getGroup(String groupId)
    {
        if(groupId != null)
        {
            for(ConsoleGroup group: mGroups)
            {
                if(groupId.equals(group.getId()))
                {
                    return group;
                }
            }
        }

        return null;
    }

    /**
     * Finds the group that contains the card with the specified card identifier.
     * @param cardId to find
     * @return group containing the card or null.
     */
    public ConsoleGroup getGroupForCard(String cardId)
    {
        if(cardId != null)
        {
            for(ConsoleGroup group: mGroups)
            {
                if(group.getCard(cardId) != null)
                {
                    return group;
                }
            }
        }

        return null;
    }

    /**
     * Finds the card with the specified card identifier across all groups.
     * @param cardId to find
     * @return matching card or null.
     */
    public ConsoleCard getCard(String cardId)
    {
        ConsoleGroup group = getGroupForCard(cardId);
        return group != null ? group.getCard(cardId) : null;
    }

    /**
     * Finds the card that is bound to the specified alias unique identifier across all groups.
     * @param aliasUuid to find
     * @return matching card or null.
     */
    public ConsoleCard getCardForAlias(String aliasUuid)
    {
        if(aliasUuid != null)
        {
            for(ConsoleGroup group: mGroups)
            {
                for(ConsoleCard card: group.getCards())
                {
                    if(aliasUuid.equals(card.getAliasUuid()))
                    {
                        return card;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Total count of cards across all groups.
     */
    @JsonIgnore
    public int getCardCount()
    {
        int count = 0;

        for(ConsoleGroup group: mGroups)
        {
            count += group.getCards().size();
        }

        return count;
    }
}
