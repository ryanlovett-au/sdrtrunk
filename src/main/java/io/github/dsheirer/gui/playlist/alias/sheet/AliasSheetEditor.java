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

package io.github.dsheirer.gui.playlist.alias.sheet;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.sheet.AliasSheetColumn;
import io.github.dsheirer.alias.sheet.AliasSheetCsv;
import io.github.dsheirer.alias.sheet.AliasSheetModel;
import io.github.dsheirer.alias.sheet.AliasSheetValues;
import io.github.dsheirer.alias.sheet.DelimitedText;
import io.github.dsheirer.alias.sheet.RadioReferenceImport;
import io.github.dsheirer.alias.sheet.RadioReferenceTable;
import io.github.dsheirer.alias.sheet.SheetRange;
import io.github.dsheirer.alias.sheet.TalkgroupConflict;
import io.github.dsheirer.alias.sheet.TalkgroupConflictDetector;
import io.github.dsheirer.console.ConsoleAliasDrop;
import io.github.dsheirer.console.ConsoleLayoutEvent;
import io.github.dsheirer.console.ConsoleLayoutManager;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.console.AliasDragFormat;
import io.github.dsheirer.gui.playlist.alias.ViewAliasRequest;
import io.github.dsheirer.icon.Icon;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.protocol.Protocol;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.javafx.IconNode;
import org.controlsfx.control.textfield.TextFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Alias sheet editor: a spreadsheet-style editor for bulk alias editing and for binding aliases to console cards.
 *
 * Implementation note: this uses a TableView with cell selection rather than the ControlsFX SpreadsheetView.  The
 * SpreadsheetView skin depends on JavaFX internals (com.sun.javafx.scene.control.behavior.TableViewBehavior) that are
 * not exported by the application launch configuration, its grid is a static copy of the data that would need to be
 * rebuilt on every alias change, sorting and filtering, and its typed cells, clipboard format and editors would all
 * need customization anyway.  The TableView is bound directly to the alias model (filtered and sorted), and the
 * spreadsheet behaviors (clipboard, fill down, undo, validation) are implemented in AliasSheetModel, independent of
 * the control.
 *
 * Keyboard: arrow keys move, Shift+arrows extend the selection, typing starts editing the focused cell, Enter/F2 edits,
 * Enter commits and moves down, Tab commits and moves right, Escape cancels, Delete clears, Ctrl/Cmd+C/X/V copy, cut
 * and paste (tab separated values, compatible with Excel and Google Sheets), Ctrl/Cmd+D fills down, Ctrl/Cmd+Z undo,
 * Ctrl/Cmd+Y or Ctrl/Cmd+Shift+Z redo.
 *
 * Pasting a talkgroup table copied from RadioReference.com (see RadioReferenceTable) opens an import preview
 * (RadioReferencePasteDialog) instead of pasting positionally; the toolbar's Paste from RadioReference action does the
 * same and explains the expected format when the clipboard doesn't contain such a table.
 */
public class AliasSheetEditor extends BorderPane
{
    private static final Logger mLog = LoggerFactory.getLogger(AliasSheetEditor.class);
    public static final String ALL_LISTS = "All lists";
    private static final KeyCombination COPY = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination CUT = new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination PASTE = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination FILL_DOWN = new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination UNDO = new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination REDO = new KeyCodeCombination(KeyCode.Y, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination REDO_SHIFT = new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN,
        KeyCombination.SHIFT_DOWN);
    private static final String HINT = "Type to edit · Copy/paste with Excel or Google Sheets · " +
        "Ctrl/⌘+D fill down · Ctrl/⌘+Z undo";
    private static final int MAXIMUM_CONFLICT_TOOLTIP_LINES = 30;

    private final PlaylistManager mPlaylistManager;
    private final AliasSheetModel mModel;
    private TableView<Alias> mTableView;
    private FilteredList<Alias> mFilteredList;
    private SortedList<Alias> mSortedList;
    private final Map<Alias,Long> mRowSequence = new IdentityHashMap<>();
    private long mNextRowSequence;
    private ComboBox<String> mAliasListComboBox;
    private TextField mSearchField;
    private Button mUndoButton;
    private Button mRedoButton;
    private HBox mConflictBanner;
    private Label mConflictLabel;
    private Label mCountLabel;
    private Label mConsoleCountLabel;
    private Label mConflictCountLabel;
    private Label mStatusLabel;
    private List<Alias> mConsoleDragAliases;
    private TalkgroupConflictDetector.Report mConflictReport = TalkgroupConflictDetector.Report.empty();
    private boolean mRefreshScheduled = false;
    private String mPendingEditText;
    private boolean mKeyboardEdit = false;
    private Path mLayoutPath;

    /**
     * Constructs an instance
     * @param playlistManager for the alias model, console layout, icons and streaming configurations
     */
    public AliasSheetEditor(PlaylistManager playlistManager)
    {
        mPlaylistManager = playlistManager;
        mModel = new AliasSheetModel(playlistManager.getAliasModel(), playlistManager.getConsoleLayoutManager());
        mModel.setIconNameSupplier(() -> mPlaylistManager.getIconModel().iconsProperty().stream()
            .map(Icon::getName).toList());
        mModel.setBroadcastChannelSupplier(() -> mPlaylistManager.getBroadcastModel().getBroadcastConfigurationNames());

        VBox top = new VBox(getToolbar(), getConflictBanner());
        setTop(top);
        setCenter(getTableView());
        setBottom(getFooter());

        getAliasModel().aliasList().addListener((ListChangeListener<Alias>)change -> scheduleRefresh());
        getAliasModel().aliasListNames().addListener((ListChangeListener<String>)change -> {
            if(Platform.isFxApplicationThread())
            {
                updateAliasListChoices();
            }
            else
            {
                Platform.runLater(this::updateAliasListChoices);
            }
        });

        ConsoleLayoutManager consoleLayoutManager = mModel.getConsoleLayoutManager();

        if(consoleLayoutManager != null)
        {
            mLayoutPath = consoleLayoutManager.getLayoutPath();
            consoleLayoutManager.addListener(event -> Platform.runLater(() -> {
                //A layout loaded from a different file means that a different playlist was loaded: the undo history
                //refers to the aliases of the previous playlist
                if(event.getType() == ConsoleLayoutEvent.Type.LAYOUT_LOADED &&
                    !Objects.equals(mLayoutPath, consoleLayoutManager.getLayoutPath()))
                {
                    mLayoutPath = consoleLayoutManager.getLayoutPath();
                    mModel.reset();
                    setStatus("Playlist changed - undo history cleared");
                }

                scheduleRefresh();
            }));
        }

        mModel.getUndoStack().canUndoProperty().addListener((observable, oldValue, newValue) -> updateUndoButtons());
        mModel.getUndoStack().canRedoProperty().addListener((observable, oldValue, newValue) -> updateUndoButtons());

        updateAliasListChoices();
        updateUndoButtons();
        refresh();
    }

    /**
     * Sheet data and editing model
     */
    public AliasSheetModel getModel()
    {
        return mModel;
    }

    private AliasModel getAliasModel()
    {
        return mPlaylistManager.getAliasModel();
    }

    /**
     * Current talkgroup conflicts
     */
    public TalkgroupConflictDetector.Report getConflictReport()
    {
        return mConflictReport;
    }

    /**
     * Toolbar with the alias list selector, search box and actions.
     */
    private HBox getToolbar()
    {
        //Plain text controls with the default (Modena) styling, as in the playlist editor's alias table
        Label listLabel = new Label("Alias List");

        mSearchField = TextFields.createClearableTextField();
        mSearchField.setPromptText("Alias, TG, group");
        mSearchField.setPrefColumnCount(16);
        mSearchField.textProperty().addListener((observable, oldValue, newValue) -> updateFilter());

        Button addRowButton = button("Add Row", "Add a new alias to the selected alias list", this::addRow);
        Button deleteButton = button("Delete Rows", "Delete the aliases in the selected rows",
            this::deleteSelectedRows);
        Button fillDownButton = button("Fill Down", "Copy the top value of the selection down (Ctrl/⌘+D)",
            this::fillDown);
        mUndoButton = button("Undo", null, this::undo);
        mRedoButton = button("Redo", null, this::redo);

        //Less frequently used actions
        MenuItem radioReferenceItem = new MenuItem("Paste from RadioReference…");
        radioReferenceItem.setOnAction(event -> pasteFromRadioReference());
        MenuItem importItem = new MenuItem("Import CSV…");
        importItem.setOnAction(event -> importCsv());
        MenuItem exportItem = new MenuItem("Export CSV…");
        exportItem.setOnAction(event -> exportCsv());
        MenuButton importExportButton = new MenuButton("Import / Export", null, radioReferenceItem,
            new SeparatorMenuItem(), importItem, exportItem);
        importExportButton.setTooltip(new Tooltip("Paste talkgroups copied from a RadioReference.com talkgroup " +
            "table, or import/export the aliases as a CSV file"));

        MenuButton consoleButton = new MenuButton("Add Selected to Console");
        consoleButton.setTooltip(new Tooltip("Assign the aliases in the selected rows to a console group.\nYou can " +
            "also drag a selection onto a group in the console window."));
        consoleButton.setOnShowing(event -> populateConsoleMenu(consoleButton));
        populateConsoleMenu(consoleButton);
        consoleButton.setDisable(mModel.getConsoleLayoutManager() == null);

        Label searchLabel = new Label("Search");
        HBox searchBox = new HBox(5, searchLabel, mSearchField);
        searchBox.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(searchBox, Priority.ALWAYS);

        HBox toolbar = new HBox(5, listLabel, getAliasListComboBox(), addRowButton, deleteButton, fillDownButton,
            mUndoButton, mRedoButton, importExportButton, consoleButton, searchBox);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(10));
        return toolbar;
    }

    private static Button button(String text, String tooltip, Runnable action)
    {
        Button button = new Button(text);
        button.setOnAction(event -> action.run());

        if(tooltip != null)
        {
            button.setTooltip(new Tooltip(tooltip));
        }

        return button;
    }

    /**
     * Alias list selector: All lists plus each alias list.
     */
    private ComboBox<String> getAliasListComboBox()
    {
        if(mAliasListComboBox == null)
        {
            mAliasListComboBox = new ComboBox<>();
            mAliasListComboBox.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> updateFilter());
        }

        return mAliasListComboBox;
    }

    /**
     * Updates the alias list choices from the alias model, retaining the current selection when possible.
     */
    private void updateAliasListChoices()
    {
        String selected = getAliasListComboBox().getSelectionModel().getSelectedItem();
        List<String> choices = new ArrayList<>();
        choices.add(ALL_LISTS);

        List<String> names = new ArrayList<>(getAliasModel().aliasListNames());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        choices.addAll(names);

        if(!choices.equals(getAliasListComboBox().getItems()))
        {
            getAliasListComboBox().getItems().setAll(choices);
        }

        if(selected != null && choices.contains(selected))
        {
            getAliasListComboBox().getSelectionModel().select(selected);
        }
        else
        {
            //Default to the first named alias list
            String first = names.stream().filter(name -> !name.equals(AliasModel.NO_ALIAS_LIST)).findFirst()
                .orElse(ALL_LISTS);
            getAliasListComboBox().getSelectionModel().select(first);
        }
    }

    /**
     * Selected alias list, or null for all lists.
     */
    private String getSelectedAliasList()
    {
        String selected = getAliasListComboBox().getSelectionModel().getSelectedItem();
        return selected == null || selected.equals(ALL_LISTS) ? null : selected;
    }

    /**
     * Alias list for new rows: the selected alias list, or (for all lists) the alias list of the focused row or the
     * first alias list.
     */
    private String getNewRowAliasList()
    {
        String selected = getSelectedAliasList();

        if(selected != null)
        {
            return selected.equals(AliasModel.NO_ALIAS_LIST) ? null : selected;
        }

        int focusedRow = getTableView().getFocusModel().getFocusedCell().getRow();

        if(focusedRow >= 0 && focusedRow < getTableView().getItems().size())
        {
            return getTableView().getItems().get(focusedRow).getAliasListName();
        }

        return getAliasModel().aliasListNames().stream().filter(name -> !name.equals(AliasModel.NO_ALIAS_LIST))
            .findFirst().orElse(null);
    }

    /**
     * Applies the alias list and search filter.
     */
    private void updateFilter()
    {
        String aliasList = getSelectedAliasList();
        String search = mSearchField != null && mSearchField.getText() != null ?
            mSearchField.getText().trim().toLowerCase(Locale.ROOT) : "";

        getFilteredList().setPredicate(alias -> {
            if(aliasList != null && !alias.matchesAliasList(aliasList))
            {
                return false;
            }

            if(search.isEmpty())
            {
                return true;
            }

            for(AliasSheetColumn column: AliasSheetColumn.values())
            {
                if(mModel.getText(alias, column).toLowerCase(Locale.ROOT).contains(search))
                {
                    return true;
                }
            }

            return false;
        });

        scheduleRefresh();
    }

    private FilteredList<Alias> getFilteredList()
    {
        if(mFilteredList == null)
        {
            mFilteredList = new FilteredList<>(getAliasModel().aliasList(), alias -> false);
        }

        return mFilteredList;
    }

    /**
     * Amber banner that summarises talkgroup conflicts.
     */
    private HBox getConflictBanner()
    {
        if(mConflictBanner == null)
        {
            //Same warning icon style as the playlist editor's alias table error column, on a light amber tint
            IconNode warning = new IconNode(FontAwesome.EXCLAMATION_TRIANGLE);
            warning.setIconSize(16);
            warning.setFill(javafx.scene.paint.Color.web("#F0AD4E"));
            mConflictLabel = new Label();
            mConflictLabel.setWrapText(true);
            mConflictBanner = new HBox(8, warning, mConflictLabel);
            mConflictBanner.setAlignment(Pos.CENTER_LEFT);
            mConflictBanner.setPadding(new Insets(6, 10, 6, 10));
            VBox.setMargin(mConflictBanner, new Insets(0, 10, 8, 10));
            mConflictBanner.setStyle("-fx-background-color: rgba(240,173,78,0.18); " +
                "-fx-border-color: rgba(240,173,78,0.75); -fx-border-radius: 3; -fx-background-radius: 3;");
            mConflictBanner.setVisible(false);
            mConflictBanner.setManaged(false);
        }

        return mConflictBanner;
    }

    /**
     * Footer with counts, status and hints.
     */
    private HBox getFooter()
    {
        mCountLabel = new Label();
        mConsoleCountLabel = new Label();
        mConflictCountLabel = new Label();
        IconNode conflictIcon = new IconNode(FontAwesome.EXCLAMATION_TRIANGLE);
        conflictIcon.setFill(javafx.scene.paint.Color.web("#F0AD4E"));
        mConflictCountLabel.setGraphic(conflictIcon);
        mStatusLabel = new Label();
        Label hint = new Label(HINT);
        hint.setDisable(true);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox footer = new HBox(16, mCountLabel, mConsoleCountLabel, mConflictCountLabel, mStatusLabel, spacer, hint);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(6, 10, 8, 10));
        return footer;
    }

    /**
     * Alias sheet table
     */
    private TableView<Alias> getTableView()
    {
        if(mTableView == null)
        {
            mTableView = new TableView<>();
            mTableView.setEditable(true);
            mTableView.getSelectionModel().setCellSelectionEnabled(true);
            mTableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            mTableView.setPlaceholder(new Label("No aliases.  Click Add Row or paste rows from a spreadsheet."));

            for(AliasSheetColumn column: AliasSheetColumn.values())
            {
                mTableView.getColumns().add(createColumn(column));
            }

            //Rows that compare equal under the user's column sort keep the order they were added to the alias model
            //(SortedList is not stable for rows inserted while sorted, e.g. pasted rows that share an alias list)
            for(Alias alias: getAliasModel().aliasList())
            {
                mRowSequence.put(alias, mNextRowSequence++);
            }

            getAliasModel().aliasList().addListener((ListChangeListener<Alias>)change -> {
                while(change.next())
                {
                    for(Alias removed: change.getRemoved())
                    {
                        mRowSequence.remove(removed);
                    }

                    for(Alias added: change.getAddedSubList())
                    {
                        mRowSequence.put(added, mNextRowSequence++);
                    }
                }
            });

            mSortedList = new SortedList<>(getFilteredList());
            mSortedList.comparatorProperty().bind(Bindings.createObjectBinding(() -> {
                Comparator<Alias> comparator = mTableView.getComparator();
                Comparator<Alias> insertionOrder = Comparator.comparingLong(alias ->
                    mRowSequence.getOrDefault(alias, Long.MAX_VALUE));
                return comparator != null ? comparator.thenComparing(insertionOrder) : null;
            }, mTableView.comparatorProperty()));
            mTableView.setItems(mSortedList);

            mTableView.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
            mTableView.addEventHandler(KeyEvent.KEY_TYPED, this::handleKeyTyped);
            mTableView.setContextMenu(createContextMenu());
        }

        return mTableView;
    }

    /**
     * Creates the table column for the sheet column.
     */
    private TableColumn<Alias,String> createColumn(AliasSheetColumn column)
    {
        TableColumn<Alias,String> tableColumn = new TableColumn<>(column.getLabel());
        tableColumn.setUserData(column);
        tableColumn.setReorderable(false);
        tableColumn.setCellValueFactory(param -> new ReadOnlyStringWrapper(param.getValue() != null ?
            mModel.getText(param.getValue(), column) : null));
        tableColumn.setCellFactory(param -> new AliasSheetCell(this, column));

        switch(column)
        {
            //Alias, group, color, icon, listen and record use the playlist editor alias table's column widths
            case ALIAS_LIST:
                tableColumn.setPrefWidth(110);
                break;
            case NAME:
            case GROUP:
                tableColumn.setPrefWidth(140);
                break;
            case PROTOCOL:
                tableColumn.setPrefWidth(90);
                break;
            case TALKGROUP:
                tableColumn.setPrefWidth(150);
                tableColumn.setComparator(AliasSheetEditor::compareNumeric);
                break;
            case STREAM:
                tableColumn.setPrefWidth(140);
                break;
            case CONSOLE_GROUP:
                tableColumn.setPrefWidth(150);
                break;
            default:
                break;
        }

        if(column != AliasSheetColumn.TALKGROUP)
        {
            tableColumn.setComparator(Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
        }

        return tableColumn;
    }

    /**
     * Compares values numerically when both start with a number (e.g. talkgroups), otherwise as text.
     */
    private static int compareNumeric(String a, String b)
    {
        Long numberA = leadingNumber(a);
        Long numberB = leadingNumber(b);

        if(numberA != null && numberB != null && !numberA.equals(numberB))
        {
            return Long.compare(numberA, numberB);
        }
        else if(numberA != null && numberB == null)
        {
            return -1;
        }
        else if(numberA == null && numberB != null)
        {
            return 1;
        }

        return String.CASE_INSENSITIVE_ORDER.compare(a != null ? a : "", b != null ? b : "");
    }

    private static Long leadingNumber(String value)
    {
        if(value == null)
        {
            return null;
        }

        int end = 0;

        while(end < value.length() && end < 18 && Character.isDigit(value.charAt(end)))
        {
            end++;
        }

        return end > 0 ? Long.parseLong(value.substring(0, end)) : null;
    }

    /**
     * Table context menu
     */
    private ContextMenu createContextMenu()
    {
        MenuItem copy = new MenuItem("Copy");
        copy.setAccelerator(COPY);
        copy.setOnAction(event -> copy());
        MenuItem cut = new MenuItem("Cut");
        cut.setAccelerator(CUT);
        cut.setOnAction(event -> cut());
        MenuItem paste = new MenuItem("Paste");
        paste.setAccelerator(PASTE);
        paste.setOnAction(event -> paste());
        MenuItem pasteRadioReference = new MenuItem("Paste from RadioReference…");
        pasteRadioReference.setOnAction(event -> pasteFromRadioReference());
        MenuItem fillDown = new MenuItem("Fill Down");
        fillDown.setAccelerator(FILL_DOWN);
        fillDown.setOnAction(event -> fillDown());
        MenuItem clear = new MenuItem("Clear Cells");
        clear.setOnAction(event -> clearSelection());
        MenuItem addRow = new MenuItem("Add Row");
        addRow.setOnAction(event -> addRow());
        MenuItem delete = new MenuItem("Delete Rows…");
        delete.setOnAction(event -> deleteSelectedRows());
        MenuItem open = new MenuItem("Open in alias editor…");
        open.setOnAction(event -> {
            List<Alias> selected = getSelectedAliases();

            if(!selected.isEmpty())
            {
                openAliasEditor(selected.getFirst());
            }
        });
        Menu console = new Menu("Console Group");
        console.setOnShowing(event -> populateConsoleMenu(console.getItems()));
        populateConsoleMenu(console.getItems());
        console.setDisable(mModel.getConsoleLayoutManager() == null);

        return new ContextMenu(copy, cut, paste, pasteRadioReference, new SeparatorMenuItem(), fillDown, clear, new SeparatorMenuItem(),
            addRow, delete, new SeparatorMenuItem(), console, open);
    }

    private void populateConsoleMenu(MenuButton menuButton)
    {
        populateConsoleMenu(menuButton.getItems());
    }

    /**
     * Populates the console group assignment menu items.
     */
    private void populateConsoleMenu(List<MenuItem> items)
    {
        items.clear();

        for(String group: mModel.getConsoleGroupNames())
        {
            MenuItem item = new MenuItem(group);
            item.setMnemonicParsing(false);
            item.setOnAction(event -> assignConsoleGroup(group));
            items.add(item);
        }

        MenuItem newGroup = new MenuItem("New console group…");
        newGroup.setOnAction(event -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.initOwner(getScene().getWindow());
            dialog.setTitle("New Console Group");
            dialog.setHeaderText("Console group name for the selected aliases");
            Optional<String> result = dialog.showAndWait();
            result.filter(name -> !name.isBlank()).ifPresent(name -> assignConsoleGroup(name.trim()));
        });

        MenuItem remove = new MenuItem("Remove from console");
        remove.setOnAction(event -> assignConsoleGroup(""));

        if(!items.isEmpty())
        {
            items.add(new SeparatorMenuItem());
        }

        items.add(newGroup);
        items.add(remove);
    }

    /**
     * Assigns the aliases in the selected rows to the console group (or removes them from the console).
     */
    private void assignConsoleGroup(String group)
    {
        List<AliasSheetModel.CellEdit> edits = new ArrayList<>();

        for(Alias alias: getSelectedAliases())
        {
            edits.add(new AliasSheetModel.CellEdit(alias, AliasSheetColumn.CONSOLE_GROUP, group));
        }

        if(edits.isEmpty())
        {
            setStatus("Select one or more rows first");
            return;
        }

        int changed = mModel.applyEdits(group.isEmpty() ? "remove from console" : "assign console group", edits);
        setStatus(changed + " alias" + (changed == 1 ? "" : "es") + (group.isEmpty() ? " removed from the console" :
            " assigned to " + group));
    }

    /**
     * Keyboard shortcuts for the table (when not editing a cell).
     */
    private void handleKeyPressed(KeyEvent event)
    {
        if(getTableView().getEditingCell() != null)
        {
            return;
        }

        if(COPY.match(event))
        {
            copy();
        }
        else if(CUT.match(event))
        {
            cut();
        }
        else if(PASTE.match(event))
        {
            paste();
        }
        else if(FILL_DOWN.match(event))
        {
            fillDown();
        }
        else if(REDO_SHIFT.match(event) || REDO.match(event))
        {
            redo();
        }
        else if(UNDO.match(event))
        {
            undo();
        }
        else if(event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE)
        {
            clearSelection();
        }
        else if(event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.F2)
        {
            editFocusedCell(null);
        }
        else if(event.getCode() == KeyCode.TAB)
        {
            moveFocus(0, event.isShiftDown() ? -1 : 1);
        }
        else
        {
            return;
        }

        event.consume();
    }

    /**
     * Type-to-edit: a printable character starts editing the focused cell with that character.
     */
    private void handleKeyTyped(KeyEvent event)
    {
        if(getTableView().getEditingCell() != null || event.isShortcutDown() || event.isControlDown() ||
            event.isAltDown() || event.isMetaDown())
        {
            return;
        }

        String character = event.getCharacter();

        if(character == null || character.isEmpty() || character.charAt(0) < 32 || character.charAt(0) == 127)
        {
            return;
        }

        if(editFocusedCell(character))
        {
            event.consume();
        }
    }

    /**
     * Starts editing the focused cell.
     * @param initialText typed text to replace the cell value with, or null to edit the current value
     * @return true if editing was started
     */
    private boolean editFocusedCell(String initialText)
    {
        TablePosition<Alias,?> focused = getTableView().getFocusModel().getFocusedCell();

        if(focused == null || focused.getRow() < 0 || focused.getTableColumn() == null)
        {
            return false;
        }

        mPendingEditText = initialText;
        mKeyboardEdit = true;
        getTableView().edit(focused.getRow(), focused.getTableColumn());
        mPendingEditText = null;
        mKeyboardEdit = false;
        return true;
    }

    /**
     * Indicates (and clears) that the current edit was started from the keyboard.  Invoked by the cell when editing
     * starts.
     */
    public boolean consumeKeyboardEditRequest()
    {
        boolean keyboard = mKeyboardEdit;
        mKeyboardEdit = false;
        return keyboard;
    }

    /**
     * Returns and clears the typed text that started the current edit, or null.  Invoked by the cell when editing
     * starts.
     */
    public String consumePendingEditText()
    {
        String text = mPendingEditText;
        mPendingEditText = null;
        return text;
    }

    /**
     * Moves the focus (and selection) from the focused cell by the row and column offsets and returns keyboard focus
     * to the table.
     */
    public void moveFocus(int rowDelta, int columnDelta)
    {
        TableView<Alias> table = getTableView();
        TablePosition<Alias,?> focused = table.getFocusModel().getFocusedCell();
        int rowCount = table.getItems().size();
        int columnCount = table.getVisibleLeafColumns().size();

        if(focused != null && focused.getRow() >= 0 && rowCount > 0 && columnCount > 0)
        {
            int column = focused.getTableColumn() != null ? table.getVisibleLeafIndex(focused.getTableColumn()) : 0;
            int row = Math.clamp(focused.getRow() + rowDelta, 0, rowCount - 1);
            column = Math.clamp(column + columnDelta, 0, columnCount - 1);
            TableColumn<Alias,?> tableColumn = table.getVisibleLeafColumn(column);
            table.getSelectionModel().clearAndSelect(row, tableColumn);
            table.getFocusModel().focus(row, tableColumn);
            table.scrollTo(Math.max(0, row - 3));
        }

        restoreTableFocus();
    }

    /**
     * Returns keyboard focus to the table after an editor closes.
     */
    public void restoreTableFocus()
    {
        Platform.runLater(() -> getTableView().requestFocus());
    }

    /**
     * Sheet columns in view order
     */
    private List<AliasSheetColumn> getViewColumns()
    {
        List<AliasSheetColumn> columns = new ArrayList<>();

        for(TableColumn<Alias,?> column: getTableView().getVisibleLeafColumns())
        {
            columns.add((AliasSheetColumn)column.getUserData());
        }

        return columns;
    }

    /**
     * Rows (aliases) in view order
     */
    private List<Alias> getViewRows()
    {
        return new ArrayList<>(getTableView().getItems());
    }

    /**
     * Bounding range of the selected cells, or the focused cell when nothing is selected, or null.
     */
    private SheetRange getSelectedRange()
    {
        TableView<Alias> table = getTableView();
        List<TablePosition> positions = new ArrayList<>(table.getSelectionModel().getSelectedCells());

        if(positions.isEmpty())
        {
            TablePosition<Alias,?> focused = table.getFocusModel().getFocusedCell();

            if(focused != null && focused.getRow() >= 0 && focused.getTableColumn() != null)
            {
                return SheetRange.cell(focused.getRow(), table.getVisibleLeafIndex(focused.getTableColumn()));
            }

            return null;
        }

        int firstRow = Integer.MAX_VALUE;
        int lastRow = -1;
        int firstColumn = Integer.MAX_VALUE;
        int lastColumn = -1;

        for(TablePosition position: positions)
        {
            int column = position.getTableColumn() != null ? table.getVisibleLeafIndex(position.getTableColumn()) :
                position.getColumn();

            if(column < 0)
            {
                //Row selection (no column) - select all columns
                firstColumn = 0;
                lastColumn = Math.max(lastColumn, table.getVisibleLeafColumns().size() - 1);
            }
            else
            {
                firstColumn = Math.min(firstColumn, column);
                lastColumn = Math.max(lastColumn, column);
            }

            firstRow = Math.min(firstRow, position.getRow());
            lastRow = Math.max(lastRow, position.getRow());
        }

        return new SheetRange(firstRow, lastRow, firstColumn, lastColumn);
    }

    /**
     * Aliases for the rows that contain a selected cell, in view order.
     */
    private List<Alias> getSelectedAliases()
    {
        TableView<Alias> table = getTableView();
        Map<Alias,Boolean> selected = new IdentityHashMap<>();
        List<Integer> rows = new ArrayList<>();

        for(TablePosition position: table.getSelectionModel().getSelectedCells())
        {
            rows.add(position.getRow());
        }

        if(rows.isEmpty() && table.getFocusModel().getFocusedIndex() >= 0)
        {
            rows.add(table.getFocusModel().getFocusedIndex());
        }

        rows.sort(Integer::compare);
        List<Alias> aliases = new ArrayList<>();

        for(int row: rows)
        {
            if(row >= 0 && row < table.getItems().size())
            {
                Alias alias = table.getItems().get(row);

                if(selected.put(alias, Boolean.TRUE) == null)
                {
                    aliases.add(alias);
                }
            }
        }

        return aliases;
    }

    /**
     * Selects the range of cells.
     */
    private void selectRange(SheetRange range)
    {
        TableView<Alias> table = getTableView();
        int rowCount = table.getItems().size();
        int columnCount = table.getVisibleLeafColumns().size();

        if(range == null || rowCount == 0 || columnCount == 0)
        {
            return;
        }

        int firstRow = Math.clamp(range.firstRow(), 0, rowCount - 1);
        int lastRow = Math.clamp(range.lastRow(), 0, rowCount - 1);
        TableColumn<Alias,?> firstColumn = table.getVisibleLeafColumn(Math.clamp(range.firstColumn(), 0,
            columnCount - 1));
        TableColumn<Alias,?> lastColumn = table.getVisibleLeafColumn(Math.clamp(range.lastColumn(), 0,
            columnCount - 1));

        table.getSelectionModel().clearSelection();
        table.getSelectionModel().selectRange(firstRow, firstColumn, lastRow, lastColumn);
        table.getFocusModel().focus(firstRow, firstColumn);
    }

    /**
     * Copies the selected cells to the clipboard as tab separated values.
     */
    private void copy()
    {
        SheetRange range = getSelectedRange();

        if(range != null)
        {
            ClipboardContent content = new ClipboardContent();
            content.putString(mModel.copy(getViewRows(), getViewColumns(), range));
            Clipboard.getSystemClipboard().setContent(content);
            setStatus("Copied " + range.rowCount() * range.columnCount() + " cell" +
                (range.rowCount() * range.columnCount() == 1 ? "" : "s"));
        }
    }

    /**
     * Copies the selected cells to the clipboard and clears them.
     */
    private void cut()
    {
        SheetRange range = getSelectedRange();

        if(range != null)
        {
            copy();
            mModel.clear(getViewRows(), getViewColumns(), range);
        }
    }

    /**
     * Pastes tab separated values from the clipboard into the selection.
     */
    private void paste()
    {
        String text = Clipboard.getSystemClipboard().getString();

        if(text == null || text.isEmpty())
        {
            setStatus("The clipboard does not contain text");
            return;
        }

        List<List<String>> data = DelimitedText.parseTsv(text);

        //A talkgroup table copied from RadioReference.com is imported with a preview instead of pasted positionally
        RadioReferenceTable radioReferenceTable = RadioReferenceTable.parse(data);

        if(radioReferenceTable.isRecognised())
        {
            importRadioReference(radioReferenceTable);
            return;
        }

        SheetRange selection = getSelectedRange();
        AliasSheetModel.PasteResult result = mModel.paste(getViewRows(), getViewColumns(), selection, data,
            getNewRowAliasList());

        StringBuilder sb = new StringBuilder();
        sb.append("Pasted ").append(result.changedCells()).append(" cell").append(result.changedCells() == 1 ? "" :
            "s");

        if(result.addedRows() > 0)
        {
            sb.append(", added ").append(result.addedRows()).append(" row").append(result.addedRows() == 1 ? "" : "s");
        }

        if(result.invalidCells() > 0)
        {
            sb.append(", ").append(result.invalidCells()).append(" invalid value")
                .append(result.invalidCells() == 1 ? "" : "s").append(" (red)");
        }

        if(result.skippedCells() > 0)
        {
            sb.append(", ").append(result.skippedCells()).append(" read-only cell")
                .append(result.skippedCells() == 1 ? "" : "s").append(" skipped");
        }

        setStatus(sb.toString());

        SheetRange pasted = result.range();
        Platform.runLater(() -> selectRange(pasted));
    }

    /**
     * Reads a RadioReference.com talkgroup table from the clipboard and imports it with a preview.
     */
    private void pasteFromRadioReference()
    {
        String text = Clipboard.getSystemClipboard().getString();
        RadioReferenceTable table = text != null ? RadioReferenceTable.parse(text) : null;

        if(table == null || !table.isRecognised())
        {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, (text == null || text.isBlank() ?
                "The clipboard does not contain text." : "The clipboard does not contain a RadioReference " +
                "talkgroup table.") + "\n\nOpen the trunked system's page on RadioReference.com, select the " +
                "talkgroup table (from the DEC/HEX/Mode header row down to the last talkgroup - several category " +
                "tables can be selected at once), copy it (Ctrl/⌘+C) and try again.\n\nExpected columns: DEC, HEX, " +
                "Mode, Alpha Tag, Description, Tag.", ButtonType.OK);
            alert.setTitle("Paste from RadioReference");
            alert.setHeaderText("No RadioReference talkgroups found");
            alert.initOwner(getScene().getWindow());
            alert.showAndWait();
            return;
        }

        importRadioReference(table);
    }

    /**
     * Shows the RadioReference import preview for the table and applies the import as a single undoable operation.
     */
    private void importRadioReference(RadioReferenceTable table)
    {
        List<String> listNames = new ArrayList<>(getAliasModel().aliasListNames().stream()
            .filter(name -> name != null && !name.equals(AliasModel.NO_ALIAS_LIST)).toList());
        listNames.sort(String.CASE_INSENSITIVE_ORDER);

        RadioReferencePasteDialog dialog = new RadioReferencePasteDialog(getScene().getWindow(), mModel, table,
            listNames, getNewRowAliasList());
        Optional<RadioReferenceImport.Plan> plan = dialog.showAndWait();

        if(plan.isEmpty())
        {
            return;
        }

        RadioReferenceImport.Result result;

        try
        {
            result = mModel.applyRadioReferenceImport(plan.get());
        }
        catch(Exception e)
        {
            mLog.error("Error applying RadioReference talkgroup import", e);
            showError("Unable to import the RadioReference talkgroups: " + e.getMessage());
            return;
        }

        //Show the alias list that received the aliases
        String selected = getSelectedAliasList();
        String target = plan.get().getAliasList();

        if(selected != null && target != null && !selected.equals(target))
        {
            updateAliasListChoices();
            getAliasListComboBox().getSelectionModel().select(target);
        }

        refresh();
        int conflicts = mConflictReport.getConflictsForList(target).size();
        setStatus("RadioReference: " + result.getSummary() + (conflicts > 0 ? " - " + conflicts + " talkgroup " +
            "conflict" + (conflicts == 1 ? "" : "s") + " (see banner)" : ""));
    }

    /**
     * Fills down the selected range.
     */
    private void fillDown()
    {
        SheetRange range = getSelectedRange();

        if(range != null)
        {
            int changed = mModel.fillDown(getViewRows(), getViewColumns(), range);
            setStatus("Filled " + changed + " cell" + (changed == 1 ? "" : "s"));
        }
    }

    /**
     * Clears the selected cells.
     */
    private void clearSelection()
    {
        SheetRange range = getSelectedRange();

        if(range != null)
        {
            mModel.clear(getViewRows(), getViewColumns(), range);
        }
    }

    /**
     * Commits a single cell edit (undoable).  Invoked by the cells.
     */
    public void commitCell(Alias alias, AliasSheetColumn column, String text)
    {
        try
        {
            mModel.editCell(alias, column, text);
        }
        catch(Exception e)
        {
            mLog.error("Error applying alias sheet edit for column " + column, e);
        }

        String error = mModel.getError(alias, column);

        if(error != null)
        {
            setStatus(error);
        }
    }

    private void undo()
    {
        String description = mModel.getUndoStack().getUndoDescription();

        if(mModel.undo())
        {
            setStatus("Undid " + description);
        }
    }

    private void redo()
    {
        String description = mModel.getUndoStack().getRedoDescription();

        if(mModel.redo())
        {
            setStatus("Redid " + description);
        }
    }

    private void updateUndoButtons()
    {
        mUndoButton.setDisable(!mModel.getUndoStack().canUndoProperty().get());
        mRedoButton.setDisable(!mModel.getUndoStack().canRedoProperty().get());
        String undo = mModel.getUndoStack().getUndoDescription();
        String redo = mModel.getUndoStack().getRedoDescription();
        mUndoButton.setTooltip(new Tooltip(undo != null ? "Undo " + undo + " (Ctrl/⌘+Z)" : "Nothing to undo"));
        mRedoButton.setTooltip(new Tooltip(redo != null ? "Redo " + redo + " (Ctrl/⌘+Y)" : "Nothing to redo"));
    }

    /**
     * Adds a new row (alias) and starts editing its name.
     */
    private void addRow()
    {
        if(!mSearchField.getText().isEmpty())
        {
            mSearchField.clear();
        }

        Alias alias = mModel.addRow(getNewRowAliasList());

        Platform.runLater(() -> {
            int index = getTableView().getItems().indexOf(alias);

            if(index >= 0)
            {
                TableColumn<Alias,?> nameColumn = getTableColumn(AliasSheetColumn.NAME);
                getTableView().scrollTo(Math.max(0, index - 3));
                getTableView().getSelectionModel().clearAndSelect(index, nameColumn);
                getTableView().getFocusModel().focus(index, nameColumn);
                getTableView().requestFocus();
                getTableView().edit(index, (TableColumn<Alias,?>)nameColumn);
            }
        });
    }

    private TableColumn<Alias,?> getTableColumn(AliasSheetColumn column)
    {
        for(TableColumn<Alias,?> tableColumn: getTableView().getVisibleLeafColumns())
        {
            if(tableColumn.getUserData() == column)
            {
                return tableColumn;
            }
        }

        return null;
    }

    /**
     * Deletes the aliases in the selected rows after confirmation.
     */
    private void deleteSelectedRows()
    {
        List<Alias> aliases = getSelectedAliases();

        if(aliases.isEmpty())
        {
            setStatus("Select one or more rows first");
            return;
        }

        long onConsole = aliases.stream().filter(mModel::isOnConsole).count();
        String message = "Delete " + aliases.size() + " alias" + (aliases.size() == 1 ? "" : "es") + "?";

        if(aliases.size() <= 5)
        {
            message += "\n\n" + aliases.stream().map(alias -> alias.getName() != null ? alias.getName() : "(unnamed)")
                .collect(Collectors.joining("\n"));
        }

        if(onConsole > 0)
        {
            message += "\n\n" + onConsole + " of these " + (onConsole == 1 ? "is" : "are") + " on the console - " +
                (onConsole == 1 ? "its card" : "their cards") + " will be removed.";
        }

        message += "\n\nYou can undo this with Undo.";

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.NO, ButtonType.YES);
        alert.setTitle("Delete Aliases");
        alert.setHeaderText("Are you sure?");
        alert.initOwner(getScene().getWindow());
        Optional<ButtonType> result = alert.showAndWait();

        if(result.isPresent() && result.get() == ButtonType.YES)
        {
            int deleted = mModel.deleteRows(aliases);
            setStatus("Deleted " + deleted + " alias" + (deleted == 1 ? "" : "es"));
        }
    }

    /**
     * Opens the alias in the playlist editor's alias editor.
     */
    public void openAliasEditor(Alias alias)
    {
        MyEventBus.getGlobalEventBus().post(new ViewAliasRequest(alias));
    }

    /**
     * Shows the stream (broadcast channel) selection dialog for the alias.
     */
    public void editStreams(Alias alias)
    {
        List<String> configured = new ArrayList<>(mPlaylistManager.getBroadcastModel().getBroadcastConfigurationNames());
        List<String> current = AliasSheetValues.splitStreams(mModel.getText(alias, AliasSheetColumn.STREAM));

        for(String stream: current)
        {
            if(!configured.contains(stream))
            {
                configured.add(stream);
            }
        }

        if(configured.isEmpty())
        {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "There are no streaming configurations.  Add a " +
                "stream in the Playlist Editor's Streaming tab first.", ButtonType.OK);
            alert.setTitle("Streams");
            alert.setHeaderText("No streams configured");
            alert.initOwner(getScene().getWindow());
            alert.showAndWait();
            return;
        }

        configured.sort(String.CASE_INSENSITIVE_ORDER);
        VBox checkBoxes = new VBox(6);
        checkBoxes.setPadding(new Insets(10));
        List<CheckBox> boxes = new ArrayList<>();

        for(String stream: configured)
        {
            CheckBox checkBox = new CheckBox(stream);
            checkBox.setMnemonicParsing(false);
            checkBox.setSelected(current.stream().anyMatch(name -> name.equalsIgnoreCase(stream)));
            boxes.add(checkBox);
            checkBoxes.getChildren().add(checkBox);
        }

        ScrollPane scrollPane = new ScrollPane(checkBoxes);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(Math.min(300, configured.size() * 28 + 20));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Streams");
        dialog.setHeaderText("Stream audio for " + alias.getName() + " to:");
        dialog.getDialogPane().setContent(scrollPane);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

        Optional<ButtonType> result = dialog.showAndWait();

        if(result.isPresent() && result.get() == ButtonType.OK)
        {
            String text = AliasSheetValues.formatStreams(boxes.stream().filter(CheckBox::isSelected)
                .map(CheckBox::getText).toList());
            commitCell(alias, AliasSheetColumn.STREAM, text);
        }

        restoreTableFocus();
    }

    /**
     * Choices for a choice column's editor.
     */
    public List<String> getChoices(AliasSheetColumn column)
    {
        List<String> choices = new ArrayList<>();

        switch(column)
        {
            case ALIAS_LIST:
                getAliasModel().aliasListNames().stream().filter(name -> !name.equals(AliasModel.NO_ALIAS_LIST))
                    .sorted(String.CASE_INSENSITIVE_ORDER).forEach(choices::add);
                break;
            case PROTOCOL:
                for(Protocol protocol: AliasSheetValues.getTalkgroupProtocols())
                {
                    choices.add(AliasSheetValues.formatProtocol(protocol));
                }
                break;
            case ICON:
                choices.add("");
                mPlaylistManager.getIconModel().iconsProperty().stream().map(Icon::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER).forEach(choices::add);
                break;
            case LISTEN:
                choices.addAll(AliasSheetValues.getListenChoices());
                break;
            case CONSOLE_GROUP:
                choices.add(AliasSheetValues.NOT_ON_CONSOLE);
                choices.addAll(mModel.getConsoleGroupNames());
                break;
            default:
                break;
        }

        return choices;
    }

    /**
     * Icon model for the alias icons
     */
    public IconModel getIconModel()
    {
        return mPlaylistManager.getIconModel();
    }

    /**
     * Image for the named icon, or null.
     */
    public Image getIconImage(String iconName)
    {
        if(iconName == null || iconName.isEmpty())
        {
            return null;
        }

        for(Icon icon: mPlaylistManager.getIconModel().iconsProperty())
        {
            if(iconName.equals(icon.getName()))
            {
                return icon.getFxImage();
            }
        }

        return null;
    }

    /**
     * Imports aliases from a CSV file with a preview of the changes.
     */
    private void importCsv()
    {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import Aliases from CSV");
        fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("CSV files", "*.csv"),
            new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = fileChooser.showOpenDialog(getScene().getWindow());

        if(file == null)
        {
            return;
        }

        String csv;

        try
        {
            byte[] bytes = Files.readAllBytes(file.toPath());

            try
            {
                csv = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            }
            catch(CharacterCodingException cce)
            {
                //Not UTF-8 - e.g. a CSV file saved by Excel using the Windows code page
                csv = new String(bytes, StandardCharsets.ISO_8859_1);
            }
        }
        catch(Exception e)
        {
            mLog.error("Error reading alias CSV file [" + file + "]", e);
            showError("Unable to read " + file.getName() + ": " + e.getMessage());
            return;
        }

        AliasSheetImportDialog dialog = new AliasSheetImportDialog(getScene().getWindow(), mModel, csv, file.getName(),
            getNewRowAliasList());
        Optional<AliasSheetCsv.ImportPlan> plan = dialog.showAndWait();

        if(plan.isPresent())
        {
            mModel.applyImport(plan.get());
            setStatus("Imported " + file.getName() + ": " + plan.get().getSummary());
        }
    }

    /**
     * Exports the aliases shown in the sheet (current alias list, search filter and sort order) to a CSV file.
     */
    private void exportCsv()
    {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Aliases to CSV");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        String list = getSelectedAliasList();
        fileChooser.setInitialFileName((list != null ? list.replaceAll("[^A-Za-z0-9 _-]", "") : "All") +
            " aliases.csv");
        File file = fileChooser.showSaveDialog(getScene().getWindow());

        if(file == null)
        {
            return;
        }

        try
        {
            //Byte order mark so that Excel detects UTF-8
            String csv = "﻿" + AliasSheetCsv.export(mModel, getViewRows());
            Files.writeString(file.toPath(), csv, StandardCharsets.UTF_8);
            setStatus("Exported " + getViewRows().size() + " aliases to " + file.getName());
        }
        catch(Exception e)
        {
            mLog.error("Error writing alias CSV file [" + file + "]", e);
            showError("Unable to write " + file.getName() + ": " + e.getMessage());
        }
    }

    private void showError(String message)
    {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle("Alias Sheet");
        alert.initOwner(getScene().getWindow());
        alert.showAndWait();
    }

    private void setStatus(String status)
    {
        mStatusLabel.setText(status);
    }

    /**
     * Indicates if the selected rows can be dragged onto console groups (the console layout is available).
     */
    boolean isConsoleDragEnabled()
    {
        return mModel.getConsoleLayoutManager() != null;
    }

    /**
     * Captures the aliases of the selected rows when the mouse is pressed on an already selected cell, before the
     * table changes the selection, so that a drag that follows carries the whole selection.
     */
    void armConsoleDrag()
    {
        mConsoleDragAliases = getSelectedAliases();
    }

    /**
     * Starts dragging the aliases captured by armConsoleDrag() to the console window, where they can be dropped onto
     * a console group (see ConsoleGroupView).  The drag carries the alias unique identifiers in a custom data format
     * and shows the number of aliases as the drag view.
     * @param source node of the drag gesture
     * @return true if a drag was started.
     */
    boolean startConsoleDrag(Node source)
    {
        List<Alias> aliases = mConsoleDragAliases;
        mConsoleDragAliases = null;

        if(aliases == null || aliases.isEmpty() || !isConsoleDragEnabled())
        {
            return false;
        }

        List<String> uuids = new ArrayList<>();

        for(Alias alias: aliases)
        {
            uuids.add(alias.getUuid());
        }

        String label = ConsoleAliasDrop.getDragLabel(uuids.size());
        Dragboard dragboard = source.startDragAndDrop(TransferMode.COPY);
        ClipboardContent content = new ClipboardContent();
        AliasDragFormat.put(content, uuids);
        dragboard.setContent(content);
        dragboard.setDragView(createDragView(label));
        setStatus("Drop on a console group to add " + label + " to the console");
        return true;
    }

    /**
     * Creates the drag view image: a small badge with the label (e.g. "3 aliases").
     */
    private static Image createDragView(String text)
    {
        Label badge = new Label(text);
        badge.setStyle("-fx-background-color: #14365E; -fx-text-fill: white; -fx-font-weight: bold; " +
            "-fx-padding: 4 10 4 10; -fx-background-radius: 3;");
        new Scene(new Group(badge)).setFill(Color.TRANSPARENT);
        badge.applyCss();
        badge.autosize();
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return badge.snapshot(parameters, null);
    }

    /**
     * Schedules a (coalesced) refresh of validation, counts and the table cells after model changes.
     */
    private void scheduleRefresh()
    {
        if(!mRefreshScheduled)
        {
            mRefreshScheduled = true;
            Platform.runLater(this::refresh);
        }
    }

    /**
     * Refreshes the conflict report, banner, footer counts and table cells.
     */
    private void refresh()
    {
        mRefreshScheduled = false;
        mConflictReport = mModel.findConflicts();

        List<TalkgroupConflict> conflicts = mConflictReport.getConflictsForList(getSelectedAliasList());

        if(conflicts.isEmpty())
        {
            getConflictBanner().setVisible(false);
            getConflictBanner().setManaged(false);
        }
        else
        {
            String text = conflicts.getFirst().getMessage() + ". Only the first match will be used for calls.";

            if(conflicts.size() > 1)
            {
                text += "  (+" + (conflicts.size() - 1) + " more - see the highlighted talkgroups)";
            }

            mConflictLabel.setText(text);
            mConflictLabel.setTooltip(new Tooltip(conflicts.stream().limit(MAXIMUM_CONFLICT_TOOLTIP_LINES)
                .map(TalkgroupConflict::getMessage).collect(Collectors.joining("\n"))));
            getConflictBanner().setVisible(true);
            getConflictBanner().setManaged(true);
        }

        Collection<Alias> rows = getTableView().getItems();
        long onConsole = rows.stream().filter(mModel::isOnConsole).count();
        mCountLabel.setText(rows.size() + " alias" + (rows.size() == 1 ? "" : "es"));
        mConsoleCountLabel.setText(onConsole + " on console");
        mConflictCountLabel.setText(conflicts.isEmpty() ? "" : conflicts.size() + " conflict" +
            (conflicts.size() == 1 ? "" : "s"));
        mConflictCountLabel.setVisible(!conflicts.isEmpty());
        mConflictCountLabel.setManaged(!conflicts.isEmpty());

        updateUndoButtons();

        //Avoid cancelling an in-progress cell edit - the edit commit schedules another refresh
        if(getTableView().getEditingCell() == null)
        {
            getTableView().refresh();
        }
    }
}
