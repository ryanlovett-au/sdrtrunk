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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.github.dsheirer.console.ConsoleLayoutManager;
import io.github.dsheirer.playlist.PlaylistV2;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Reads and validates a configuration bundle (zip) file.  The bundle is rejected when it isn't a configuration bundle,
 * was created by a newer (unsupported) bundle format, contains unexpected entries or entry names that could escape
 * the target directory (path traversal), is too large, or contains invalid playlist, console layout, tuner or
 * preference files.
 */
public final class ConfigurationBundleReader
{
    private static final int MAX_ENTRIES = 1000;
    private static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final Set<String> DIRECTORY_ENTRIES = Set.of(ConfigurationBundle.PLAYLIST_DIRECTORY,
        ConfigurationBundle.TUNER_DIRECTORY, ConfigurationBundle.PREFERENCES_DIRECTORY);

    private ConfigurationBundleReader()
    {
    }

    /**
     * Reads and validates the configuration bundle file.
     * @param path of the bundle (zip) file
     * @return validated bundle contents
     * @throws ConfigurationBundleException if the bundle is invalid or can't be read
     */
    public static ConfigurationBundle read(Path path) throws ConfigurationBundleException
    {
        if(path == null || !Files.isRegularFile(path))
        {
            throw new ConfigurationBundleException("Configuration file not found: " + path);
        }

        Map<String,byte[]> entries = new LinkedHashMap<>();

        try(ZipFile zipFile = new ZipFile(path.toFile()))
        {
            Enumeration<? extends ZipEntry> enumeration = zipFile.entries();
            long total = 0;
            int count = 0;

            while(enumeration.hasMoreElements())
            {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName();

                if(++count > MAX_ENTRIES)
                {
                    throw new ConfigurationBundleException("The configuration file contains too many files");
                }

                validateEntryName(name);

                if(entry.isDirectory())
                {
                    continue;
                }

                if(entries.containsKey(name))
                {
                    throw new ConfigurationBundleException("The configuration file contains duplicate file [" + name + "]");
                }

                byte[] content = readEntry(zipFile, entry);
                total += content.length;

                if(total > MAX_TOTAL_BYTES)
                {
                    throw new ConfigurationBundleException("The configuration file is too large");
                }

                entries.put(name, content);
            }
        }
        catch(ConfigurationBundleException cbe)
        {
            throw cbe;
        }
        catch(ZipException ze)
        {
            throw new ConfigurationBundleException("The file is not a valid sdrtrunk configuration (zip) file", ze);
        }
        catch(IOException ioe)
        {
            throw new ConfigurationBundleException("Unable to read the configuration file: " + ioe.getMessage(), ioe);
        }

        return parse(entries);
    }

    /**
     * Validates the entry name: rejects absolute paths, parent directory references and other path traversal, and any
     * entry that isn't part of the bundle format.
     * @param name of the zip entry
     * @throws ConfigurationBundleException if the name is invalid or unexpected
     */
    static void validateEntryName(String name) throws ConfigurationBundleException
    {
        if(name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":") ||
            name.contains("\0"))
        {
            throw new ConfigurationBundleException("The configuration file contains an invalid file name [" + name + "]");
        }

        for(String part: name.split("/"))
        {
            if(part.equals("..") || part.equals("."))
            {
                throw new ConfigurationBundleException("The configuration file contains an invalid file name [" +
                    name + "]");
            }
        }

        if(DIRECTORY_ENTRIES.contains(name) || name.equals(ConfigurationBundle.MANIFEST_ENTRY) || name.equals(ConfigurationBundle.TUNER_ENTRY) ||
            name.equals(ConfigurationBundle.PREFERENCES_ENTRY))
        {
            return;
        }

        if(name.startsWith(ConfigurationBundle.PLAYLIST_DIRECTORY))
        {
            String fileName = name.substring(ConfigurationBundle.PLAYLIST_DIRECTORY.length());

            if(!fileName.contains("/") && (ConfigurationBundle.isValidPlaylistName(fileName) ||
                isValidConsoleLayoutName(fileName)))
            {
                return;
            }
        }

        throw new ConfigurationBundleException("The configuration file contains an unexpected file [" + name + "]");
    }

    /**
     * Indicates if the file name is the console layout file name of a valid playlist file name.
     */
    private static boolean isValidConsoleLayoutName(String fileName)
    {
        String suffix = ConsoleLayoutManager.SIDECAR_FILE_SUFFIX;

        if(fileName.endsWith(suffix))
        {
            String playlist = fileName.substring(0, fileName.length() - suffix.length()) +
                ConfigurationBundle.PLAYLIST_EXTENSION;
            return ConfigurationBundle.isValidPlaylistName(playlist) &&
                ConfigurationBundle.getConsoleLayoutName(playlist).equals(fileName);
        }

        return false;
    }

    /**
     * Reads the entry content, enforcing the maximum entry size.
     */
    private static byte[] readEntry(ZipFile zipFile, ZipEntry entry) throws IOException
    {
        if(entry.getSize() > MAX_ENTRY_BYTES)
        {
            throw new ConfigurationBundleException("The configuration file contains a file that is too large [" +
                entry.getName() + "]");
        }

        try(InputStream in = zipFile.getInputStream(entry))
        {
            byte[] content = in.readNBytes((int)MAX_ENTRY_BYTES + 1);

            if(content.length > MAX_ENTRY_BYTES)
            {
                throw new ConfigurationBundleException("The configuration file contains a file that is too large [" +
                    entry.getName() + "]");
            }

            return content;
        }
    }

    /**
     * Parses and validates the bundle entries.
     * @param entries map of entry name to content
     * @return bundle
     * @throws ConfigurationBundleException if the bundle is invalid
     */
    static ConfigurationBundle parse(Map<String,byte[]> entries) throws ConfigurationBundleException
    {
        ObjectMapper jsonMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        byte[] manifestContent = entries.get(ConfigurationBundle.MANIFEST_ENTRY);

        if(manifestContent == null)
        {
            throw new ConfigurationBundleException("The file is not an sdrtrunk configuration file (no manifest)");
        }

        ConfigurationManifest manifest;

        try
        {
            manifest = jsonMapper.readValue(manifestContent, ConfigurationManifest.class);
        }
        catch(IOException ioe)
        {
            throw new ConfigurationBundleException("The configuration file manifest is invalid", ioe);
        }

        if(manifest == null || !ConfigurationManifest.FORMAT.equals(manifest.getFormat()))
        {
            throw new ConfigurationBundleException("The file is not an sdrtrunk configuration file");
        }

        if(manifest.getFormatVersion() < 1)
        {
            throw new ConfigurationBundleException("The configuration file format version is invalid");
        }

        if(manifest.getFormatVersion() > ConfigurationManifest.CURRENT_FORMAT_VERSION)
        {
            throw new ConfigurationBundleException("The configuration file was created by a newer version of " +
                "sdrtrunk (format version " + manifest.getFormatVersion() + ").  Please update sdrtrunk.");
        }

        Set<String> used = new HashSet<>();
        used.add(ConfigurationBundle.MANIFEST_ENTRY);

        //Playlists and console layouts
        Map<String,byte[]> playlists = new LinkedHashMap<>();
        Map<String,byte[]> consoleLayouts = new LinkedHashMap<>();
        ObjectMapper xmlMapper = createPlaylistMapper();

        for(ConfigurationManifest.PlaylistEntry playlistEntry: manifest.getPlaylists())
        {
            String name = playlistEntry != null ? playlistEntry.getName() : null;

            if(!ConfigurationBundle.isValidPlaylistName(name))
            {
                throw new ConfigurationBundleException("The configuration file contains an invalid playlist name [" +
                    name + "]");
            }

            if(playlists.containsKey(name))
            {
                throw new ConfigurationBundleException("The configuration file lists playlist [" + name + "] twice");
            }

            String entryName = ConfigurationBundle.getPlaylistEntryName(name);
            byte[] playlist = entries.get(entryName);

            if(playlist == null)
            {
                throw new ConfigurationBundleException("The configuration file is missing playlist [" + name + "]");
            }

            try
            {
                if(xmlMapper.readValue(playlist, PlaylistV2.class) == null)
                {
                    throw new IOException("Empty playlist");
                }
            }
            catch(IOException ioe)
            {
                throw new ConfigurationBundleException("Playlist [" + name + "] in the configuration file is not a " +
                    "valid playlist", ioe);
            }

            playlists.put(name, playlist);
            used.add(entryName);

            String layoutEntryName = ConfigurationBundle.getConsoleLayoutEntryName(name);
            byte[] layout = entries.get(layoutEntryName);

            if(layout != null)
            {
                try
                {
                    //The console layout manager tolerates layout content from other versions - check for an object
                    if(!jsonMapper.readTree(layout).isObject())
                    {
                        throw new IOException("Console layout is not a JSON object");
                    }
                }
                catch(IOException ioe)
                {
                    throw new ConfigurationBundleException("The console layout for playlist [" + name + "] in the " +
                        "configuration file is invalid", ioe);
                }

                consoleLayouts.put(name, layout);
                used.add(layoutEntryName);
            }
            else if(playlistEntry.isConsoleLayout())
            {
                throw new ConfigurationBundleException("The configuration file is missing the console layout for " +
                    "playlist [" + name + "]");
            }
        }

        //Tuner settings
        TunerConfigurationState tunerState = null;
        byte[] tunerContent = entries.get(ConfigurationBundle.TUNER_ENTRY);

        if(tunerContent != null)
        {
            try
            {
                tunerState = jsonMapper.readValue(tunerContent, TunerConfigurationState.class);
            }
            catch(IOException ioe)
            {
                throw new ConfigurationBundleException("The tuner settings in the configuration file are invalid", ioe);
            }

            if(tunerState == null)
            {
                throw new ConfigurationBundleException("The tuner settings in the configuration file are empty");
            }

            used.add(ConfigurationBundle.TUNER_ENTRY);
        }
        else if(manifest.isTuners())
        {
            throw new ConfigurationBundleException("The configuration file is missing the tuner settings");
        }

        //Application preferences
        Map<String,Map<String,String>> preferences = null;
        byte[] preferencesContent = entries.get(ConfigurationBundle.PREFERENCES_ENTRY);

        if(preferencesContent != null)
        {
            try
            {
                preferences = jsonMapper.readValue(preferencesContent,
                    new TypeReference<LinkedHashMap<String,Map<String,String>>>(){});
            }
            catch(IOException ioe)
            {
                throw new ConfigurationBundleException("The application preferences in the configuration file are " +
                    "invalid", ioe);
            }

            if(preferences == null)
            {
                throw new ConfigurationBundleException("The application preferences in the configuration file are " +
                    "empty");
            }

            used.add(ConfigurationBundle.PREFERENCES_ENTRY);
        }
        else if(manifest.isPreferences())
        {
            throw new ConfigurationBundleException("The configuration file is missing the application preferences");
        }

        //Reject files that the manifest doesn't account for (e.g. a playlist or console layout that isn't listed)
        for(String name: entries.keySet())
        {
            if(!used.contains(name))
            {
                throw new ConfigurationBundleException("The configuration file contains an unexpected file [" +
                    name + "]");
            }
        }

        return new ConfigurationBundle(manifest, playlists, consoleLayouts, tunerState, preferences);
    }

    /**
     * Creates the playlist XML mapper, configured as the playlist manager does for loading playlists.
     */
    static ObjectMapper createPlaylistMapper()
    {
        JacksonXmlModule xmlModule = new JacksonXmlModule();
        xmlModule.setDefaultUseWrapper(false);
        return new XmlMapper(xmlModule).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
