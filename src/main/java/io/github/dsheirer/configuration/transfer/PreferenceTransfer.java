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

import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.preference.duplicate.CallManagementPreference;
import io.github.dsheirer.preference.event.DecodeEventPreference;
import io.github.dsheirer.preference.identifier.TalkgroupFormatPreference;
import io.github.dsheirer.preference.mp3.MP3Preference;
import io.github.dsheirer.preference.playback.PlaybackPreference;
import io.github.dsheirer.preference.radioreference.RadioReferencePreference;
import io.github.dsheirer.preference.record.RecordPreference;
import io.github.dsheirer.preference.source.TunerPreference;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Exports and imports whitelisted application preferences (Java Preferences) for moving a setup to another computer.
 *
 * Only the preference nodes and keys listed in SECTIONS are exported and imported.  Preferences that are specific to a
 * computer are never transferred: folder locations, the JMBE library path, CPU (vector) calibration, window positions
 * and sizes, playlist file locations, viewer settings, recent files and the RadioReference login.
 *
 * Preferences are read from and written to a preferences root node that corresponds to the sdrtrunk package
 * (/io/github/dsheirer in the user preferences tree), with node paths that are relative to that root, for example
 * 'preference/application'.  The root is injected so that the transfer can be tested with an in-memory preferences
 * implementation.
 */
public final class PreferenceTransfer
{
    /**
     * Package that corresponds to the sdrtrunk preferences root node
     */
    public static final String ROOT_PACKAGE = "io.github.dsheirer";

    /**
     * Whitelisted preference sections (node and key filter).
     */
    public static final List<Section> SECTIONS = List.of(
        new Section("Application (theme, scale, channel auto-start)", nodePath(ApplicationPreference.class),
            key -> true),
        new Section("Audio playback (output device, channels, tones)", nodePath(PlaybackPreference.class),
            key -> true),
        new Section("Talkgroup and radio ID format", nodePath(TalkgroupFormatPreference.class), key -> true),
        new Section("Duplicate call management", nodePath(CallManagementPreference.class), key -> true),
        new Section("Decode event display", nodePath(DecodeEventPreference.class), key -> true),
        new Section("MP3 encoding", nodePath(MP3Preference.class), key -> true),
        new Section("Recording format", nodePath(RecordPreference.class), key -> true),
        //Tuner preferences and multi-frequency channel preferences (ChannelMultiFrequencyPreference) share a node
        new Section("Tuner channelizer and multi-frequency channels", nodePath(TunerPreference.class), key -> true),
        new Section("RadioReference selections (without login)", nodePath(RadioReferencePreference.class),
            key -> !RadioReferencePreference.USER_NAME.equals(key) && !RadioReferencePreference.PASSWORD.equals(key)),
        new Section("Recording and event log maximum disk usage", nodePath(DirectoryPreference.class),
            key -> DirectoryPreference.PREFERENCE_KEY_DIRECTORY_MAX_USAGE_RECORDINGS.equals(key) ||
                DirectoryPreference.PREFERENCE_KEY_DIRECTORY_MAX_USAGE_EVENT_LOGS.equals(key)));

    /**
     * Description of what isn't transferred, for display to the user.
     */
    public static final String NOT_INCLUDED = "Not included: folder locations, JMBE library, CPU calibration, " +
        "window positions, RadioReference login, recordings and event logs.";

    private PreferenceTransfer()
    {
    }

    /**
     * Preferences node path (relative to the sdrtrunk preferences root) that Preferences.userNodeForPackage() uses
     * for the class: the class package relative to ROOT_PACKAGE, with '/' separators.
     * @param preferenceClass to lookup
     * @return relative node path, for example 'preference/application'
     */
    public static String nodePath(Class<?> preferenceClass)
    {
        String packageName = preferenceClass.getPackageName();

        if(!packageName.startsWith(ROOT_PACKAGE + "."))
        {
            throw new IllegalArgumentException("Class [" + preferenceClass.getName() + "] is not in package [" +
                ROOT_PACKAGE + "]");
        }

        return packageName.substring(ROOT_PACKAGE.length() + 1).replace('.', '/');
    }

    /**
     * The sdrtrunk preferences root node in the user preferences tree (/io/github/dsheirer).
     *
     * Note: this is the real user preferences store - tests must use an in-memory root instead.
     */
    public static Preferences getUserPreferencesRoot()
    {
        return Preferences.userRoot().node("/" + ROOT_PACKAGE.replace('.', '/'));
    }

    /**
     * Finds the whitelisted section for the relative node path.
     * @param nodePath relative to the preferences root
     * @return section or null when the node isn't whitelisted
     */
    public static Section getSection(String nodePath)
    {
        for(Section section: SECTIONS)
        {
            if(section.nodePath().equals(nodePath))
            {
                return section;
            }
        }

        return null;
    }

    /**
     * Exports the whitelisted preferences that are set.  Nodes that don't exist are not created.
     * @param root preferences root node (see getUserPreferencesRoot())
     * @return map of relative node path to (sorted) key/value map, containing only nodes that have exported values.
     * @throws IOException if the preferences can't be read
     */
    public static Map<String,Map<String,String>> exportPreferences(Preferences root) throws IOException
    {
        Map<String,Map<String,String>> exported = new TreeMap<>();

        try
        {
            for(Section section: SECTIONS)
            {
                if(!root.nodeExists(section.nodePath()))
                {
                    continue;
                }

                Preferences node = root.node(section.nodePath());
                Map<String,String> values = new TreeMap<>();

                for(String key: node.keys())
                {
                    String value = node.get(key, null);

                    if(value != null && section.includes(key))
                    {
                        values.put(key, value);
                    }
                }

                if(!values.isEmpty())
                {
                    exported.put(section.nodePath(), values);
                }
            }
        }
        catch(BackingStoreException | IllegalStateException e)
        {
            throw new IOException("Unable to read application preferences", e);
        }

        return exported;
    }

    /**
     * Imports preferences into the whitelisted nodes.  Values for nodes or keys that are not whitelisted (for example
     * folder locations or the RadioReference login) are ignored and listed in the result.  Other existing values are
     * kept.
     *
     * Note: the application caches preference values, so imported values take effect when the application restarts.
     *
     * @param root preferences root node (see getUserPreferencesRoot())
     * @param preferences to import: relative node path to key/value map
     * @return import result
     * @throws IOException if the preferences can't be written
     */
    public static ImportResult importPreferences(Preferences root, Map<String,Map<String,String>> preferences)
        throws IOException
    {
        int imported = 0;
        List<String> ignored = new ArrayList<>();

        try
        {
            for(Map.Entry<String,Map<String,String>> entry: preferences.entrySet())
            {
                Section section = getSection(entry.getKey());

                if(section == null || entry.getValue() == null)
                {
                    ignored.add(entry.getKey());
                    continue;
                }

                Preferences node = null;

                for(Map.Entry<String,String> value: entry.getValue().entrySet())
                {
                    String key = value.getKey();

                    if(key == null || value.getValue() == null || !section.includes(key) ||
                        key.length() > Preferences.MAX_KEY_LENGTH ||
                        value.getValue().length() > Preferences.MAX_VALUE_LENGTH)
                    {
                        ignored.add(entry.getKey() + ":" + key);
                        continue;
                    }

                    if(node == null)
                    {
                        node = root.node(section.nodePath());
                    }

                    node.put(key, value.getValue());
                    imported++;
                }

                if(node != null)
                {
                    node.flush();
                }
            }
        }
        catch(BackingStoreException | IllegalStateException e)
        {
            throw new IOException("Unable to write application preferences", e);
        }

        return new ImportResult(imported, Collections.unmodifiableList(ignored));
    }

    /**
     * Count of the values in the preferences map
     */
    public static int count(Map<String,Map<String,String>> preferences)
    {
        int count = 0;

        for(Map<String,String> values: preferences.values())
        {
            count += values != null ? values.size() : 0;
        }

        return count;
    }

    /**
     * Relative node paths of all whitelisted sections
     */
    public static Set<String> getNodePaths()
    {
        Set<String> paths = new LinkedHashSet<>();

        for(Section section: SECTIONS)
        {
            paths.add(section.nodePath());
        }

        return paths;
    }

    /**
     * Whitelisted preferences node with a key filter.
     * @param label describing the preferences for the user
     * @param nodePath relative to the sdrtrunk preferences root
     * @param keyFilter that accepts the transferable keys
     */
    public record Section(String label, String nodePath, Predicate<String> keyFilter)
    {
        /**
         * Indicates if the key is transferable
         */
        public boolean includes(String key)
        {
            return keyFilter.test(key);
        }
    }

    /**
     * Preference import result
     * @param imported count of the imported values
     * @param ignored node paths and node:key values that were ignored because they are not whitelisted or invalid
     */
    public record ImportResult(int imported, List<String> ignored)
    {
    }
}
