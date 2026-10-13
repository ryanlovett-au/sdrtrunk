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

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.console.AliasSignature;
import io.github.dsheirer.console.ConsoleCard;
import io.github.dsheirer.console.ConsoleCardResolver;
import io.github.dsheirer.console.ConsoleGroup;
import io.github.dsheirer.console.ConsoleLayout;
import io.github.dsheirer.console.ConsoleLayoutEvent;
import io.github.dsheirer.console.ConsoleLayoutManager;
import io.github.dsheirer.console.activity.ConsoleActivityRouter;
import io.github.dsheirer.console.activity.ConsoleActivitySnapshot;
import io.github.dsheirer.console.ConsoleAliasDrop;
import io.github.dsheirer.console.control.ControlChannelMonitor;
import io.github.dsheirer.console.control.NetworkSiteDetailProvider;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.ApplicationWindowRegistry;
import io.github.dsheirer.gui.DirectoryOpener;
import io.github.dsheirer.gui.icon.ViewIconManagerRequest;
import io.github.dsheirer.gui.playlist.ViewPlaylistRequest;
import io.github.dsheirer.gui.playlist.alias.sheet.ViewAliasSheetRequest;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.gui.viewer.ViewRecordingViewerRequest;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import java.awt.EventQueue;
import java.io.File;
import java.net.URL;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Radio console window: a menu bar, a header (title, edit layout, console mode and clock), talkgroup
 * cards arranged in groups, a control channel strip, and an activity log.
 *
 * Menu bar: File (traditional view, exit), View (the application's other windows and data folders, as in the main
 * window's View menu) and Console (layout editing and console settings).  The menu bar is part of the window (not the
 * macOS system menu bar), like the main window's Swing menu bar.
 *
 * The window is a separate JavaFX stage that registers with the application window registry as a keep-alive window,
 * so closing the main window hides it (instead of exiting) while the console is showing, and closing the console
 * exits the application when the main window is hidden.
 *
 * Updates: card activity is pulled from the ConsoleActivityRouter as an immutable snapshot by a 10 Hz timeline while
 * the window is showing (call events are never dispatched to the JavaFX thread individually).  Console layout changes
 * are received from the ConsoleLayoutManager: card setting changes update the affected card and all other changes
 * (including LAYOUT_LOADED on playlist switch) rebuild the groups and cards.  Layout edits are applied via the
 * ConsoleLayoutManager.
 *
 * Invoke all methods on the JavaFX application thread, except dispose() and isKeepAliveWindowShowing().
 */
public class ConsoleWindow implements ApplicationWindowRegistry.IKeepAliveWindow
{
    private final static Logger mLog = LoggerFactory.getLogger(ConsoleWindow.class);
    public static final String STAGE_MONITOR_KEY = "console";
    private static final String TITLE = "sdrtrunk - Radio Console";
    private static final String STYLESHEET = "console.css";
    private static final Duration REFRESH_INTERVAL = Duration.millis(100);
    private static final double MINIMUM_COLUMN_WIDTH = 300;
    private static final double COLUMN_GAP = 12;
    private static final long[] HANG_TIME_OPTIONS_MS = {0, 1000, 2000, 3000, 5000, 10000};
    private static final DateTimeFormatter CLOCK_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ConsoleLayoutManager mLayoutManager;
    private final ConsoleCardResolver mConsoleCardResolver;
    private final AliasModel mAliasModel;
    private final ApplicationWindowRegistry mApplicationWindowRegistry;
    private final ConsoleActivityRouter mActivityRouter;
    private final ControlChannelMonitor mControlChannelMonitor;
    private final UserPreferences mUserPreferences;
    private final Listener<ConsoleLayoutEvent> mLayoutListener = this::layoutChanged;
    private final ListChangeListener<Alias> mAliasListener = change -> mAliasesChanged = true;
    private final AtomicBoolean mRebuildPending = new AtomicBoolean();
    private final AtomicBoolean mSettingsPending = new AtomicBoolean();
    private final Map<String,ConsoleGroupView> mGroupViews = new LinkedHashMap<>();
    private final Map<String,ConsoleCardView> mCardViews = new LinkedHashMap<>();
    private volatile boolean mShowing;
    private String mStylesheet;
    private Stage mStage;
    private Timeline mRefreshTimeline;
    private ConsoleLayout mLayout = new ConsoleLayout();
    private boolean mEditMode;
    private volatile boolean mAliasesChanged;
    private boolean mUpdatingSettings;
    private long mLastSecond = -1;

    private Label mClockLabel;
    private ToggleButton mEditButton;
    private ToggleButton mConsoleModeSwitch;
    private CheckMenuItem mEditMenuItem;
    private CheckMenuItem mConsoleModeMenuItem;
    private CheckMenuItem mFilterMenuItem;
    private CheckMenuItem mShowAllTrafficMenuItem;
    private CheckMenuItem mClearAudioAlertMenuItem;
    private final ToggleGroup mHangTimeToggleGroup = new ToggleGroup();
    private ControlChannelStrip mControlChannelStrip;
    private VBox mHintBanner;
    private Label mHintTitle;
    private Label mHintText;
    private Button mHintAddGroupButton;
    private Button mHintFollowAllButton;
    private Button mHintConsoleModeButton;
    private ConsoleColumnPane mGroupsPane;
    private ActivityLogView mActivityLogView;

    /**
     * Constructs an instance.  The stage is created when the window is first shown.
     * @param playlistManager for the console layout, alias model and channels
     * @param userPreferences for window size and position
     * @param applicationWindowRegistry for the keep-alive window lifecycle and to show the main window
     * @param activityRouter providing the console card activity
     */
    public ConsoleWindow(PlaylistManager playlistManager, UserPreferences userPreferences,
                         ApplicationWindowRegistry applicationWindowRegistry, ConsoleActivityRouter activityRouter)
    {
        mLayoutManager = playlistManager.getConsoleLayoutManager();
        mConsoleCardResolver = playlistManager.getConsoleCardResolver();
        mAliasModel = playlistManager.getAliasModel();
        mUserPreferences = userPreferences;
        mApplicationWindowRegistry = applicationWindowRegistry;
        mActivityRouter = activityRouter;
        mControlChannelMonitor = new ControlChannelMonitor(playlistManager.getChannelModel(),
            playlistManager.getChannelProcessingManager());
        mControlChannelMonitor.setDetailProvider(
            new NetworkSiteDetailProvider(playlistManager.getChannelProcessingManager()));
        mActivityRouter.setDecodedSiteResolver(mControlChannelMonitor);
    }

    /**
     * Control channel monitor, for registering a control channel detail provider.
     */
    public ControlChannelMonitor getControlChannelMonitor()
    {
        return mControlChannelMonitor;
    }

    @Override
    public boolean isKeepAliveWindowShowing()
    {
        return mShowing;
    }

    /**
     * Shows the console window (creating it on first use), restoring it if minimized and bringing it to the front.
     */
    public void show()
    {
        if(mStage == null)
        {
            createStage();
        }

        mStage.setIconified(false);
        mStage.show();
        mStage.toFront();
        mStage.requestFocus();
    }

    /**
     * Releases resources: deregisters from the window registry and the layout manager and stops the refresh timer.
     * May be invoked on any thread.
     */
    public void dispose()
    {
        mShowing = false;
        mApplicationWindowRegistry.unregisterKeepAliveWindow(this);
        mLayoutManager.removeListener(mLayoutListener);

        Platform.runLater(() -> {
            mAliasModel.aliasList().removeListener(mAliasListener);

            if(mRefreshTimeline != null)
            {
                mRefreshTimeline.stop();
            }
        });
    }

    /**
     * Console layout manager
     */
    ConsoleLayoutManager getLayoutManager()
    {
        return mLayoutManager;
    }

    /**
     * Indicates if layout editing is enabled.
     */
    boolean isEditMode()
    {
        return mEditMode;
    }

    /**
     * Groups of the current layout snapshot, in display order.
     */
    List<ConsoleGroup> getGroups()
    {
        return mLayout.getGroups();
    }

    /**
     * Creates the stage, scene and window content.
     */
    private void createStage()
    {
        URL stylesheet = ConsoleWindow.class.getResource(STYLESHEET);
        mStylesheet = stylesheet != null ? stylesheet.toExternalForm() : null;

        //The groups area takes the vertical slack (and scrolls) when the groups are taller than the window, otherwise
        //it is only as tall as the groups so that the activity log follows directly below the groups
        ScrollPane groupsScrollPane = createCenter();
        VBox.setVgrow(groupsScrollPane, Priority.ALWAYS);
        mActivityLogView = new ActivityLogView(this::getInkColor, showAll -> {
            if(!mUpdatingSettings)
            {
                mLayoutManager.setActivityLogShowAll(showAll);
            }
        });
        MenuBar menuBar = createMenuBar();
        VBox root = new VBox(menuBar, createHeader(), createControlChannelStrip(), groupsScrollPane,
            mActivityLogView);
        root.getStyleClass().add("console-root");

        Scene scene = new Scene(root, 1280, 860);

        if(mStylesheet != null)
        {
            scene.getStylesheets().add(mStylesheet);
        }
        else
        {
            mLog.warn("Console stylesheet [" + STYLESHEET + "] not found");
        }

        String monospaceStylesheet = ConsoleFonts.getMonospaceStylesheet();

        if(monospaceStylesheet != null)
        {
            scene.getStylesheets().add(monospaceStylesheet);
        }

        mStage = new Stage();
        mStage.setTitle(TITLE);
        mStage.setScene(scene);
        mStage.setMinWidth(420);
        mStage.setMinHeight(360);
        mUserPreferences.getJavaFxPreferences().monitor(mStage, STAGE_MONITOR_KEY);

        mRefreshTimeline = new Timeline(new KeyFrame(REFRESH_INTERVAL, event -> refresh()));
        mRefreshTimeline.setCycleCount(Animation.INDEFINITE);

        mStage.setOnShown(event -> {
            mShowing = true;
            mRefreshTimeline.play();
        });
        mStage.setOnHidden(event -> {
            mShowing = false;
            mRefreshTimeline.stop();
        });
        mStage.setOnCloseRequest(event -> {
            mShowing = false;
            EventQueue.invokeLater(() -> mApplicationWindowRegistry.keepAliveWindowClosed(this));
        });

        mApplicationWindowRegistry.registerKeepAliveWindow(this);
        mLayoutManager.addListener(mLayoutListener);
        mAliasModel.aliasList().addListener(mAliasListener);
        rebuild();
    }

    /**
     * Creates the menu bar: File, View and Console menus.
     */
    private MenuBar createMenuBar()
    {
        //File
        MenuItem traditionalItem = new MenuItem("_Traditional view");
        traditionalItem.setOnAction(event -> EventQueue.invokeLater(mApplicationWindowRegistry::showMainWindow));
        MenuItem exitItem = new MenuItem("E_xit");
        exitItem.setOnAction(event -> EventQueue.invokeLater(mApplicationWindowRegistry::exitApplication));
        Menu fileMenu = new Menu("_File", null, traditionalItem, new SeparatorMenuItem(), exitItem);

        //View: the same application windows and folders as the main window's View menu
        Menu viewMenu = new Menu("_View", null,
            requestItem("_Playlist Editor", ViewPlaylistRequest::new),
            requestItem("_Alias Sheet", ViewAliasSheetRequest::new),
            requestItem("_User Preferences", ViewUserPreferenceEditorRequest::new),
            requestItem("_Icon Manager", ViewIconManagerRequest::new),
            requestItem("_Message Recording Viewer (.bits)", ViewRecordingViewerRequest::new),
            new SeparatorMenuItem(),
            directoryItem("Application _Log Files", () -> mUserPreferences.getDirectoryPreference()
                .getDirectoryApplicationLog().toFile()),
            directoryItem("Audio _Recordings", () -> mUserPreferences.getDirectoryPreference()
                .getDirectoryRecording().toFile()),
            directoryItem("Channel _Event Log Files", () -> mUserPreferences.getDirectoryPreference()
                .getDirectoryEventLog().toFile()));

        //Console
        mEditMenuItem = new CheckMenuItem("_Edit layout");
        mEditMenuItem.setAccelerator(new KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN));
        mEditMenuItem.setOnAction(event -> setEditMode(mEditMenuItem.isSelected()));
        MenuItem addGroupItem = new MenuItem("_Add group…");
        addGroupItem.setOnAction(event -> addGroup());

        mConsoleModeMenuItem = new CheckMenuItem("_Console mode");
        mConsoleModeMenuItem.setOnAction(event -> mLayoutManager.setConsoleModeEnabled(
            mConsoleModeMenuItem.isSelected()));
        mFilterMenuItem = new CheckMenuItem("_Follow only console talkgroups");
        mFilterMenuItem.setOnAction(event -> mLayoutManager.setTalkgroupFilterEnabled(mFilterMenuItem.isSelected()));

        Menu hangTimeMenu = new Menu("Activity _hang time");

        for(long hangTime: HANG_TIME_OPTIONS_MS)
        {
            RadioMenuItem item = new RadioMenuItem(hangTime == 0 ? "None" : (hangTime / 1000) + " s");
            item.setUserData(hangTime);
            item.setToggleGroup(mHangTimeToggleGroup);
            item.setOnAction(event -> mLayoutManager.setActivityHangTimeMs(hangTime));
            hangTimeMenu.getItems().add(item);
        }

        mShowAllTrafficMenuItem = new CheckMenuItem("_Show all traffic in activity log");
        mShowAllTrafficMenuItem.setOnAction(event -> mLayoutManager.setActivityLogShowAll(
            mShowAllTrafficMenuItem.isSelected()));
        mClearAudioAlertMenuItem = new CheckMenuItem("Alert on clear audio from encrypted _talkgroups");
        mClearAudioAlertMenuItem.setOnAction(event -> mLayoutManager.setClearAudioAlertEnabled(
            mClearAudioAlertMenuItem.isSelected()));

        Menu consoleMenu = new Menu("_Console", null, mEditMenuItem, addGroupItem, new SeparatorMenuItem(),
            mConsoleModeMenuItem, mFilterMenuItem, hangTimeMenu, mShowAllTrafficMenuItem, mClearAudioAlertMenuItem);

        MenuBar menuBar = new MenuBar(fileMenu, viewMenu, consoleMenu);
        menuBar.getStyleClass().add("console-menu-bar");
        menuBar.setUseSystemMenuBar(false);
        return menuBar;
    }

    /**
     * Creates a menu item that posts a window request on the global event bus.
     */
    private static MenuItem requestItem(String text, Supplier<Object> request)
    {
        MenuItem item = new MenuItem(text);
        item.setOnAction(event -> MyEventBus.getGlobalEventBus().post(request.get()));
        return item;
    }

    /**
     * Creates a menu item that opens a directory in the platform's file explorer (on the Swing thread, as the main
     * window does) and shows an error dialog with the directory location when the file explorer can't be launched.
     */
    private MenuItem directoryItem(String text, Supplier<File> directory)
    {
        MenuItem item = new MenuItem(text);
        item.setOnAction(event -> {
            File folder = directory.get();
            EventQueue.invokeLater(() -> {
                if(!DirectoryOpener.open(folder))
                {
                    Platform.runLater(() -> showError(DirectoryOpener.ERROR_TITLE,
                        DirectoryOpener.getErrorMessage(folder)));
                }
            });
        });
        return item;
    }

    /**
     * Creates the header bar: title, edit layout, console mode switch and clock.
     */
    private HBox createHeader()
    {
        Label title = ConsoleFonts.condensed(new Label("SDRTRUNK CONSOLE"));
        title.getStyleClass().add("console-title");
        title.setMinWidth(Region.USE_PREF_SIZE);

        mEditButton = new ToggleButton("Edit layout");
        mEditButton.getStyleClass().add("header-button");
        mEditButton.setTooltip(new Tooltip("Add, rename, reorder and remove groups and talkgroup cards (" +
            new KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN).getDisplayText() + ")"));
        mEditButton.selectedProperty().addListener((observable, oldValue, selected) -> setEditMode(selected));

        StackPane track = new StackPane();
        track.getStyleClass().add("switch-track");
        Region thumb = new Region();
        thumb.getStyleClass().add("switch-thumb");
        track.getChildren().add(thumb);
        mConsoleModeSwitch = new ToggleButton("Console mode");
        mConsoleModeSwitch.setGraphic(track);
        mConsoleModeSwitch.getStyleClass().addAll("header-button", "mode-switch");
        mConsoleModeSwitch.setTooltip(new Tooltip("When on, the console mixer plays audio using each card's volume, " +
            "mute and speaker settings (instead of the traditional audio playback)"));
        mConsoleModeSwitch.selectedProperty().addListener((observable, oldValue, selected) -> {
            if(!mUpdatingSettings)
            {
                mLayoutManager.setConsoleModeEnabled(selected);
            }
        });

        mClockLabel = new Label();
        mClockLabel.getStyleClass().addAll("console-clock", "mono");
        mClockLabel.setMinWidth(Region.USE_PREF_SIZE);

        for(Region control: List.of(mEditButton, mConsoleModeSwitch))
        {
            control.setMinWidth(Region.USE_PREF_SIZE);
        }

        //Controls wrap onto additional rows when the window is narrow
        FlowPane controls = new FlowPane(12, 8, mEditButton, mConsoleModeSwitch, mClockLabel);
        controls.setAlignment(Pos.CENTER_RIGHT);
        controls.setMinWidth(0);
        controls.setPrefWrapLength(900);
        HBox.setHgrow(controls, Priority.ALWAYS);

        HBox header = new HBox(title, controls);
        header.getStyleClass().add("console-header");
        updateClock();
        return header;
    }

    /**
     * Creates the control channel strip.
     */
    private ControlChannelStrip createControlChannelStrip()
    {
        mControlChannelStrip = new ControlChannelStrip();
        return mControlChannelStrip;
    }

    /**
     * Creates the scrollable center content: hint banner and group columns.
     */
    private ScrollPane createCenter()
    {
        mHintTitle = new Label();
        mHintTitle.getStyleClass().add("hint-title");
        mHintTitle.setWrapText(true);
        mHintText = new Label();
        mHintText.setWrapText(true);
        mHintAddGroupButton = new Button("Add a group");
        mHintAddGroupButton.getStyleClass().add("hint-button");
        mHintAddGroupButton.setOnAction(event -> addGroup());
        mHintFollowAllButton = new Button("Follow all talkgroups");
        mHintFollowAllButton.getStyleClass().add("hint-button");
        mHintFollowAllButton.setOnAction(event -> mLayoutManager.setTalkgroupFilterEnabled(false));
        mHintConsoleModeButton = new Button("Turn on Console mode");
        mHintConsoleModeButton.getStyleClass().add("hint-button");
        mHintConsoleModeButton.setOnAction(event -> mLayoutManager.setConsoleModeEnabled(true));
        HBox hintButtons = new HBox(8, mHintAddGroupButton, mHintFollowAllButton, mHintConsoleModeButton);
        hintButtons.setAlignment(Pos.CENTER_LEFT);
        mHintBanner = new VBox(6, mHintTitle, mHintText, hintButtons);
        mHintBanner.getStyleClass().add("hint-banner");

        mGroupsPane = new ConsoleColumnPane(MINIMUM_COLUMN_WIDTH, COLUMN_GAP);

        VBox content = new VBox(mHintBanner, mGroupsPane);
        content.getStyleClass().add("groups-content");

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.getStyleClass().add("groups-scroll");
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setMinHeight(120);
        scrollPane.maxHeightProperty().bind(Bindings.createDoubleBinding(() -> content.getHeight() +
            scrollPane.snappedTopInset() + scrollPane.snappedBottomInset() + 2, content.heightProperty()));
        return scrollPane;
    }

    /**
     * Processes console layout change events (on any thread).
     */
    private void layoutChanged(ConsoleLayoutEvent event)
    {
        switch(event.getType())
        {
            case CARD_CHANGED ->
            {
                if(event.hasCard())
                {
                    runOnFxThread(() -> cardChanged(event.getCard()));
                }
            }
            case SETTINGS_CHANGED ->
            {
                if(mSettingsPending.compareAndSet(false, true))
                {
                    runOnFxThread(this::updateSettings);
                }
            }
            default ->
            {
                if(mRebuildPending.compareAndSet(false, true))
                {
                    //Always defer the rebuild so that multiple events (e.g. group removal) result in one rebuild
                    Platform.runLater(this::rebuild);
                }
            }
        }
    }

    /**
     * Runs the runnable now when invoked on the JavaFX application thread, otherwise schedules it.
     */
    private static void runOnFxThread(Runnable runnable)
    {
        if(Platform.isFxApplicationThread())
        {
            runnable.run();
        }
        else
        {
            Platform.runLater(runnable);
        }
    }

    /**
     * Updates the card view and the layout snapshot for a card settings change.
     */
    private void cardChanged(ConsoleCard card)
    {
        ConsoleGroup group = mLayout.getGroupForCard(card.getId());

        if(group != null)
        {
            int index = group.getCards().indexOf(group.getCard(card.getId()));
            group.getCards().set(index, card);
        }

        ConsoleCardView view = mCardViews.get(card.getId());

        if(view != null)
        {
            view.setCard(card);
        }
    }

    /**
     * Rebuilds the groups and cards from a snapshot of the console layout.
     */
    private void rebuild()
    {
        mRebuildPending.set(false);

        if(mStage == null)
        {
            return;
        }

        mLayout = mLayoutManager.getLayout();
        mGroupViews.clear();
        mCardViews.clear();
        List<ConsoleGroupView> groupViews = new ArrayList<>();
        int groupCount = mLayout.getGroups().size();

        for(ConsoleGroup group: mLayout.getGroups())
        {
            ConsoleGroupView groupView = new ConsoleGroupView(this, group, groupCount);
            List<ConsoleCardView> cardViews = new ArrayList<>();

            for(ConsoleCard card: group.getCards())
            {
                ConsoleCardView cardView = new ConsoleCardView(this, card, group.getId());
                cardView.setAliasInfo(getAliasInfo(card));
                cardView.setEditMode(mEditMode);
                mCardViews.put(card.getId(), cardView);
                cardViews.add(cardView);
            }

            groupView.setCardViews(cardViews, mEditMode);
            mGroupViews.put(group.getId(), groupView);
            groupViews.add(groupView);
        }

        mGroupsPane.getChildren().setAll(groupViews);
        mAliasesChanged = false;
        updateSettings();
        updateActivity(mActivityRouter.getSnapshot());
        mActivityLogView.refresh();
    }

    /**
     * Updates the header controls, hint banner and edit controls from the console settings.
     */
    private void updateSettings()
    {
        mSettingsPending.set(false);

        if(mStage == null)
        {
            return;
        }

        boolean consoleMode = mLayoutManager.isConsoleModeEnabled();
        boolean filter = mLayoutManager.isTalkgroupFilterEnabled();
        long hangTime = mLayoutManager.getActivityHangTimeMs();
        boolean showAll = mLayoutManager.isActivityLogShowAll();
        boolean clearAudioAlert = mLayoutManager.isClearAudioAlertEnabled();
        mLayout.setConsoleModeEnabled(consoleMode);
        mLayout.setTalkgroupFilterEnabled(filter);
        mLayout.setActivityHangTimeMs(hangTime);
        mLayout.setActivityLogShowAll(showAll);
        mLayout.setClearAudioAlertEnabled(clearAudioAlert);

        mUpdatingSettings = true;

        try
        {
            mConsoleModeSwitch.setSelected(consoleMode);
            mConsoleModeMenuItem.setSelected(consoleMode);
            mFilterMenuItem.setSelected(filter);
            mHangTimeToggleGroup.getToggles().forEach(toggle ->
                toggle.setSelected(toggle.getUserData() instanceof Long value && value == hangTime));
            mShowAllTrafficMenuItem.setSelected(showAll);
            mActivityLogView.setShowAll(showAll);
            mClearAudioAlertMenuItem.setSelected(clearAudioAlert);
        }
        finally
        {
            mUpdatingSettings = false;
        }

        updateHintBanner();
        updateControlChannels();
    }

    /**
     * Shows the hint banner (empty state) when the console has no talkgroup cards.  When console mode and the
     * talkgroup filter are both on, the banner warns that all calls are being ignored.
     */
    private void updateHintBanner()
    {
        boolean noCards = mLayout.getCardCount() == 0;
        boolean noGroups = mLayout.getGroups().isEmpty();
        boolean ignoringAll = noCards && mConsoleCardResolver.isFilterActive();
        boolean consoleModeOff = !noCards && !mLayoutManager.isConsoleModeEnabled();

        mHintBanner.setVisible(noCards || consoleModeOff);
        mHintBanner.setManaged(noCards || consoleModeOff);
        mHintBanner.getStyleClass().remove("warning");
        mHintConsoleModeButton.setVisible(consoleModeOff);
        mHintConsoleModeButton.setManaged(consoleModeOff);

        if(consoleModeOff)
        {
            mHintTitle.setText("Console mode is off");
            mHintText.setText("Audio is played by the traditional audio playback: every talkgroup is heard and the " +
                "cards' volume, mute and speaker settings are not applied" +
                (mLayoutManager.isTalkgroupFilterEnabled() ? ", and talkgroups that are not on the console are " +
                    "still followed." : "."));
            mHintAddGroupButton.setVisible(false);
            mHintAddGroupButton.setManaged(false);
            mHintFollowAllButton.setVisible(false);
            mHintFollowAllButton.setManaged(false);
            return;
        }

        if(!noCards)
        {
            return;
        }

        if(ignoringAll)
        {
            mHintBanner.getStyleClass().add("warning");
            mHintTitle.setText("Console mode is on, but there are no talkgroups on the console");
            mHintText.setText("All calls are being ignored and no audio will play. Add talkgroup cards to the " +
                "console, or follow all talkgroups so that calls are processed while you set up the console.");
        }
        else
        {
            mHintTitle.setText("No talkgroups on the console yet");
            mHintText.setText((noGroups ? "Add a group, then " : "Click Edit layout, then ") + "use '+ Add talkgroup' " +
                "to add a card for each talkgroup alias that you want to follow. Turn on Console mode to play audio " +
                "with each card's volume, mute and speaker settings" +
                (mLayoutManager.isTalkgroupFilterEnabled() ? " and to follow only the talkgroups on the console." : "."));
        }

        mHintAddGroupButton.setVisible(noGroups);
        mHintAddGroupButton.setManaged(noGroups);
        mHintFollowAllButton.setVisible(ignoringAll);
        mHintFollowAllButton.setManaged(ignoringAll);
    }

    /**
     * Enables or disables layout editing.
     */
    private void setEditMode(boolean editMode)
    {
        if(mEditMode == editMode)
        {
            return;
        }

        mEditMode = editMode;

        if(mEditButton.isSelected() != editMode)
        {
            mEditButton.setSelected(editMode);
        }

        if(mEditMenuItem.isSelected() != editMode)
        {
            mEditMenuItem.setSelected(editMode);
        }

        rebuild();
    }

    /**
     * Periodic (10 Hz) refresh while the window is showing: clock, card activity and the activity log, and once per
     * second the control channel strip and alias changes.
     */
    private void refresh()
    {
        try
        {
            updateActivity(mActivityRouter.getSnapshot());

            long second = System.currentTimeMillis() / 1000;

            if(second != mLastSecond)
            {
                mLastSecond = second;
                updateClock();
                updateControlChannels();

                if(mAliasesChanged)
                {
                    refreshAliasInfo();
                }
            }
        }
        catch(Exception e)
        {
            mLog.error("Error refreshing console window", e);
        }
    }

    /**
     * Applies the activity snapshot to the cards, group active counts and the activity log.
     */
    private void updateActivity(ConsoleActivitySnapshot snapshot)
    {
        for(ConsoleCardView cardView: mCardViews.values())
        {
            cardView.setActivity(snapshot.getCard(cardView.getCardId()));
        }

        for(ConsoleGroup group: mLayout.getGroups())
        {
            ConsoleGroupView groupView = mGroupViews.get(group.getId());

            if(groupView != null)
            {
                int active = 0;

                for(ConsoleCard card: group.getCards())
                {
                    ConsoleCardView cardView = mCardViews.get(card.getId());

                    if(cardView != null && cardView.isInProgress())
                    {
                        active++;
                    }
                }

                groupView.setActiveCount(active);
            }
        }

        mActivityLogView.update(snapshot);
    }

    /**
     * Updates the clock label.
     */
    private void updateClock()
    {
        mClockLabel.setText(LocalTime.now().format(CLOCK_FORMATTER));
    }

    /**
     * Updates the control channel strip.
     */
    private void updateControlChannels()
    {
        mControlChannelStrip.update(mControlChannelMonitor.getControlChannels());
    }

    /**
     * Refreshes the alias-derived display values of each card after aliases change (rename, colour, add, remove).
     */
    private void refreshAliasInfo()
    {
        mAliasesChanged = false;

        for(ConsoleGroup group: mLayout.getGroups())
        {
            for(ConsoleCard card: group.getCards())
            {
                ConsoleCardView cardView = mCardViews.get(card.getId());

                if(cardView != null)
                {
                    cardView.setAliasInfo(getAliasInfo(card));
                }
            }
        }

        mActivityLogView.refresh();
    }

    /**
     * Alias-derived display values for the card: from the bound alias, or from the card's alias signature when the
     * alias is not found (unlinked card).
     */
    private ConsoleCardView.AliasInfo getAliasInfo(ConsoleCard card)
    {
        Alias alias = mAliasModel.getAliasByUuid(card.getAliasUuid());

        if(alias != null)
        {
            return new ConsoleCardView.AliasInfo(true, alias.getName() != null ? alias.getName() : "",
                AliasPickerDialog.getIdentifierLabel(alias), alias.getColor());
        }

        AliasSignature signature = card.getSignature();
        String name = signature != null && signature.getAliasName() != null ? signature.getAliasName() :
            "Unknown alias";
        String identifier = signature != null && signature.getTalkgroup() != null ?
            String.valueOf(signature.getTalkgroup()) : "";
        return new ConsoleCardView.AliasInfo(false, name, identifier, 0);
    }

    /**
     * Ink (text) colour for the card, or -1 when the card is not on the console.
     */
    private int getInkColor(String cardId)
    {
        ConsoleCardView cardView = mCardViews.get(cardId);
        return cardView != null ? ConsoleCardColors.getInkColor(cardView.getBaseColor()) : -1;
    }

    /**
     * Index of the card within its group in the current layout snapshot, or -1.
     */
    int getCardIndex(String cardId)
    {
        ConsoleGroup group = mLayout.getGroupForCard(cardId);
        return group != null ? group.getCards().indexOf(group.getCard(cardId)) : -1;
    }

    /**
     * Number of cards in the group in the current layout snapshot.
     */
    int getCardCount(String groupId)
    {
        ConsoleGroup group = mLayout.getGroup(groupId);
        return group != null ? group.getCards().size() : 0;
    }

    /**
     * Moves the card so that it is positioned before the card currently at the target index in the target group
     * (an index at or beyond the end of the group appends the card).
     * @param cardId to move
     * @param targetGroupId destination group
     * @param targetIndex insertion position in the destination group's current card list
     */
    void moveCard(String cardId, String targetGroupId, int targetIndex)
    {
        ConsoleGroup source = mLayout.getGroupForCard(cardId);
        int index = Math.max(0, targetIndex);

        if(source != null && source.getId().equals(targetGroupId))
        {
            int current = getCardIndex(cardId);

            //The manager removes the card before inserting it, which shifts later positions down by one
            if(current >= 0 && current < index)
            {
                index--;
            }

            if(current == index)
            {
                return;
            }
        }

        mLayoutManager.moveCard(cardId, targetGroupId, index);
    }

    /**
     * Adds the dropped aliases (e.g. alias sheet rows) to the group: one card per alias, moving cards that are in
     * another group (see ConsoleAliasDrop).
     * @param groupId of the target group
     * @param aliasUuids in drag order
     * @return outcome of the drop
     */
    ConsoleAliasDrop.Result addAliases(String groupId, List<String> aliasUuids)
    {
        ConsoleAliasDrop.Result result = ConsoleAliasDrop.apply(mLayoutManager, groupId, aliasUuids,
            uuid -> mAliasModel.getAliasByUuid(uuid) != null);
        ConsoleGroup group = mLayout.getGroup(groupId);
        mLog.info("Aliases dropped on console group: " + result.describe(group != null ? group.getName() : groupId));
        return result;
    }

    /**
     * Prompts for a group name and adds a new group, enabling layout editing.
     */
    void addGroup()
    {
        TextInputDialog dialog = new TextInputDialog("Group " + (mLayout.getGroups().size() + 1));
        prepare(dialog);
        dialog.setTitle("Add Group");
        dialog.setHeaderText("Add a console group");
        dialog.setContentText("Group name:");
        Optional<String> name = dialog.showAndWait();

        if(name.isPresent() && !name.get().isBlank())
        {
            setEditMode(true);
            mLayoutManager.addGroup(name.get().trim());
        }
    }

    /**
     * Prompts for a new group name and renames the group.
     */
    void renameGroup(ConsoleGroup group)
    {
        TextInputDialog dialog = new TextInputDialog(group.getName());
        prepare(dialog);
        dialog.setTitle("Rename Group");
        dialog.setHeaderText("Rename console group");
        dialog.setContentText("Group name:");
        dialog.showAndWait().filter(name -> !name.isBlank())
            .ifPresent(name -> mLayoutManager.renameGroup(group.getId(), name.trim()));
    }

    /**
     * Deletes the group, after confirmation when the group has cards.
     */
    void deleteGroup(ConsoleGroup group)
    {
        int cards = group.getCards().size();

        if(cards > 0)
        {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Delete group '" + group.getName() + "' and its " +
                cards + (cards == 1 ? " talkgroup card?" : " talkgroup cards?"), ButtonType.YES, ButtonType.NO);
            prepare(alert);
            alert.setTitle("Delete Group");
            alert.setHeaderText(null);

            if(alert.showAndWait().orElse(ButtonType.NO) != ButtonType.YES)
            {
                return;
            }
        }

        mLayoutManager.removeGroup(group.getId());
    }

    /**
     * Chooses an alias and adds a card for it to the group.
     */
    void addCard(String groupId)
    {
        AliasPickerDialog dialog = new AliasPickerDialog(mStage, mStylesheet, mAliasModel, this::getAssignedGroupName,
            "Add Talkgroup", "Choose the alias for the new console card");
        dialog.showAndWait().ifPresent(alias -> {
            try
            {
                mLayoutManager.addCard(groupId, alias.getUuid());
            }
            catch(Exception e)
            {
                showError("Unable to add the talkgroup card", e.getMessage());
            }
        });
    }

    /**
     * Chooses an alias and re-binds (re-links) the card to it.
     */
    void relinkCard(String cardId)
    {
        ConsoleCard card = mLayout.getCard(cardId);
        String name = card != null && card.getSignature() != null && card.getSignature().getAliasName() != null ?
            " '" + card.getSignature().getAliasName() + "'" : "";
        AliasPickerDialog dialog = new AliasPickerDialog(mStage, mStylesheet, mAliasModel, this::getAssignedGroupName,
            "Re-link Card", "Choose the alias for console card" + name);
        dialog.showAndWait().ifPresent(alias -> {
            try
            {
                mLayoutManager.relinkCard(cardId, alias.getUuid());
            }
            catch(Exception e)
            {
                showError("Unable to re-link the card", e.getMessage());
            }
        });
    }

    /**
     * Removes the card from the console.
     */
    void removeCard(String cardId)
    {
        mLayoutManager.removeCard(cardId);
    }

    /**
     * Name of the console group that contains a card for the alias, or null when the alias is not on the console.
     */
    private String getAssignedGroupName(Alias alias)
    {
        if(alias == null)
        {
            return null;
        }

        for(ConsoleGroup group: mLayout.getGroups())
        {
            for(ConsoleCard card: group.getCards())
            {
                if(alias.getUuid().equals(card.getAliasUuid()))
                {
                    return group.getName();
                }
            }
        }

        return null;
    }

    /**
     * Sets the owner and stylesheet for a dialog.
     */
    private void prepare(javafx.scene.control.Dialog<?> dialog)
    {
        dialog.initOwner(mStage);

        if(mStylesheet != null)
        {
            dialog.getDialogPane().getStylesheets().add(mStylesheet);
        }
    }

    /**
     * Shows an error alert.
     */
    private void showError(String header, String message)
    {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        prepare(alert);
        alert.setTitle("Radio Console");
        alert.setHeaderText(header);
        alert.showAndWait();
    }
}
