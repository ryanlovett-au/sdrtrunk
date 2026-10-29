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
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationState;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration bundle: a zip file that carries a whole sdrtrunk setup to another computer.  Defines the bundle entry
 * names and playlist file naming rules, and holds the validated contents of a bundle that was read from disk.
 *
 * Bundle entries:
 * <ul>
 *     <li>manifest.json - see ConfigurationManifest</li>
 *     <li>playlists/[name].xml - playlist</li>
 *     <li>playlists/[name].console.json - console layout of the playlist (optional)</li>
 *     <li>tuners/tuner_configuration.json - tuner configurations and disabled tuners (optional)</li>
 *     <li>preferences/preferences.json - whitelisted application preferences (optional): node path relative to the
 *     sdrtrunk preferences root, mapped to key/value pairs</li>
 * </ul>
 */
public class ConfigurationBundle
{
    public static final String MANIFEST_ENTRY = "manifest.json";
    public static final String PLAYLIST_DIRECTORY = "playlists/";
    public static final String TUNER_DIRECTORY = "tuners/";
    public static final String TUNER_ENTRY = TUNER_DIRECTORY + "tuner_configuration.json";
    public static final String PREFERENCES_DIRECTORY = "preferences/";
    public static final String PREFERENCES_ENTRY = PREFERENCES_DIRECTORY + "preferences.json";
    public static final String PLAYLIST_EXTENSION = ".xml";
    public static final String FILE_EXTENSION = ".zip";
    private static final String DEFAULT_FILE_NAME_PREFIX = "sdrtrunk-configuration-";
    private static final String DEFAULT_PLAYLIST_NAME = "playlist";
    private static final int MAX_PLAYLIST_NAME_LENGTH = 100;

    private final ConfigurationManifest mManifest;
    private final Map<String,byte[]> mPlaylists;
    private final Map<String,byte[]> mConsoleLayouts;
    private final TunerConfigurationState mTunerState;
    private final Map<String,Map<String,String>> mPreferences;

    /**
     * Constructs an instance with validated bundle contents.
     * @param manifest of the bundle
     * @param playlists map of playlist file name to playlist file content
     * @param consoleLayouts map of playlist file name to console layout file content
     * @param tunerState tuner settings or null when not included
     * @param preferences application preferences or null when not included
     */
    public ConfigurationBundle(ConfigurationManifest manifest, Map<String,byte[]> playlists,
                               Map<String,byte[]> consoleLayouts, TunerConfigurationState tunerState,
                               Map<String,Map<String,String>> preferences)
    {
        mManifest = manifest;
        mPlaylists = new LinkedHashMap<>(playlists);
        mConsoleLayouts = new LinkedHashMap<>(consoleLayouts);
        mTunerState = tunerState;
        mPreferences = preferences;
    }

    /**
     * Bundle manifest
     */
    public ConfigurationManifest getManifest()
    {
        return mManifest;
    }

    /**
     * Playlist file names in the bundle, in manifest order.
     */
    public List<String> getPlaylistNames()
    {
        return new ArrayList<>(mPlaylists.keySet());
    }

    /**
     * Playlist file content
     * @param name of the playlist file
     * @return content or null when the bundle doesn't contain the playlist
     */
    public byte[] getPlaylist(String name)
    {
        return mPlaylists.get(name);
    }

    /**
     * Console layout file content for the playlist
     * @param name of the playlist file
     * @return content or null when the bundle doesn't contain a console layout for the playlist
     */
    public byte[] getConsoleLayout(String name)
    {
        return mConsoleLayouts.get(name);
    }

    /**
     * Indicates if the bundle contains a console layout for the playlist
     */
    public boolean hasConsoleLayout(String name)
    {
        return mConsoleLayouts.containsKey(name);
    }

    /**
     * Playlist file name of the playlist that was current when the bundle was created, or null if that playlist is
     * not included in the bundle.
     */
    public String getCurrentPlaylist()
    {
        String current = mManifest.getCurrentPlaylist();
        return current != null && mPlaylists.containsKey(current) ? current : null;
    }

    /**
     * Indicates if the bundle contains tuner settings
     */
    public boolean hasTunerState()
    {
        return mTunerState != null;
    }

    /**
     * Tuner settings or null when not included
     */
    public TunerConfigurationState getTunerState()
    {
        return mTunerState;
    }

    /**
     * Indicates if the bundle contains application preferences
     */
    public boolean hasPreferences()
    {
        return mPreferences != null;
    }

    /**
     * Application preferences (node path to key/value map), or an empty map when not included.
     */
    public Map<String,Map<String,String>> getPreferences()
    {
        return mPreferences != null ? Collections.unmodifiableMap(mPreferences) : Map.of();
    }

    /**
     * Count of the preference values in the bundle
     */
    public int getPreferenceCount()
    {
        return PreferenceTransfer.count(getPreferences());
    }

    /**
     * Default bundle file name for the date: sdrtrunk-configuration-YYYY-MM-DD.zip
     */
    public static String getDefaultFileName(LocalDate date)
    {
        return DEFAULT_FILE_NAME_PREFIX + date.format(DateTimeFormatter.ISO_LOCAL_DATE) + FILE_EXTENSION;
    }

    /**
     * Creates a safe playlist file name from a playlist file name: a single file name (no directories) with only
     * letters, digits, spaces and the characters - _ . ( ) + , that doesn't start with a dot, with an .xml extension.
     * For example, 'My Playlist.xml' is unchanged, 'radio' becomes 'radio.xml' and '../x/y:z.xml' becomes
     * '___x_y_z.xml'.
     *
     * @param fileName of the playlist file
     * @return safe file name
     */
    public static String sanitizePlaylistName(String fileName)
    {
        String base = fileName != null ? fileName.trim() : "";

        if(base.toLowerCase().endsWith(PLAYLIST_EXTENSION))
        {
            base = base.substring(0, base.length() - PLAYLIST_EXTENSION.length());
        }

        StringBuilder sb = new StringBuilder();

        for(int x = 0; x < base.length(); x++)
        {
            char c = base.charAt(x);

            if(Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '.' || c == '(' ||
                c == ')' || c == '+' || c == ',')
            {
                sb.append(c);
            }
            else
            {
                sb.append('_');
            }
        }

        //No hidden files or relative directory names
        for(int x = 0; x < sb.length() && sb.charAt(x) == '.'; x++)
        {
            sb.setCharAt(x, '_');
        }

        String sanitized = sb.toString().trim();

        if(sanitized.length() > MAX_PLAYLIST_NAME_LENGTH)
        {
            sanitized = sanitized.substring(0, MAX_PLAYLIST_NAME_LENGTH).trim();
        }

        if(sanitized.isEmpty())
        {
            sanitized = DEFAULT_PLAYLIST_NAME;
        }

        return sanitized + PLAYLIST_EXTENSION;
    }

    /**
     * Indicates if the name is a safe playlist file name: it is unchanged by sanitizePlaylistName().
     */
    public static boolean isValidPlaylistName(String name)
    {
        return name != null && name.equals(sanitizePlaylistName(name));
    }

    /**
     * Console layout file name for the playlist file name, using the same naming rule as the console layout manager:
     * 'default.xml' uses 'default.console.json'.
     */
    public static String getConsoleLayoutName(String playlistName)
    {
        return ConsoleLayoutManager.getSidecarPath(Path.of(playlistName)).getFileName().toString();
    }

    /**
     * Bundle entry name for the playlist file
     */
    public static String getPlaylistEntryName(String playlistName)
    {
        return PLAYLIST_DIRECTORY + playlistName;
    }

    /**
     * Bundle entry name for the console layout file of the playlist
     */
    public static String getConsoleLayoutEntryName(String playlistName)
    {
        return PLAYLIST_DIRECTORY + getConsoleLayoutName(playlistName);
    }
}
