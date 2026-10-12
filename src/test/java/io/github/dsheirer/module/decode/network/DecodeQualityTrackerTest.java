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
package io.github.dsheirer.module.decode.network;

import io.github.dsheirer.protocol.Protocol;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests the sliding window decode quality measure and the network site snapshot labels.
 */
public class DecodeQualityTrackerTest
{
    private static final long WINDOW_MS = 10_000;
    private static final long STALE_MS = 3_000;
    private static final int FRAME = 360;

    private final AtomicLong mClock = new AtomicLong(1_000_000);
    private DecodeQualityTracker mTracker;

    @BeforeEach
    void setup()
    {
        mTracker = new DecodeQualityTracker(WINDOW_MS, STALE_MS, mClock::get);
    }

    @Test
    void unknownUntilMessagesReceived()
    {
        assertEquals(DecodeQualityTracker.UNKNOWN, mTracker.getQuality());
        mTracker.message(0, 0, true);
        assertEquals(DecodeQualityTracker.UNKNOWN, mTracker.getQuality(), "Zero airtime messages are not counted");
    }

    @Test
    void ratioOfValidAirtime()
    {
        for(int x = 0; x < 9; x++)
        {
            mTracker.message(x * 40, FRAME, true);
        }

        assertEquals(100, mTracker.getQuality());

        mTracker.message(400, FRAME, false);
        assertEquals(90, mTracker.getQuality(), "9 valid of 10 frames");

        //Missed frames (no sync) count against the quality
        mTracker.syncLoss(800, FRAME * 8);
        assertEquals(50, mTracker.getQuality(), "9 valid of 18 frames of airtime");
    }

    @Test
    void totalSyncLossIsZero()
    {
        mTracker.syncLoss(0, 9600);
        mTracker.syncLoss(1000, 9600);
        assertEquals(0, mTracker.getQuality());
    }

    @Test
    void windowSlides()
    {
        //Poor signal at first, then a good signal: the poor second leaves the 10 second window
        mTracker.syncLoss(0, 9600);
        mTracker.message(5_000, 9600, true);
        assertEquals(50, mTracker.getQuality());

        mTracker.message(10_500, 9600, true);
        assertEquals(100, mTracker.getQuality(), "Expected the first second to have left the window");

        //A large backwards jump (e.g. a different recording) restarts the window
        mTracker.syncLoss(-60_000, 9600);
        assertEquals(0, mTracker.getQuality());
    }

    @Test
    void staleAndReset()
    {
        mTracker.message(0, FRAME, true);
        assertEquals(100, mTracker.getQuality());

        mClock.addAndGet(STALE_MS + 1);
        assertEquals(DecodeQualityTracker.UNKNOWN, mTracker.getQuality(), "Expected unknown when no messages arrive");

        mTracker.message(1000, FRAME, false);
        assertEquals(50, mTracker.getQuality());

        mTracker.reset();
        assertEquals(DecodeQualityTracker.UNKNOWN, mTracker.getQuality());
    }

    @Test
    void siteInfoLabels()
    {
        assertNull(NetworkSiteInfo.builder(Protocol.APCO25).neighborCount(3).build(),
            "Expected no snapshot without identifying details");

        NetworkSiteInfo p25 = NetworkSiteInfo.builder(Protocol.APCO25_PHASE2).rfss(1).site(3).accessCode(0x2A1)
            .build();
        assertEquals("1-3", p25.getSiteIdLabel());
        assertEquals("NAC 2A1", p25.getAccessCodeLabel());

        //Builder keeps the first (preferred) value
        NetworkSiteInfo dmr = NetworkSiteInfo.builder(Protocol.DMR).systemName("Tier III Trunking").system(12)
            .system(99).site(4).accessCode(1).build();
        assertEquals(12, dmr.system());
        assertEquals("4", dmr.getSiteIdLabel());
        assertEquals("CC 1", dmr.getAccessCodeLabel());
        assertEquals("Tier III Trunking NET 12", dmr.getSystemLabel());

        NetworkSiteInfo dmrColorCodeOnly = NetworkSiteInfo.builder(Protocol.DMR).accessCode(7).build();
        assertNull(dmrColorCodeOnly.getSiteIdLabel(), "Capacity Plus has no site identifier");
        assertEquals("CC 7", dmrColorCodeOnly.getAccessCodeLabel());

        NetworkSiteInfo nxdn = NetworkSiteInfo.builder(Protocol.NXDN).system(100).site(2).accessCode(5)
            .controlChannelFrequency(451_000_000).build();
        assertEquals("2", nxdn.getSiteIdLabel());
        assertEquals("RAN 5", nxdn.getAccessCodeLabel());
        assertEquals("SYS 100", nxdn.getSystemLabel());
        assertEquals(451_000_000, nxdn.controlChannelFrequency());
    }
}
