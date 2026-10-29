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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.dsheirer.console.ConsoleLayoutManager;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationState;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a configuration bundle (zip) file from playlist files (and their console layout files), a tuner settings
 * snapshot and exported application preferences.
 */
public final class ConfigurationExporter
{
    private static final String TEMPORARY_FILE_SUFFIX = ".tmp";

    private ConfigurationExporter()
    {
    }

    /**
     * Writes the configuration bundle.  The bundle is written to a temporary file that then replaces the bundle file,
     * so that a failed export doesn't leave a partial bundle file.
     *
     * @param bundlePath for the bundle (zip) file
     * @param playlists to include (each with its console layout file when it exists)
     * @param tunerState to include or null to exclude tuner settings
     * @param preferences to include (see PreferenceTransfer.exportPreferences()) or null to exclude preferences
     * @param applicationVersion of this application or null when not known
     * @param created timestamp for the manifest
     * @return summary of the exported content
     * @throws IOException if a playlist file can't be read or the bundle can't be written
     */
    public static ExportResult export(Path bundlePath, List<PlaylistSource> playlists, TunerConfigurationState tunerState,
                                      Map<String,Map<String,String>> preferences, String applicationVersion,
                                      ZonedDateTime created) throws IOException
    {
        ObjectMapper jsonMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        ConfigurationManifest manifest = new ConfigurationManifest();
        manifest.setCreated(created.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        manifest.setApplicationVersion(applicationVersion);
        manifest.setTuners(tunerState != null);
        manifest.setPreferences(preferences != null);

        List<String> names = new ArrayList<>();
        int layouts = 0;
        Path temporary = bundlePath.resolveSibling(bundlePath.getFileName().toString() + TEMPORARY_FILE_SUFFIX);

        try
        {
            try(ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary)))
            {
                Set<String> used = new HashSet<>();

                for(PlaylistSource source: playlists)
                {
                    if(!Files.isRegularFile(source.path()))
                    {
                        throw new IOException("Playlist file not found: " + source.path());
                    }

                    String name = getUniqueName(ConfigurationBundle.sanitizePlaylistName(
                        source.path().getFileName().toString()), used);
                    write(zip, ConfigurationBundle.getPlaylistEntryName(name), Files.readAllBytes(source.path()));

                    Path layout = ConsoleLayoutManager.getSidecarPath(source.path());
                    boolean hasLayout = layout != null && Files.isRegularFile(layout);

                    if(hasLayout)
                    {
                        write(zip, ConfigurationBundle.getConsoleLayoutEntryName(name), Files.readAllBytes(layout));
                        layouts++;
                    }

                    manifest.getPlaylists().add(new ConfigurationManifest.PlaylistEntry(name, source.current(),
                        hasLayout));
                    names.add(name);

                    if(source.current())
                    {
                        manifest.setCurrentPlaylist(name);
                    }
                }

                if(tunerState != null)
                {
                    write(zip, ConfigurationBundle.TUNER_ENTRY, jsonMapper.writeValueAsBytes(tunerState));
                }

                if(preferences != null)
                {
                    write(zip, ConfigurationBundle.PREFERENCES_ENTRY, jsonMapper.writeValueAsBytes(preferences));
                }

                //Manifest is written last, once the playlist names are known; readers locate entries by name
                write(zip, ConfigurationBundle.MANIFEST_ENTRY, jsonMapper.writeValueAsBytes(manifest));
            }

            try
            {
                Files.move(temporary, bundlePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch(AtomicMoveNotSupportedException amnse)
            {
                Files.move(temporary, bundlePath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally
        {
            Files.deleteIfExists(temporary);
        }

        return new ExportResult(bundlePath, names, layouts,
            tunerState != null ? tunerState.getTunerConfigurations().size() : -1,
            tunerState != null ? tunerState.getDisabledTuners().size() : -1,
            preferences != null ? PreferenceTransfer.count(preferences) : -1);
    }

    /**
     * Name that isn't already used: adds a -2, -3, ... suffix before the extension when needed.
     */
    private static String getUniqueName(String name, Set<String> used)
    {
        String unique = name;
        String base = name.substring(0, name.length() - ConfigurationBundle.PLAYLIST_EXTENSION.length());

        for(int x = 2; !used.add(unique); x++)
        {
            unique = base + "-" + x + ConfigurationBundle.PLAYLIST_EXTENSION;
        }

        return unique;
    }

    /**
     * Writes a zip entry
     */
    private static void write(ZipOutputStream zip, String name, byte[] content) throws IOException
    {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    /**
     * Playlist file to export.
     * @param path of the playlist file
     * @param current indicates if this is the current playlist
     */
    public record PlaylistSource(Path path, boolean current)
    {
    }

    /**
     * Export result
     * @param bundlePath of the bundle file
     * @param playlists bundle file names of the exported playlists
     * @param consoleLayouts count of the exported console layouts
     * @param tunerConfigurations count of the exported tuner configurations, or -1 when tuner settings are excluded
     * @param disabledTuners count of the exported disabled tuners, or -1 when tuner settings are excluded
     * @param preferences count of the exported preference values, or -1 when preferences are excluded
     */
    public record ExportResult(Path bundlePath, List<String> playlists, int consoleLayouts, int tunerConfigurations,
                               int disabledTuners, int preferences)
    {
        /**
         * Summary of the exported content for display to the user
         */
        public String getSummary()
        {
            StringBuilder sb = new StringBuilder();
            sb.append("Saved to ").append(bundlePath).append("\n\n");

            if(playlists.isEmpty())
            {
                sb.append("Playlists: none\n");
            }
            else
            {
                sb.append("Playlists: ").append(String.join(", ", playlists)).append(" (")
                    .append(consoleLayouts).append(" with console layout)\n");
            }

            sb.append("Tuner settings: ").append(tunerConfigurations < 0 ? "not included" :
                tunerConfigurations + " tuner(s), " + disabledTuners + " disabled tuner(s)").append("\n");
            sb.append("Application preferences: ").append(preferences < 0 ? "not included" :
                preferences + " setting(s)").append("\n");
            return sb.toString();
        }
    }
}
