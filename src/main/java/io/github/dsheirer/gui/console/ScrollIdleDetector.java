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
package io.github.dsheirer.gui.console;

/**
 * Detects scrolling: scrolling continues until there has been no scroll movement for the idle time.  Used to pause view
 * updates while the user scrolls (and to apply the latest state in one go when scrolling stops).  Toolkit independent:
 * times are supplied by the caller in milliseconds from a monotonic clock.  Not thread safe (use on one thread, e.g.
 * the JavaFX thread).
 */
public class ScrollIdleDetector
{
    private final long mIdleTimeMs;
    private boolean mScrolling;
    private long mLastScrollMs;

    /**
     * Constructs an instance
     * @param idleTimeMs time without scroll movement after which scrolling is considered to have stopped
     */
    public ScrollIdleDetector(long idleTimeMs)
    {
        if(idleTimeMs < 0)
        {
            throw new IllegalArgumentException("Idle time must not be negative");
        }

        mIdleTimeMs = idleTimeMs;
    }

    /**
     * Idle time in milliseconds.
     */
    public long getIdleTimeMs()
    {
        return mIdleTimeMs;
    }

    /**
     * Records scroll movement.
     * @param nowMs current time
     */
    public void scrolled(long nowMs)
    {
        mScrolling = true;
        mLastScrollMs = nowMs;
    }

    /**
     * Indicates if the user is scrolling: there was scroll movement within the idle time.
     * @param nowMs current time
     */
    public boolean isScrolling(long nowMs)
    {
        return mScrolling && nowMs - mLastScrollMs < mIdleTimeMs;
    }
}
