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

import io.github.dsheirer.channel.state.State;

/**
 * Immutable status of a running trunking control channel, for the console control channel strip.
 *
 * @param channelName configured channel name
 * @param system configured system name, or null
 * @param site configured site name, or null
 * @param frequency current frequency in Hertz, or 0 when unknown
 * @param decoder decoder type label (e.g. "P25 Phase 1")
 * @param channelState channel (decoder) state, or null when unknown
 * @param detail decoded network details
 */
public record ControlChannelStatus(String channelName, String system, String site, long frequency, String decoder,
                                   State channelState, ControlChannelDetail detail)
{
    /**
     * Constructs an instance
     */
    public ControlChannelStatus
    {
        detail = detail != null ? detail : ControlChannelDetail.NONE;
    }

    /**
     * Operator-friendly control channel state (e.g. LOCKED or SEARCHING).
     */
    public ControlChannelState getState()
    {
        return ControlChannelState.of(channelState);
    }

    /**
     * Display name for the site: configured site name, decoded site name, system name or channel name.
     */
    public String getSiteLabel()
    {
        if(site != null && !site.isBlank())
        {
            return site;
        }

        if(detail.siteName() != null && !detail.siteName().isBlank())
        {
            return detail.siteName();
        }

        if(system != null && !system.isBlank())
        {
            return system;
        }

        return channelName;
    }

    /**
     * Frequency formatted in MHz with 4 decimal places, or an empty string when unknown.
     */
    public String getFrequencyLabel()
    {
        return frequency > 0 ? String.format("%.4f", frequency / 1E6) : "";
    }
}
