/*
 * *****************************************************************************
 * Copyright (C) 2026 Ryan Lovett
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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the mapping of channel states to operator-friendly control channel states.
 */
public class ControlChannelStateTest
{
    @Test
    void channelStatesMapToControlChannelStates()
    {
        assertEquals(ControlChannelState.LOCKED, ControlChannelState.of(State.CONTROL));
        assertEquals(ControlChannelState.DECODING, ControlChannelState.of(State.ACTIVE));
        assertEquals(ControlChannelState.DECODING, ControlChannelState.of(State.CALL));
        assertEquals(ControlChannelState.DECODING, ControlChannelState.of(State.DATA));
        assertEquals(ControlChannelState.DECODING, ControlChannelState.of(State.ENCRYPTED));
        assertEquals(ControlChannelState.FADING, ControlChannelState.of(State.FADE));
        assertEquals(ControlChannelState.SEARCHING, ControlChannelState.of(State.RESET));
        assertEquals(ControlChannelState.SEARCHING, ControlChannelState.of(State.IDLE));
        assertEquals(ControlChannelState.SEARCHING, ControlChannelState.of(null));
        assertEquals(ControlChannelState.STOPPING, ControlChannelState.of(State.TEARDOWN));

        for(State state: State.values())
        {
            ControlChannelState.of(state);
        }
    }

    @Test
    void labelsAndDecoding()
    {
        assertEquals("LOCKED", ControlChannelState.LOCKED.getLabel());
        assertEquals("SEARCHING", ControlChannelState.SEARCHING.getLabel());
        assertTrue(ControlChannelState.LOCKED.isDecoding());
        assertTrue(ControlChannelState.DECODING.isDecoding());
        assertFalse(ControlChannelState.FADING.isDecoding());
        assertFalse(ControlChannelState.SEARCHING.isDecoding());
        assertFalse(ControlChannelState.STOPPING.isDecoding());
    }
}
