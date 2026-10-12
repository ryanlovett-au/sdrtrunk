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

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.protocol.Protocol;

/**
 * Immutable, protocol-neutral snapshot of the decoded network and site details of a trunking channel, as broadcast by
 * the system (e.g. P25 network and RFSS status broadcasts, DMR system identity codes, NXDN site information).  Each
 * value is null (or zero for the frequency and neighbor count) when it has not been decoded.
 *
 * Field meanings per protocol:
 * <ul>
 *     <li>APCO-25 (Phase 1 and 2): WACN, system, RFSS, site, NAC (access code) and location registration area.</li>
 *     <li>DMR: system name is the detected system type (e.g. Tier III or Connect+), system is the network, access code
 *     is the color code.</li>
 *     <li>NXDN: system and site from the location ID, access code is the radio access number (RAN).</li>
 * </ul>
 *
 * @param protocol of the decoded channel
 * @param systemName decoded system type or name, or null
 * @param wacn wide area communications network identifier (P25), or null
 * @param system system identifier (P25 system, DMR network, NXDN system), or null
 * @param rfss radio frequency subsystem identifier (P25), or null
 * @param site site identifier, or null
 * @param accessCode network access code (P25 NAC, DMR color code or NXDN RAN), or null
 * @param locationRegistrationArea location registration area (P25), or null
 * @param controlChannelFrequency announced (primary) control channel downlink frequency in Hertz, or 0
 * @param neighborCount number of distinct neighbor (adjacent) sites announced, or 0
 */
public record NetworkSiteInfo(Protocol protocol, String systemName, Integer wacn, Integer system, Integer rfss,
                              Integer site, Integer accessCode, Integer locationRegistrationArea,
                              long controlChannelFrequency, int neighborCount)
{
    /**
     * Indicates if the protocol is APCO-25 phase 1 or phase 2.
     */
    public boolean isP25()
    {
        return protocol == Protocol.APCO25 || protocol == Protocol.APCO25_PHASE2;
    }

    /**
     * Indicates if nothing that identifies the network or site has been decoded.
     */
    public boolean isEmpty()
    {
        return systemName == null && wacn == null && system == null && rfss == null && site == null &&
            accessCode == null;
    }

    /**
     * Short site identifier for display: RFSS-site for P25 (e.g. "1-3"), otherwise the site number.
     * @return site identifier or null when the site has not been decoded.
     */
    public String getSiteIdLabel()
    {
        if(site == null)
        {
            return null;
        }

        if(isP25() && rfss != null)
        {
            return rfss + "-" + site;
        }

        return String.valueOf(site);
    }

    /**
     * Network access code for display: "NAC 293" (3 hexadecimal digits) for P25, "CC 1" for DMR and "RAN 5" for NXDN.
     * @return label or null when the access code has not been decoded.
     */
    public String getAccessCodeLabel()
    {
        if(accessCode == null)
        {
            return null;
        }

        if(isP25())
        {
            return "NAC " + String.format("%03X", accessCode);
        }

        return switch(protocol)
        {
            case DMR -> "CC " + accessCode;
            case NXDN -> "RAN " + accessCode;
            default -> "ID " + accessCode;
        };
    }

    /**
     * System description for display, e.g. "WACN BEE00 SYS 3A1" for P25, "Tier III Trunking NET 12" for DMR.
     * @return description or null when nothing about the system has been decoded.
     */
    public String getSystemLabel()
    {
        StringBuilder sb = new StringBuilder();

        if(systemName != null)
        {
            sb.append(systemName);
        }

        if(isP25())
        {
            if(wacn != null)
            {
                append(sb, "WACN " + String.format("%05X", wacn));
            }

            if(system != null)
            {
                append(sb, "SYS " + String.format("%03X", system));
            }
        }
        else if(system != null)
        {
            append(sb, (protocol == Protocol.DMR ? "NET " : "SYS ") + system);
        }

        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * Multi-line description of all decoded values, e.g. for a tooltip.
     */
    public String getSummary()
    {
        StringBuilder sb = new StringBuilder();
        String systemLabel = getSystemLabel();

        if(systemLabel != null)
        {
            sb.append("System: ").append(systemLabel);
        }

        if(site != null)
        {
            line(sb, (isP25() ? "RFSS-Site: " : "Site: ") + getSiteIdLabel());
        }

        if(accessCode != null)
        {
            line(sb, getAccessCodeLabel());
        }

        if(locationRegistrationArea != null)
        {
            line(sb, "LRA: " + String.format("%02X", locationRegistrationArea));
        }

        if(controlChannelFrequency > 0)
        {
            line(sb, "Control channel: " + String.format("%.5f MHz", controlChannelFrequency / 1E6));
        }

        if(neighborCount > 0)
        {
            line(sb, "Neighbor sites: " + neighborCount);
        }

        return sb.toString();
    }

    private static void append(StringBuilder sb, String value)
    {
        if(!sb.isEmpty())
        {
            sb.append(" ");
        }

        sb.append(value);
    }

    private static void line(StringBuilder sb, String value)
    {
        if(!sb.isEmpty())
        {
            sb.append("\n");
        }

        sb.append(value);
    }

    /**
     * Integer value of the identifier.
     * @param identifier with a numeric value, or null
     * @return integer value or null when the identifier is null or not numeric.
     */
    public static Integer toInteger(Identifier<?> identifier)
    {
        if(identifier != null && identifier.getValue() instanceof Number number)
        {
            return number.intValue();
        }

        return null;
    }

    /**
     * Creates a builder for the protocol.
     */
    public static Builder builder(Protocol protocol)
    {
        return new Builder(protocol);
    }

    /**
     * Builder for a network site info snapshot.  Setters that are passed a null value leave the current value
     * unchanged, so that values can be applied from the most to the least preferred source.
     */
    public static class Builder
    {
        private final Protocol mProtocol;
        private String mSystemName;
        private Integer mWacn;
        private Integer mSystem;
        private Integer mRfss;
        private Integer mSite;
        private Integer mAccessCode;
        private Integer mLocationRegistrationArea;
        private long mControlChannelFrequency;
        private int mNeighborCount;

        private Builder(Protocol protocol)
        {
            mProtocol = protocol;
        }

        public Builder systemName(String systemName)
        {
            mSystemName = mSystemName != null ? mSystemName : systemName;
            return this;
        }

        public Builder wacn(Integer wacn)
        {
            mWacn = mWacn != null ? mWacn : wacn;
            return this;
        }

        public Builder system(Integer system)
        {
            mSystem = mSystem != null ? mSystem : system;
            return this;
        }

        public Builder rfss(Integer rfss)
        {
            mRfss = mRfss != null ? mRfss : rfss;
            return this;
        }

        public Builder site(Integer site)
        {
            mSite = mSite != null ? mSite : site;
            return this;
        }

        public Builder accessCode(Integer accessCode)
        {
            mAccessCode = mAccessCode != null ? mAccessCode : accessCode;
            return this;
        }

        public Builder locationRegistrationArea(Integer locationRegistrationArea)
        {
            mLocationRegistrationArea = mLocationRegistrationArea != null ? mLocationRegistrationArea :
                locationRegistrationArea;
            return this;
        }

        public Builder controlChannelFrequency(long frequency)
        {
            mControlChannelFrequency = mControlChannelFrequency > 0 ? mControlChannelFrequency : Math.max(frequency, 0);
            return this;
        }

        public Builder neighborCount(int neighborCount)
        {
            mNeighborCount = neighborCount;
            return this;
        }

        /**
         * Builds the snapshot.
         * @return snapshot, or null when nothing that identifies the network or site has been set.
         */
        public NetworkSiteInfo build()
        {
            NetworkSiteInfo info = new NetworkSiteInfo(mProtocol, mSystemName, mWacn, mSystem, mRfss, mSite,
                mAccessCode, mLocationRegistrationArea, mControlChannelFrequency, mNeighborCount);
            return info.isEmpty() ? null : info;
        }
    }
}
