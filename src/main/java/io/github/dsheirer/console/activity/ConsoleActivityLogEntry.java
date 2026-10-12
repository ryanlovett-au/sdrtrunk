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
 * Immutable console activity log entry for a call that was routed to a console card.  The entry for a call in
 * progress is replaced (same identifier) when the call's talker, encryption or mute status changes.
 *
 * @param id unique (sequence) identifier of the entry
 * @param timestamp when the call started, milliseconds since epoch
 * @param cardId console card identifier
 * @param talkgroupName alias name of the card's alias
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
     * Audio status of a logged call
     */
    public enum Status
    {
        AUDIO("AUDIO"),
        MUTED("MUTED"),
        ENCRYPTED("ENCRYPTED");

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
