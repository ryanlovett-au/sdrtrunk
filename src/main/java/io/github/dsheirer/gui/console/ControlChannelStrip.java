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
 * Control channel strip: one chip per running control channel (status dot, site, site ID, frequency, NAC and decode
 * state) and a summary of the number of talkgroups on the console.  Invoke all methods on the JavaFX thread.
 */
public class ControlChannelStrip extends HBox
{
    private final FlowPane mChips = new FlowPane(8, 6);
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

        Label title = ConsoleFonts.condensed(new Label("CONTROL"));
        title.getStyleClass().add("control-strip-title");
        title.setMinWidth(Region.USE_PREF_SIZE);

        mChips.setAlignment(Pos.CENTER_LEFT);
        mChips.setPrefWrapLength(800);
        HBox.setHgrow(mChips, Priority.ALWAYS);

        mSummaryLabel.getStyleClass().add("secondary");
        mSummaryLabel.setMinWidth(Region.USE_PREF_SIZE);

        getChildren().addAll(title, mChips, mSummaryLabel);
        update(List.of(), 0, false);
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
            mChips.getChildren().clear();

            if(statuses.isEmpty())
            {
                Label none = new Label("No control channels running");
                none.getStyleClass().add("secondary");
                mChips.getChildren().add(none);
            }
            else
            {
                for(ControlChannelStatus status: statuses)
                {
                    mChips.getChildren().add(createChip(status));
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
    }

    /**
     * Creates the chip for a control channel.
     */
    private static HBox createChip(ControlChannelStatus status)
    {
        Region dot = new Region();
        dot.getStyleClass().add("cc-dot");

        if(status.locked())
        {
            dot.getStyleClass().add("locked");
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
            Label nac = new Label("NAC " + detail.networkId());
            nac.getStyleClass().addAll("mono", "secondary");
            chip.getChildren().add(nac);
        }

        String decode = detail.decodeQuality() != null ? detail.decodeQuality() :
            (status.locked() ? "LOCKED" : (status.state() != null ? status.state() : "SRCH"));
        Label state = new Label(decode);
        state.getStyleClass().addAll("cc-state", "mono");
        chip.getChildren().add(state);

        String tooltip = status.channelName() + " (" + status.decoder() + ")" +
            (status.system() != null ? "\nSystem: " + status.system() : "") +
            (status.site() != null ? "\nSite: " + status.site() : "") +
            (status.state() != null ? "\nState: " + status.state() : "");
        Tooltip.install(chip, new Tooltip(tooltip));
        chip.setAccessibleText("Control channel " + status.getSiteLabel() + " " + status.getFrequencyLabel() + " " +
            decode);
        return chip;
    }
}
