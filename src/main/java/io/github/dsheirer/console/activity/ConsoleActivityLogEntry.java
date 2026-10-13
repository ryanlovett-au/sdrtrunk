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

/**
 * Immutable console activity log entry for a call.  The entry for a call in progress is replaced (same identifier)
 * when the call's talker, encryption or mute status changes.
 *
 * Calls routed to a console card have a card identifier.  The all-traffic log also has entries for calls that are not
 * on the console (null card identifier), with an IGNORED, NOT_ON_CONSOLE or ENCRYPTED status.
 *
 * @param id unique (sequence) identifier of the entry
 * @param timestamp when the call started, milliseconds since epoch
 * @param cardId console card identifier, or null when the call is not on the console
 * @param talkgroupName alias name of the card's alias (or of the talkgroup's alias, or the formatted TO identifier
 * when the call is not on the console)
 * @param talkgroup formatted TO identifier of the call
 * @param radioName alias name of the talker, or null
 * @param radioId formatted radio identifier of the talker, or null
 * @param site where the call was received, or null
 * @param status of the call
 */
public record ConsoleActivityLogEntry(long id, long timestamp, String cardId, String talkgroupName, String talkgroup,
                                      String radioName, String radioId, String site, Status status)
{
    /**
     * Indicates if the call was routed to a console card.
     */
    public boolean isOnConsole()
    {
        return cardId != null;
    }

    /**
     * Separator between the console group name and the talkgroup name in the talkgroup label.
     */
    public static final String GROUP_SEPARATOR = " - ";

    /**
     * Talkgroup label for the activity log: for calls on a console card, the name of the card's (current) console group
     * and the talkgroup name, e.g. "Ops - TG 23 Primary", otherwise the talkgroup name.
     * @param groupName of the console group that contains the call's card, or null when unknown
     * @return label, or null when the entry has no talkgroup name and is not labelled with a group.
     */
    public String getTalkgroupLabel(String groupName)
    {
        if(isOnConsole() && groupName != null && !groupName.isBlank())
        {
            String name = talkgroupName != null ? talkgroupName : (talkgroup != null ? talkgroup : "");
            return groupName + GROUP_SEPARATOR + name;
        }

        return talkgroupName;
    }

    /**
     * Audio status of a logged call
     */
    public enum Status
    {
        /**
         * On the console: audio played
         */
        AUDIO("AUDIO"),
        /**
         * On the console: the card (or the card's group) is muted
         */
        MUTED("MUTED"),
        /**
         * Encrypted call (on or off the console)
         */
        ENCRYPTED("ENCRYPTED"),
        /**
         * On the console: clear audio on a talkgroup that is normally encrypted (encryption slip alert)
         */
        CLEAR("CLEAR \u26A0"),
        /**
         * Not on the console: the traffic channel manager did not allocate a traffic channel (console talkgroup filter)
         */
        IGNORED("IGNORED"),
        /**
         * Not on the console: decoded, but the talkgroup does not have a console card
         */
        NOT_ON_CONSOLE("NOT ON CONSOLE");

        private final String mLabel;

        Status(String label)
        {
            mLabel = label;
        }

        @Override
        public String toString()
        {
            return mLabel;
        }
    }
}
