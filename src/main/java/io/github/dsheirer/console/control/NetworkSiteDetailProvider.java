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
package io.github.dsheirer.console.control;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.network.DecodeQualityTracker;
import io.github.dsheirer.module.decode.network.INetworkSiteInfoProvider;
import io.github.dsheirer.module.decode.network.NetworkSiteInfo;
import java.util.List;
import java.util.function.Function;

/**
 * Control channel detail provider backed by the protocol network configuration monitors: finds the decoder states of
 * the channel's running processing chain that implement INetworkSiteInfoProvider (P25 phase 1 and 2, DMR and NXDN)
 * and converts their network site snapshot and decode quality to a ControlChannelDetail.
 *
 * The decoder states publish immutable snapshots (volatile reference / synchronized tracker), so the details can be
 * read from the JavaFX application thread without blocking the decoders.  The console layer depends only on the
 * protocol-neutral module.decode.network package; the decoders do not depend on the console.
 */
public class NetworkSiteDetailProvider implements IControlChannelDetailProvider
{
    private final Function<Channel,List<?>> mModuleLookup;

    /**
     * Constructs an instance that looks up the decoder states of each channel's processing chain.
     * @param channelProcessingManager for the processing chain of each running channel
     */
    public NetworkSiteDetailProvider(ChannelProcessingManager channelProcessingManager)
    {
        this(channel -> {
            ProcessingChain processingChain = channelProcessingManager.getProcessingChain(channel);
            return processingChain != null ? processingChain.getDecoderStates() : List.of();
        });
    }

    /**
     * Constructs an instance
     * @param moduleLookup providing the (decoder state) modules of the channel's processing chain
     */
    public NetworkSiteDetailProvider(Function<Channel,List<?>> moduleLookup)
    {
        mModuleLookup = moduleLookup;
    }

    /**
     * Decoded details for the control channel: the first decoded network site snapshot and the first known decode
     * quality among the channel's decoder states (e.g. the DMR and P25 phase 2 timeslot 1 decoder state).
     */
    @Override
    public ControlChannelDetail getDetail(Channel channel)
    {
        List<?> modules = mModuleLookup.apply(channel);

        if(modules == null || modules.isEmpty())
        {
            return ControlChannelDetail.NONE;
        }

        NetworkSiteInfo info = null;
        int quality = DecodeQualityTracker.UNKNOWN;

        for(Object module: modules)
        {
            if(module instanceof INetworkSiteInfoProvider provider)
            {
                if(info == null)
                {
                    info = provider.getNetworkSiteInfo();
                }

                if(quality == DecodeQualityTracker.UNKNOWN)
                {
                    quality = provider.getDecodeQuality();
                }
            }
        }

        return ControlChannelDetail.of(info, quality);
    }
}
