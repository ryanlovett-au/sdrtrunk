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

import io.github.dsheirer.console.activity.ConsoleActivityLogEntry;
import io.github.dsheirer.console.activity.ConsoleActivitySnapshot;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;

/**
 * Collapsible console activity log: the most recent calls routed to console cards (time, talkgroup, radio, site and
 * audio status).  Invoke all methods on the JavaFX thread.
 */
public class ActivityLogView extends TitledPane
{
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault());
    private static final String[] STATUS_STYLE_CLASSES = {"status-audio", "status-muted", "status-encrypted"};

    private final TableView<ConsoleActivityLogEntry> mTableView = new TableView<>();
    private final ToIntFunction<String> mInkColorLookup;
    private long mLogVersion = -1;

    /**
     * Constructs an instance
     * @param inkColorLookup provides the ink (text) RGB colour for a card identifier, or -1 for the default colour
     */
    public ActivityLogView(ToIntFunction<String> inkColorLookup)
    {
        mInkColorLookup = inkColorLookup;
        setText("ACTIVITY LOG");
        getStyleClass().add("activity-log");
        setAnimated(false);
        setExpanded(true);

        mTableView.getStyleClass().add("activity-table");
        mTableView.setPrefHeight(210);
        mTableView.setMinHeight(80);
        mTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        mTableView.setPlaceholder(new Label("No console activity"));

        TableColumn<ConsoleActivityLogEntry,String> time = column("Time", 90,
            entry -> TIME_FORMATTER.format(Instant.ofEpochMilli(entry.timestamp())));
        time.setCellFactory(column -> styledCell("mono"));
        time.setMinWidth(96);
        TableColumn<ConsoleActivityLogEntry,String> talkgroupName = column("Talkgroup", 200,
            ConsoleActivityLogEntry::talkgroupName);
        talkgroupName.setCellFactory(column -> new TalkgroupCell());
        TableColumn<ConsoleActivityLogEntry,String> talkgroup = column("TG", 90, ConsoleActivityLogEntry::talkgroup);
        talkgroup.setCellFactory(column -> styledCell("mono"));
        talkgroup.setMinWidth(60);
        TableColumn<ConsoleActivityLogEntry,String> radioName = column("Radio", 160, ConsoleActivityLogEntry::radioName);
        TableColumn<ConsoleActivityLogEntry,String> radioId = column("Radio ID", 110, ConsoleActivityLogEntry::radioId);
        radioId.setCellFactory(column -> styledCell("mono"));
        radioId.setMinWidth(90);
        TableColumn<ConsoleActivityLogEntry,String> site = column("Site", 160, ConsoleActivityLogEntry::site);
        TableColumn<ConsoleActivityLogEntry,String> status = column("Status", 110,
            entry -> entry.status() != null ? entry.status().toString() : null);
        status.setCellFactory(column -> new StatusCell());
        status.setMinWidth(100);

        mTableView.getColumns().addAll(time, talkgroupName, talkgroup, radioName, radioId, site, status);
        setContent(mTableView);
    }

    /**
     * Creates a table column with a string value extractor.
     */
    private static TableColumn<ConsoleActivityLogEntry,String> column(String title, double width,
                                                                      Function<ConsoleActivityLogEntry,String> value)
    {
        TableColumn<ConsoleActivityLogEntry,String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setSortable(false);
        column.setCellValueFactory(features -> new ReadOnlyStringWrapper(value.apply(features.getValue())));
        return column;
    }

    /**
     * Creates a text cell with the style class.
     */
    private static TableCell<ConsoleActivityLogEntry,String> styledCell(String styleClass)
    {
        TableCell<ConsoleActivityLogEntry,String> cell = new TableCell<>()
        {
            @Override
            protected void updateItem(String item, boolean empty)
            {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        };

        cell.getStyleClass().add(styleClass);
        return cell;
    }

    /**
     * Updates the log from the activity snapshot when the log has changed.
     */
    public void update(ConsoleActivitySnapshot snapshot)
    {
        if(snapshot.logVersion() != mLogVersion)
        {
            mLogVersion = snapshot.logVersion();
            mTableView.getItems().setAll(snapshot.log());
        }
    }

    /**
     * Forces the log to refresh (e.g. after card colours change).
     */
    public void refresh()
    {
        mTableView.refresh();
    }

    /**
     * Talkgroup name cell, coloured with the card's ink colour.
     */
    private class TalkgroupCell extends TableCell<ConsoleActivityLogEntry,String>
    {
        private TalkgroupCell()
        {
            getStyleClass().add("log-talkgroup");
        }

        @Override
        protected void updateItem(String item, boolean empty)
        {
            super.updateItem(item, empty);

            if(empty || getTableRow() == null || getTableRow().getItem() == null)
            {
                setText(null);
                setStyle(null);
                return;
            }

            setText(item);
            int ink = mInkColorLookup.applyAsInt(getTableRow().getItem().cardId());
            setStyle(ink >= 0 ? "-fx-text-fill: " + ConsoleCardColors.toHex(ink) + ";" : null);
        }
    }

    /**
     * Status cell, coloured by status.
     */
    private static class StatusCell extends TableCell<ConsoleActivityLogEntry,String>
    {
        @Override
        protected void updateItem(String item, boolean empty)
        {
            super.updateItem(item, empty);
            getStyleClass().removeAll(STATUS_STYLE_CLASSES);

            if(empty || item == null)
            {
                setText(null);
                return;
            }

            setText(item);

            if(getTableRow() != null && getTableRow().getItem() != null && getTableRow().getItem().status() != null)
            {
                getStyleClass().add(switch(getTableRow().getItem().status())
                {
                    case AUDIO -> "status-audio";
                    case MUTED -> "status-muted";
                    case ENCRYPTED -> "status-encrypted";
                });
            }
        }
    }
}
