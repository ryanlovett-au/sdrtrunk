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
 * Resolves the decoded site identifier (e.g. P25 RFSS-site "1-3") of the running control channel that a call was
 * received through, from the configuration identifiers carried by the call's decode event.  Used by the console
 * activity router to enrich the card and activity log site, e.g. "North Hill · 1-3".
 *
 * Implementations are invoked on decoder/event threads and must be thread safe and return quickly.
 */
public interface IDecodedSiteResolver
{
    /**
     * Decoded site identifier of the control channel for the call.
     * @param system configured system name of the call's channel, or null
     * @param site configured site name of the call's channel, or null
     * @param channelName configured name of the call's channel (a control channel, or a traffic channel named
     * "T-" followed by its control channel name), or null
     * @return decoded site identifier, or null when not known.
     */
    String getDecodedSiteId(String system, String site, String channelName);
}
