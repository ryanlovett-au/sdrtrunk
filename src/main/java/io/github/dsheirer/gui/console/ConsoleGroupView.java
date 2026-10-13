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

import io.github.dsheirer.console.ConsoleCard;
import io.github.dsheirer.console.ConsoleGroup;
import io.github.dsheirer.console.activity.ConsoleGroupActivity;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;

/**
 * Console group panel: header with a collapse chevron, the group name, the active call count and a group mute
 * button, and the group's cards.  In edit mode the header has move, rename and delete buttons, the panel has an add
 * talkgroup button and a resize handle on its right edge, and cards can be dropped onto the panel (appended to the
 * group).  In any mode, aliases dragged from the alias sheet can be dropped onto the panel (header or cards) to add
 * their cards to the group, or move them from another group.
 *
 * Width: a group spans one or more console columns (or the full width; see ConsoleColumnPane.Spanned), set with the
 * header's context menu (Width) or, in edit mode, by dragging the resize handle, which snaps to whole columns and shows
 * a snap preview.  The cards of a group that spans several columns are laid out in a grid with the same number of card
 * columns (see ConsoleCardGrid).
 *
 * Collapsed groups show the header and a summary body (styled like the empty group body) instead of the cards: up to
 * four rows for the live cards (colour dot, talkgroup name, talker and status), ordered by call start (see
 * ConsoleGroupActivity), a "+N more active" row, or "N talkgroups · no activity".  The card views of a collapsed group
 * are not created (the console window creates them when the group is expanded).  Cards dragged in edit mode can be
 * dropped onto a collapsed group.
 *
 * Invoke all methods on the JavaFX thread.
 */
public class ConsoleGroupView extends VBox implements ConsoleColumnPane.Spanned
{
    private static final String EXPANDED_ICON = "M6 9 L12 15 L18 9";
    private static final String COLLAPSED_ICON = "M9 6 L15 12 L9 18";
    private static final String MUTED_ICON = "M11 5 L6 9 H3 V15 H6 L11 19 Z M16 9 L22 15 M22 9 L16 15";
    private static final String UNMUTED_ICON = "M11 5 L6 9 H3 V15 H6 L11 19 Z M15.5 8.5 A5 5 0 0 1 15.5 15.5 " +
        "M18.5 5.5 A9 9 0 0 1 18.5 18.5";
    private static final double RESIZE_HANDLE_WIDTH = 12;

    private final ConsoleWindow mConsoleWindow;
    private ConsoleGroup mGroup;
    private final Label mNameLabel;
    private final Label mActiveLabel = new Label();
    private final Button mCollapseButton = new Button();
    private final SVGPath mCollapseIcon = new SVGPath();
    private final ToggleButton mMuteButton = new ToggleButton();
    private final SVGPath mMuteIcon = new SVGPath();
    private final Button mMoveLeftButton;
    private final Button mMoveRightButton;
    private final HBox mEditButtons;
    private final VBox mCardsBox = new VBox();
    private final ConsoleCardGrid mCardGrid;
    private final Label mEmptyLabel = new Label("No talkgroups in this group");
    private final Button mAddCardButton = new Button("+ Add talkgroup");
    private final VBox mSummaryBody = new VBox();
    private final List<SummaryRow> mSummaryRows = new ArrayList<>();
    private final Label mMoreLabel = new Label();
    private final Label mIdleLabel = new Label();
    private final Tooltip mSummaryTooltip = new Tooltip();
    private final StackPane mResizeHandle = new StackPane();
    private List<String> mCardIds = List.of();
    private ConsoleGroupActivity mActivity;
    private int mDisplayedSpan = 1;
    private int mResizeSpan = -1;
    private boolean mEditMode;
    private boolean mUpdating;

    /**
     * Constructs an instance
     * @param consoleWindow that owns this view
     * @param group snapshot
     * @param groupCount total number of groups (to enable the move buttons)
     * @param cardColumnGap horizontal gap between card columns when the group spans several console columns
     */
    public ConsoleGroupView(ConsoleWindow consoleWindow, ConsoleGroup group, int groupCount, double cardColumnGap)
    {
        mConsoleWindow = consoleWindow;
        mGroup = group;
        mCardGrid = new ConsoleCardGrid(cardColumnGap, 6);
        getStyleClass().add("group");

        mCollapseIcon.getStyleClass().add("group-chevron-icon");
        mCollapseButton.setGraphic(mCollapseIcon);
        mCollapseButton.getStyleClass().add("group-collapse-button");
        mCollapseButton.setMinWidth(Region.USE_PREF_SIZE);
        mCollapseButton.setOnAction(e -> mConsoleWindow.getLayoutManager().setGroupCollapsed(mGroup.getId(),
            !mGroup.isCollapsed()));

        mNameLabel = ConsoleFonts.condensed(new Label());
        mNameLabel.getStyleClass().add("group-name");
        mNameLabel.setMinWidth(0);
        mNameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(mNameLabel, Priority.ALWAYS);
        mActiveLabel.getStyleClass().add("group-active");
        mActiveLabel.setMinWidth(Region.USE_PREF_SIZE);

        mMuteIcon.getStyleClass().add("group-mute-icon");
        mMuteIcon.setScaleX(0.85);
        mMuteIcon.setScaleY(0.85);
        mMuteButton.setGraphic(mMuteIcon);
        mMuteButton.getStyleClass().add("group-mute-button");
        mMuteButton.setMinWidth(Region.USE_PREF_SIZE);
        mMuteButton.selectedProperty().addListener((observable, oldValue, muted) -> {
            updateMuteButton();

            if(!mUpdating)
            {
                mConsoleWindow.getLayoutManager().setGroupMuted(mGroup.getId(), muted);
            }
        });

        mMoveLeftButton = editButton("◀", "Move group left");
        mMoveLeftButton.setOnAction(e -> mConsoleWindow.getLayoutManager().moveGroup(mGroup.getId(),
            mGroup.getDisplayOrder() - 1));
        mMoveRightButton = editButton("▶", "Move group right");
        mMoveRightButton.setOnAction(e -> mConsoleWindow.getLayoutManager().moveGroup(mGroup.getId(),
            mGroup.getDisplayOrder() + 1));
        Button rename = editButton("✎", "Rename group");
        rename.setOnAction(e -> mConsoleWindow.renameGroup(mGroup));
        Button delete = editButton("✕", "Delete group");
        delete.setOnAction(e -> mConsoleWindow.deleteGroup(mGroup));
        mEditButtons = new HBox(4, mMoveLeftButton, mMoveRightButton, rename, delete);
        mEditButtons.setAlignment(Pos.CENTER_RIGHT);
        mEditButtons.setMinWidth(Region.USE_PREF_SIZE);

        HBox headerRow = new HBox(8, mCollapseButton, mNameLabel, mActiveLabel, mMuteButton, mEditButtons);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        VBox header = new VBox(headerRow);
        header.getStyleClass().add("group-header");
        header.setOnContextMenuRequested(event -> {
            createHeaderMenu().show(header, event.getScreenX(), event.getScreenY());
            event.consume();
        });

        mCardsBox.getStyleClass().add("group-cards");
        mCardsBox.getChildren().add(mCardGrid);
        mEmptyLabel.getStyleClass().add("group-empty");
        mAddCardButton.getStyleClass().add("add-card-button");
        mAddCardButton.setMaxWidth(Double.MAX_VALUE);
        mAddCardButton.setOnAction(e -> mConsoleWindow.addCard(mGroup.getId()));

        //Collapsed group summary body: activity rows, the count of other live cards, or the idle text
        mSummaryBody.getStyleClass().addAll("group-cards", "group-summary-body");

        for(int x = 0; x < ConsoleGroupActivity.MAXIMUM_ROWS; x++)
        {
            SummaryRow row = new SummaryRow();
            mSummaryRows.add(row);
            mSummaryBody.getChildren().add(row);
        }

        mMoreLabel.getStyleClass().add("group-summary-more");
        mIdleLabel.getStyleClass().add("group-empty");
        mIdleLabel.setMinWidth(0);
        mSummaryBody.getChildren().addAll(mMoreLabel, mIdleLabel);
        Tooltip.install(mSummaryBody, mSummaryTooltip);

        Region resizeGrip = new Region();
        resizeGrip.getStyleClass().add("group-resize-grip");
        mResizeHandle.getChildren().add(resizeGrip);
        mResizeHandle.getStyleClass().add("group-resize-handle");
        mResizeHandle.setManaged(false);
        mResizeHandle.setCursor(Cursor.H_RESIZE);
        Tooltip.install(mResizeHandle, mConsoleWindow.getTooltip("Drag to change the group width (whole columns). " +
            "Right-click the group header for width options."));
        setupResizeHandle();

        getChildren().addAll(header, mCardsBox, mSummaryBody, mResizeHandle);
        setupDropTarget();
        setGroup(group, groupCount);
        setActivity(new ConsoleGroupActivity(List.of(), 0, 0, group.getCards().size()));
    }

    /**
     * Creates a header edit button.
     */
    private Button editButton(String text, String tooltip)
    {
        Button button = new Button(text);
        button.getStyleClass().add("group-edit-button");
        button.setTooltip(mConsoleWindow.getTooltip(tooltip));
        button.setAccessibleText(tooltip);
        return button;
    }

    /**
     * Group identifier
     */
    public String getGroupId()
    {
        return mGroup.getId();
    }

    /**
     * Group snapshot.  The collapsed, muted and span state of the snapshot is kept current by setGroupState().
     */
    public ConsoleGroup getGroup()
    {
        return mGroup;
    }

    /**
     * Updates the view for a new snapshot of the group (e.g. after a layout change): name, move buttons, collapsed and
     * muted state and column span.
     * @param group snapshot
     * @param groupCount total number of groups (to enable the move buttons)
     */
    public void setGroup(ConsoleGroup group, int groupCount)
    {
        if(mGroup.getColumnSpan() != group.getColumnSpan())
        {
            requestParentLayout();
        }

        mGroup = group;
        mCardIds = group.getCards().stream().map(ConsoleCard::getId).toList();
        mNameLabel.setText(group.getName().toUpperCase());
        mMoveLeftButton.setDisable(group.getDisplayOrder() <= 0);
        mMoveRightButton.setDisable(group.getDisplayOrder() >= groupCount - 1);
        setGroupState(group.isCollapsed(), group.isMuted(), group.getColumnSpan());
    }

    /**
     * Applies the group's collapsed and muted state and column span: a collapsed group shows the activity summary body
     * instead of its cards, and the mute button shows the group's mute state.
     * @param collapsed state
     * @param muted state
     * @param columnSpan requested column span (see ConsoleGroup.getColumnSpan())
     */
    public void setGroupState(boolean collapsed, boolean muted, int columnSpan)
    {
        mGroup.setCollapsed(collapsed);
        mGroup.setMuted(muted);

        if(mGroup.getColumnSpan() != columnSpan)
        {
            mGroup.setColumnSpan(columnSpan);
            requestParentLayout();
        }

        mCardsBox.setVisible(!collapsed);
        mCardsBox.setManaged(!collapsed);
        mSummaryBody.setVisible(collapsed);
        mSummaryBody.setManaged(collapsed);
        mCollapseIcon.setContent(collapsed ? COLLAPSED_ICON : EXPANDED_ICON);
        mCollapseButton.setTooltip(mConsoleWindow.getTooltip(collapsed ? "Expand group (show cards)" :
            "Collapse group (activity summary only)"));
        mCollapseButton.setAccessibleText((collapsed ? "Expand group " : "Collapse group ") + mGroup.getName());

        if(collapsed && !getStyleClass().contains("collapsed"))
        {
            getStyleClass().add("collapsed");
        }
        else if(!collapsed)
        {
            getStyleClass().remove("collapsed");
        }

        mUpdating = true;

        try
        {
            mMuteButton.setSelected(muted);
        }
        finally
        {
            mUpdating = false;
        }

        updateMuteButton();
        updateSummary();
    }

    /**
     * Identifiers of the group's cards, in display order.
     */
    public List<String> getCardIds()
    {
        return mCardIds;
    }

    @Override
    public int getRequestedColumnSpan()
    {
        return mGroup.getColumnSpan();
    }

    @Override
    public void setDisplayedColumnSpan(int span)
    {
        mDisplayedSpan = span;
        mCardGrid.setColumns(span);
    }

    /**
     * Number of columns of the console column layout that contains this group, or 1.
     */
    private int getColumnCount()
    {
        return getParent() instanceof ConsoleColumnPane pane ? pane.getColumnCount() : 1;
    }

    /**
     * Updates the group mute button icon and tooltip for the mute state.
     */
    private void updateMuteButton()
    {
        boolean muted = mMuteButton.isSelected();
        mMuteIcon.setContent(muted ? MUTED_ICON : UNMUTED_ICON);
        mMuteButton.setTooltip(mConsoleWindow.getTooltip(muted ? "Unmute group (cards keep their own mute settings)" :
            "Mute all cards in this group"));
        mMuteButton.setAccessibleText((muted ? "Unmute group " : "Mute group ") + mGroup.getName());
    }

    /**
     * Updates the group's activity: the active call count in the header and, for a collapsed group, the summary body.
     * Ignored when the summary has not changed.
     * @param activity summary for the group's cards
     */
    public void setActivity(ConsoleGroupActivity activity)
    {
        ConsoleGroupActivity summary = activity != null ? activity : ConsoleGroupActivity.IDLE;

        if(summary.equals(mActivity))
        {
            return;
        }

        mActivity = summary;
        mActiveLabel.setText(summary.activeCount() > 0 ? summary.activeCount() + " ACTIVE" : "");

        if(mGroup.isCollapsed())
        {
            updateSummary();
        }
    }

    /**
     * Refreshes the summary body after a change of colours (e.g. alias colours or the palette).
     */
    public void refreshSummary()
    {
        updateSummary();
    }

    /**
     * Updates the collapsed group's summary body from the activity summary.
     */
    private void updateSummary()
    {
        if(mActivity == null || !mGroup.isCollapsed())
        {
            return;
        }

        boolean idle = mActivity.isIdle();
        List<ConsoleGroupActivity.Row> rows = mActivity.rows();

        for(int x = 0; x < mSummaryRows.size(); x++)
        {
            SummaryRow summaryRow = mSummaryRows.get(x);
            boolean shown = x < rows.size();
            summaryRow.setVisible(shown);
            summaryRow.setManaged(shown);

            if(shown)
            {
                summaryRow.update(rows.get(x));
            }
        }

        boolean more = mActivity.moreCount() > 0;
        mMoreLabel.setText(mActivity.getMoreText());
        mMoreLabel.setVisible(more);
        mMoreLabel.setManaged(more);
        mIdleLabel.setText(mActivity.getIdleText());
        mIdleLabel.setVisible(idle);
        mIdleLabel.setManaged(idle);

        String description = idle ? mIdleLabel.getText() : mActivity.getDescription();
        mSummaryBody.setAccessibleText(description);
        mSummaryTooltip.setText(description);
    }

    /**
     * Sets the card views for this group, in display order.  The cards are only replaced when they changed.
     * @param cardViews in display order (empty for a collapsed group, whose card views are not created)
     * @param editMode true when layout editing is enabled
     */
    public void setCardViews(List<ConsoleCardView> cardViews, boolean editMode)
    {
        mEditMode = editMode;

        if(!mCardGrid.getChildren().equals(cardViews))
        {
            mCardGrid.getChildren().setAll(cardViews);
        }

        List<Region> extras = new ArrayList<>();

        if(mGroup.getCards().isEmpty() && !editMode)
        {
            extras.add(mEmptyLabel);
        }

        if(editMode)
        {
            extras.add(mAddCardButton);
        }

        if(!mCardsBox.getChildren().subList(1, mCardsBox.getChildren().size()).equals(extras))
        {
            mCardsBox.getChildren().remove(1, mCardsBox.getChildren().size());
            mCardsBox.getChildren().addAll(extras);
        }

        mEditButtons.setVisible(editMode);
        mEditButtons.setManaged(editMode);
        mResizeHandle.setVisible(editMode);

        //The active call count is hidden while editing so that the group name has room next to the edit buttons
        mActiveLabel.setVisible(!editMode);
        mActiveLabel.setManaged(!editMode);
        updateSummary();
    }

    @Override
    protected void layoutChildren()
    {
        super.layoutChildren();

        //Resize handle on the right edge (unmanaged), over the group border
        mResizeHandle.resizeRelocate(getWidth() - RESIZE_HANDLE_WIDTH + 2, 0, RESIZE_HANDLE_WIDTH, getHeight());
    }

    /**
     * Resize handle (edit mode): dragging snaps the group width to whole columns, with a snap preview, and sets the
     * group's column span when released.
     */
    private void setupResizeHandle()
    {
        mResizeHandle.setOnMousePressed(event -> {
            if(event.getButton() == MouseButton.PRIMARY && mEditMode)
            {
                mResizeSpan = mDisplayedSpan;
                event.consume();
            }
        });

        mResizeHandle.setOnMouseDragged(event -> {
            if(mResizeSpan >= 0 && getParent() instanceof ConsoleColumnPane pane)
            {
                double width = sceneToLocal(event.getSceneX(), event.getSceneY()).getX();
                mResizeSpan = ConsoleMasonry.getSnappedSpan(width, pane.getColumnWidth(), pane.getGap(),
                    pane.getColumnCount());
                pane.showSpanPreview(this, mResizeSpan);
                event.consume();
            }
        });

        mResizeHandle.setOnMouseReleased(event -> {
            if(mResizeSpan >= 0)
            {
                if(getParent() instanceof ConsoleColumnPane pane)
                {
                    pane.hideSpanPreview();
                }

                int span = mResizeSpan;
                mResizeSpan = -1;

                if(span != mDisplayedSpan || span != mGroup.getColumnSpan())
                {
                    mConsoleWindow.getLayoutManager().setGroupColumnSpan(mGroup.getId(), span);
                }

                event.consume();
            }
        });
    }

    /**
     * Creates the group header context menu: width (column span), collapse or expand, and mute or unmute.  Only the
     * spans that fit the current number of columns are offered, plus full width (and the group's current span when it
     * is clamped because the window is narrow).
     */
    private ContextMenu createHeaderMenu()
    {
        Menu widthMenu = new Menu("Width");
        ToggleGroup toggleGroup = new ToggleGroup();
        int columns = Math.min(getColumnCount(), ConsoleGroup.MAXIMUM_COLUMN_SPAN);
        int requested = mGroup.getColumnSpan();

        for(int span = 1; span <= Math.max(columns, 1); span++)
        {
            widthMenu.getItems().add(widthItem(toggleGroup, span, span == 1 ? "1 column" : span + " columns",
                requested == span));
        }

        if(requested > columns)
        {
            widthMenu.getItems().add(widthItem(toggleGroup, requested, requested + " columns (" + columns +
                (columns == 1 ? " fits)" : " fit)"), true));
        }

        widthMenu.getItems().add(new SeparatorMenuItem());
        widthMenu.getItems().add(widthItem(toggleGroup, ConsoleGroup.FULL_WIDTH, "Full width",
            requested == ConsoleGroup.FULL_WIDTH));

        MenuItem collapse = new MenuItem(mGroup.isCollapsed() ? "Expand group" : "Collapse group");
        collapse.setOnAction(e -> mConsoleWindow.getLayoutManager().setGroupCollapsed(mGroup.getId(),
            !mGroup.isCollapsed()));
        MenuItem mute = new MenuItem(mGroup.isMuted() ? "Unmute group" : "Mute group");
        mute.setOnAction(e -> mConsoleWindow.getLayoutManager().setGroupMuted(mGroup.getId(), !mGroup.isMuted()));

        return new ContextMenu(widthMenu, new SeparatorMenuItem(), collapse, mute);
    }

    /**
     * Creates a width (column span) menu item.
     */
    private RadioMenuItem widthItem(ToggleGroup toggleGroup, int span, String text, boolean selected)
    {
        RadioMenuItem item = new RadioMenuItem(text);
        item.setToggleGroup(toggleGroup);
        item.setSelected(selected);
        item.setOnAction(e -> mConsoleWindow.getLayoutManager().setGroupColumnSpan(mGroup.getId(), span));
        return item;
    }

    /**
     * Shows or hides the drop highlight on the group's cards area.
     */
    private void setDropHighlight(boolean highlight)
    {
        //A collapsed group has no visible cards area, so the whole group panel is highlighted
        Region target = mGroup.isCollapsed() ? this : mCardsBox;

        if(highlight && !target.getStyleClass().contains("drop-target"))
        {
            target.getStyleClass().add("drop-target");
        }
        else if(!highlight)
        {
            getStyleClass().remove("drop-target");
            mCardsBox.getStyleClass().remove("drop-target");
        }
    }

    /**
     * Cards dropped onto the group panel (outside of another card) are appended to the group.
     */
    private void setupDropTarget()
    {
        mCardsBox.setOnDragOver(event -> {
            if(mConsoleWindow.isEditMode() && ConsoleCardView.getDraggedCardId(event.getDragboard()) != null)
            {
                event.acceptTransferModes(TransferMode.MOVE);

                if(!mCardsBox.getStyleClass().contains("drop-target"))
                {
                    mCardsBox.getStyleClass().add("drop-target");
                }

                event.consume();
            }
        });

        mCardsBox.setOnDragExited(event -> mCardsBox.getStyleClass().remove("drop-target"));

        //Aliases dragged from the alias sheet: drag events that the cards and the cards box do not consume bubble up
        //to the group panel, so the whole panel (header included) is the drop target.  Cards dragged in edit mode can
        //be dropped onto a collapsed group (its cards box is hidden).
        setOnDragOver(event -> {
            if(AliasDragFormat.hasAliases(event.getDragboard()))
            {
                event.acceptTransferModes(TransferMode.COPY);
                setDropHighlight(true);
                event.consume();
            }
            else if(mEditMode && mGroup.isCollapsed() &&
                ConsoleCardView.getDraggedCardId(event.getDragboard()) != null)
            {
                event.acceptTransferModes(TransferMode.MOVE);
                setDropHighlight(true);
                event.consume();
            }
        });

        setOnDragExited(event -> setDropHighlight(false));

        setOnDragDropped(event -> {
            setDropHighlight(false);
            List<String> aliasUuids = AliasDragFormat.getAliasUuids(event.getDragboard());
            String cardId = ConsoleCardView.getDraggedCardId(event.getDragboard());

            if(!aliasUuids.isEmpty())
            {
                mConsoleWindow.addAliases(mGroup.getId(), aliasUuids);
                event.setDropCompleted(true);
                event.consume();
            }
            else if(cardId != null && mEditMode && mGroup.isCollapsed())
            {
                mConsoleWindow.moveCard(cardId, mGroup.getId(), Integer.MAX_VALUE);
                event.setDropCompleted(true);
                event.consume();
            }
        });

        mCardsBox.setOnDragDropped(event -> {
            mCardsBox.getStyleClass().remove("drop-target");
            String cardId = ConsoleCardView.getDraggedCardId(event.getDragboard());

            if(cardId != null)
            {
                mConsoleWindow.moveCard(cardId, mGroup.getId(), Integer.MAX_VALUE);
                event.setDropCompleted(true);
            }
            else
            {
                event.setDropCompleted(false);
            }

            event.consume();
        });
    }

    /**
     * Collapsed group summary row for a live card: colour dot, talkgroup name, talker and status pill.  Rows are
     * created once and updated in place.
     */
    private class SummaryRow extends HBox
    {
        private final Circle mDot = new Circle(5);
        private final Label mName = new Label();
        private final Label mTalker = new Label();
        private final Label mStatus = new Label();
        private String mStyleKey;

        private SummaryRow()
        {
            super(8);
            getStyleClass().add("group-summary-row");
            setAlignment(Pos.CENTER_LEFT);
            mDot.getStyleClass().add("group-summary-dot");
            mDot.setStrokeWidth(1.5);
            mName.getStyleClass().add("group-summary-name");
            mName.setMinWidth(0);
            mName.setMaxWidth(Double.MAX_VALUE);
            mName.setTextOverrun(OverrunStyle.ELLIPSIS);
            HBox.setHgrow(mName, Priority.ALWAYS);
            mTalker.getStyleClass().add("group-summary-talker");
            mTalker.setMinWidth(0);
            mTalker.setTextOverrun(OverrunStyle.ELLIPSIS);
            mStatus.getStyleClass().add("group-summary-status");
            mStatus.setMinWidth(Region.USE_PREF_SIZE);
            getChildren().addAll(mDot, mName, mTalker, mStatus);
        }

        /**
         * Updates the row for the live card.
         */
        private void update(ConsoleGroupActivity.Row row)
        {
            mName.setText(row.name());
            mTalker.setText(row.talker());
            mStatus.setText(row.getStatusLabel());

            ConsoleCardColors.Palette palette = mConsoleWindow.getPalette();
            int base = mConsoleWindow.getBaseColor(row.cardId());
            ConsoleCardColors.VisualState state = ConsoleCardColors.VisualState.of(row.state(), row.hangFrom(),
                row.clearAlert(), row.muted(), true);
            String key = base + ":" + state + ":" + row.clearAlert() + ":" + palette;

            if(key.equals(mStyleKey))
            {
                return;
            }

            mStyleKey = key;
            ConsoleCardColors.SummaryPaint paint = ConsoleCardColors.getSummaryPaint(base, state, row.clearAlert(),
                palette);
            mDot.setFill(Color.web(ConsoleCardColors.toHex(paint.dot())));
            mDot.setStroke(Color.web(ConsoleCardColors.toHex(paint.dotOutline())));
            mStatus.setStyle("-fx-background-color: " + ConsoleCardColors.toHex(paint.statusFill()) +
                "; -fx-border-color: " + ConsoleCardColors.toHex(paint.statusBorder()) + "; -fx-text-fill: " +
                ConsoleCardColors.toHex(paint.statusText()) + ";");
        }
    }
}
