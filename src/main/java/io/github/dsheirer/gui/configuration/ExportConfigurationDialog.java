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
import io.github.dsheirer.configuration.transfer.ConfigurationExporter;
import io.github.dsheirer.configuration.transfer.ConfigurationTransferService;
import io.github.dsheirer.configuration.transfer.PreferenceTransfer;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationState;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Export Configuration dialog: choose the playlists (with their console layouts), tuner settings and application
 * preferences to save to a configuration file for importing on another computer.
 */
public class ExportConfigurationDialog extends Dialog<Void>
{
    private static final Logger mLog = LoggerFactory.getLogger(ExportConfigurationDialog.class);
    private static final String TITLE = "Export Configuration";

    private final ConfigurationTransferService mService;
    private final Map<CheckBox,Path> mPlaylistCheckBoxes = new LinkedHashMap<>();
    private final CheckBox mTunersCheckBox = new CheckBox();
    private final CheckBox mPreferencesCheckBox = new CheckBox();
    private final ButtonType mExportButtonType = new ButtonType("Export…", ButtonBar.ButtonData.OK_DONE);

    /**
     * Constructs an instance
     * @param service for the export
     */
    public ExportConfigurationDialog(ConfigurationTransferService service)
    {
        mService = service;

        initModality(Modality.NONE);
        setTitle("sdrtrunk - " + TITLE);
        setHeaderText("Save your setup to a file to move it to another computer");
        setResizable(true);

        VBox content = new VBox(8);
        content.setPadding(new Insets(10));
        content.setPrefWidth(ConfigurationDialogSupport.CONTENT_WIDTH);
        content.getChildren().add(ConfigurationDialogSupport.createHeading("Playlists"));
        content.getChildren().add(ConfigurationDialogSupport.createNote("Each playlist includes its aliases, " +
            "channels, streaming and channel maps, and its console layout (groups, cards, volume, mute and pan)."));

        for(ConfigurationTransferService.PlaylistInfo playlist: mService.getPlaylists())
        {
            CheckBox checkBox = new CheckBox(getPlaylistLabel(playlist));
            checkBox.setTooltip(new Tooltip(playlist.path().toString()));
            checkBox.setSelected(playlist.current() && playlist.exists());
            checkBox.setDisable(!playlist.exists());
            checkBox.selectedProperty().addListener((observable, oldValue, newValue) -> updateButtons());
            mPlaylistCheckBoxes.put(checkBox, playlist.path());
            content.getChildren().add(checkBox);
        }

        TunerConfigurationState tunerState = mService.getTunerState();
        mTunersCheckBox.setText("Tuner settings (" + tunerState.getTunerConfigurations().size() + " tuner(s), " +
            tunerState.getDisabledTuners().size() + " disabled tuner(s))");
        mTunersCheckBox.setTooltip(new Tooltip("Gain, PPM, sample rate and other settings per tuner (matched by " +
            "serial number), and which tuners are disabled"));
        mTunersCheckBox.setSelected(true);
        mTunersCheckBox.selectedProperty().addListener((observable, oldValue, newValue) -> updateButtons());

        int preferenceCount = 0;

        try
        {
            preferenceCount = PreferenceTransfer.count(mService.getPreferences());
        }
        catch(IOException ioe)
        {
            mLog.error("Error reading application preferences", ioe);
        }

        mPreferencesCheckBox.setText("Application preferences (" + preferenceCount + " setting(s))");
        mPreferencesCheckBox.setTooltip(new Tooltip(getPreferenceSections()));
        mPreferencesCheckBox.setSelected(true);
        mPreferencesCheckBox.selectedProperty().addListener((observable, oldValue, newValue) -> updateButtons());

        content.getChildren().addAll(new Separator(), ConfigurationDialogSupport.createHeading("Settings"),
            mTunersCheckBox, mPreferencesCheckBox,
            ConfigurationDialogSupport.createNote("Preferences include the theme, audio output device and " +
                "channels, talkgroup format, duplicate call, recording and MP3 settings."),
            new Separator(), ConfigurationDialogSupport.createNotIncludedNote());

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE, mExportButtonType);

        //Export runs when the button is pressed - the dialog closes only when the export succeeds
        getDialogPane().lookupButton(mExportButtonType).addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            export();
        });

        updateButtons();
    }

    /**
     * Label for a playlist check box
     */
    private static String getPlaylistLabel(ConfigurationTransferService.PlaylistInfo playlist)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(playlist.path().getFileName());

        List<String> notes = new ArrayList<>();

        if(playlist.current())
        {
            notes.add("current playlist");
        }

        if(!playlist.exists())
        {
            notes.add("file not found");
        }
        else if(playlist.consoleLayout())
        {
            notes.add("with console layout");
        }

        if(!notes.isEmpty())
        {
            sb.append(" (").append(String.join(", ", notes)).append(")");
        }

        return sb.toString();
    }

    /**
     * Tooltip text listing the exported preference sections
     */
    static String getPreferenceSections()
    {
        StringBuilder sb = new StringBuilder();

        for(PreferenceTransfer.Section section: PreferenceTransfer.SECTIONS)
        {
            if(!sb.isEmpty())
            {
                sb.append("\n");
            }

            sb.append(section.label());
        }

        return sb.toString();
    }

    /**
     * Enables the export button when anything is selected
     */
    private void updateButtons()
    {
        boolean selected = mTunersCheckBox.isSelected() || mPreferencesCheckBox.isSelected() ||
            mPlaylistCheckBoxes.keySet().stream().anyMatch(CheckBox::isSelected);
        Node exportButton = getDialogPane().lookupButton(mExportButtonType);

        if(exportButton != null)
        {
            exportButton.setDisable(!selected);
        }
    }

    /**
     * Prompts for the export file and exports the selected configuration.
     */
    private void export()
    {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(TITLE);
        fileChooser.setInitialDirectory(ConfigurationDialogSupport.getInitialDirectory());
        fileChooser.setInitialFileName(ConfigurationBundle.getDefaultFileName(LocalDate.now()));
        fileChooser.getExtensionFilters().addAll(ConfigurationDialogSupport.BUNDLE_FILE_FILTER,
            ConfigurationDialogSupport.ALL_FILES_FILTER);
        File file = fileChooser.showSaveDialog(getDialogPane().getScene().getWindow());

        if(file == null)
        {
            return;
        }

        ConfigurationDialogSupport.setLastFile(file);

        if(!file.getName().contains("."))
        {
            file = new File(file.getParentFile(), file.getName() + ConfigurationBundle.FILE_EXTENSION);
        }

        List<Path> playlists = new ArrayList<>();

        for(Map.Entry<CheckBox,Path> entry: mPlaylistCheckBoxes.entrySet())
        {
            if(entry.getKey().isSelected())
            {
                playlists.add(entry.getValue());
            }
        }

        try
        {
            ConfigurationExporter.ExportResult result = mService.export(file.toPath(), playlists,
                mTunersCheckBox.isSelected(), mPreferencesCheckBox.isSelected());
            ConfigurationDialogSupport.showSummary(getDialogPane().getScene().getWindow(), TITLE,
                "Configuration exported", result.getSummary() + "\nOn the other computer, install sdrtrunk and " +
                    "use File > Import Configuration… to import this file.");
            close();
        }
        catch(Exception e)
        {
            mLog.error("Error exporting configuration to [" + file + "]", e);
            ConfigurationDialogSupport.showError(getDialogPane().getScene().getWindow(), TITLE,
                "Unable to export the configuration", e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }
}
