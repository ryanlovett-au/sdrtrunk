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

import io.github.dsheirer.console.control.ControlChannelDetail;
import io.github.dsheirer.console.control.ControlChannelState;
import io.github.dsheirer.console.control.ControlChannelStatus;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Control channel strip: one chip per running control channel (status dot, site, decoded site ID, frequency, decoded
 * network access code (P25 NAC, DMR color code or NXDN RAN) and decode quality, or the state when not decoding) and
 * a summary of the number of talkgroups on the console.  The summary is right-aligned when everything fits
 * on one row; otherwise it flows after the chips so that the chips can wrap side by side.  Invoke all methods on the
 * JavaFX thread.
 */
public class ControlChannelStrip extends HBox
{
    private static final String UNKNOWN = "\u2014";
    private final FlowPane mChips = new FlowPane(8, 6);
    private final Label mTitleLabel = ConsoleFonts.condensed(new Label("CONTROL"));
    private final Label mSummaryLabel = new Label();
    private List<ControlChannelStatus> mStatuses;
    private String mSummary;

    /**
     * Constructs an instance
     */
    public ControlChannelStrip()
    {
        getStyleClass().add("control-strip");
        setAccessibleText("Control channels");

        mTitleLabel.getStyleClass().add("control-strip-title");
        mTitleLabel.setMinWidth(Region.USE_PREF_SIZE);

        mChips.setAlignment(Pos.CENTER_LEFT);
        mChips.setPrefWrapLength(800);
        HBox.setHgrow(mChips, Priority.ALWAYS);

        mSummaryLabel.getStyleClass().add("secondary");
        mSummaryLabel.setMinWidth(Region.USE_PREF_SIZE);

        getChildren().addAll(mTitleLabel, mChips, mSummaryLabel);
        widthProperty().addListener((observable, oldValue, width) -> updateSummaryPlacement());
        update(List.of(), 0, false);
    }

    /**
     * Places the summary label at the right end of the strip when the title, chips and summary fit on one row,
     * otherwise as the last item of the chip flow so that the chips are not squeezed into one chip per row.
     */
    private void updateSummaryPlacement()
    {
        if(getWidth() <= 0)
        {
            return;
        }

        double required = snappedLeftInset() + snappedRightInset() + mTitleLabel.prefWidth(-1) +
            mSummaryLabel.prefWidth(-1) + getSpacing() * 2;

        for(var chip: mChips.getChildren())
        {
            if(chip != mSummaryLabel)
            {
                required += chip.prefWidth(-1) + mChips.getHgap();
            }
        }

        boolean inline = required <= getWidth();

        if(inline && mSummaryLabel.getParent() != this)
        {
            mChips.getChildren().remove(mSummaryLabel);
            getChildren().add(mSummaryLabel);
        }
        else if(!inline && mSummaryLabel.getParent() != mChips)
        {
            getChildren().remove(mSummaryLabel);
            mChips.getChildren().add(mSummaryLabel);
        }
    }

    /**
     * Updates the strip.  The chips are only rebuilt when the control channel status changes.
     * @param statuses for the running control channels
     * @param talkgroupCount number of (linked) cards on the console
     * @param filterActive true when calls for talkgroups that are not on the console are ignored
     */
    public void update(List<ControlChannelStatus> statuses, int talkgroupCount, boolean filterActive)
    {
        if(!statuses.equals(mStatuses))
        {
            mStatuses = statuses;
            mChips.getChildren().removeIf(node -> node != mSummaryLabel);
            int index = 0;

            if(statuses.isEmpty())
            {
                Label none = new Label("No control channels running");
                none.getStyleClass().add("secondary");
                mChips.getChildren().add(index, none);
            }
            else
            {
                for(ControlChannelStatus status: statuses)
                {
                    mChips.getChildren().add(index++, createChip(status));
                }
            }
        }

        String summary = talkgroupCount + (talkgroupCount == 1 ? " talkgroup" : " talkgroups") + " on console" +
            (filterActive ? " · others ignored" : "");

        if(!summary.equals(mSummary))
        {
            mSummary = summary;
            mSummaryLabel.setText(summary);
        }

        updateSummaryPlacement();
    }

    /**
     * Creates the chip for a control channel.
     */
    private static HBox createChip(ControlChannelStatus status)
    {
        Region dot = new Region();
        dot.getStyleClass().add("cc-dot");

        ControlChannelState state = status.getState();

        if(state.isDecoding())
        {
            dot.getStyleClass().add("locked");
        }
        else if(state == ControlChannelState.STOPPING)
        {
            dot.getStyleClass().add("stopped");
        }

        HBox chip = new HBox(dot);
        chip.getStyleClass().add("cc-chip");

        Label site = new Label(status.getSiteLabel());
        site.getStyleClass().add("cc-site");
        chip.getChildren().add(site);

        ControlChannelDetail detail = status.detail();

        if(detail.siteId() != null)
        {
            Label siteId = new Label(detail.siteId());
            siteId.getStyleClass().add("secondary");
            chip.getChildren().add(siteId);
        }

        if(status.frequency() > 0)
        {
            Label frequency = new Label(status.getFrequencyLabel());
            frequency.getStyleClass().add("mono");
            chip.getChildren().add(frequency);
        }

        if(detail.networkId() != null)
        {
            Label nac = new Label(detail.networkId());
            nac.getStyleClass().addAll("mono", "secondary");
            chip.getChildren().add(nac);
        }

        //Show the decode quality while decoding ("—" when not yet known), otherwise the state (e.g. SEARCHING)
        String decode = state.isDecoding() ? (detail.decodeQuality() != null ? detail.decodeQuality() : UNKNOWN) :
            state.getLabel();
        Label stateLabel = new Label(decode);
        stateLabel.getStyleClass().addAll("cc-state", "mono");
        chip.getChildren().add(stateLabel);

        String tooltip = status.channelName() + " (" + status.decoder() + ")" +
            (status.system() != null ? "\nSystem: " + status.system() : "") +
            (status.site() != null ? "\nSite: " + status.site() : "") +
            "\nState: " + state.getLabel() + (status.channelState() != null ? " (" + status.channelState() + ")" : "") +
            (detail.summary() != null ? "\n\nDecoded\n" + detail.summary() : "");
        Tooltip.install(chip, new Tooltip(tooltip));
        chip.setAccessibleText("Control channel " + status.getSiteLabel() +
            (detail.siteId() != null ? " site " + detail.siteId() : "") + " " + status.getFrequencyLabel() + " " +
            (state.isDecoding() && detail.decodeQuality() == null ? state.getLabel() : decode));
        return chip;
    }
}
