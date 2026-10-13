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

import io.github.dsheirer.module.decode.network.DecodeQualityTracker;
import io.github.dsheirer.module.decode.network.NetworkSiteInfo;

/**
 * Decoded network details for a control channel, for example from a protocol's network configuration monitor.  Each
 * value is a display string, or null when the value has not been decoded.
 *
 * @param siteName decoded or system-provided site name, or null to use the configured site name
 * @param siteId decoded site identifier (e.g. P25 RFSS-site "1-3")
 * @param networkId decoded network access code with its label (e.g. P25 "NAC 293", DMR "CC 1", NXDN "RAN 5")
 * @param decodeQuality decode quality indicator (e.g. "98%"), or null when unknown
 * @param summary multi-line description of all decoded details (e.g. for a tooltip), or null
 */
public record ControlChannelDetail(String siteName, String siteId, String networkId, String decodeQuality,
                                   String summary)
{
    /**
     * Detail with no decoded values.
     */
    public static final ControlChannelDetail NONE = new ControlChannelDetail(null, null, null, null, null);

    /**
     * Creates the detail from a decoded network site snapshot and decode quality.
     * @param info decoded network and site details, or null
     * @param decodeQuality percentage (0-100) or DecodeQualityTracker.UNKNOWN
     * @return detail, or NONE when nothing is known.
     */
    public static ControlChannelDetail of(NetworkSiteInfo info, int decodeQuality)
    {
        String quality = formatQuality(decodeQuality);

        if(info == null && quality == null)
        {
            return NONE;
        }

        StringBuilder summary = new StringBuilder();

        if(info != null)
        {
            summary.append(info.getSummary());
        }

        if(quality != null)
        {
            if(!summary.isEmpty())
            {
                summary.append("\n");
            }

            summary.append("Decode quality: ").append(quality).append(" (valid share of airtime, last 10 s)");
        }

        return new ControlChannelDetail(null, info != null ? info.getSiteIdLabel() : null,
            info != null ? info.getAccessCodeLabel() : null, quality, summary.isEmpty() ? null : summary.toString());
    }

    /**
     * Formats the decode quality percentage.
     * @param decodeQuality percentage (0-100) or DecodeQualityTracker.UNKNOWN
     * @return formatted percentage (e.g. "98%") or null when unknown.
     */
    public static String formatQuality(int decodeQuality)
    {
        if(decodeQuality == DecodeQualityTracker.UNKNOWN || decodeQuality < 0)
        {
            return null;
        }

        return Math.min(decodeQuality, 100) + "%";
    }
}
