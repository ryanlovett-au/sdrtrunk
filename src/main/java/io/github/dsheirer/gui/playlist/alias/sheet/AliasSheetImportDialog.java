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

import io.github.dsheirer.alias.sheet.AliasSheetCsv;
import io.github.dsheirer.alias.sheet.AliasSheetModel;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * CSV import dialog: choose the import mode and preview the planned changes before applying them.
 */
public class AliasSheetImportDialog extends Dialog<AliasSheetCsv.ImportPlan>
{
    private static final int MAXIMUM_PREVIEW_LINES = 500;

    private final AliasSheetModel mModel;
    private final String mCsv;
    private final String mDefaultAliasList;
    private final Label mSummaryLabel = new Label();
    private final TextArea mDetailsArea = new TextArea();
    private final ButtonType mImportButtonType = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
    private AliasSheetCsv.ImportPlan mPlan;

    /**
     * Constructs an instance
     * @param owner window
     * @param model for planning the import
     * @param csv file contents
     * @param fileName for the dialog header
     * @param defaultAliasList for rows that don't specify an alias list
     */
    public AliasSheetImportDialog(Window owner, AliasSheetModel model, String csv, String fileName,
                                  String defaultAliasList)
    {
        mModel = model;
        mCsv = csv;
        mDefaultAliasList = defaultAliasList;

        initOwner(owner);
        setTitle("Import Aliases from CSV");
        setHeaderText("Import " + fileName + (defaultAliasList != null ? " (default alias list: " +
            defaultAliasList + ")" : ""));
        setResizable(true);

        ToggleGroup modeGroup = new ToggleGroup();
        RadioButton updateButton = new RadioButton(AliasSheetCsv.ImportMode.UPDATE_BY_NAME.toString());
        updateButton.setUserData(AliasSheetCsv.ImportMode.UPDATE_BY_NAME);
        updateButton.setToggleGroup(modeGroup);
        RadioButton appendButton = new RadioButton(AliasSheetCsv.ImportMode.APPEND.toString());
        appendButton.setUserData(AliasSheetCsv.ImportMode.APPEND);
        appendButton.setToggleGroup(modeGroup);
        modeGroup.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
            if(newValue != null)
            {
                updatePlan((AliasSheetCsv.ImportMode)newValue.getUserData());
            }
        });

        mSummaryLabel.setWrapText(true);
        mDetailsArea.setEditable(false);
        mDetailsArea.setPrefRowCount(16);
        mDetailsArea.setPrefColumnCount(70);
        mDetailsArea.setStyle("-fx-font-family: monospace;");
        VBox.setVgrow(mDetailsArea, Priority.ALWAYS);

        VBox content = new VBox(8, updateButton, appendButton, mSummaryLabel, mDetailsArea);
        content.setPadding(new Insets(10));
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, mImportButtonType);

        setResultConverter(buttonType -> buttonType == mImportButtonType ? mPlan : null);
        modeGroup.selectToggle(updateButton);
    }

    /**
     * Plans the import for the mode and updates the preview.
     */
    private void updatePlan(AliasSheetCsv.ImportMode mode)
    {
        mPlan = AliasSheetCsv.plan(mModel, mCsv, mode, mDefaultAliasList);
        mSummaryLabel.setText(mPlan.getSummary());
        mDetailsArea.setText(String.join("\n", mPlan.getDetails(MAXIMUM_PREVIEW_LINES)));
        getDialogPane().lookupButton(mImportButtonType).setDisable(!mPlan.canApply());
    }
}
