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
package io.github.dsheirer.configuration.transfer;

import io.github.dsheirer.console.ConsoleLayoutManager;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.playlist.PlaylistPreference;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationManager;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration export and import for moving a whole setup (playlists with console layouts, tuner settings and
 * application preferences) to another computer.  Connects the bundle reader/writer to the running application.
 */
public class ConfigurationTransferService
{
    private static final Logger mLog = LoggerFactory.getLogger(ConfigurationTransferService.class);

    private final PlaylistManager mPlaylistManager;
    private final UserPreferences mUserPreferences;
    private final TunerConfigurationManager mTunerConfigurationManager;
    private final Supplier<Preferences> mPreferencesRoot;

    /**
     * Constructs an instance that uses the user preferences store.
     * @param playlistManager for the current playlist
     * @param userPreferences for the playlist list and directories
     * @param tunerConfigurationManager for tuner settings
     */
    public ConfigurationTransferService(PlaylistManager playlistManager, UserPreferences userPreferences,
                                        TunerConfigurationManager tunerConfigurationManager)
    {
        this(playlistManager, userPreferences, tunerConfigurationManager, PreferenceTransfer::getUserPreferencesRoot);
    }

    /**
     * Constructs an instance
     * @param playlistManager for the current playlist
     * @param userPreferences for the playlist list and directories
     * @param tunerConfigurationManager for tuner settings
     * @param preferencesRoot supplies the sdrtrunk preferences root node
     */
    public ConfigurationTransferService(PlaylistManager playlistManager, UserPreferences userPreferences,
                                        TunerConfigurationManager tunerConfigurationManager,
                                        Supplier<Preferences> preferencesRoot)
    {
        mPlaylistManager = playlistManager;
        mUserPreferences = userPreferences;
        mTunerConfigurationManager = tunerConfigurationManager;
        mPreferencesRoot = preferencesRoot;
    }

    /**
     * Version of this application from the jar manifest, or null when not available (e.g. development builds).
     */
    public static String getApplicationVersion()
    {
        Package classPackage = ConfigurationTransferService.class.getPackage();
        return classPackage != null ? classPackage.getImplementationVersion() : null;
    }

    /**
     * Playlists from the playlist list, with the current playlist first.
     */
    public List<PlaylistInfo> getPlaylists()
    {
        PlaylistPreference playlistPreference = mUserPreferences.getPlaylistPreference();
        Path current = playlistPreference.getPlaylist();
        List<PlaylistInfo> playlists = new ArrayList<>();

        for(Path path: playlistPreference.getPlaylistList())
        {
            boolean isCurrent = isSameFile(path, current);
            Path layout = ConsoleLayoutManager.getSidecarPath(path);
            PlaylistInfo info = new PlaylistInfo(path, isCurrent, Files.isRegularFile(path),
                layout != null && Files.isRegularFile(layout));

            if(isCurrent)
            {
                playlists.add(0, info);
            }
            else
            {
                playlists.add(info);
            }
        }

        return playlists;
    }

    /**
     * Tuner settings that would be exported.
     */
    public TunerConfigurationState getTunerState()
    {
        return mTunerConfigurationManager.getTransferableState();
    }

    /**
     * Application preferences that would be exported.
     * @throws IOException if the preferences can't be read
     */
    public Map<String,Map<String,String>> getPreferences() throws IOException
    {
        return PreferenceTransfer.exportPreferences(mPreferencesRoot.get());
    }

    /**
     * Playlist directory that imported playlists are written to.
     */
    public Path getPlaylistDirectory()
    {
        return mUserPreferences.getDirectoryPreference().getDirectoryPlaylist();
    }

    /**
     * Exports the selected configuration to a bundle file.  Pending playlist and console layout changes are saved
     * first.
     *
     * @param bundlePath for the bundle (zip) file
     * @param playlists to export
     * @param tuners to include tuner settings
     * @param preferences to include application preferences
     * @return export result
     * @throws IOException if the bundle can't be written
     */
    public ConfigurationExporter.ExportResult export(Path bundlePath, Collection<Path> playlists, boolean tuners,
                                                     boolean preferences) throws IOException
    {
        mPlaylistManager.flushPendingSaves();

        Path current = mUserPreferences.getPlaylistPreference().getPlaylist();
        List<ConfigurationExporter.PlaylistSource> sources = new ArrayList<>();

        for(Path playlist: playlists)
        {
            sources.add(new ConfigurationExporter.PlaylistSource(playlist, isSameFile(playlist, current)));
        }

        ConfigurationExporter.ExportResult result = ConfigurationExporter.export(bundlePath, sources,
            tuners ? getTunerState() : null, preferences ? getPreferences() : null, getApplicationVersion(),
            ZonedDateTime.now());
        mLog.info("Exported configuration to [" + bundlePath + "] - " + result.getSummary().replace('\n', ' '));
        return result;
    }

    /**
     * Imports the selected configuration from the bundle.
     *
     * Playlists are written to the playlist directory and added to the playlist list; existing files are backed up
     * and replaced.  When the current playlist is replaced, or the bundle's current playlist is imported, that
     * playlist is (re)loaded as the active playlist without saving the previous in-memory playlist over it.
     *
     * @param bundle to import
     * @param playlists names of the playlists to import
     * @param tuners to import tuner settings
     * @param preferences to import application preferences
     * @return import result
     * @throws IOException if the import fails
     */
    public ImportResult importBundle(ConfigurationBundle bundle, Collection<String> playlists, boolean tuners,
                                     boolean preferences) throws IOException
    {
        PlaylistImporter.Result playlistResult = null;
        Path activated = null;
        Path directory = getPlaylistDirectory();

        if(!playlists.isEmpty())
        {
            PlaylistPreference playlistPreference = mUserPreferences.getPlaylistPreference();
            Path current = playlistPreference.getPlaylist();
            String timestamp = PlaylistImporter.createTimestamp();
            boolean replacesCurrent = false;

            for(String name: playlists)
            {
                if(isSameFile(PlaylistImporter.getTargetPath(directory, name), current))
                {
                    replacesCurrent = true;
                }
            }

            String bundleCurrent = bundle.getCurrentPlaylist();
            Path active = bundleCurrent != null && playlists.contains(bundleCurrent) ?
                PlaylistImporter.getTargetPath(directory, bundleCurrent) : null;

            if(replacesCurrent || (active != null && !isSameFile(active, current)))
            {
                PlaylistImporter.Result[] holder = new PlaylistImporter.Result[1];
                Path load = active != null ? active : current;
                mPlaylistManager.replacePlaylistFiles(() -> holder[0] = PlaylistImporter.writePlaylists(bundle,
                    playlists, directory, timestamp), load);
                playlistResult = holder[0];
                activated = load;
            }
            else
            {
                playlistResult = PlaylistImporter.writePlaylists(bundle, playlists, directory, timestamp);
            }

            playlistPreference.addToPlaylistList(playlistResult.written());
        }

        TunerConfigurationState.MergeResult tunerResult = null;

        if(tuners && bundle.hasTunerState())
        {
            tunerResult = mTunerConfigurationManager.importState(bundle.getTunerState());
        }

        PreferenceTransfer.ImportResult preferenceResult = null;

        if(preferences && bundle.hasPreferences())
        {
            preferenceResult = PreferenceTransfer.importPreferences(mPreferencesRoot.get(), bundle.getPreferences());
        }

        ImportResult result = new ImportResult(directory, playlistResult, activated, tunerResult, preferenceResult);
        mLog.info("Imported configuration - " + result.getSummary().replace('\n', ' '));
        return result;
    }

    /**
     * Indicates if the paths refer to the same file.
     */
    private static boolean isSameFile(Path a, Path b)
    {
        if(a == null || b == null)
        {
            return false;
        }

        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    /**
     * Playlist that can be exported
     * @param path of the playlist file
     * @param current indicates if this is the current playlist
     * @param exists indicates if the playlist file exists
     * @param consoleLayout indicates if the playlist has a console layout file
     */
    public record PlaylistInfo(Path path, boolean current, boolean exists, boolean consoleLayout)
    {
    }

    /**
     * Import result
     * @param playlistDirectory that playlists were imported into
     * @param playlists playlist import result or null when no playlists were imported
     * @param activePlaylist playlist that was (re)loaded as the active playlist, or null when the active playlist
     * didn't change
     * @param tuners tuner settings import result or null when tuner settings were not imported
     * @param preferences preference import result or null when preferences were not imported
     */
    public record ImportResult(Path playlistDirectory, PlaylistImporter.Result playlists, Path activePlaylist,
                               TunerConfigurationState.MergeResult tuners, PreferenceTransfer.ImportResult preferences)
    {
        /**
         * Summary of the imported content for display to the user
         */
        public String getSummary()
        {
            StringBuilder sb = new StringBuilder();

            if(playlists != null)
            {
                sb.append("Playlists imported into ").append(playlistDirectory).append(":\n");

                for(Path path: playlists.written())
                {
                    sb.append("  ").append(path.getFileName());

                    if(activePlaylist != null && path.equals(activePlaylist.toAbsolutePath().normalize()))
                    {
                        sb.append(" (now the active playlist)");
                    }

                    sb.append("\n");
                }

                if(!playlists.backups().isEmpty())
                {
                    sb.append("Backups of the replaced files:\n");

                    for(Path path: playlists.backups())
                    {
                        sb.append("  ").append(path.getFileName()).append("\n");
                    }
                }
            }
            else
            {
                sb.append("Playlists: none imported\n");
            }

            sb.append("\n");

            if(tuners != null)
            {
                sb.append("Tuner settings: ").append(tuners.replaced()).append(" updated, ").append(tuners.added())
                    .append(" added, ").append(tuners.disabledAdded()).append(" disabled tuner(s) added.\n")
                    .append("A tuner that is plugged in now picks up its imported settings after you unplug and ")
                    .append("replug it, or restart sdrtrunk.\n");
            }
            else
            {
                sb.append("Tuner settings: not imported\n");
            }

            sb.append("\n");

            if(preferences != null)
            {
                sb.append("Application preferences: ").append(preferences.imported()).append(" setting(s) imported");

                if(!preferences.ignored().isEmpty())
                {
                    sb.append(", ").append(preferences.ignored().size()).append(" ignored");
                }

                sb.append(".\nRestart sdrtrunk for the imported preferences to take effect.\n");
            }
            else
            {
                sb.append("Application preferences: not imported\n");
            }

            return sb.toString();
        }
    }
}
