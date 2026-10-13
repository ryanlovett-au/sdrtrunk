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
import io.github.dsheirer.console.ClearAudioAlert;
import io.github.dsheirer.console.ConsoleCard;
import io.github.dsheirer.console.ConsoleGroup;
import io.github.dsheirer.console.activity.ConsoleCardActivity;
import io.github.dsheirer.console.activity.RecentTalker;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
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
 * Cards whose talkgroup is treated as normally encrypted (see ConsoleCard.isTreatedAsEncrypted()) show a lock outline
 * next to the talkgroup.  While a call on the card raised a clear audio (encryption slip) alert, the card shows a red
 * CLEAR status pill and a red border.
 *
 * Cards in a muted group are shown as muted (RX MUTED, muted fill) while the card's own mute setting is unchanged: the
 * mute button shows a "muted by group" state (muted icon with a dashed red outline) unless the card is also muted.
 *
 * Hanging cards keep the colour of the call before it ended: a tint of the alias colour, of the encrypted colour after
 * an encrypted call, or of the alert red after a clear audio alert (see ConsoleCardColors.VisualState).
 *
 * Audio settings changes are applied via the console layout manager; the view is updated from layout manager events
 * (setCard()) and from console activity snapshots (setActivity()).  Updates only touch the nodes whose values changed:
 * the card style (inline colours and state style classes, which cause CSS to be reapplied to the card) is only changed
 * when the card's visual state changes, and tooltips are shared (see ConsoleWindow.getTooltip()).  Invoke all methods
 * on the JavaFX thread.
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
    private static final String LOCK_ICON = "M7 11 V8 A5 5 0 0 1 17 8 V11 M5 11 H19 V21 H5 Z";
    private static final String CLEAR_ALERT_STYLE_CLASS = "clear-alert";
    private static final String CLEAR_ALERT_BORDER = ConsoleCardColors.toHex(ConsoleCardColors.CLEAR_ALERT);
    private static final String GROUP_MUTED_STYLE_CLASS = "group-muted";
    private static final Map<String,String> CARD_STYLES = new ConcurrentHashMap<>();

    private final ConsoleWindow mConsoleWindow;
    private ConsoleCard mCard;
    private String mGroupId;
    private AliasInfo mAliasInfo = new AliasInfo(true, "", "", 0);
    private ConsoleCardActivity mActivity;
    private ConsoleCardColors.VisualState mVisualState;
    private boolean mClearAlertShown;
    private String mCardStyle;
    private int mBaseColor;
    private boolean mUpdating;
    private boolean mEditMode;
    private boolean mGroupMuted;

    private final Label mNameLabel = ConsoleFonts.condensed(new Label());
    private final Label mTalkgroupLabel = new Label();
    private Label mEncryptedIndicator;
    private final Label mStatusPill = new Label();
    private final Label mTalkerLabel = new Label();
    private final Label mSiteLabel = new Label();
    private final Label mRecentLabel = new Label();
    private Label mDragHandle;
    private Button mRemoveButton;
    private final ToggleButton mMuteButton = new ToggleButton();
    private final SVGPath mMuteIcon = new SVGPath();
    private final ToggleGroup mPanToggleGroup = new ToggleGroup();
    private final ToggleButton mPanLeft = new ToggleButton("L");
    private final ToggleButton mPanBoth = new ToggleButton("B");
    private final ToggleButton mPanRight = new ToggleButton("R");
    private final Slider mVolumeSlider = new Slider(0, 100, 100);
    private final Label mVolumeLabel = new Label();
    private final HBox mNameRow;
    private final HBox mControlsRow;
    private HBox mUnlinkedRow;

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
        mBaseColor = ConsoleCardColors.getBaseColor(0, card.getId(), consoleWindow.getPalette());
        getStyleClass().add("card");

        //Row 1: drag handle (edit mode), name, talkgroup and remove button (edit mode)
        mNameLabel.getStyleClass().add("card-name");
        mNameLabel.setMinWidth(0);
        mNameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(mNameLabel, Priority.ALWAYS);
        mTalkgroupLabel.getStyleClass().addAll("card-tg", "mono", "sub");
        mTalkgroupLabel.setMinWidth(Region.USE_PREF_SIZE);
        //The edit mode controls, the encrypted indicator and the unlinked card actions are created when first needed
        mNameRow = new HBox(8, mNameLabel, mTalkgroupLabel);
        mNameRow.setAlignment(Pos.CENTER_LEFT);

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
        mPanLeft.setTooltip(consoleWindow.getTooltip("Left speaker"));
        mPanBoth.setTooltip(consoleWindow.getTooltip("Both speakers"));
        mPanRight.setTooltip(consoleWindow.getTooltip("Right speaker"));
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

        getChildren().addAll(mNameRow, statusRow, mRecentLabel, mControlsRow);

        setOnContextMenuRequested(event -> {
            createContextMenu().show(this, event.getScreenX(), event.getScreenY());
            event.consume();
        });

        setupDragAndDrop();
        mCard = null;
        setCard(card);
        setActivity(ConsoleCardActivity.idle(card.getId()));
    }

    /**
     * Creates the edit mode controls: drag handle (first in the name row) and remove button (last in the name row).
     */
    private void createEditControls()
    {
        mDragHandle = new Label("⠇⠇");
        mDragHandle.getStyleClass().add("drag-handle");
        mDragHandle.setMinWidth(Region.USE_PREF_SIZE);
        mDragHandle.setTooltip(mConsoleWindow.getTooltip("Drag to move this card"));
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
        mRemoveButton = new Button("\u2715");
        mRemoveButton.getStyleClass().add("card-edit-button");
        mRemoveButton.setTooltip(mConsoleWindow.getTooltip("Remove from console"));
        mRemoveButton.setAccessibleText("Remove from console");
        mRemoveButton.setMinWidth(Region.USE_PREF_SIZE);
        mRemoveButton.setOnAction(e -> mConsoleWindow.removeCard(mCard.getId()));
        mNameRow.getChildren().addFirst(mDragHandle);
        mNameRow.getChildren().addLast(mRemoveButton);
    }

    /**
     * Creates the encrypted talkgroup indicator (lock outline) before the talkgroup label.
     */
    private void createEncryptedIndicator()
    {
        SVGPath lockIcon = new SVGPath();
        lockIcon.setContent(LOCK_ICON);
        lockIcon.getStyleClass().add("lock-icon");
        lockIcon.setScaleX(0.6);
        lockIcon.setScaleY(0.6);
        mEncryptedIndicator = new Label();
        mEncryptedIndicator.setGraphic(lockIcon);
        mEncryptedIndicator.getStyleClass().add("encrypted-indicator");
        mEncryptedIndicator.setMinWidth(Region.USE_PREF_SIZE);
        mEncryptedIndicator.setTooltip(mConsoleWindow.getTooltip("Normally encrypted talkgroup: clear (unencrypted) " +
            "audio plays an alert tone. Right-click the card to change the clear-audio alert setting."));
        mEncryptedIndicator.setAccessibleText("Normally encrypted talkgroup");
        mNameRow.getChildren().add(mNameRow.getChildren().indexOf(mTalkgroupLabel), mEncryptedIndicator);
    }

    /**
     * Creates the unlinked card actions row: re-link and remove.
     */
    private void createUnlinkedRow()
    {
        Button relinkButton = new Button("Re-link…");
        relinkButton.getStyleClass().add("card-edit-button");
        relinkButton.setOnAction(e -> mConsoleWindow.relinkCard(mCard.getId()));
        Button removeUnlinkedButton = new Button("Remove");
        removeUnlinkedButton.getStyleClass().add("card-edit-button");
        removeUnlinkedButton.setOnAction(e -> mConsoleWindow.removeCard(mCard.getId()));
        mUnlinkedRow = new HBox(6, relinkButton, removeUnlinkedButton);
        mUnlinkedRow.setAlignment(Pos.CENTER_LEFT);
        getChildren().add(mUnlinkedRow);
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
     * Sets the identifier of the group that contains this card (when the card view is reused after a move).
     */
    public void setGroupId(String groupId)
    {
        mGroupId = groupId;
    }

    /**
     * Current base (active) colour for this card, for the console window's palette.
     */
    public int getBaseColor()
    {
        return mBaseColor;
    }

    /**
     * Card (alias) name
     */
    public String getName()
    {
        return mAliasInfo.name();
    }

    /**
     * Indicates if the card's audio is muted: the card is muted or the card's group is muted.
     */
    public boolean isAudioMuted()
    {
        return mCard.isMuted() || mGroupMuted;
    }

    /**
     * Sets the muted state of the card's group.  The card is shown as muted while its group is muted, without changing
     * the card's own mute setting.
     */
    public void setGroupMuted(boolean groupMuted)
    {
        if(groupMuted == mGroupMuted)
        {
            return;
        }

        mGroupMuted = groupMuted;
        updateMuteButton();
        updateActivity(mActivity);
    }

    /**
     * Indicates if the card's alias was found.
     */
    public boolean isLinked()
    {
        return mAliasInfo.linked();
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
        mBaseColor = ConsoleCardColors.getBaseColor(aliasInfo.color(), mCard.getId(), mConsoleWindow.getPalette());
        updateMuteButton();
        boolean linked = aliasInfo.linked();
        mControlsRow.setVisible(linked);
        mControlsRow.setManaged(linked);
        mRecentLabel.setVisible(linked);
        mRecentLabel.setManaged(linked);

        if(!linked && mUnlinkedRow == null)
        {
            createUnlinkedRow();
        }

        if(mUnlinkedRow != null)
        {
            mUnlinkedRow.setVisible(!linked);
            mUnlinkedRow.setManaged(!linked);
        }
        updateActivity(mActivity);
    }

    /**
     * Updates the audio controls from the card snapshot.  Only the controls whose settings changed are updated.
     */
    public void setCard(ConsoleCard card)
    {
        ConsoleCard previous = mCard;
        mCard = card;
        mUpdating = true;

        try
        {
            if(previous == null || previous.isMuted() != card.isMuted())
            {
                mMuteButton.setSelected(card.isMuted());
                updateMuteButton();
            }

            if(previous == null || previous.getPan() != card.getPan())
            {
                ToggleButton pan = switch(card.getPan())
                {
                    case LEFT -> mPanLeft;
                    case RIGHT -> mPanRight;
                    default -> mPanBoth;
                };

                pan.setSelected(true);
            }

            double volume = Math.round(card.getVolume() * 100.0);

            if(!mVolumeSlider.isValueChanging() && Math.round(mVolumeSlider.getValue()) != volume)
            {
                mVolumeSlider.setValue(volume);
            }

            mVolumeLabel.setText(String.valueOf((int)Math.round(mVolumeSlider.getValue())));
            boolean encrypted = card.isTreatedAsEncrypted();

            if(encrypted && mEncryptedIndicator == null)
            {
                createEncryptedIndicator();
            }

            if(mEncryptedIndicator != null)
            {
                mEncryptedIndicator.setVisible(encrypted);
                mEncryptedIndicator.setManaged(encrypted);
            }
        }
        finally
        {
            mUpdating = false;
        }

        //The card's mute setting affects the visual state (RX or RX MUTED)
        if(mActivity != null && previous != null && previous.isMuted() != card.isMuted())
        {
            updateActivity(mActivity);
        }
    }

    /**
     * Updates the mute button icon, style and tooltip for the card's mute state and the group's mute state.
     */
    private void updateMuteButton()
    {
        boolean muted = mMuteButton.isSelected();
        boolean groupMutedOnly = mGroupMuted && !muted;
        mMuteIcon.setContent(muted || mGroupMuted ? MUTED_ICON : UNMUTED_ICON);

        if(groupMutedOnly && !mMuteButton.getStyleClass().contains(GROUP_MUTED_STYLE_CLASS))
        {
            mMuteButton.getStyleClass().add(GROUP_MUTED_STYLE_CLASS);
        }
        else if(!groupMutedOnly)
        {
            mMuteButton.getStyleClass().remove(GROUP_MUTED_STYLE_CLASS);
        }

        String tooltip;

        if(groupMutedOnly)
        {
            tooltip = "Muted by group (click to also mute this card)";
        }
        else if(muted && mGroupMuted)
        {
            tooltip = "Unmute this card (the group is also muted)";
        }
        else
        {
            tooltip = muted ? "Unmute" : "Mute";
        }

        mMuteButton.setTooltip(mConsoleWindow.getTooltip(tooltip));
        mMuteButton.setAccessibleText((muted ? "Unmute " : "Mute ") + mAliasInfo.name() +
            (mGroupMuted ? ", muted by group" : ""));
    }

    /**
     * Shows or hides the layout editing controls.
     */
    public void setEditMode(boolean editMode)
    {
        if(editMode == mEditMode)
        {
            return;
        }

        mEditMode = editMode;

        if(editMode && mDragHandle == null)
        {
            createEditControls();
        }

        if(mDragHandle != null)
        {
            mDragHandle.setVisible(editMode);
            mDragHandle.setManaged(editMode);
            mRemoveButton.setVisible(editMode);
            mRemoveButton.setManaged(editMode);
        }
    }

    /**
     * Updates the view from the card's activity snapshot.  Ignored when the activity has not changed (the activity
     * router reuses the activity instance of unchanged cards, so this is usually a reference comparison).
     */
    public void setActivity(ConsoleCardActivity activity)
    {
        if(activity == mActivity || (Objects.equals(activity, mActivity) && mVisualState != null))
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

        ConsoleCardColors.VisualState state = ConsoleCardColors.VisualState.of(mActivity.state(), mActivity.hangFrom(),
            mActivity.clearAlert(), isAudioMuted(), mAliasInfo.linked());

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

        boolean clearAlert = mActivity.clearAlert() && state.isLive();
        applyVisualState(state, clearAlert);
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
     * Applies the style classes and the (alias colour dependent) fill, border and text colours for the visual state in
     * the console window's palette.  The text colours are set as the looked-up colours that the stylesheet uses for
     * the card's labels ('-card-text' and '-card-sub').  Nothing is changed when the state, alert and colours are
     * unchanged, so that CSS is only reapplied to the card when its appearance changes.
     */
    private void applyVisualState(ConsoleCardColors.VisualState state, boolean clearAlert)
    {
        String style = getCardStyle(mBaseColor, state, clearAlert, mConsoleWindow.getPalette());

        if(state == mVisualState && clearAlert == mClearAlertShown && style.equals(mCardStyle))
        {
            return;
        }

        if(state != mVisualState)
        {
            if(mVisualState != null && !mVisualState.getStyleClass().equals(state.getStyleClass()))
            {
                getStyleClass().remove(mVisualState.getStyleClass());
            }

            if(!getStyleClass().contains(state.getStyleClass()))
            {
                getStyleClass().add(state.getStyleClass());
            }

            setStyleClass("live", state.isLive());
        }

        setStyleClass(CLEAR_ALERT_STYLE_CLASS, clearAlert);
        mVisualState = state;
        mClearAlertShown = clearAlert;

        if(!style.equals(mCardStyle))
        {
            mCardStyle = style;
            setStyle(style);
        }

        mStatusPill.setText(clearAlert ? "CLEAR" : state.getLabel());
        setAccessibleText(mAliasInfo.name() + ", " + (clearAlert ? "clear audio on an encrypted talkgroup" :
            state.getLabel()));
    }

    /**
     * Adds or removes the style class.
     */
    private void setStyleClass(String styleClass, boolean present)
    {
        boolean contains = getStyleClass().contains(styleClass);

        if(present && !contains)
        {
            getStyleClass().add(styleClass);
        }
        else if(!present && contains)
        {
            getStyleClass().remove(styleClass);
        }
    }

    /**
     * Inline card style (fill, border and text colours) for the base colour, state and palette, cached since there are
     * few distinct combinations.
     */
    private static String getCardStyle(int base, ConsoleCardColors.VisualState state, boolean clearAlert,
                                       ConsoleCardColors.Palette palette)
    {
        String key = base + ":" + state.ordinal() + ":" + clearAlert + ":" + palette.ordinal();
        return CARD_STYLES.computeIfAbsent(key, k -> {
            ConsoleCardColors.CardPaint paint = ConsoleCardColors.getPaint(base, state, palette);
            return "-fx-background-color: " + ConsoleCardColors.toHex(paint.fill()) + "; -fx-border-color: " +
                (clearAlert ? CLEAR_ALERT_BORDER : ConsoleCardColors.toHex(paint.border())) + "; -fx-border-style: " +
                (paint.dashed() && !clearAlert ? "segments(6, 4)" : "solid") + "; -card-text: " +
                ConsoleCardColors.toHex(paint.text()) + "; -card-sub: " +
                ConsoleCardColors.toHex(paint.secondaryText()) + ";";
        });
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

        contextMenu.getItems().addAll(moveToMenu, moveUp, moveDown, new SeparatorMenuItem(), createClearAudioAlertMenu(),
            new SeparatorMenuItem(), relink, remove);
        return contextMenu;
    }

    /**
     * Creates the clear audio (encryption slip) alert menu: Auto (learned), Always or Never.
     */
    private Menu createClearAudioAlertMenu()
    {
        Menu menu = new Menu("Clear-audio alert");
        ToggleGroup toggleGroup = new ToggleGroup();

        for(ClearAudioAlert setting: ClearAudioAlert.values())
        {
            String label = switch(setting)
            {
                case AUTO -> "Auto" + (mCard.isEncryptedLearned() ? " (learned: normally encrypted)" :
                    " (learns after " + ConsoleCard.ENCRYPTED_CALLS_TO_LEARN + " encrypted calls)");
                case ALWAYS -> "Always (treat as encrypted)";
                case NEVER -> "Never";
            };

            RadioMenuItem item = new RadioMenuItem(label);
            item.setToggleGroup(toggleGroup);
            item.setSelected(mCard.getClearAudioAlert() == setting);
            item.setOnAction(e -> mConsoleWindow.getLayoutManager().setCardClearAudioAlert(mCard.getId(), setting));
            menu.getItems().add(item);
        }

        return menu;
    }

    /**
     * Drag and drop: in edit mode the drag handle starts a card drag, and dropping a card on this card inserts the
     * dragged card before this card.
     */
    private void setupDragAndDrop()
    {
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
