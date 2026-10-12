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
 * Operator-friendly control channel state for the console control channel strip, derived from the channel (decoder)
 * state.
 */
public enum ControlChannelState
{
    /**
     * The trunking control channel is being decoded (channel state CONTROL).
     */
    LOCKED("LOCKED", true),
    /**
     * Messages are being decoded, but not as a control channel (channel states ACTIVE, CALL, DATA and ENCRYPTED),
     * e.g. a DMR channel that is carrying a call.
     */
    DECODING("DECODING", true),
    /**
     * Decoding stopped recently and the channel may recover (channel state FADE).
     */
    FADING("FADING", false),
    /**
     * Not decoding: searching for the control channel (channel states RESET and IDLE, or unknown).
     */
    SEARCHING("SEARCHING", false),
    /**
     * The channel is being torn down (channel state TEARDOWN).
     */
    STOPPING("STOPPING", false);

    private final String mLabel;
    private final boolean mDecoding;

    ControlChannelState(String label, boolean decoding)
    {
        mLabel = label;
        mDecoding = decoding;
    }

    /**
     * Display label
     */
    public String getLabel()
    {
        return mLabel;
    }

    /**
     * Indicates if the channel is currently decoding messages.
     */
    public boolean isDecoding()
    {
        return mDecoding;
    }

    /**
     * Control channel state for the channel state.
     * @param state of the channel, or null when unknown
     * @return control channel state
     */
    public static ControlChannelState of(State state)
    {
        if(state == null)
        {
            return SEARCHING;
        }

        return switch(state)
        {
            case CONTROL -> LOCKED;
            case ACTIVE, CALL, DATA, ENCRYPTED -> DECODING;
            case FADE -> FADING;
            case TEARDOWN -> STOPPING;
            default -> SEARCHING;
        };
    }

    @Override
    public String toString()
    {
        return mLabel;
    }
}
