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
package io.github.dsheirer.gui.configuration;

import io.github.dsheirer.configuration.transfer.ConfigurationBundle;
import io.github.dsheirer.configuration.transfer.PreferenceTransfer;
import java.io.File;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Shared controls and alerts for the configuration export and import dialogs.
 */
final class ConfigurationDialogSupport
{
    static final double CONTENT_WIDTH = 520;
    static final FileChooser.ExtensionFilter BUNDLE_FILE_FILTER = new FileChooser.ExtensionFilter(
        "sdrtrunk configuration (*" + ConfigurationBundle.FILE_EXTENSION + ")", "*" + ConfigurationBundle.FILE_EXTENSION);
    static final FileChooser.ExtensionFilter ALL_FILES_FILTER = new FileChooser.ExtensionFilter("All Files (*.*)",
        "*.*");

    private static File sLastDirectory;

    private ConfigurationDialogSupport()
    {
    }

    /**
     * Section heading label
     */
    static Label createHeading(String text)
    {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    /**
     * Wrapping explanatory label
     */
    static Label createNote(String text)
    {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(CONTENT_WIDTH);
        label.setMinHeight(Label.USE_PREF_SIZE);
        return label;
    }

    /**
     * Label that lists what is not transferred
     */
    static Label createNotIncludedNote()
    {
        Label label = createNote(PreferenceTransfer.NOT_INCLUDED);
        label.setStyle("-fx-font-style: italic;");
        return label;
    }

    /**
     * Directory that was last used for an export or import, or the user's home directory.
     */
    static File getInitialDirectory()
    {
        if(sLastDirectory != null && sLastDirectory.isDirectory())
        {
            return sLastDirectory;
        }

        File home = new File(System.getProperty("user.home"));
        return home.isDirectory() ? home : null;
    }

    /**
     * Remembers the directory of the chosen file for the next export or import.
     */
    static void setLastFile(File file)
    {
        if(file != null && file.getParentFile() != null)
        {
            sLastDirectory = file.getParentFile();
        }
    }

    /**
     * Shows an information alert with the (multi-line) summary text and waits for it to close.
     */
    static void showSummary(Window owner, String title, String header, String summary)
    {
        TextArea textArea = new TextArea(summary);
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefRowCount(14);
        textArea.setPrefColumnCount(60);
        VBox.setVgrow(textArea, Priority.ALWAYS);

        Alert alert = new Alert(Alert.AlertType.INFORMATION, null, ButtonType.OK);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.getDialogPane().setContent(textArea);
        alert.setResizable(true);
        alert.showAndWait();
    }

    /**
     * Shows an error alert and waits for it to close.
     */
    static void showError(Window owner, String title, String header, String message)
    {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.getDialogPane().setMinWidth(CONTENT_WIDTH);
        alert.setResizable(true);
        alert.showAndWait();
    }
}
