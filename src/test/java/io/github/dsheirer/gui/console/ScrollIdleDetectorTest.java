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
package io.github.dsheirer.gui.console;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the scroll idle detection that pauses console view updates while scrolling.
 */
public class ScrollIdleDetectorTest
{
    @Test
    void notScrollingInitially()
    {
        ScrollIdleDetector detector = new ScrollIdleDetector(150);
        assertEquals(150, detector.getIdleTimeMs());
        assertFalse(detector.isScrolling(0));
        assertFalse(detector.isScrolling(1_000_000));
    }

    @Test
    void scrollingUntilIdleTimeAfterLastMovement()
    {
        ScrollIdleDetector detector = new ScrollIdleDetector(150);
        detector.scrolled(1000);
        assertTrue(detector.isScrolling(1000));
        assertTrue(detector.isScrolling(1149));
        assertFalse(detector.isScrolling(1150), "Stopped exactly at the idle time");
        assertFalse(detector.isScrolling(5000));
    }

    @Test
    void continuousMovementKeepsScrolling()
    {
        ScrollIdleDetector detector = new ScrollIdleDetector(150);

        //A scroll movement on every 60 Hz frame for 3 seconds: scrolling throughout, longer than the idle time
        long last = 0;

        for(long time = 1000; time <= 4000; time += 16)
        {
            detector.scrolled(time);
            assertTrue(detector.isScrolling(time + 15));
            last = time;
        }

        assertTrue(detector.isScrolling(last + 149));
        assertFalse(detector.isScrolling(last + 150));
    }

    @Test
    void scrollingAgainAfterStopping()
    {
        ScrollIdleDetector detector = new ScrollIdleDetector(150);
        detector.scrolled(1000);
        assertFalse(detector.isScrolling(1200));
        detector.scrolled(1300);
        assertTrue(detector.isScrolling(1300));
        assertTrue(detector.isScrolling(1400));
        assertFalse(detector.isScrolling(1450));
    }

    @Test
    void zeroIdleTimeNeverPauses()
    {
        ScrollIdleDetector detector = new ScrollIdleDetector(0);
        detector.scrolled(1000);
        assertFalse(detector.isScrolling(1000));
    }

    @Test
    void negativeIdleTimeIsRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> new ScrollIdleDetector(-1));
    }
}
