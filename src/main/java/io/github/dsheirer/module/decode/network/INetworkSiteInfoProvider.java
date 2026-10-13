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
package io.github.dsheirer.module.decode.network;

/**
 * Provides the decoded network/site details and the decode quality of a trunking channel.  Implemented by the
 * decoder states of the trunking protocols (P25 phase 1 and 2, DMR and NXDN) so that the details can be found among
 * the modules of a running processing chain.
 *
 * Both methods may be invoked from any thread (e.g. the JavaFX application thread) and return an already-captured,
 * immutable snapshot without blocking the decoder.
 */
public interface INetworkSiteInfoProvider
{
    /**
     * Decoded network and site details.
     * @return snapshot, or null when no network or site details have been decoded.
     */
    NetworkSiteInfo getNetworkSiteInfo();

    /**
     * Decode quality over the recent past as a percentage (see DecodeQualityTracker for the measure).
     * @return 0-100, or DecodeQualityTracker.UNKNOWN (-1) when not known.
     */
    int getDecodeQuality();
}
