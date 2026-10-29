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
import io.github.dsheirer.configuration.transfer.ConfigurationBundleReader;
import io.github.dsheirer.configuration.transfer.ConfigurationManifest;
import io.github.dsheirer.configuration.transfer.ConfigurationTransferService;
import io.github.dsheirer.configuration.transfer.PlaylistImporter;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.playlist.PlaylistManager;
import java.io.File;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Import Configuration dialog: reads and validates a configuration file exported by sdrtrunk (on another computer),
 * shows what it contains and imports the selected playlists, tuner settings and application preferences.
 */
public class ImportConfigurationDialog extends Dialog<Void>
{
    private static final Logger mLog = LoggerFactory.getLogger(ImportConfigurationDialog.class);
    private static final String TITLE = "Import Configuration";
    private static final DateTimeFormatter CREATED_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ConfigurationTransferService mService;
    private final PlaylistManager mPlaylistManager;
    private final TextField mFileField = new TextField();
    private final VBox mBundleContent = new VBox(8);
    private final Map<CheckBox,String> mPlaylistCheckBoxes = new LinkedHashMap<>();
    private final CheckBox mTunersCheckBox = new CheckBox();
    private final CheckBox mPreferencesCheckBox = new CheckBox();
    private final ButtonType mImportButtonType = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
    private ConfigurationBundle mBundle;

    /**
     * Constructs an instance
     * @param service for the import
     * @param playlistManager for starting auto-start channels of an imported active playlist
     */
    public ImportConfigurationDialog(ConfigurationTransferService service, PlaylistManager playlistManager)
    {
        mService = service;
        mPlaylistManager = playlistManager;

        initModality(Modality.NONE);
        setTitle("sdrtrunk - " + TITLE);
        setHeaderText("Import a setup that was exported from sdrtrunk on another computer");
        setResizable(true);

        mFileField.setEditable(false);
        mFileField.setPromptText("No configuration file selected");
        HBox.setHgrow(mFileField, Priority.ALWAYS);
        Button chooseButton = new Button("Choose File…");
        chooseButton.setOnAction(event -> chooseFile());
        HBox fileBox = new HBox(8, mFileField, chooseButton);
        fileBox.setAlignment(Pos.CENTER_LEFT);

        mTunersCheckBox.selectedProperty().addListener((observable, oldValue, newValue) -> updateButtons());
        mPreferencesCheckBox.selectedProperty().addListener((observable, oldValue, newValue) -> updateButtons());

        VBox content = new VBox(8, fileBox, mBundleContent);
        content.setPadding(new Insets(10));
        content.setPrefWidth(ConfigurationDialogSupport.CONTENT_WIDTH);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE, mImportButtonType);

        //Import runs when the button is pressed - the dialog closes only when the import succeeds
        getDialogPane().lookupButton(mImportButtonType).addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            importBundle();
        });

        showBundle();
    }

    /**
     * Shows the dialog and prompts for the configuration file.
     */
    public void showAndChooseFile()
    {
        show();
        Platform.runLater(this::chooseFile);
    }

    /**
     * Owner window for alerts and file choosers
     */
    private Window getWindow()
    {
        return getDialogPane().getScene().getWindow();
    }

    /**
     * Prompts for a configuration file and reads it.
     */
    private void chooseFile()
    {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(TITLE);
        fileChooser.setInitialDirectory(ConfigurationDialogSupport.getInitialDirectory());
        fileChooser.getExtensionFilters().addAll(ConfigurationDialogSupport.BUNDLE_FILE_FILTER,
            ConfigurationDialogSupport.ALL_FILES_FILTER);
        File file = fileChooser.showOpenDialog(getWindow());

        if(file == null)
        {
            return;
        }

        ConfigurationDialogSupport.setLastFile(file);

        try
        {
            mBundle = ConfigurationBundleReader.read(file.toPath());
            mFileField.setText(file.getAbsolutePath());
        }
        catch(Exception e)
        {
            mLog.error("Error reading configuration file [" + file + "]", e);
            mBundle = null;
            mFileField.setText(null);
            ConfigurationDialogSupport.showError(getWindow(), TITLE, "Unable to read the configuration file",
                e.getMessage() != null ? e.getMessage() : e.toString());
        }

        showBundle();
    }

    /**
     * Shows the contents of the configuration file with check boxes for the import selection.
     */
    private void showBundle()
    {
        mBundleContent.getChildren().clear();
        mPlaylistCheckBoxes.clear();

        if(mBundle == null)
        {
            mBundleContent.getChildren().addAll(ConfigurationDialogSupport.createNote("Choose a configuration " +
                "file (.zip) that was created with File > Export Configuration…"), new Separator(),
                ConfigurationDialogSupport.createNotIncludedNote());
            updateButtons();
            resizeWindow();
            return;
        }

        ConfigurationManifest manifest = mBundle.getManifest();
        mBundleContent.getChildren().add(ConfigurationDialogSupport.createNote("Exported " +
            formatCreated(manifest.getCreated()) + (manifest.getApplicationVersion() != null ?
            " by sdrtrunk " + manifest.getApplicationVersion() : "")));

        //Playlists
        mBundleContent.getChildren().addAll(new Separator(), ConfigurationDialogSupport.createHeading("Playlists"));
        List<String> names = mBundle.getPlaylistNames();
        List<String> existing = PlaylistImporter.getExistingPlaylists(names, mService.getPlaylistDirectory());

        if(names.isEmpty())
        {
            mBundleContent.getChildren().add(new Label("No playlists"));
        }

        for(String name: names)
        {
            List<String> notes = new ArrayList<>();

            if(name.equals(mBundle.getCurrentPlaylist()))
            {
                notes.add("becomes the active playlist");
            }

            if(mBundle.hasConsoleLayout(name))
            {
                notes.add("with console layout");
            }

            if(existing.contains(name))
            {
                notes.add("replaces the existing file");
            }

            CheckBox checkBox = new CheckBox(name + (notes.isEmpty() ? "" : " (" + String.join(", ", notes) + ")"));
            checkBox.setSelected(true);
            checkBox.selectedProperty().addListener((observable, oldValue, newValue) -> updateButtons());
            mPlaylistCheckBoxes.put(checkBox, name);
            mBundleContent.getChildren().add(checkBox);
        }

        if(!names.isEmpty())
        {
            mBundleContent.getChildren().add(ConfigurationDialogSupport.createNote("Playlists are imported into " +
                mService.getPlaylistDirectory() + ".  A replaced playlist or console layout file is kept as a " +
                "backup (.pre-import-[date_time])."));
        }

        //Settings
        mBundleContent.getChildren().addAll(new Separator(), ConfigurationDialogSupport.createHeading("Settings"));

        if(mBundle.hasTunerState())
        {
            mTunersCheckBox.setText("Tuner settings (" + mBundle.getTunerState().getTunerConfigurations().size() +
                " tuner(s), " + mBundle.getTunerState().getDisabledTuners().size() + " disabled tuner(s))");
            mTunersCheckBox.setTooltip(new Tooltip("Settings apply to the same tuners (matched by serial number).  " +
                "A tuner that is plugged in now uses the imported settings after you unplug and replug it, or " +
                "restart sdrtrunk."));
            mTunersCheckBox.setSelected(true);
            mBundleContent.getChildren().add(mTunersCheckBox);
        }
        else
        {
            mTunersCheckBox.setSelected(false);
            mBundleContent.getChildren().add(new Label("Tuner settings: not included"));
        }

        if(mBundle.hasPreferences())
        {
            mPreferencesCheckBox.setText("Application preferences (" + mBundle.getPreferenceCount() +
                " setting(s), applied when sdrtrunk restarts)");
            mPreferencesCheckBox.setTooltip(new Tooltip(ExportConfigurationDialog.getPreferenceSections()));
            mPreferencesCheckBox.setSelected(true);
            mBundleContent.getChildren().add(mPreferencesCheckBox);
        }
        else
        {
            mPreferencesCheckBox.setSelected(false);
            mBundleContent.getChildren().add(new Label("Application preferences: not included"));
        }

        mBundleContent.getChildren().addAll(new Separator(), ConfigurationDialogSupport.createNotIncludedNote());
        updateButtons();
        resizeWindow();
    }

    /**
     * Resizes the dialog window to fit the content.
     */
    private void resizeWindow()
    {
        if(getDialogPane().getScene() != null && getWindow() != null && getWindow().isShowing())
        {
            getWindow().sizeToScene();
        }
    }

    /**
     * Formats the manifest creation timestamp for display
     */
    private static String formatCreated(String created)
    {
        if(created == null)
        {
            return "(date unknown)";
        }

        try
        {
            return OffsetDateTime.parse(created).format(CREATED_FORMAT);
        }
        catch(DateTimeParseException dtpe)
        {
            return created;
        }
    }

    /**
     * Selected playlist names
     */
    private List<String> getSelectedPlaylists()
    {
        List<String> selected = new ArrayList<>();

        for(Map.Entry<CheckBox,String> entry: mPlaylistCheckBoxes.entrySet())
        {
            if(entry.getKey().isSelected())
            {
                selected.add(entry.getValue());
            }
        }

        return selected;
    }

    /**
     * Enables the import button when a configuration file is loaded and anything is selected
     */
    private void updateButtons()
    {
        boolean selected = mBundle != null && (!getSelectedPlaylists().isEmpty() ||
            (mBundle.hasTunerState() && mTunersCheckBox.isSelected()) ||
            (mBundle.hasPreferences() && mPreferencesCheckBox.isSelected()));
        Node importButton = getDialogPane().lookupButton(mImportButtonType);

        if(importButton != null)
        {
            importButton.setDisable(!selected);
        }
    }

    /**
     * Confirms replacing existing playlists, imports the selection and shows the result.
     */
    private void importBundle()
    {
        if(mBundle == null)
        {
            return;
        }

        List<String> playlists = getSelectedPlaylists();
        List<String> replaced = PlaylistImporter.getExistingPlaylists(playlists, mService.getPlaylistDirectory());

        if(!replaced.isEmpty())
        {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Replace " + String.join(", ", replaced) +
                "?\n\nThe existing playlist and console layout files are kept as backups.", ButtonType.CANCEL,
                ButtonType.OK);
            confirm.initOwner(getWindow());
            confirm.setTitle(TITLE);
            confirm.setHeaderText("Replace existing playlist" + (replaced.size() > 1 ? "s" : ""));

            if(confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK)
            {
                return;
            }
        }

        ConfigurationTransferService.ImportResult result;

        try
        {
            result = mService.importBundle(mBundle, playlists, mBundle.hasTunerState() && mTunersCheckBox.isSelected(),
                mBundle.hasPreferences() && mPreferencesCheckBox.isSelected());
        }
        catch(Exception e)
        {
            mLog.error("Error importing configuration", e);
            ConfigurationDialogSupport.showError(getWindow(), TITLE, "Unable to import the configuration",
                (e.getMessage() != null ? e.getMessage() : e.toString()) +
                    "\n\nSee the application log for details.");
            return;
        }

        ConfigurationDialogSupport.showSummary(getWindow(), TITLE, "Configuration imported", result.getSummary());
        close();

        if(result.activePlaylist() != null)
        {
            promptAutoStart();
        }
    }

    /**
     * Offers to start the auto-start channels of the (re)loaded active playlist, as the playlist editor does when a
     * playlist is selected.
     */
    private void promptAutoStart()
    {
        List<Channel> autoStartChannels = mPlaylistManager.getChannelModel().getAutoStartChannels();

        if(autoStartChannels.isEmpty())
        {
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Would you like to auto-start your channels?",
            ButtonType.YES, ButtonType.NO);
        alert.setTitle("Auto-Start Channels");
        alert.setHeaderText("Discovered [" + autoStartChannels.size() + "] auto-start channel" +
            (autoStartChannels.size() > 1 ? "s" : ""));

        if(alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES)
        {
            boolean error = false;

            for(Channel channel: autoStartChannels)
            {
                try
                {
                    mPlaylistManager.getChannelProcessingManager().start(channel);
                }
                catch(ChannelException ce)
                {
                    mLog.error("Error auto-starting channel [" + channel.getName() + "]", ce);
                    error = true;
                }
            }

            if(error)
            {
                ConfigurationDialogSupport.showError(null, "Channel Auto-Start Error(s)", "Auto-Start Error",
                    "Unable to start some or all of the auto-start channels");
            }
        }
    }
}
