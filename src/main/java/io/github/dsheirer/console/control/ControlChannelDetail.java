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
package io.github.dsheirer.console.control;

/**
 * Decoded network details for a control channel, for example from a protocol's network configuration monitor.  Each
 * value is a display string, or null when the value has not been decoded.
 *
 * @param siteName decoded or system-provided site name, or null to use the configured site name
 * @param siteId decoded site identifier (e.g. P25 RFSS-site "1-3")
 * @param networkId decoded network access code / color code / system identifier (e.g. P25 NAC "293")
 * @param decodeQuality decode quality indicator (e.g. "98%"), or null
 */
public record ControlChannelDetail(String siteName, String siteId, String networkId, String decodeQuality)
{
    /**
     * Detail with no decoded values.
     */
    public static final ControlChannelDetail NONE = new ControlChannelDetail(null, null, null, null);
}
