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
package io.github.dsheirer.console.activity;

/**
 * Activity state of a console card.  Muted is not an activity state: the console window combines an ACTIVE state
 * with the card's mute setting to show a muted-active card.
 */
public enum CardActivityState
{
    /**
     * No call activity.
     */
    IDLE,
    /**
     * A call is in progress (call event updates are arriving, or the console mixer is playing a call for the card).
     */
    ACTIVE,
    /**
     * An encrypted call is in progress.
     */
    ENCRYPTED,
    /**
     * The call ended within the activity hang time.
     */
    HANG;

    /**
     * Indicates if this state represents a call in progress (ACTIVE or ENCRYPTED).
     */
    public boolean isInProgress()
    {
        return this == ACTIVE || this == ENCRYPTED;
    }
}
