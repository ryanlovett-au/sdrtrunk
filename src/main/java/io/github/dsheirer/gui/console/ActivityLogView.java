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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;

/**
 * Collapsible console activity log: the most recent calls (time, talkgroup, radio, site and audio status).
 *
 * The title bar has a segmented toggle to show either the calls routed to console cards ("Console only") or all voice
 * calls ("All traffic"), including calls that are not on the console, which are de-emphasised (grey text, with the
 * IGNORED status in amber).  Invoke all methods on the JavaFX thread.
 */
public class ActivityLogView extends TitledPane
{
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault());
    private static final String[] STATUS_STYLE_CLASSES = {"status-audio", "status-muted", "status-encrypted",
        "status-clear", "status-ignored", "status-not-on-console"};
    private static final String OFF_CONSOLE_STYLE_CLASS = "off-console";

    private final TableView<ConsoleActivityLogEntry> mTableView = new TableView<>();
    private final ToIntFunction<String> mInkColorLookup;
    private final ToggleGroup mModeToggleGroup = new ToggleGroup();
    private final ToggleButton mConsoleOnlyButton = new ToggleButton("Console only");
    private final ToggleButton mAllTrafficButton = new ToggleButton("All traffic");
    private final Label mConsolePlaceholder = new Label("No console activity");
    private final Label mAllTrafficPlaceholder = new Label("No traffic");
    private ConsoleActivitySnapshot mSnapshot;
    private long mLogVersion = -1;
    private boolean mShowAll;

    /**
     * Constructs an instance
     * @param inkColorLookup provides the ink (text) RGB colour for a card identifier, or -1 for the default colour
     * @param showAllListener notified when the user changes the log mode (true for all traffic)
     */
    public ActivityLogView(ToIntFunction<String> inkColorLookup, Consumer<Boolean> showAllListener)
    {
        mInkColorLookup = inkColorLookup;
        setText("ACTIVITY LOG");
        getStyleClass().add("activity-log");
        setAnimated(false);
        setExpanded(true);

        //Segmented log mode toggle in the title bar
        mConsoleOnlyButton.setUserData(Boolean.FALSE);
        mAllTrafficButton.setUserData(Boolean.TRUE);
        mConsoleOnlyButton.getStyleClass().addAll("log-mode-button", "first");
        mAllTrafficButton.getStyleClass().addAll("log-mode-button", "last");
        mConsoleOnlyButton.setTooltip(new Tooltip("Show only calls on console talkgroup cards"));
        mAllTrafficButton.setTooltip(new Tooltip("Also show calls that are not on the console (ignored, not on the " +
            "console, or encrypted)"));
        mConsoleOnlyButton.setToggleGroup(mModeToggleGroup);
        mAllTrafficButton.setToggleGroup(mModeToggleGroup);
        mConsoleOnlyButton.setSelected(true);
        mModeToggleGroup.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
            if(newValue == null)
            {
                //Always keep one mode selected
                mModeToggleGroup.selectToggle(oldValue);
                return;
            }

            boolean showAll = Boolean.TRUE.equals(newValue.getUserData());

            if(showAll != mShowAll)
            {
                mShowAll = showAll;
                updateItems();

                if(showAllListener != null)
                {
                    showAllListener.accept(showAll);
                }
            }
        });

        HBox modeToggle = new HBox(mConsoleOnlyButton, mAllTrafficButton);
        modeToggle.getStyleClass().add("log-mode-toggle");
        //Keep clicks on the toggle from also expanding or collapsing the log
        modeToggle.addEventHandler(MouseEvent.MOUSE_RELEASED, MouseEvent::consume);
        modeToggle.addEventHandler(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
        setGraphic(modeToggle);
        setContentDisplay(ContentDisplay.RIGHT);
        setGraphicTextGap(16);

        mTableView.getStyleClass().add("activity-table");
        mTableView.setPrefHeight(280);
        mTableView.setMinHeight(80);
        mTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        mTableView.setPlaceholder(mConsolePlaceholder);
        mTableView.setRowFactory(table -> new LogRow());

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
        mSnapshot = snapshot;

        if(snapshot.logVersion() != mLogVersion)
        {
            mLogVersion = snapshot.logVersion();
            updateItems();
        }
    }

    /**
     * Indicates if the log shows all traffic (true) or only calls on console cards (false).
     */
    public boolean isShowAll()
    {
        return mShowAll;
    }

    /**
     * Sets the log mode without notifying the show all listener.
     * @param showAll true to show all traffic, false to show only calls on console cards
     */
    public void setShowAll(boolean showAll)
    {
        if(showAll != mShowAll)
        {
            mShowAll = showAll;
            (showAll ? mAllTrafficButton : mConsoleOnlyButton).setSelected(true);
            updateItems();
        }
    }

    /**
     * Shows the log entries for the current mode from the latest snapshot.
     */
    private void updateItems()
    {
        mTableView.setPlaceholder(mShowAll ? mAllTrafficPlaceholder : mConsolePlaceholder);

        if(mSnapshot != null)
        {
            mTableView.getItems().setAll(mShowAll ? mSnapshot.allTrafficLog() : mSnapshot.log());
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
            ConsoleActivityLogEntry entry = getTableRow().getItem();
            int ink = entry.isOnConsole() ? mInkColorLookup.applyAsInt(entry.cardId()) : -1;
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
                    case CLEAR -> "status-clear";
                    case IGNORED -> "status-ignored";
                    case NOT_ON_CONSOLE -> "status-not-on-console";
                });
            }
        }
    }

    /**
     * Table row that is de-emphasised (style class off-console) for calls that are not on the console.
     */
    private static class LogRow extends TableRow<ConsoleActivityLogEntry>
    {
        @Override
        protected void updateItem(ConsoleActivityLogEntry item, boolean empty)
        {
            super.updateItem(item, empty);
            getStyleClass().remove(OFF_CONSOLE_STYLE_CLASS);

            if(!empty && item != null && !item.isOnConsole())
            {
                getStyleClass().add(OFF_CONSOLE_STYLE_CLASS);
            }
        }
    }
}
