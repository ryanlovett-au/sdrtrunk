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

import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.channel.state.AbstractChannelState;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.console.activity.IDecodedSiteResolver;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.mpt1327.DecodeConfigMPT1327;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures the status of the running trunking control channels for the console control channel strip, from the
 * information that is already available: the channel configuration (name, system, site, decoder), the channel
 * processing chain's channel metadata (current frequency and channel state) and, optionally, an
 * IControlChannelDetailProvider for decoded network details (e.g. NetworkSiteDetailProvider).
 *
 * A control channel is a running, standard (non-traffic) channel with a trunking-capable decoder (P25 Phase 1/2,
 * DMR, NXDN or MPT-1327).
 *
 * Intended to be polled by the console window (e.g. once per second) on the JavaFX application thread.  Channel
 * metadata values are written by decoder threads and are read here without locking, which is acceptable for display.
 *
 * Also resolves the decoded site identifier of the control channel that a call was received through (for the console
 * card site), from the most recently polled status, which is safe to read from any thread.
 */
public class ControlChannelMonitor implements IDecodedSiteResolver
{
    /**
     * Name prefix that the trunking traffic channel managers give traffic channels: "T-" + control channel name.
     */
    public static final String TRAFFIC_CHANNEL_PREFIX = "T-";

    private final static Logger mLog = LoggerFactory.getLogger(ControlChannelMonitor.class);

    private final ChannelModel mChannelModel;
    private final ChannelProcessingManager mChannelProcessingManager;
    private volatile IControlChannelDetailProvider mDetailProvider;
    private volatile List<ControlChannelStatus> mLastStatus = List.of();

    /**
     * Constructs an instance
     * @param channelModel containing the channel configurations
     * @param channelProcessingManager for the processing chain of each running channel
     */
    public ControlChannelMonitor(ChannelModel channelModel, ChannelProcessingManager channelProcessingManager)
    {
        mChannelModel = channelModel;
        mChannelProcessingManager = channelProcessingManager;
    }

    /**
     * Sets the provider of decoded network details for control channels.
     * @param detailProvider to use, or null for none
     */
    public void setDetailProvider(IControlChannelDetailProvider detailProvider)
    {
        mDetailProvider = detailProvider;
    }

    /**
     * Indicates if the decode configuration is for a trunking protocol that has control channels.
     * @param decodeConfiguration to check
     * @return true for P25, DMR, NXDN and MPT-1327 decoders.
     */
    public static boolean isTrunkingDecoder(DecodeConfiguration decodeConfiguration)
    {
        return decodeConfiguration instanceof DecodeConfigP25 || decodeConfiguration instanceof DecodeConfigDMR ||
            decodeConfiguration instanceof DecodeConfigNXDN || decodeConfiguration instanceof DecodeConfigMPT1327;
    }

    /**
     * Current status of each running control channel.  On error (e.g. the channel list is modified while it is
     * being read), the previous status is returned.
     */
    public List<ControlChannelStatus> getControlChannels()
    {
        try
        {
            List<ControlChannelStatus> statuses = new ArrayList<>();

            for(Channel channel: new ArrayList<>(mChannelModel.getChannels()))
            {
                if(channel.isStandardChannel() && channel.isProcessing() &&
                    isTrunkingDecoder(channel.getDecodeConfiguration()))
                {
                    statuses.add(getStatus(channel));
                }
            }

            mLastStatus = List.copyOf(statuses);
        }
        catch(Exception e)
        {
            mLog.debug("Error reading control channel status - using previous status", e);
        }

        return mLastStatus;
    }

    /**
     * Decoded site identifier of the control channel for a call, from the most recently polled control channel status.
     */
    @Override
    public String getDecodedSiteId(String system, String site, String channelName)
    {
        return resolveSiteId(mLastStatus, system, site, channelName);
    }

    /**
     * Resolves the decoded site identifier of the control channel for a call.  The call's channel is matched to a
     * control channel by name (the control channel itself, or a traffic channel named "T-" + control channel name),
     * preferring a match with the same system and site; otherwise by configured site (and system) when exactly one
     * control channel with a decoded site identifier matches.
     *
     * @param statuses of the running control channels
     * @param system configured system name of the call's channel, or null
     * @param site configured site name of the call's channel, or null
     * @param channelName configured name of the call's channel, or null
     * @return decoded site identifier, or null when no control channel matches or its site has not been decoded.
     */
    public static String resolveSiteId(List<ControlChannelStatus> statuses, String system, String site,
                                       String channelName)
    {
        if(statuses == null || statuses.isEmpty())
        {
            return null;
        }

        ControlChannelStatus nameMatch = null;

        if(channelName != null)
        {
            for(ControlChannelStatus status: statuses)
            {
                if(status.channelName() != null && (channelName.equals(status.channelName()) ||
                    channelName.equals(TRAFFIC_CHANNEL_PREFIX + status.channelName())))
                {
                    if(nameMatch == null || (Objects.equals(system, status.system()) &&
                        Objects.equals(site, status.site())))
                    {
                        nameMatch = status;
                    }
                }
            }
        }

        if(nameMatch != null)
        {
            return nameMatch.detail().siteId();
        }

        if(site == null || site.isBlank())
        {
            return null;
        }

        String siteId = null;

        for(ControlChannelStatus status: statuses)
        {
            if(site.equals(status.site()) && (system == null || system.equals(status.system())) &&
                status.detail().siteId() != null)
            {
                if(siteId != null && !siteId.equals(status.detail().siteId()))
                {
                    return null; //Ambiguous
                }

                siteId = status.detail().siteId();
            }
        }

        return siteId;
    }

    /**
     * Creates the status for the running control channel.
     */
    private ControlChannelStatus getStatus(Channel channel)
    {
        long frequency = 0;
        State state = null;

        ProcessingChain processingChain = mChannelProcessingManager.getProcessingChain(channel);
        AbstractChannelState channelState = processingChain != null ? processingChain.getChannelState() : null;

        if(channelState != null)
        {
            List<ChannelMetadata> channelMetadata = channelState.getChannelMetadata();

            if(channelMetadata != null && !channelMetadata.isEmpty())
            {
                ChannelMetadata metadata = channelMetadata.getFirst();

                if(metadata.hasFrequencyConfigurationIdentifier())
                {
                    Long value = metadata.getFrequencyConfigurationIdentifier().getValue();
                    frequency = value != null ? value : 0;
                }

                if(metadata.hasDecoderStateIdentifier())
                {
                    state = metadata.getChannelStateIdentifier().getValue();
                }
            }
        }

        if(frequency == 0 && channel.getSourceConfiguration() instanceof SourceConfigTuner sourceConfigTuner)
        {
            frequency = sourceConfigTuner.getFrequency();
        }

        ControlChannelDetail detail = ControlChannelDetail.NONE;
        IControlChannelDetailProvider provider = mDetailProvider;

        if(provider != null)
        {
            try
            {
                detail = provider.getDetail(channel);
            }
            catch(Exception e)
            {
                mLog.debug("Error obtaining control channel detail", e);
            }
        }

        return new ControlChannelStatus(channel.getName(), channel.getSystem(), channel.getSite(), frequency,
            channel.getDecodeConfiguration().getDecoderType().getShortDisplayString(), state, detail);
    }
}
