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

import io.github.dsheirer.console.ConsoleGroup;
import io.github.dsheirer.console.activity.ConsoleGroupActivity;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;

/**
 * Console group panel: navy header with a collapse chevron, the group name, the active call count and a group mute
 * button, and the group's cards in a vertical list.  In edit mode the header has move, rename and delete buttons, the
 * panel has an add talkgroup button, and cards can be dropped onto the panel (appended to the group).  In any mode,
 * aliases dragged from the alias sheet can be dropped onto the panel (header or cards) to add their cards to the group,
 * or move them from another group.
 *
 * Collapsed groups show the header only, with a second header row that summarises the activity on the group's cards
 * (see ConsoleGroupActivity): a badge in the featured card's colour with the card name and talker, and the count of
 * other live cards.  Cards dragged in edit mode can be dropped onto a collapsed group's header.
 *
 * Invoke all methods on the JavaFX thread.
 */
public class ConsoleGroupView extends VBox
{
    private static final String EXPANDED_ICON = "M6 9 L12 15 L18 9";
    private static final String COLLAPSED_ICON = "M9 6 L15 12 L9 18";
    private static final String MUTED_ICON = "M11 5 L6 9 H3 V15 H6 L11 19 Z M16 9 L22 15 M22 9 L16 15";
    private static final String UNMUTED_ICON = "M11 5 L6 9 H3 V15 H6 L11 19 Z M15.5 8.5 A5 5 0 0 1 15.5 15.5 " +
        "M18.5 5.5 A9 9 0 0 1 18.5 18.5";
    private static final String CLEAR_FILL = "#B42318";
    private static final String CLEAR_TEXT = "#FFFFFF";
    /**
     * Light outline of the red clear alert badge, which has low contrast with the navy header on its own.
     */
    private static final String CLEAR_OUTLINE = "#FFB4AB";

    private final ConsoleWindow mConsoleWindow;
    private final ConsoleGroup mGroup;
    private final Label mNameLabel;
    private final Label mActiveLabel = new Label();
    private final Button mCollapseButton = new Button();
    private final SVGPath mCollapseIcon = new SVGPath();
    private final ToggleButton mMuteButton = new ToggleButton();
    private final SVGPath mMuteIcon = new SVGPath();
    private final HBox mSummaryRow = new HBox(6);
    private final HBox mSummaryBadge = new HBox(6);
    private final Circle mSummaryDot = new Circle(4);
    private final Label mSummaryLabel = new Label();
    private final Label mMoreLabel = new Label();
    private final Label mIdleLabel = new Label();
    private final Tooltip mSummaryTooltip = new Tooltip();
    private final VBox mCardsBox = new VBox();
    private final Label mEmptyLabel = new Label("No talkgroups in this group");
    private final Button mAddCardButton = new Button("+ Add talkgroup");
    private final HBox mEditButtons;
    private int mActiveCount = -1;
    private ConsoleGroupActivity mActivity;
    private int mActivityColor = -1;
    private boolean mEditMode;
    private boolean mUpdating;

    /**
     * Constructs an instance
     * @param consoleWindow that owns this view
     * @param group snapshot
     * @param groupCount total number of groups (to enable the move buttons)
     */
    public ConsoleGroupView(ConsoleWindow consoleWindow, ConsoleGroup group, int groupCount)
    {
        mConsoleWindow = consoleWindow;
        mGroup = group;
        getStyleClass().add("group");

        mCollapseIcon.getStyleClass().add("group-chevron-icon");
        mCollapseButton.setGraphic(mCollapseIcon);
        mCollapseButton.getStyleClass().add("group-collapse-button");
        mCollapseButton.setMinWidth(Region.USE_PREF_SIZE);
        mCollapseButton.setOnAction(e -> mConsoleWindow.getLayoutManager().setGroupCollapsed(mGroup.getId(),
            !mGroup.isCollapsed()));

        mNameLabel = ConsoleFonts.condensed(new Label(group.getName().toUpperCase()));
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

        Button moveLeft = editButton("◀", "Move group left");
        moveLeft.setDisable(group.getDisplayOrder() <= 0);
        moveLeft.setOnAction(e -> mConsoleWindow.getLayoutManager().moveGroup(mGroup.getId(),
            mGroup.getDisplayOrder() - 1));
        Button moveRight = editButton("▶", "Move group right");
        moveRight.setDisable(group.getDisplayOrder() >= groupCount - 1);
        moveRight.setOnAction(e -> mConsoleWindow.getLayoutManager().moveGroup(mGroup.getId(),
            mGroup.getDisplayOrder() + 1));
        Button rename = editButton("\u270E", "Rename group");
        rename.setOnAction(e -> mConsoleWindow.renameGroup(mGroup));
        Button delete = editButton("\u2715", "Delete group");
        delete.setOnAction(e -> mConsoleWindow.deleteGroup(mGroup));
        mEditButtons = new HBox(4, moveLeft, moveRight, rename, delete);
        mEditButtons.setAlignment(Pos.CENTER_RIGHT);
        mEditButtons.setMinWidth(Region.USE_PREF_SIZE);

        HBox headerRow = new HBox(8, mCollapseButton, mNameLabel, mActiveLabel, mMuteButton, mEditButtons);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        //Collapsed group activity summary: badge (dot, card name and talker) and the count of other live cards
        mSummaryDot.getStyleClass().add("group-summary-dot");
        mSummaryLabel.getStyleClass().add("group-summary-text");
        mSummaryLabel.setMinWidth(0);
        mSummaryLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        mSummaryBadge.getStyleClass().add("group-summary-badge");
        mSummaryBadge.setAlignment(Pos.CENTER_LEFT);
        //The badge is as wide as its text and shrinks (the text is truncated with an ellipsis) when space is short
        mSummaryBadge.setMinWidth(0);
        mSummaryBadge.setMaxWidth(Region.USE_PREF_SIZE);
        mSummaryBadge.getChildren().addAll(mSummaryDot, mSummaryLabel);
        mMoreLabel.getStyleClass().add("group-summary-more");
        mMoreLabel.setMinWidth(Region.USE_PREF_SIZE);
        mIdleLabel.getStyleClass().add("group-summary-idle");
        mIdleLabel.setMinWidth(0);
        mSummaryRow.getStyleClass().add("group-summary");
        mSummaryRow.setAlignment(Pos.CENTER_LEFT);
        mSummaryRow.getChildren().addAll(mSummaryBadge, mMoreLabel, mIdleLabel);
        Tooltip.install(mSummaryRow, mSummaryTooltip);

        VBox header = new VBox(headerRow, mSummaryRow);
        header.getStyleClass().add("group-header");

        mCardsBox.getStyleClass().add("group-cards");
        mEmptyLabel.getStyleClass().add("group-empty");
        mAddCardButton.getStyleClass().add("add-card-button");
        mAddCardButton.setMaxWidth(Double.MAX_VALUE);
        mAddCardButton.setOnAction(e -> mConsoleWindow.addCard(mGroup.getId()));

        getChildren().addAll(header, mCardsBox);
        setupDropTarget();
        setGroupState(group.isCollapsed(), group.isMuted());
        setActivity(ConsoleGroupActivity.IDLE, 0);
    }

    /**
     * Creates a header edit button.
     */
    private static Button editButton(String text, String tooltip)
    {
        Button button = new Button(text);
        button.getStyleClass().add("group-edit-button");
        button.setTooltip(new Tooltip(tooltip));
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
     * Group snapshot.  The collapsed and muted state of the snapshot is kept current by setGroupState().
     */
    public ConsoleGroup getGroup()
    {
        return mGroup;
    }

    /**
     * Applies the group's collapsed and muted state: a collapsed group hides its cards and shows the activity summary
     * row, and the mute button shows the group's mute state.
     * @param collapsed state
     * @param muted state
     */
    public void setGroupState(boolean collapsed, boolean muted)
    {
        mGroup.setCollapsed(collapsed);
        mGroup.setMuted(muted);

        mCardsBox.setVisible(!collapsed);
        mCardsBox.setManaged(!collapsed);
        mSummaryRow.setVisible(collapsed);
        mSummaryRow.setManaged(collapsed);
        mCollapseIcon.setContent(collapsed ? COLLAPSED_ICON : EXPANDED_ICON);
        String action = (collapsed ? "Expand group " : "Collapse group ") + mGroup.getName();
        mCollapseButton.setTooltip(new Tooltip(collapsed ? "Expand group (show cards)" :
            "Collapse group (header only)"));
        mCollapseButton.setAccessibleText(action);

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
        updateIdleText();
    }

    /**
     * Updates the group mute button icon and tooltip for the mute state.
     */
    private void updateMuteButton()
    {
        boolean muted = mMuteButton.isSelected();
        mMuteIcon.setContent(muted ? MUTED_ICON : UNMUTED_ICON);
        mMuteButton.setTooltip(new Tooltip(muted ? "Unmute group (cards keep their own mute settings)" :
            "Mute all cards in this group"));
        mMuteButton.setAccessibleText((muted ? "Unmute group " : "Mute group ") + mGroup.getName());
    }

    /**
     * Updates the summary row text shown when no card in the collapsed group is live.
     */
    private void updateIdleText()
    {
        int cards = mGroup.getCards().size();
        mIdleLabel.setText((cards == 1 ? "1 talkgroup" : cards + " talkgroups") + ConsoleGroupActivity.SEPARATOR +
            "no activity");
    }

    /**
     * Updates the collapsed group's activity summary.  Ignored when the summary and colour have not changed.
     * @param activity summary for the group's cards
     * @param baseColor RGB base (active) colour of the featured card (see ConsoleCardColors.getBaseColor())
     */
    public void setActivity(ConsoleGroupActivity activity, int baseColor)
    {
        ConsoleGroupActivity summary = activity != null ? activity : ConsoleGroupActivity.IDLE;

        if(summary.equals(mActivity) && baseColor == mActivityColor)
        {
            return;
        }

        mActivity = summary;
        mActivityColor = baseColor;
        boolean idle = summary.isIdle();
        mSummaryBadge.setVisible(!idle);
        mSummaryBadge.setManaged(!idle);
        mMoreLabel.setVisible(!idle && summary.otherCount() > 0);
        mMoreLabel.setManaged(!idle && summary.otherCount() > 0);
        mIdleLabel.setVisible(idle);
        mIdleLabel.setManaged(idle);

        String description = summary.getDescription();
        mSummaryRow.setAccessibleText(description);
        mSummaryTooltip.setText(description);

        if(idle)
        {
            return;
        }

        String fill;
        String text;
        String dot;
        String outline = "transparent";

        if(summary.clearAlert())
        {
            fill = CLEAR_FILL;
            text = CLEAR_TEXT;
            dot = CLEAR_TEXT;
            outline = CLEAR_OUTLINE;
        }
        else
        {
            ConsoleCardColors.VisualState state = ConsoleCardColors.VisualState.of(summary.state(), summary.muted(),
                true);
            ConsoleCardColors.CardPaint paint = ConsoleCardColors.getPaint(baseColor, state);
            fill = ConsoleCardColors.toHex(paint.fill());
            text = ConsoleCardColors.toHex(ConsoleCardColors.TEXT);
            dot = ConsoleCardColors.toHex(paint.border());
        }

        mSummaryBadge.setStyle("-fx-background-color: " + fill + "; -fx-border-color: " + outline + ";");
        mSummaryLabel.setStyle("-fx-text-fill: " + text + ";");
        mSummaryDot.setFill(Color.web(dot));
        mSummaryLabel.setText((summary.clearAlert() ? "CLEAR \u26A0 " : "") + summary.text());
        mMoreLabel.setText(summary.getMoreText());
    }

    /**
     * Sets the card views for this group, in display order.
     */
    public void setCardViews(List<ConsoleCardView> cardViews, boolean editMode)
    {
        mEditMode = editMode;
        mCardsBox.getChildren().setAll(cardViews);

        if(cardViews.isEmpty() && !editMode)
        {
            mCardsBox.getChildren().add(mEmptyLabel);
        }

        if(editMode)
        {
            mCardsBox.getChildren().add(mAddCardButton);
        }

        mEditButtons.setVisible(editMode);
        mEditButtons.setManaged(editMode);

        //The active call count is hidden while editing so that the group name has room next to the edit buttons
        mActiveLabel.setVisible(!editMode);
        mActiveLabel.setManaged(!editMode);
    }

    /**
     * Updates the active call count shown in the header.
     */
    public void setActiveCount(int activeCount)
    {
        if(activeCount != mActiveCount)
        {
            mActiveCount = activeCount;
            mActiveLabel.setText(activeCount > 0 ? activeCount + " ACTIVE" : "");
        }
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
}
