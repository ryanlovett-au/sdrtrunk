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
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.network.DecodeQualityTracker;
import io.github.dsheirer.module.decode.network.INetworkSiteInfoProvider;
import io.github.dsheirer.module.decode.network.NetworkSiteInfo;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the control channel detail provider backed by the decoder states' network site snapshots, the conversion to
 * control channel details, and the decoded site resolution for console cards.
 */
public class NetworkSiteDetailProviderTest
{
    private static final NetworkSiteInfo P25_SITE = NetworkSiteInfo.builder(Protocol.APCO25).wacn(0xBEE00).system(0x3A1)
        .rfss(1).site(3).accessCode(0x293).neighborCount(2).build();

    /**
     * Test provider with fixed values.
     */
    private record FixedProvider(NetworkSiteInfo info, int quality) implements INetworkSiteInfoProvider
    {
        @Override
        public NetworkSiteInfo getNetworkSiteInfo()
        {
            return info;
        }

        @Override
        public int getDecodeQuality()
        {
            return quality;
        }
    }

    @Test
    void detailFromDecoderStates()
    {
        Channel control = new Channel("North");
        Channel dmr = new Channel("DMR");
        Channel stopped = new Channel("Stopped");

        //DMR: the timeslot 2 decoder state has nothing; timeslot 1 has the snapshot and the quality
        NetworkSiteInfo dmrSite = NetworkSiteInfo.builder(Protocol.DMR).system(12).site(4).accessCode(1).build();
        Map<Channel,List<?>> modules = Map.of(
            control, List.of("not a provider", new FixedProvider(P25_SITE, 98)),
            dmr, List.of(new FixedProvider(null, DecodeQualityTracker.UNKNOWN), new FixedProvider(dmrSite, 87)));

        NetworkSiteDetailProvider provider = new NetworkSiteDetailProvider(channel -> modules.get(channel));

        ControlChannelDetail detail = provider.getDetail(control);
        assertEquals("1-3", detail.siteId());
        assertEquals("NAC 293", detail.networkId());
        assertEquals("98%", detail.decodeQuality());
        assertNull(detail.siteName(), "No protocol broadcasts a site name");
        assertTrue(detail.summary().contains("WACN BEE00 SYS 3A1"));
        assertTrue(detail.summary().contains("Neighbor sites: 2"));
        assertTrue(detail.summary().contains("Decode quality: 98%"));

        ControlChannelDetail dmrDetail = provider.getDetail(dmr);
        assertEquals("4", dmrDetail.siteId());
        assertEquals("CC 1", dmrDetail.networkId());
        assertEquals("87%", dmrDetail.decodeQuality());

        assertSame(ControlChannelDetail.NONE, provider.getDetail(stopped), "Expected NONE without a processing chain");
    }

    @Test
    void detailConversion()
    {
        assertSame(ControlChannelDetail.NONE, ControlChannelDetail.of(null, DecodeQualityTracker.UNKNOWN));

        ControlChannelDetail qualityOnly = ControlChannelDetail.of(null, 75);
        assertNull(qualityOnly.siteId());
        assertEquals("75%", qualityOnly.decodeQuality());

        ControlChannelDetail siteOnly = ControlChannelDetail.of(P25_SITE, DecodeQualityTracker.UNKNOWN);
        assertEquals("1-3", siteOnly.siteId());
        assertNull(siteOnly.decodeQuality(), "Expected unknown quality to be null (shown as a dash)");

        assertNull(ControlChannelDetail.formatQuality(-1));
        assertEquals("0%", ControlChannelDetail.formatQuality(0));
        assertEquals("100%", ControlChannelDetail.formatQuality(100));
    }

    private static ControlChannelStatus status(String name, String system, String site, String siteId)
    {
        return new ControlChannelStatus(name, system, site, 851_012_500, "P25 Phase 1", State.CONTROL,
            new ControlChannelDetail(null, siteId, "NAC 293", "98%", null));
    }

    @Test
    void siteLabelPrefersConfiguredSite()
    {
        assertEquals("North Hill", status("North", "Metro", "North Hill", "1-3").getSiteLabel());
        assertEquals("Metro", status("North", "Metro", null, "1-3").getSiteLabel());

        ControlChannelStatus decodedName = new ControlChannelStatus("North", null, null, 0, "DMR", State.CONTROL,
            new ControlChannelDetail("Decoded", null, null, null, null));
        assertEquals("Decoded", decodedName.getSiteLabel());
    }

    @Test
    void decodedSiteResolution()
    {
        List<ControlChannelStatus> statuses = List.of(status("North", "Metro", "North Hill", "1-3"),
            status("Bay", "Metro", "Bayside", "1-7"), status("Bay 2", "Metro", "Bayside", "1-7"),
            status("Central", "Metro", "Central", null));

        //By control channel name, and by traffic channel name ("T-" + control channel name)
        assertEquals("1-3", ControlChannelMonitor.resolveSiteId(statuses, "Metro", "North Hill", "North"));
        assertEquals("1-3", ControlChannelMonitor.resolveSiteId(statuses, "Metro", "North Hill", "T-North"));
        assertEquals("1-7", ControlChannelMonitor.resolveSiteId(statuses, "Metro", "Bayside", "T-Bay"));

        //By configured site when the channel name does not match a control channel
        assertEquals("1-7", ControlChannelMonitor.resolveSiteId(statuses, "Metro", "Bayside", "Conventional"));
        assertEquals("1-3", ControlChannelMonitor.resolveSiteId(statuses, null, "North Hill", null));

        //Not decoded, not found or ambiguous
        assertNull(ControlChannelMonitor.resolveSiteId(statuses, "Metro", "Central", "Central"));
        assertNull(ControlChannelMonitor.resolveSiteId(statuses, "Metro", "Unknown", "Unknown"));
        assertNull(ControlChannelMonitor.resolveSiteId(statuses, "Other", "North Hill", null));
        assertNull(ControlChannelMonitor.resolveSiteId(List.of(), "Metro", "North Hill", "North"));
        assertNull(ControlChannelMonitor.resolveSiteId(List.of(status("A", null, "Hill", "1-1"),
            status("B", null, "Hill", "1-2")), null, "Hill", null), "Expected ambiguous sites to be unresolved");
    }
}
