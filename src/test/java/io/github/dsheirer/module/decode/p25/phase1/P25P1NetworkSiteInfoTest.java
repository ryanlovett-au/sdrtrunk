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
package io.github.dsheirer.module.decode.p25.phase1;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.network.DecodeQualityTracker;
import io.github.dsheirer.module.decode.network.NetworkSiteInfo;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.AdjacentStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.NetworkStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.RFSSStatusBroadcast;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the structured network site snapshot of the P25 phase 1 network configuration monitor and the P25 phase 1
 * decoder state's network site and decode quality provider, using TSBK messages constructed from known bit patterns.
 */
public class P25P1NetworkSiteInfoTest
{
    private static final int WACN = 0xBEE00;
    private static final int SYSTEM = 0x3A1;
    private static final int NAC = 0x293;
    private static final int RFSS = 1;
    private static final int SITE = 3;
    private static final int LRA = 0x05;
    private static final int BAND = 1;
    private static final int CHANNEL_NUMBER = 100;
    private static final long BASE_FREQUENCY = 851_000_000;
    private static final long SPACING = 12_500;
    private static final int TSBK_LENGTH = 96;

    /**
     * Creates a TSBK message body (after deinterleaving and error correction) with the standard vendor and the opcode.
     */
    private static CorrectedBinaryMessage tsbk(Opcode opcode)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(TSBK_LENGTH);
        message.setSize(TSBK_LENGTH);
        message.load(0, 1, 1); //Last block
        message.load(2, 6, opcode.getCode());
        message.load(8, 8, 0); //Standard vendor
        return message;
    }

    /**
     * RFSS status broadcast for the current site, with the channel's frequency band applied as the message
     * processor would do.
     */
    private static RFSSStatusBroadcast rfssStatus(int rfss, int site, long timestamp)
    {
        CorrectedBinaryMessage message = tsbk(Opcode.OSP_RFSS_STATUS_BROADCAST);
        message.load(16, 8, LRA);
        message.load(28, 12, SYSTEM);
        message.load(40, 8, rfss);
        message.load(48, 8, site);
        message.load(56, 4, BAND);
        message.load(60, 12, CHANNEL_NUMBER);
        RFSSStatusBroadcast rfssStatus = new RFSSStatusBroadcast(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, message,
            NAC, timestamp);
        ((APCO25Channel)rfssStatus.getChannel()).setFrequencyBand(createBand());
        return rfssStatus;
    }

    private static NetworkStatusBroadcast networkStatus(long timestamp)
    {
        CorrectedBinaryMessage message = tsbk(Opcode.OSP_NETWORK_STATUS_BROADCAST);
        message.load(16, 8, LRA);
        message.load(24, 20, WACN);
        message.load(44, 12, SYSTEM);
        message.load(56, 4, BAND);
        message.load(60, 12, CHANNEL_NUMBER);
        return new NetworkStatusBroadcast(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, message, NAC, timestamp);
    }

    private static AdjacentStatusBroadcast adjacentStatus(int site, long timestamp)
    {
        CorrectedBinaryMessage message = tsbk(Opcode.OSP_ADJACENT_STATUS_BROADCAST);
        message.load(16, 8, LRA);
        message.load(28, 12, SYSTEM);
        message.load(40, 8, RFSS);
        message.load(48, 8, site);
        message.load(56, 4, BAND);
        message.load(60, 12, CHANNEL_NUMBER + site);
        return new AdjacentStatusBroadcast(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, message, NAC, timestamp);
    }

    /**
     * 12.5 kHz FDMA frequency band starting at 851 MHz.
     */
    private static IFrequencyBand createBand()
    {
        return new IFrequencyBand()
        {
            @Override
            public boolean isValid()
            {
                return true;
            }

            @Override
            public int getIdentifier()
            {
                return BAND;
            }

            @Override
            public long getChannelSpacing()
            {
                return SPACING;
            }

            @Override
            public long getBaseFrequency()
            {
                return BASE_FREQUENCY;
            }

            @Override
            public int getBandwidth()
            {
                return 12500;
            }

            @Override
            public long getTransmitOffset()
            {
                return -45_000_000;
            }

            @Override
            public long getDownlinkFrequency(int channelNumber)
            {
                return BASE_FREQUENCY + channelNumber * SPACING;
            }

            @Override
            public long getUplinkFrequency(int channelNumber)
            {
                return getDownlinkFrequency(channelNumber) + getTransmitOffset();
            }

            @Override
            public boolean isTDMA()
            {
                return false;
            }

            @Override
            public int getTimeslotCount()
            {
                return 1;
            }
        };
    }

    @Test
    void constructedMessagesDecodeAsExpected()
    {
        RFSSStatusBroadcast rfssStatus = rfssStatus(RFSS, SITE, 0);
        assertEquals(Opcode.OSP_RFSS_STATUS_BROADCAST, rfssStatus.getOpcode());
        assertEquals(SITE, rfssStatus.getSite().getValue());
        assertEquals(BASE_FREQUENCY + CHANNEL_NUMBER * SPACING, rfssStatus.getChannel().getDownlinkFrequency());

        NetworkStatusBroadcast networkStatus = networkStatus(0);
        assertEquals(Opcode.OSP_NETWORK_STATUS_BROADCAST, networkStatus.getOpcode());
        assertEquals(WACN, networkStatus.getWacn().getValue());
    }

    @Test
    void snapshotFromNetworkAndRfssStatus()
    {
        P25P1NetworkConfigurationMonitor monitor = new P25P1NetworkConfigurationMonitor(Modulation.C4FM);
        assertNull(monitor.getNetworkSiteInfo(), "Expected no snapshot before any broadcasts");

        monitor.process(rfssStatus(RFSS, SITE, 1000));
        NetworkSiteInfo info = monitor.getNetworkSiteInfo();
        assertNotNull(info);
        assertEquals(Protocol.APCO25, info.protocol());
        assertEquals(RFSS, info.rfss());
        assertEquals(SITE, info.site());
        assertEquals(SYSTEM, info.system());
        assertEquals(NAC, info.accessCode(), "Expected the NAC of the RFSS status message");
        assertNull(info.wacn(), "WACN is only known from the network status broadcast");
        assertEquals(BASE_FREQUENCY + CHANNEL_NUMBER * SPACING, info.controlChannelFrequency());
        assertEquals("1-3", info.getSiteIdLabel());
        assertEquals("NAC 293", info.getAccessCodeLabel());

        monitor.process(networkStatus(2000));
        monitor.process(adjacentStatus(4, 3000));
        monitor.process(adjacentStatus(5, 3000));
        monitor.process(adjacentStatus(4, 4000));
        info = monitor.getNetworkSiteInfo();
        assertEquals(WACN, info.wacn());
        assertEquals(LRA, info.locationRegistrationArea());
        assertEquals(2, info.neighborCount(), "Expected 2 distinct neighbor sites");
        assertEquals("WACN BEE00 SYS 3A1", info.getSystemLabel());
        assertTrue(info.getSummary().contains("RFSS-Site: 1-3"));
        assertTrue(info.getSummary().contains("852.25000 MHz"));

        //Site change is reflected in a new snapshot; the previous snapshot is immutable
        NetworkSiteInfo previous = info;
        monitor.process(rfssStatus(2, 7, 5000));
        assertEquals("2-7", monitor.getNetworkSiteInfo().getSiteIdLabel());
        assertEquals("1-3", previous.getSiteIdLabel());

        String summary = monitor.getActivitySummary();
        assertTrue(summary.contains("RFSS:"), "Expected the existing text summary to be unchanged");

        monitor.reset();
        assertNull(monitor.getNetworkSiteInfo(), "Expected reset to clear the snapshot");
    }

    @Test
    void airtimeOfFramedMessages()
    {
        //Single block TSBK frame: 360 bits; each additional block adds its share of the multi-block frame
        assertEquals(360, P25P1DecoderState.getAirtimeBits(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1));
        assertEquals(216, P25P1DecoderState.getAirtimeBits(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_2));
        assertEquals(144, P25P1DecoderState.getAirtimeBits(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_3));
        assertEquals(720, P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_3.getElapsedDibitLength() * 2,
            "Expected the three blocks to add up to a three block frame");
        assertEquals(0, P25P1DecoderState.getAirtimeBits(P25P1DataUnitID.ALTERNATE_MULTI_BLOCK_TRUNKING_CONTROL));
        assertEquals(0, P25P1DecoderState.getAirtimeBits(null));
    }

    @Test
    void decoderStateProvidesSnapshotAndQuality()
    {
        Channel channel = new Channel("Control");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        P25P1DecoderState decoderState = new P25P1DecoderState(channel);

        assertNull(decoderState.getNetworkSiteInfo());
        assertEquals(DecodeQualityTracker.UNKNOWN, decoderState.getDecodeQuality());

        long timestamp = 10_000;
        decoderState.receive(rfssStatus(RFSS, SITE, timestamp));
        decoderState.receive(networkStatus(timestamp + 40));
        decoderState.receive(adjacentStatus(4, timestamp + 80));

        NetworkSiteInfo info = decoderState.getNetworkSiteInfo();
        assertNotNull(info);
        assertEquals("1-3", info.getSiteIdLabel());
        assertEquals(WACN, info.wacn());
        assertEquals(100, decoderState.getDecodeQuality(), "Expected 100% with only valid messages");

        //A CRC failure for one frame and a sync loss equivalent to two frames: 3 of 6 frames' airtime valid
        TSBKMessage failed = rfssStatus(RFSS, SITE, timestamp + 120);
        failed.setValid(false);
        decoderState.receive(failed);
        decoderState.receive(new SyncLossMessage(timestamp + 200, 720, Protocol.APCO25));
        assertEquals(50, decoderState.getDecodeQuality());
        assertEquals("1-3", decoderState.getNetworkSiteInfo().getSiteIdLabel(),
            "Expected invalid messages to be ignored by the network configuration monitor");
    }
}
