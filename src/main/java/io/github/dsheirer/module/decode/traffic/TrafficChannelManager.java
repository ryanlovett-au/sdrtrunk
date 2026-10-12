/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.module.decode.traffic;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.console.ConsoleCardResolver;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.Module;

/**
 * Traffic Channel Manager base class
 *
 * Supports the optional console talkgroup filter: when a console card resolver is provided and the console talkgroup
 * filter is active, subclasses should not allocate a traffic channel for a call that does not resolve to a console
 * card (see isConsoleAllowed()), but should still track and broadcast the call event with NOT_ON_CONSOLE details.
 */
public abstract class TrafficChannelManager extends Module
{
    /**
     * Decode event details for a call that was not allocated a traffic channel by the console talkgroup filter.
     */
    public static final String NOT_ON_CONSOLE = "IGNORED: NOT ON CONSOLE";

    private long mCurrentControlFrequency;
    private volatile ConsoleCardResolver mConsoleCardResolver;
    private volatile AliasList mAliasList;

    /**
     * Constructs an instance.
     */
    public TrafficChannelManager()
    {
    }

    /**
     * Sets the console card resolver and the alias list for the channel so that traffic channel allocation can be
     * filtered by the console talkgroup filter.  When the resolver is null (default), every call is allowed.
     * @param consoleCardResolver to resolve calls to console cards, or null
     * @param aliasList for the (control) channel
     */
    public void setConsoleCardResolver(ConsoleCardResolver consoleCardResolver, AliasList aliasList)
    {
        mAliasList = aliasList;
        mConsoleCardResolver = consoleCardResolver;
    }

    /**
     * Indicates if a traffic channel can be allocated for the call described by the identifier collection, according
     * to the console talkgroup filter.  Always true when there is no console card resolver or when the filter is not
     * active.  Otherwise, true only when the call's TO identifier (talkgroup, patch group or radio) resolves to a console
     * card.
     *
     * @param identifierCollection for the call
     * @return true if the call is allowed
     */
    protected boolean isConsoleAllowed(IdentifierCollection identifierCollection)
    {
        ConsoleCardResolver resolver = mConsoleCardResolver;
        return resolver == null || resolver.isAllowed(mAliasList, identifierCollection);
    }

    /**
     * Current control frequency
     * @return frequency in hertz
     */
    protected long getCurrentControlFrequency()
    {
        return mCurrentControlFrequency;
    }

    /**
     * Sets the current control frequency.
     * @param frequency that is now the control frequency
     * @param parentChannel channel configuration
     */
    public void setCurrentControlFrequency(long frequency, Channel parentChannel)
    {
        long previous = mCurrentControlFrequency;
        mCurrentControlFrequency = frequency;
        processControlFrequencyUpdate(previous, frequency, parentChannel);
    }

    /**
     * Subclass implementation to receive notification that the control channel frequency has changed when the source
     * is set for multiple frequencies, or in the case of DMR when the REST channel changes.  Subclass should remove
     * any traffic channels currently allocated against the new control frequency.
     *
     * @param previous frequency for the control channel (to remove from allocated channels)
     * @param current frequency for the control channel (to add to allocated channels)
     * @param channel for the current control channel
     */
    protected abstract void processControlFrequencyUpdate(long previous, long current, Channel channel);
}
