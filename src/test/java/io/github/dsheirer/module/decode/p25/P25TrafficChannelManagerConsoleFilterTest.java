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
package io.github.dsheirer.module.decode.p25;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.console.ConsoleCardResolver;
import io.github.dsheirer.console.ConsoleGroup;
import io.github.dsheirer.console.ConsoleLayoutManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import io.github.dsheirer.module.decode.traffic.TrafficChannelManager;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the console talkgroup filter in the P25 traffic channel manager: a voice grant for a talkgroup that is not on
 * a console card is tracked as a decode event, but no traffic channel start is requested.
 */
public class P25TrafficChannelManagerConsoleFilterTest
{
    private static final int TALKGROUP_ON_CARD = 100;
    private static final int TALKGROUP_OFF_CARD = 200;
    private static final int TALKGROUP_UNALIASED = 300;
    private static final int RADIO = 1234;
    private static final int FDMA_BAND = 1;
    private static final int TDMA_BAND = 2;

    @TempDir
    Path mTempDir;

    private AliasList mAliasList;
    private Alias mOffCardAlias;
    private ConsoleLayoutManager mConsoleLayoutManager;
    private ConsoleGroup mGroup;
    private P25TrafficChannelManager mTrafficChannelManager;
    private RequestCollector mRequests;
    private final List<IDecodeEvent> mDecodeEvents = new ArrayList<>();
    private final List<ChannelEvent> mChannelEvents = new ArrayList<>();
    private long mTimestamp = System.currentTimeMillis();

    /**
     * Collects channel start requests posted to the inter-module event bus.
     */
    public static class RequestCollector
    {
        private final List<ChannelStartProcessingRequest> mRequests = new ArrayList<>();

        @Subscribe
        public void receive(ChannelStartProcessingRequest request)
        {
            mRequests.add(request);
        }

        public List<ChannelStartProcessingRequest> getRequests()
        {
            return mRequests;
        }
    }

    /**
     * Test frequency band: 851.0 MHz base, 12.5 kHz spacing, either FDMA (1 timeslot) or TDMA (2 timeslots).
     */
    private static IFrequencyBand createBand(int identifier, boolean tdma)
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
                return identifier;
            }

            @Override
            public long getChannelSpacing()
            {
                return 12500;
            }

            @Override
            public long getBaseFrequency()
            {
                return 851000000;
            }

            @Override
            public int getBandwidth()
            {
                return 12500;
            }

            @Override
            public long getTransmitOffset()
            {
                return -45000000;
            }

            @Override
            public long getDownlinkFrequency(int channelNumber)
            {
                return getBaseFrequency() + (channelNumber / getTimeslotCount()) * getChannelSpacing();
            }

            @Override
            public long getUplinkFrequency(int channelNumber)
            {
                return getDownlinkFrequency(channelNumber) + getTransmitOffset();
            }

            @Override
            public boolean isTDMA()
            {
                return tdma;
            }

            @Override
            public int getTimeslotCount()
            {
                return tdma ? 2 : 1;
            }
        };
    }

    private static APCO25Channel createChannel(int band, int channelNumber)
    {
        APCO25Channel channel = APCO25Channel.create(band, channelNumber);
        channel.setFrequencyBand(createBand(band, band == TDMA_BAND));
        return channel;
    }

    private static IdentifierCollection grant(int talkgroup)
    {
        return new MutableIdentifierCollection(List.of(APCO25Talkgroup.create(talkgroup),
            APCO25RadioIdentifier.createFrom(RADIO)));
    }

    @BeforeEach
    void setup()
    {
        mAliasList = new AliasList("Test List");
        Alias onCard = new Alias("Fire Dispatch");
        onCard.addAliasID(new Talkgroup(Protocol.APCO25, TALKGROUP_ON_CARD));
        mAliasList.addAlias(onCard);
        mOffCardAlias = new Alias("Police Dispatch");
        mOffCardAlias.addAliasID(new Talkgroup(Protocol.APCO25, TALKGROUP_OFF_CARD));
        mAliasList.addAlias(mOffCardAlias);

        mConsoleLayoutManager = new ConsoleLayoutManager(mTempDir.resolve("playlist.console.json"));
        mConsoleLayoutManager.load();
        mConsoleLayoutManager.setConsoleModeEnabled(true);
        mConsoleLayoutManager.setTalkgroupFilterEnabled(true);
        mGroup = mConsoleLayoutManager.addGroup("Fire");
        mConsoleLayoutManager.addCard(mGroup.getId(), onCard.getUuid());

        Channel channel = new Channel("Control");
        DecodeConfigP25Phase1 config = new DecodeConfigP25Phase1();
        config.setIgnoreDataCalls(false);
        channel.setDecodeConfiguration(config);

        mTrafficChannelManager = new P25TrafficChannelManager(channel);
        mTrafficChannelManager.setConsoleCardResolver(new ConsoleCardResolver(mConsoleLayoutManager), mAliasList);
        mTrafficChannelManager.addDecodeEventListener(mDecodeEvents::add);
        mTrafficChannelManager.setChannelEventListener(mChannelEvents::add);

        EventBus eventBus = new EventBus();
        mRequests = new RequestCollector();
        eventBus.register(mRequests);
        mTrafficChannelManager.setInterModuleEventBus(eventBus);
    }

    private void processGrant(APCO25Channel channel, int talkgroup)
    {
        mTimestamp += 100;
        mTrafficChannelManager.processP1ControlDirectedChannelGrant(channel, VoiceServiceOptions.createUnencrypted(),
            grant(talkgroup), Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, mTimestamp, null);
    }

    private String getLastEventDetails()
    {
        return mDecodeEvents.getLast().getDetails();
    }

    @Test
    void phase1GrantOnCardIsAllocated()
    {
        processGrant(createChannel(FDMA_BAND, 10), TALKGROUP_ON_CARD);
        assertEquals(1, mRequests.getRequests().size(), "Expected traffic channel start request");
        assertFalse(getLastEventDetails().startsWith(TrafficChannelManager.NOT_ON_CONSOLE), "Details");
    }

    @Test
    void phase1GrantNotOnCardIsNotAllocated()
    {
        APCO25Channel channel = createChannel(FDMA_BAND, 10);
        processGrant(channel, TALKGROUP_OFF_CARD);
        processGrant(createChannel(FDMA_BAND, 11), TALKGROUP_UNALIASED);

        assertTrue(mRequests.getRequests().isEmpty(), "Expected no traffic channel start request");
        assertFalse(mDecodeEvents.isEmpty(), "Expected decode events for the filtered grants");
        assertTrue(getLastEventDetails().startsWith(TrafficChannelManager.NOT_ON_CONSOLE),
            "Expected not on console details: " + getLastEventDetails());

        //Grant updates for the same call remain filtered
        processGrant(channel, TALKGROUP_OFF_CARD);
        assertTrue(mRequests.getRequests().isEmpty(), "Expected no traffic channel start request for update");
    }

    @Test
    void phase1CardAddedMidCallAllocatesOnNextGrantUpdate()
    {
        APCO25Channel channel = createChannel(FDMA_BAND, 10);
        processGrant(channel, TALKGROUP_OFF_CARD);
        assertTrue(mRequests.getRequests().isEmpty(), "Expected filtered grant");

        mConsoleLayoutManager.addCard(mGroup.getId(), mOffCardAlias.getUuid());
        processGrant(channel, TALKGROUP_OFF_CARD);

        assertEquals(1, mRequests.getRequests().size(), "Expected traffic channel start on grant update");
        assertEquals(channel.getDownlinkFrequency(),
            mRequests.getRequests().getFirst().getChannelDescriptor().getDownlinkFrequency(), "Frequency");
        assertFalse(getLastEventDetails().startsWith(TrafficChannelManager.NOT_ON_CONSOLE),
            "Expected details updated: " + getLastEventDetails());
    }

    @Test
    void phase1CardAddedMidCallAllocatesOnAnnouncedGrantUpdate()
    {
        APCO25Channel channel = createChannel(FDMA_BAND, 10);
        processGrant(channel, TALKGROUP_OFF_CARD);
        mTimestamp += 100;
        mTrafficChannelManager.processP1ControlAnnouncedTrafficUpdate(channel, VoiceServiceOptions.createUnencrypted(),
            grant(TALKGROUP_OFF_CARD), Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE, mTimestamp, null);
        assertTrue(mRequests.getRequests().isEmpty(), "Expected filtered grant update");

        mConsoleLayoutManager.addCard(mGroup.getId(), mOffCardAlias.getUuid());
        mTimestamp += 100;
        mTrafficChannelManager.processP1ControlAnnouncedTrafficUpdate(channel, VoiceServiceOptions.createUnencrypted(),
            grant(TALKGROUP_OFF_CARD), Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE, mTimestamp, null);
        assertEquals(1, mRequests.getRequests().size(), "Expected traffic channel start on announced grant update");

        //Further updates don't allocate again
        mTimestamp += 100;
        mTrafficChannelManager.processP1ControlAnnouncedTrafficUpdate(channel, VoiceServiceOptions.createUnencrypted(),
            grant(TALKGROUP_OFF_CARD), Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE, mTimestamp, null);
        assertEquals(1, mRequests.getRequests().size(), "Expected a single start request");
    }

    @Test
    void phase1FilterInactiveAllocatesEverything()
    {
        mConsoleLayoutManager.setConsoleModeEnabled(false);
        processGrant(createChannel(FDMA_BAND, 10), TALKGROUP_OFF_CARD);
        processGrant(createChannel(FDMA_BAND, 11), TALKGROUP_UNALIASED);
        assertEquals(2, mRequests.getRequests().size(), "Expected start requests with console mode off");

        mConsoleLayoutManager.setConsoleModeEnabled(true);
        mConsoleLayoutManager.setTalkgroupFilterEnabled(false);
        processGrant(createChannel(FDMA_BAND, 12), TALKGROUP_UNALIASED);
        assertEquals(3, mRequests.getRequests().size(), "Expected start request with talkgroup filter off");
    }

    @Test
    void phase1NoResolverAllocatesEverything()
    {
        mTrafficChannelManager.setConsoleCardResolver(null, null);
        processGrant(createChannel(FDMA_BAND, 10), TALKGROUP_UNALIASED);
        assertEquals(1, mRequests.getRequests().size(), "Expected start request without a resolver");
    }

    @Test
    void phase1DataGrantIsNotFiltered()
    {
        mTimestamp += 100;
        mTrafficChannelManager.processP1ControlDirectedChannelGrant(createChannel(FDMA_BAND, 10),
            VoiceServiceOptions.createUnencrypted(), grant(TALKGROUP_UNALIASED), Opcode.OSP_SNDCP_DATA_CHANNEL_GRANT,
            mTimestamp, null);
        assertEquals(1, mRequests.getRequests().size(), "Expected data channel start request");
    }

    @Test
    void phase2TimeslotsAreFilteredIndependently()
    {
        APCO25Channel ts1 = createChannel(TDMA_BAND, 20);
        APCO25Channel ts2 = createChannel(TDMA_BAND, 21);
        assertEquals(1, ts1.getTimeslot(), "Timeslot 1");
        assertEquals(2, ts2.getTimeslot(), "Timeslot 2");
        assertEquals(ts1.getDownlinkFrequency(), ts2.getDownlinkFrequency(), "Same TDMA channel frequency");

        processGrant(ts1, TALKGROUP_OFF_CARD);
        assertTrue(mRequests.getRequests().isEmpty(), "Expected filtered timeslot 1 grant");
        assertTrue(getLastEventDetails().startsWith(TrafficChannelManager.NOT_ON_CONSOLE), "Details");

        processGrant(ts2, TALKGROUP_ON_CARD);
        assertEquals(1, mRequests.getRequests().size(), "Expected timeslot 2 grant to allocate the channel");

        //A filtered grant on the other timeslot doesn't disturb the allocated channel
        processGrant(ts1, TALKGROUP_UNALIASED);
        assertEquals(1, mRequests.getRequests().size(), "Expected no additional start request");
        assertTrue(mChannelEvents.isEmpty(), "Expected no channel disable request");
        assertTrue(getLastEventDetails().startsWith(TrafficChannelManager.NOT_ON_CONSOLE), "Details");
    }

    @Test
    void phase2CardAddedMidCallAllocatesOnNextGrantUpdate()
    {
        APCO25Channel ts1 = createChannel(TDMA_BAND, 20);
        processGrant(ts1, TALKGROUP_OFF_CARD);
        assertTrue(mRequests.getRequests().isEmpty(), "Expected filtered grant");

        mConsoleLayoutManager.addCard(mGroup.getId(), mOffCardAlias.getUuid());
        processGrant(ts1, TALKGROUP_OFF_CARD);
        assertEquals(1, mRequests.getRequests().size(), "Expected start request on grant update");
    }
}
