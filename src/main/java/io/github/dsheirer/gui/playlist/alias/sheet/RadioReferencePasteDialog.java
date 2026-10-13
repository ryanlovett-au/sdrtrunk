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

import io.github.dsheirer.alias.sheet.AliasSheetModel;
import io.github.dsheirer.alias.sheet.AliasSheetValues;
import io.github.dsheirer.alias.sheet.RadioReferenceImport;
import io.github.dsheirer.alias.sheet.RadioReferenceTable;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

/**
 * Preview dialog for talkgroups pasted from a RadioReference.com talkgroup table: choose the import options and review
 * the planned action for each talkgroup before applying the import.
 */
public class RadioReferencePasteDialog extends Dialog<RadioReferenceImport.Plan>
{
    private final AliasSheetModel mModel;
    private final RadioReferenceTable mTable;
    private final ComboBox<String> mAliasListComboBox = new ComboBox<>();
    private final ComboBox<Protocol> mProtocolComboBox = new ComboBox<>();
    private final ComboBox<RadioReferenceImport.NameSource> mNameComboBox = new ComboBox<>();
    private final ComboBox<RadioReferenceImport.GroupSource> mGroupComboBox = new ComboBox<>();
    private final ComboBox<RadioReferenceImport.ExistingAliasAction> mExistingComboBox = new ComboBox<>();
    private final ComboBox<RadioReferenceImport.EncryptedAction> mEncryptedComboBox = new ComboBox<>();
    private final ComboBox<RadioReferenceImport.ConsoleMode> mConsoleComboBox = new ComboBox<>();
    private final ComboBox<String> mConsoleGroupComboBox = new ComboBox<>();
    private final Label mSummaryLabel = new Label();
    private final Label mDetailLabel = new Label();
    private final TableView<RadioReferenceImport.PlannedRow> mTableView = new TableView<>();
    private final ButtonType mImportButtonType = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
    private RadioReferenceImport.Plan mPlan;

    /**
     * Constructs an instance
     * @param owner window
     * @param model of the alias sheet
     * @param table parsed from the clipboard
     * @param aliasListNames existing alias list names
     * @param defaultAliasList alias list selected by default, or null
     */
    public RadioReferencePasteDialog(Window owner, AliasSheetModel model, RadioReferenceTable table,
                                     List<String> aliasListNames, String defaultAliasList)
    {
        mModel = model;
        mTable = table;

        initOwner(owner);
        setTitle("Paste from RadioReference");
        int count = table.getRows().size();
        setHeaderText(count + " talkgroup" + (count == 1 ? "" : "s") + " found in the pasted RadioReference table" +
            (table.getIgnoredLines().isEmpty() ? "" : " (" + table.getIgnoredLines().size() +
                " unrecognised line" + (table.getIgnoredLines().size() == 1 ? "" : "s") + " ignored)"));
        setResizable(true);

        mAliasListComboBox.setEditable(true);
        mAliasListComboBox.getItems().setAll(aliasListNames);
        mAliasListComboBox.getEditor().setText(defaultAliasList != null ? defaultAliasList :
            aliasListNames.isEmpty() ? "" : aliasListNames.getFirst());
        mAliasListComboBox.setPromptText("Alias list");
        mAliasListComboBox.setTooltip(new Tooltip("Alias list for the aliases.  Type a name to create a new list."));
        mAliasListComboBox.getEditor().textProperty().addListener((observable, oldValue, newValue) -> updatePlan());
        mAliasListComboBox.valueProperty().addListener((observable, oldValue, newValue) -> updatePlan());

        mProtocolComboBox.getItems().setAll(AliasSheetValues.getTalkgroupProtocols());
        mProtocolComboBox.setConverter(converter(AliasSheetValues::formatProtocol));
        mProtocolComboBox.setValue(Protocol.APCO25);

        setup(mNameComboBox, RadioReferenceImport.NameSource.values(), RadioReferenceImport.NameSource.DESCRIPTION,
            "Alias name from the description or alpha tag (the other is used when it is empty)");
        setup(mGroupComboBox, RadioReferenceImport.GroupSource.values(), RadioReferenceImport.GroupSource.TAG,
            "Alias group from the tag (e.g. Law Dispatch), the category (sub-heading or category column) or none");
        setup(mExistingComboBox, RadioReferenceImport.ExistingAliasAction.values(),
            RadioReferenceImport.ExistingAliasAction.SKIP,
            "Aliases in the alias list that already have the talkgroup (same protocol) are skipped or have their " +
                "name and group updated");
        setup(mEncryptedComboBox, RadioReferenceImport.EncryptedAction.values(),
            RadioReferenceImport.EncryptedAction.IMPORT,
            "Encrypted talkgroups (mode E or TE): import, import with the listen setting 'Do not monitor', or skip");
        setup(mConsoleComboBox, RadioReferenceImport.ConsoleMode.values(), RadioReferenceImport.ConsoleMode.NONE,
            "Add the aliases to the console.  Aliases that are already on the console stay in their group.  " +
                "Aliases without a tag go to the '" + RadioReferenceImport.UNTAGGED_CONSOLE_GROUP + "' group.");

        List<String> consoleGroups = model.getConsoleGroupNames();
        mConsoleGroupComboBox.setEditable(true);
        mConsoleGroupComboBox.getItems().setAll(consoleGroups);
        mConsoleGroupComboBox.getEditor().setText(consoleGroups.isEmpty() ? "RadioReference" : consoleGroups.getFirst());
        mConsoleGroupComboBox.setPromptText("Console group");
        mConsoleGroupComboBox.setTooltip(new Tooltip("Existing console group, or type a name for a new group"));
        mConsoleGroupComboBox.getEditor().textProperty().addListener((observable, oldValue, newValue) -> updatePlan());
        mConsoleGroupComboBox.valueProperty().addListener((observable, oldValue, newValue) -> updatePlan());

        if(model.getConsoleLayoutManager() == null)
        {
            mConsoleComboBox.setDisable(true);
        }

        mProtocolComboBox.valueProperty().addListener((observable, oldValue, newValue) -> updatePlan());
        mConsoleComboBox.valueProperty().addListener((observable, oldValue, newValue) -> updatePlan());

        GridPane options = new GridPane();
        options.setHgap(8);
        options.setVgap(8);
        addOption(options, 0, 0, "Alias list", mAliasListComboBox);
        addOption(options, 0, 2, "Protocol", mProtocolComboBox);
        addOption(options, 1, 0, "Name from", mNameComboBox);
        addOption(options, 1, 2, "Alias group from", mGroupComboBox);
        addOption(options, 2, 0, "Existing aliases", mExistingComboBox);
        addOption(options, 2, 2, "Encrypted (E, TE)", mEncryptedComboBox);
        HBox consoleBox = new HBox(8, mConsoleComboBox, mConsoleGroupComboBox);
        addOption(options, 3, 0, "Add to console", consoleBox);
        GridPane.setColumnSpan(consoleBox, 3);

        mSummaryLabel.setStyle("-fx-font-weight: bold;");
        mDetailLabel.setWrapText(true);

        createTable();
        VBox.setVgrow(mTableView, Priority.ALWAYS);

        VBox content = new VBox(10, options, new HBox(16, mSummaryLabel, mDetailLabel), mTableView);
        content.setPadding(new Insets(10));
        content.setPrefSize(1100, 620);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, mImportButtonType);

        setResultConverter(buttonType -> buttonType == mImportButtonType ? mPlan : null);
        updatePlan();
    }

    private <T> void setup(ComboBox<T> comboBox, T[] values, T selected, String tooltip)
    {
        comboBox.getItems().setAll(values);
        comboBox.setValue(selected);
        comboBox.setTooltip(new Tooltip(tooltip));
        comboBox.valueProperty().addListener((observable, oldValue, newValue) -> updatePlan());
    }

    private static void addOption(GridPane grid, int row, int column, String label, javafx.scene.Node control)
    {
        Label labelNode = new Label(label);
        GridPane.setConstraints(labelNode, column, row);
        GridPane.setConstraints(control, column + 1, row);
        grid.getChildren().addAll(labelNode, control);
    }

    private static <T> StringConverter<T> converter(Function<T,String> formatter)
    {
        return new StringConverter<>()
        {
            @Override
            public String toString(T value)
            {
                return value != null ? formatter.apply(value) : "";
            }

            @Override
            public T fromString(String string)
            {
                return null;
            }
        };
    }

    /**
     * Creates the preview table columns.
     */
    private void createTable()
    {
        boolean hasCategory = mTable.getRows().stream().anyMatch(row -> !row.category().isEmpty());

        mTableView.getColumns().add(column("TG", 70, row -> AliasSheetModel.formatTalkgroup(
            mProtocolComboBox.getValue() != null ? mProtocolComboBox.getValue() : Protocol.APCO25,
            row.source().talkgroup())));
        mTableView.getColumns().add(column("Mode", 50, row -> row.source().modeText()));
        mTableView.getColumns().add(column("Alpha tag", 120, row -> row.source().alphaTag()));
        mTableView.getColumns().add(column("Description", 180, row -> row.source().description()));
        mTableView.getColumns().add(column("Tag", 110, row -> row.source().tag()));

        if(hasCategory)
        {
            mTableView.getColumns().add(column("Category", 110, row -> row.source().category()));
        }

        mTableView.getColumns().add(column("Alias name", 170, RadioReferenceImport.PlannedRow::name));
        mTableView.getColumns().add(column("Alias group", 110, RadioReferenceImport.PlannedRow::group));
        mTableView.getColumns().add(column("Console", 130, RadioReferenceImport.PlannedRow::getConsoleText));

        TableColumn<RadioReferenceImport.PlannedRow,String> action = column("Action", 120,
            row -> row.action().toString());
        action.setCellFactory(param -> new TableCell<>()
        {
            @Override
            protected void updateItem(String item, boolean empty)
            {
                super.updateItem(item, empty);
                RadioReferenceImport.PlannedRow row = empty || getTableRow() == null ? null : getTableRow().getItem();

                if(row == null)
                {
                    setText(null);
                    setTooltip(null);
                    setStyle("");
                    return;
                }

                setText(item);
                setTooltip(row.message().isEmpty() ? null : new Tooltip(row.message()));

                switch(row.action())
                {
                    case ADD -> setStyle("-fx-text-fill: #1A7F37; -fx-font-weight: bold;");
                    case UPDATE -> setStyle("-fx-text-fill: #0969DA; -fx-font-weight: bold;");
                    case SKIP_INVALID -> setStyle("-fx-text-fill: #CF222E;");
                    default -> setStyle("-fx-text-fill: #6E7781;");
                }
            }
        });
        mTableView.getColumns().add(action);
        mTableView.setPlaceholder(new Label("No talkgroups"));
    }

    private static TableColumn<RadioReferenceImport.PlannedRow,String> column(String title, double width,
        Function<RadioReferenceImport.PlannedRow,String> value)
    {
        TableColumn<RadioReferenceImport.PlannedRow,String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(param -> new SimpleStringProperty(param.getValue() != null ?
            value.apply(param.getValue()) : ""));
        return column;
    }

    /**
     * Options from the controls
     */
    private RadioReferenceImport.Options getOptions()
    {
        return new RadioReferenceImport.Options()
            .setAliasList(mAliasListComboBox.getEditor().getText())
            .setProtocol(mProtocolComboBox.getValue())
            .setNameSource(mNameComboBox.getValue())
            .setGroupSource(mGroupComboBox.getValue())
            .setExistingAliasAction(mExistingComboBox.getValue())
            .setEncryptedAction(mEncryptedComboBox.getValue())
            .setConsoleMode(mConsoleComboBox.getValue())
            .setConsoleGroup(mConsoleGroupComboBox.getEditor().getText());
    }

    /**
     * Plans the import for the current options and updates the preview.
     */
    private void updatePlan()
    {
        mConsoleGroupComboBox.setDisable(mConsoleComboBox.getValue() != RadioReferenceImport.ConsoleMode.SINGLE_GROUP ||
            mConsoleComboBox.isDisabled());

        mPlan = RadioReferenceImport.plan(mModel, mTable, getOptions());
        mSummaryLabel.setText(mPlan.getSummary());

        String detail = mPlan.getError() != null ? mPlan.getError() : mPlan.getConsoleSummary();

        if(mPlan.getError() == null && !mPlan.getNewConsoleGroups().isEmpty())
        {
            detail += " - new: " + mPlan.getNewConsoleGroups().stream().collect(Collectors.joining(", "));
        }

        mDetailLabel.setText(detail);
        mDetailLabel.setStyle(mPlan.getError() != null ? "-fx-text-fill: #CF222E;" : "");
        mTableView.setItems(FXCollections.observableArrayList(mPlan.getRows()));
        getDialogPane().lookupButton(mImportButtonType).setDisable(!mPlan.canApply());
    }
}
