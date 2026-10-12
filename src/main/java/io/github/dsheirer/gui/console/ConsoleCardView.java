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

import io.github.dsheirer.console.AudioPan;
import io.github.dsheirer.console.ConsoleCard;
import io.github.dsheirer.console.ConsoleGroup;
import io.github.dsheirer.console.activity.ConsoleCardActivity;
import io.github.dsheirer.console.activity.RecentTalker;
import java.util.List;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

/**
 * Console card view: alias name and talkgroup, status pill with current talker and site, recent talkers, and the mute,
 * pan (L/B/R) and volume controls.  Unlinked cards (alias not found) show re-link and remove actions instead.
 *
 * Audio settings changes are applied via the console layout manager; the view is updated from layout manager events
 * (setCard()) and from console activity snapshots (setActivity()).  Invoke all methods on the JavaFX thread.
 */
public class ConsoleCardView extends VBox
{
    /**
     * Drag and drop content prefix for console cards.
     */
    public static final String DRAG_PREFIX = "sdrtrunk-console-card:";
    private static final int RECENT_TALKER_DISPLAY_COUNT = 3;
    private static final String MUTED_ICON = "M11 5 L6 9 H3 V15 H6 L11 19 Z M16 9 L22 15 M22 9 L16 15";
    private static final String UNMUTED_ICON = "M11 5 L6 9 H3 V15 H6 L11 19 Z M15.5 8.5 A5 5 0 0 1 15.5 15.5 " +
        "M18.5 5.5 A9 9 0 0 1 18.5 18.5";

    private final ConsoleWindow mConsoleWindow;
    private ConsoleCard mCard;
    private String mGroupId;
    private AliasInfo mAliasInfo = new AliasInfo(true, "", "", 0);
    private ConsoleCardActivity mActivity;
    private ConsoleCardColors.VisualState mVisualState;
    private int mBaseColor;
    private boolean mUpdating;
    private boolean mEditMode;

    private final Label mNameLabel = ConsoleFonts.condensed(new Label());
    private final Label mTalkgroupLabel = new Label();
    private final Label mStatusPill = new Label();
    private final Label mTalkerLabel = new Label();
    private final Label mSiteLabel = new Label();
    private final Label mRecentLabel = new Label();
    private final Label mDragHandle = new Label("⠇⠇");
    private final Button mRemoveButton = new Button("\u2715");
    private final ToggleButton mMuteButton = new ToggleButton();
    private final SVGPath mMuteIcon = new SVGPath();
    private final ToggleGroup mPanToggleGroup = new ToggleGroup();
    private final ToggleButton mPanLeft = new ToggleButton("L");
    private final ToggleButton mPanBoth = new ToggleButton("B");
    private final ToggleButton mPanRight = new ToggleButton("R");
    private final Slider mVolumeSlider = new Slider(0, 100, 100);
    private final Label mVolumeLabel = new Label();
    private final HBox mControlsRow;
    private final HBox mUnlinkedRow;

    /**
     * Constructs an instance
     * @param consoleWindow that owns this view
     * @param card snapshot
     * @param groupId of the group that contains the card
     */
    public ConsoleCardView(ConsoleWindow consoleWindow, ConsoleCard card, String groupId)
    {
        mConsoleWindow = consoleWindow;
        mCard = card;
        mGroupId = groupId;
        mBaseColor = ConsoleCardColors.getBaseColor(0, card.getId());
        getStyleClass().add("card");

        //Row 1: drag handle (edit mode), name, talkgroup and remove button (edit mode)
        mNameLabel.getStyleClass().add("card-name");
        mNameLabel.setMinWidth(0);
        mNameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(mNameLabel, Priority.ALWAYS);
        mTalkgroupLabel.getStyleClass().addAll("card-tg", "mono", "sub");
        mTalkgroupLabel.setMinWidth(Region.USE_PREF_SIZE);
        mDragHandle.getStyleClass().add("drag-handle");
        mDragHandle.setMinWidth(Region.USE_PREF_SIZE);
        mDragHandle.setTooltip(new Tooltip("Drag to move this card"));
        mRemoveButton.getStyleClass().add("card-edit-button");
        mRemoveButton.setTooltip(new Tooltip("Remove from console"));
        mRemoveButton.setAccessibleText("Remove from console");
        mRemoveButton.setMinWidth(Region.USE_PREF_SIZE);
        mRemoveButton.setOnAction(e -> mConsoleWindow.removeCard(mCard.getId()));
        HBox nameRow = new HBox(8, mDragHandle, mNameLabel, mTalkgroupLabel, mRemoveButton);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        //Row 2: status pill, talker and site
        mStatusPill.getStyleClass().add("status-pill");
        mStatusPill.setMinWidth(Region.USE_PREF_SIZE);
        mTalkerLabel.getStyleClass().add("talker");
        mTalkerLabel.setMinWidth(0);
        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        mSiteLabel.getStyleClass().add("sub");
        mSiteLabel.setMinWidth(0);
        HBox statusRow = new HBox(8, mStatusPill, mTalkerLabel, statusSpacer, mSiteLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.setMinHeight(20);

        //Row 3: recent talkers
        mRecentLabel.getStyleClass().addAll("recent", "mono", "sub");
        mRecentLabel.setMinWidth(0);

        //Row 4: mute, pan and volume
        mMuteIcon.getStyleClass().add("mute-icon");
        mMuteIcon.setScaleX(0.85);
        mMuteIcon.setScaleY(0.85);
        mMuteButton.setGraphic(mMuteIcon);
        mMuteButton.getStyleClass().add("mute-button");
        mMuteButton.selectedProperty().addListener((observable, oldValue, muted) -> {
            updateMuteButton();

            if(!mUpdating)
            {
                mConsoleWindow.getLayoutManager().setCardMuted(mCard.getId(), muted);
            }
        });

        mPanLeft.setUserData(AudioPan.LEFT);
        mPanBoth.setUserData(AudioPan.BOTH);
        mPanRight.setUserData(AudioPan.RIGHT);
        mPanLeft.setTooltip(new Tooltip("Left speaker"));
        mPanBoth.setTooltip(new Tooltip("Both speakers"));
        mPanRight.setTooltip(new Tooltip("Right speaker"));
        mPanLeft.setAccessibleText("Left speaker");
        mPanBoth.setAccessibleText("Both speakers");
        mPanRight.setAccessibleText("Right speaker");

        for(ToggleButton button: List.of(mPanLeft, mPanBoth, mPanRight))
        {
            button.getStyleClass().add("pan-button");
            button.setToggleGroup(mPanToggleGroup);
        }

        mPanLeft.getStyleClass().add("first");
        mPanRight.getStyleClass().add("last");
        mPanToggleGroup.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
            if(newValue == null)
            {
                //Always keep one pan setting selected
                if(oldValue != null)
                {
                    oldValue.setSelected(true);
                }
            }
            else if(!mUpdating)
            {
                mConsoleWindow.getLayoutManager().setCardPan(mCard.getId(), (AudioPan)newValue.getUserData());
            }
        });
        HBox panGroup = new HBox(mPanLeft, mPanBoth, mPanRight);
        panGroup.getStyleClass().add("pan-group");
        panGroup.setMinWidth(Region.USE_PREF_SIZE);

        mVolumeSlider.getStyleClass().add("volume-slider");
        mVolumeSlider.setMinWidth(40);
        mVolumeSlider.setAccessibleText("Volume");
        HBox.setHgrow(mVolumeSlider, Priority.ALWAYS);
        mVolumeSlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            mVolumeLabel.setText(String.valueOf((int)Math.round(newValue.doubleValue())));

            if(!mUpdating)
            {
                mConsoleWindow.getLayoutManager().setCardVolume(mCard.getId(), newValue.floatValue() / 100.0f);
            }
        });
        mVolumeLabel.getStyleClass().addAll("volume-value", "mono", "sub");
        mVolumeLabel.setMinWidth(Region.USE_PREF_SIZE);

        mControlsRow = new HBox(6, mMuteButton, panGroup, mVolumeSlider, mVolumeLabel);
        mControlsRow.setAlignment(Pos.CENTER_LEFT);
        mControlsRow.setPadding(new javafx.geometry.Insets(2, 0, 0, 0));

        //Unlinked card actions
        Button relinkButton = new Button("Re-link…");
        relinkButton.getStyleClass().add("card-edit-button");
        relinkButton.setOnAction(e -> mConsoleWindow.relinkCard(mCard.getId()));
        Button removeUnlinkedButton = new Button("Remove");
        removeUnlinkedButton.getStyleClass().add("card-edit-button");
        removeUnlinkedButton.setOnAction(e -> mConsoleWindow.removeCard(mCard.getId()));
        mUnlinkedRow = new HBox(6, relinkButton, removeUnlinkedButton);
        mUnlinkedRow.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(nameRow, statusRow, mRecentLabel, mControlsRow, mUnlinkedRow);

        setOnContextMenuRequested(event -> {
            createContextMenu().show(this, event.getScreenX(), event.getScreenY());
            event.consume();
        });

        setupDragAndDrop();
        setCard(card);
        setEditMode(false);
        setActivity(ConsoleCardActivity.idle(card.getId()));
    }

    /**
     * Card identifier
     */
    public String getCardId()
    {
        return mCard.getId();
    }

    /**
     * Identifier of the group that contains this card
     */
    public String getGroupId()
    {
        return mGroupId;
    }

    /**
     * Current base (active) colour for this card.
     */
    public int getBaseColor()
    {
        return mBaseColor;
    }

    /**
     * Indicates if the card's alias was found.
     */
    public boolean isLinked()
    {
        return mAliasInfo.linked();
    }

    /**
     * Indicates if the current visual state represents a call in progress (active, muted active or encrypted).
     */
    public boolean isInProgress()
    {
        return mVisualState == ConsoleCardColors.VisualState.ACTIVE ||
            mVisualState == ConsoleCardColors.VisualState.MUTED ||
            mVisualState == ConsoleCardColors.VisualState.ENCRYPTED;
    }

    /**
     * Updates the alias-derived display values (name, talkgroup and colour).
     */
    public void setAliasInfo(AliasInfo aliasInfo)
    {
        if(aliasInfo == null || aliasInfo.equals(mAliasInfo))
        {
            return;
        }

        mAliasInfo = aliasInfo;
        mNameLabel.setText(aliasInfo.name());
        mTalkgroupLabel.setText(aliasInfo.identifier());
        mBaseColor = ConsoleCardColors.getBaseColor(aliasInfo.color(), mCard.getId());
        mMuteButton.setAccessibleText("Mute " + aliasInfo.name());
        boolean linked = aliasInfo.linked();
        mControlsRow.setVisible(linked);
        mControlsRow.setManaged(linked);
        mRecentLabel.setVisible(linked);
        mRecentLabel.setManaged(linked);
        mUnlinkedRow.setVisible(!linked);
        mUnlinkedRow.setManaged(!linked);
        mVisualState = null;
        updateActivity(mActivity);
    }

    /**
     * Updates the audio controls from the card snapshot.
     */
    public void setCard(ConsoleCard card)
    {
        mCard = card;
        mUpdating = true;

        try
        {
            mMuteButton.setSelected(card.isMuted());
            updateMuteButton();

            ToggleButton pan = switch(card.getPan())
            {
                case LEFT -> mPanLeft;
                case RIGHT -> mPanRight;
                default -> mPanBoth;
            };

            pan.setSelected(true);

            double volume = Math.round(card.getVolume() * 100.0);

            if(!mVolumeSlider.isValueChanging() && Math.round(mVolumeSlider.getValue()) != volume)
            {
                mVolumeSlider.setValue(volume);
            }

            mVolumeLabel.setText(String.valueOf((int)Math.round(mVolumeSlider.getValue())));
        }
        finally
        {
            mUpdating = false;
        }

        if(mActivity != null)
        {
            mVisualState = null;
            updateActivity(mActivity);
        }
    }

    /**
     * Updates the mute button icon and tooltip for the mute state.
     */
    private void updateMuteButton()
    {
        boolean muted = mMuteButton.isSelected();
        mMuteIcon.setContent(muted ? MUTED_ICON : UNMUTED_ICON);
        mMuteButton.setTooltip(new Tooltip(muted ? "Unmute" : "Mute"));
        mMuteButton.setAccessibleText((muted ? "Unmute " : "Mute ") + mAliasInfo.name());
    }

    /**
     * Shows or hides the layout editing controls.
     */
    public void setEditMode(boolean editMode)
    {
        mEditMode = editMode;
        mDragHandle.setVisible(editMode);
        mDragHandle.setManaged(editMode);
        mRemoveButton.setVisible(editMode);
        mRemoveButton.setManaged(editMode);
    }

    /**
     * Updates the view from the card's activity snapshot.  Ignored when the activity has not changed.
     */
    public void setActivity(ConsoleCardActivity activity)
    {
        if(Objects.equals(activity, mActivity) && mVisualState != null)
        {
            return;
        }

        updateActivity(activity);
    }

    /**
     * Applies the activity to the labels and the card style.
     */
    private void updateActivity(ConsoleCardActivity activity)
    {
        mActivity = activity != null ? activity : ConsoleCardActivity.idle(mCard.getId());

        ConsoleCardColors.VisualState state = ConsoleCardColors.VisualState.of(mActivity.state(), mCard.isMuted(),
            mAliasInfo.linked());

        if(state == ConsoleCardColors.VisualState.UNLINKED)
        {
            mTalkerLabel.setText("Alias not found in this playlist");
            mSiteLabel.setText("");
        }
        else if(state.isLive())
        {
            mTalkerLabel.setText(mActivity.getTalkerLabel());
            mSiteLabel.setText(mActivity.site() != null ? mActivity.site() : "");
        }
        else
        {
            mTalkerLabel.setText("");
            mSiteLabel.setText("");
        }

        mRecentLabel.setText("Recent: " + getRecentText(mActivity.recentTalkers()));

        if(state != mVisualState)
        {
            applyVisualState(state);
        }
    }

    /**
     * Formats the most recent talker radio identifiers.
     */
    private static String getRecentText(List<RecentTalker> recentTalkers)
    {
        if(recentTalkers.isEmpty())
        {
            return "—";
        }

        StringBuilder sb = new StringBuilder();

        for(int x = 0; x < recentTalkers.size() && x < RECENT_TALKER_DISPLAY_COUNT; x++)
        {
            if(x > 0)
            {
                sb.append("  ");
            }

            sb.append(recentTalkers.get(x).radioId());
        }

        return sb.toString();
    }

    /**
     * Applies the style classes and the (alias colour dependent) fill and border for the visual state.
     */
    private void applyVisualState(ConsoleCardColors.VisualState state)
    {
        mVisualState = state;

        for(ConsoleCardColors.VisualState visualState: ConsoleCardColors.VisualState.values())
        {
            getStyleClass().remove(visualState.getStyleClass());
        }

        getStyleClass().remove("live");
        getStyleClass().add(state.getStyleClass());

        if(state.isLive())
        {
            getStyleClass().add("live");
        }

        ConsoleCardColors.CardPaint paint = ConsoleCardColors.getPaint(mBaseColor, state);
        setStyle("-fx-background-color: " + ConsoleCardColors.toHex(paint.fill()) + "; -fx-border-color: " +
            ConsoleCardColors.toHex(paint.border()) + "; -fx-border-style: " +
            (paint.dashed() ? "segments(6, 4)" : "solid") + ";");
        mStatusPill.setText(state.getLabel());
        setAccessibleText(mAliasInfo.name() + ", " + state.getLabel());
    }

    /**
     * Creates the card context menu: move to another group, move up or down, re-link and remove.
     */
    private ContextMenu createContextMenu()
    {
        ContextMenu contextMenu = new ContextMenu();
        Menu moveToMenu = new Menu("Move to group");

        for(ConsoleGroup group: mConsoleWindow.getGroups())
        {
            if(!group.getId().equals(mGroupId))
            {
                MenuItem item = new MenuItem(group.getName());
                item.setOnAction(e -> mConsoleWindow.moveCard(mCard.getId(), group.getId(), group.getCards().size()));
                moveToMenu.getItems().add(item);
            }
        }

        moveToMenu.setDisable(moveToMenu.getItems().isEmpty());

        int index = mConsoleWindow.getCardIndex(mCard.getId());
        int count = mConsoleWindow.getCardCount(mGroupId);
        MenuItem moveUp = new MenuItem("Move up");
        moveUp.setDisable(index <= 0);
        moveUp.setOnAction(e -> mConsoleWindow.moveCard(mCard.getId(), mGroupId, index - 1));
        MenuItem moveDown = new MenuItem("Move down");
        moveDown.setDisable(index < 0 || index >= count - 1);
        moveDown.setOnAction(e -> mConsoleWindow.moveCard(mCard.getId(), mGroupId, index + 2));

        MenuItem relink = new MenuItem(mAliasInfo.linked() ? "Change alias…" : "Re-link…");
        relink.setOnAction(e -> mConsoleWindow.relinkCard(mCard.getId()));
        MenuItem remove = new MenuItem("Remove from console");
        remove.setOnAction(e -> mConsoleWindow.removeCard(mCard.getId()));

        contextMenu.getItems().addAll(moveToMenu, moveUp, moveDown, new SeparatorMenuItem(), relink, remove);
        return contextMenu;
    }

    /**
     * Drag and drop: in edit mode the drag handle starts a card drag, and dropping a card on this card inserts the
     * dragged card before this card.
     */
    private void setupDragAndDrop()
    {
        mDragHandle.setOnDragDetected(event -> {
            if(mEditMode)
            {
                Dragboard dragboard = mDragHandle.startDragAndDrop(TransferMode.MOVE);
                dragboard.setDragView(snapshot(null, null));
                ClipboardContent content = new ClipboardContent();
                content.putString(DRAG_PREFIX + mCard.getId());
                dragboard.setContent(content);
                event.consume();
            }
        });

        setOnDragOver(event -> {
            String cardId = getDraggedCardId(event.getDragboard());

            if(mEditMode && cardId != null)
            {
                //Dropping a card onto itself is not accepted (and not passed on to the group panel)
                if(!cardId.equals(mCard.getId()))
                {
                    event.acceptTransferModes(TransferMode.MOVE);
                    setOpacity(0.6);
                }

                event.consume();
            }
        });

        setOnDragExited(event -> setOpacity(1.0));

        setOnDragDropped(event -> {
            setOpacity(1.0);
            String cardId = getDraggedCardId(event.getDragboard());

            if(cardId != null && !cardId.equals(mCard.getId()))
            {
                mConsoleWindow.moveCard(cardId, mGroupId, mConsoleWindow.getCardIndex(mCard.getId()));
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
     * Extracts the dragged console card identifier from the dragboard.
     * @return card identifier or null if the dragboard does not contain a console card.
     */
    public static String getDraggedCardId(Dragboard dragboard)
    {
        if(dragboard != null && dragboard.hasString())
        {
            String content = dragboard.getString();

            if(content != null && content.startsWith(DRAG_PREFIX))
            {
                return content.substring(DRAG_PREFIX.length());
            }
        }

        return null;
    }

    /**
     * Alias-derived display values for a card.
     * @param linked true when the card's alias exists in the alias model
     * @param name alias name (or the signature alias name for an unlinked card)
     * @param identifier talkgroup (or radio) identifier label
     * @param color alias colour (RGB/ARGB), zero for none
     */
    public record AliasInfo(boolean linked, String name, String identifier, int color)
    {
    }
}
