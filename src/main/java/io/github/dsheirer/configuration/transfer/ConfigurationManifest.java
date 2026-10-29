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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration bundle manifest (manifest.json): describes the contents of a configuration export bundle.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"format", "formatVersion", "created", "applicationVersion", "currentPlaylist", "playlists",
    "tuners", "preferences"})
public class ConfigurationManifest
{
    /**
     * Identifies a configuration bundle manifest
     */
    public static final String FORMAT = "sdrtrunk-configuration";

    /**
     * Current (and highest supported) bundle format version
     */
    public static final int CURRENT_FORMAT_VERSION = 1;

    private String mFormat = FORMAT;
    private int mFormatVersion = CURRENT_FORMAT_VERSION;
    private String mCreated;
    private String mApplicationVersion;
    private String mCurrentPlaylist;
    private List<PlaylistEntry> mPlaylists = new ArrayList<>();
    private boolean mTuners;
    private boolean mPreferences;

    /**
     * Constructs an instance (Jackson)
     */
    public ConfigurationManifest()
    {
    }

    /**
     * Bundle format identifier, expected to be FORMAT.
     */
    @JsonProperty("format")
    public String getFormat()
    {
        return mFormat;
    }

    /**
     * Sets the format identifier
     */
    public void setFormat(String format)
    {
        mFormat = format;
    }

    /**
     * Bundle format version
     */
    @JsonProperty("formatVersion")
    public int getFormatVersion()
    {
        return mFormatVersion;
    }

    /**
     * Sets the bundle format version
     */
    public void setFormatVersion(int formatVersion)
    {
        mFormatVersion = formatVersion;
    }

    /**
     * Timestamp (ISO-8601 with offset) when the bundle was created
     */
    @JsonProperty("created")
    public String getCreated()
    {
        return mCreated;
    }

    /**
     * Sets the creation timestamp
     */
    public void setCreated(String created)
    {
        mCreated = created;
    }

    /**
     * Version of the application that created the bundle, or null when not known.
     */
    @JsonProperty("applicationVersion")
    public String getApplicationVersion()
    {
        return mApplicationVersion;
    }

    /**
     * Sets the application version
     */
    public void setApplicationVersion(String applicationVersion)
    {
        mApplicationVersion = applicationVersion;
    }

    /**
     * Bundle file name of the playlist that was current when the bundle was created, or null when the current
     * playlist is not included.
     */
    @JsonProperty("currentPlaylist")
    public String getCurrentPlaylist()
    {
        return mCurrentPlaylist;
    }

    /**
     * Sets the current playlist name
     */
    public void setCurrentPlaylist(String currentPlaylist)
    {
        mCurrentPlaylist = currentPlaylist;
    }

    /**
     * Playlists included in the bundle
     */
    @JsonProperty("playlists")
    public List<PlaylistEntry> getPlaylists()
    {
        return mPlaylists;
    }

    /**
     * Sets the included playlists
     */
    public void setPlaylists(List<PlaylistEntry> playlists)
    {
        mPlaylists = playlists != null ? playlists : new ArrayList<>();
    }

    /**
     * Finds the playlist entry by bundle file name.
     * @param name of the playlist file
     * @return entry or null
     */
    @JsonIgnore
    public PlaylistEntry getPlaylist(String name)
    {
        for(PlaylistEntry entry: mPlaylists)
        {
            if(entry.getName() != null && entry.getName().equals(name))
            {
                return entry;
            }
        }

        return null;
    }

    /**
     * Indicates if the bundle includes tuner settings
     */
    @JsonProperty("tuners")
    public boolean isTuners()
    {
        return mTuners;
    }

    /**
     * Sets the tuner settings included flag
     */
    public void setTuners(boolean tuners)
    {
        mTuners = tuners;
    }

    /**
     * Indicates if the bundle includes application preferences
     */
    @JsonProperty("preferences")
    public boolean isPreferences()
    {
        return mPreferences;
    }

    /**
     * Sets the application preferences included flag
     */
    public void setPreferences(boolean preferences)
    {
        mPreferences = preferences;
    }

    /**
     * Playlist included in the bundle.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"name", "current", "consoleLayout"})
    public static class PlaylistEntry
    {
        private String mName;
        private boolean mCurrent;
        private boolean mConsoleLayout;

        /**
         * Constructs an instance (Jackson)
         */
        public PlaylistEntry()
        {
        }

        /**
         * Constructs an instance
         * @param name of the playlist file in the bundle (e.g. default.xml)
         * @param current indicates if this was the current playlist
         * @param consoleLayout indicates if the bundle includes the playlist's console layout file
         */
        public PlaylistEntry(String name, boolean current, boolean consoleLayout)
        {
            mName = name;
            mCurrent = current;
            mConsoleLayout = consoleLayout;
        }

        /**
         * Playlist file name in the bundle (e.g. default.xml)
         */
        @JsonProperty("name")
        public String getName()
        {
            return mName;
        }

        /**
         * Sets the playlist file name
         */
        public void setName(String name)
        {
            mName = name;
        }

        /**
         * Indicates if this was the current playlist when the bundle was created
         */
        @JsonProperty("current")
        public boolean isCurrent()
        {
            return mCurrent;
        }

        /**
         * Sets the current flag
         */
        public void setCurrent(boolean current)
        {
            mCurrent = current;
        }

        /**
         * Indicates if the bundle includes this playlist's console layout file
         */
        @JsonProperty("consoleLayout")
        public boolean isConsoleLayout()
        {
            return mConsoleLayout;
        }

        /**
         * Sets the console layout included flag
         */
        public void setConsoleLayout(boolean consoleLayout)
        {
            mConsoleLayout = consoleLayout;
        }
    }
}
