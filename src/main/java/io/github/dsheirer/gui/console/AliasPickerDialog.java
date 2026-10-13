/*
 * *****************************************************************************
 * Copyright (C) 2026 Ryan Lovett
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
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Dialog to choose an alias for a console card.  Lists the aliases of the current playlist (alias list, name and
 * talkgroup) with a search box.  Aliases that are already on a console card are shown dimmed with the card's group
 * name and cannot be chosen.
 */
public class AliasPickerDialog extends Dialog<Alias>
{
    private final Function<Alias,String> mAssignedGroupLookup;
    private final TableView<Alias> mTableView = new TableView<>();

    /**
     * Constructs an instance
     * @param owner window
     * @param stylesheet console stylesheet URL, or null
     * @param aliasModel providing the aliases
     * @param assignedGroupLookup provides the name of the console group that already contains a card for the alias,
     * or null when the alias is not on the console
     * @param title for the dialog
     * @param header text for the dialog
     */
    public AliasPickerDialog(Window owner, String stylesheet, AliasModel aliasModel,
                             Function<Alias,String> assignedGroupLookup, String title, String header)
    {
        mAssignedGroupLookup = assignedGroupLookup;
        initOwner(owner);
        setTitle(title);
        setHeaderText(header);
        setResizable(true);
        getDialogPane().getStyleClass().add("alias-picker");

        if(stylesheet != null)
        {
            getDialogPane().getStylesheets().add(stylesheet);
        }

        TextField searchField = new TextField();
        searchField.setPromptText("Search by alias list, alias name or talkgroup");

        FilteredList<Alias> filtered = new FilteredList<>(aliasModel.aliasList(), alias -> true);
        searchField.textProperty().addListener((observable, oldValue, text) -> {
            String search = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            filtered.setPredicate(alias -> search.isEmpty() || matches(alias, search));
        });
        SortedList<Alias> sorted = new SortedList<>(filtered, Comparator
            .comparing((Alias alias) -> nullToEmpty(alias.getAliasListName()), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(alias -> nullToEmpty(alias.getName()), String.CASE_INSENSITIVE_ORDER));

        TableColumn<Alias,String> listColumn = new TableColumn<>("Alias List");
        listColumn.setCellValueFactory(features -> new ReadOnlyStringWrapper(features.getValue().getAliasListName()));
        listColumn.setPrefWidth(150);
        TableColumn<Alias,String> nameColumn = new TableColumn<>("Alias");
        nameColumn.setCellValueFactory(features -> new ReadOnlyStringWrapper(features.getValue().getName()));
        nameColumn.setPrefWidth(220);
        TableColumn<Alias,String> identifierColumn = new TableColumn<>("Talkgroup");
        identifierColumn.setCellValueFactory(features -> new ReadOnlyStringWrapper(getIdentifierLabel(features.getValue())));
        identifierColumn.setPrefWidth(110);
        TableColumn<Alias,String> consoleColumn = new TableColumn<>("On Console");
        consoleColumn.setCellValueFactory(features -> new ReadOnlyStringWrapper(
            mAssignedGroupLookup.apply(features.getValue())));
        consoleColumn.setPrefWidth(150);

        mTableView.getColumns().addAll(listColumn, nameColumn, identifierColumn, consoleColumn);
        mTableView.setItems(sorted);
        mTableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        mTableView.setPlaceholder(new Label("No matching aliases"));
        mTableView.setPrefSize(660, 420);
        mTableView.setRowFactory(table -> new AliasRow());
        VBox.setVgrow(mTableView, Priority.ALWAYS);

        VBox content = new VBox(8, searchField, mTableView);
        getDialogPane().setContent(content);

        ButtonType chooseButtonType = new ButtonType("Choose", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(chooseButtonType, ButtonType.CANCEL);
        Node chooseButton = getDialogPane().lookupButton(chooseButtonType);
        chooseButton.disableProperty().bind(Bindings.createBooleanBinding(() -> !isSelectable(
            mTableView.getSelectionModel().getSelectedItem()), mTableView.getSelectionModel().selectedItemProperty()));

        setResultConverter(buttonType -> buttonType == chooseButtonType &&
            isSelectable(mTableView.getSelectionModel().getSelectedItem()) ?
            mTableView.getSelectionModel().getSelectedItem() : null);

        Platform.runLater(searchField::requestFocus);
    }

    /**
     * Indicates if the alias can be chosen: it is not null and is not already on a console card.
     */
    private boolean isSelectable(Alias alias)
    {
        return alias != null && mAssignedGroupLookup.apply(alias) == null;
    }

    /**
     * Indicates if the alias matches the (lower case) search text.
     */
    private static boolean matches(Alias alias, String search)
    {
        return nullToEmpty(alias.getName()).toLowerCase(Locale.ROOT).contains(search) ||
            nullToEmpty(alias.getAliasListName()).toLowerCase(Locale.ROOT).contains(search) ||
            getIdentifierLabel(alias).contains(search);
    }

    private static String nullToEmpty(String value)
    {
        return value != null ? value : "";
    }

    /**
     * Talkgroup (or radio) identifier label for the alias: the first talkgroup, talkgroup range, radio or radio range.
     * @param alias to describe
     * @return label or an empty string.
     */
    public static String getIdentifierLabel(Alias alias)
    {
        if(alias == null)
        {
            return "";
        }

        String radio = null;

        for(AliasID aliasID: alias.getAliasIdentifiers())
        {
            if(aliasID instanceof Talkgroup talkgroup)
            {
                return String.valueOf(talkgroup.getValue());
            }
            else if(aliasID instanceof TalkgroupRange range)
            {
                return range.getMinTalkgroup() + "–" + range.getMaxTalkgroup();
            }
            else if(radio == null && aliasID instanceof Radio r)
            {
                radio = "R " + r.getValue();
            }
            else if(radio == null && aliasID instanceof RadioRange range)
            {
                radio = "R " + range.getMinRadio() + "–" + range.getMaxRadio();
            }
        }

        return radio != null ? radio : "";
    }

    /**
     * Alias table row: dims aliases that are already on the console and chooses an alias on double click.
     */
    private class AliasRow extends TableRow<Alias>
    {
        private AliasRow()
        {
            setOnMouseClicked(event -> {
                if(event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && isSelectable(getItem()))
                {
                    setResult(getItem());
                    close();
                }
            });
        }

        @Override
        protected void updateItem(Alias alias, boolean empty)
        {
            super.updateItem(alias, empty);
            getStyleClass().remove("assigned");

            if(!empty && alias != null && mAssignedGroupLookup.apply(alias) != null)
            {
                getStyleClass().add("assigned");
            }
        }
    }
}
