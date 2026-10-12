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

import io.github.dsheirer.controller.channel.Channel;

/**
 * Provides decoded network details (site ID, NAC, decode quality) for a running control channel.
 *
 * NetworkSiteDetailProvider is the implementation backed by the protocol network configuration monitors (e.g. P25
 * RFSS/site/NAC) and decode quality trackers; it is registered with the ControlChannelMonitor by the console window.
 * Without a provider, the monitor uses ControlChannelDetail.NONE and the console shows only the configured channel
 * details.
 *
 * Implementations are invoked on the JavaFX application thread (about once per second per control channel) and should
 * return quickly from an already-captured snapshot.
 */
public interface IControlChannelDetailProvider
{
    /**
     * Decoded details for the control channel.
     * @param channel configuration of the running control channel
     * @return details, or ControlChannelDetail.NONE when nothing has been decoded.
     */
    ControlChannelDetail getDetail(Channel channel);
}
