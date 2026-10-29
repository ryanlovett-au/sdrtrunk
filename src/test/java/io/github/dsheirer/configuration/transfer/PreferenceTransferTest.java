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
import io.github.dsheirer.preference.playback.PlaybackPreference;
import io.github.dsheirer.preference.radioreference.RadioReferencePreference;
import io.github.dsheirer.preference.source.ChannelMultiFrequencyPreference;
import io.github.dsheirer.preference.source.TunerPreference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the application preference whitelist export and import (in-memory preferences only).
 */
public class PreferenceTransferTest
{
    /**
     * Creates an in-memory preferences root with whitelisted and machine-specific values.
     */
    private static Preferences createSourceRoot()
    {
        Preferences root = new InMemoryPreferences();
        root.node("preference/application").put("ui.theme", "DARK");
        root.node("preference/application").putDouble("ui.gui.scale", 1.25);
        root.node("preference/playback").put("audio.playback.device.name", "MacBook Pro Speakers");
        root.node("preference/playback").putInt("audio.playback.channel.count", 2);
        root.node("preference/identifier").put("talkgroup.format.APCO25", "DECIMAL");
        root.node("preference/source").put("channelizer.type", "POLYPHASE");
        root.node("preference/source").putLong("rotation.delay", 2000);
        root.node("preference/radioreference").put(RadioReferencePreference.USER_NAME, "someone");
        root.node("preference/radioreference").put(RadioReferencePreference.PASSWORD, "secret");
        root.node("preference/radioreference").putInt("preferred.state", 7);
        root.node("preference/directory").put("directory.application.root", "/Users/someone/SDRTrunk");
        root.node("preference/directory").put("directory.playlist", "/Users/someone/SDRTrunk/playlist");
        root.node("preference/directory").putInt(DirectoryPreference.PREFERENCE_KEY_DIRECTORY_MAX_USAGE_RECORDINGS,
            500);
        root.node("preference/directory").putInt(DirectoryPreference.PREFERENCE_KEY_DIRECTORY_MAX_USAGE_EVENT_LOGS,
            50);
        root.node("preference/decoder").put("path.jmbe.library.1.0.0", "/Users/someone/jmbe.jar");
        root.node("preference/calibration").putBoolean("vector.enabled", true);
        root.node("vector/calibrate").put("FIR_FILTER", "VECTOR_256");
        root.node("preference/javafx").putDouble("stage.x.playlist", 100);
        root.node("preference/swing").putInt("sdrtrunk.x", 10);
        root.node("preference/playlist").put("playlist.path", "/Users/someone/SDRTrunk/playlist/default.xml");
        root.node("gui").putBoolean("broadcast.status.visible", true);
        root.node("gui/viewer").put("last.file", "/Users/someone/file.bits");
        return root;
    }

    @Test
    public void nodePathsAreDerivedFromPreferenceClasses()
    {
        assertEquals("preference/application", PreferenceTransfer.nodePath(ApplicationPreference.class));
        assertEquals("preference/playback", PreferenceTransfer.nodePath(PlaybackPreference.class));
        assertEquals(PreferenceTransfer.nodePath(TunerPreference.class),
            PreferenceTransfer.nodePath(ChannelMultiFrequencyPreference.class),
            "Tuner and multi-frequency channel preferences share a node");
        assertTrue(PreferenceTransfer.getNodePaths().contains("preference/source"));
        assertFalse(PreferenceTransfer.getNodePaths().contains("preference/decoder"));
        assertFalse(PreferenceTransfer.getNodePaths().contains("preference/calibration"));
        assertFalse(PreferenceTransfer.getNodePaths().contains("preference/javafx"));
        assertFalse(PreferenceTransfer.getNodePaths().contains("preference/swing"));
        assertFalse(PreferenceTransfer.getNodePaths().contains("preference/playlist"));
    }

    @Test
    public void exportIncludesOnlyWhitelistedValues() throws Exception
    {
        Map<String,Map<String,String>> exported = PreferenceTransfer.exportPreferences(createSourceRoot());

        assertEquals("DARK", exported.get("preference/application").get("ui.theme"));
        assertEquals("1.25", exported.get("preference/application").get("ui.gui.scale"));
        assertEquals("MacBook Pro Speakers", exported.get("preference/playback").get("audio.playback.device.name"));
        assertEquals("2", exported.get("preference/playback").get("audio.playback.channel.count"));
        assertEquals("DECIMAL", exported.get("preference/identifier").get("talkgroup.format.APCO25"));
        assertEquals("2000", exported.get("preference/source").get("rotation.delay"));

        //RadioReference selections without the login
        Map<String,String> radioReference = exported.get("preference/radioreference");
        assertEquals("7", radioReference.get("preferred.state"));
        assertFalse(radioReference.containsKey(RadioReferencePreference.USER_NAME));
        assertFalse(radioReference.containsKey(RadioReferencePreference.PASSWORD));

        //Directory: only the maximum usage values, no paths
        assertEquals(Map.of(DirectoryPreference.PREFERENCE_KEY_DIRECTORY_MAX_USAGE_RECORDINGS, "500",
            DirectoryPreference.PREFERENCE_KEY_DIRECTORY_MAX_USAGE_EVENT_LOGS, "50"),
            exported.get("preference/directory"));

        //Machine-specific nodes are not exported
        for(String node: new String[]{"preference/decoder", "preference/calibration", "vector/calibrate",
            "preference/javafx", "preference/swing", "preference/playlist", "gui", "gui/viewer"})
        {
            assertFalse(exported.containsKey(node), "Node should not be exported: " + node);
        }

        String all = exported.toString();
        assertFalse(all.contains("secret"));
        assertFalse(all.contains("/Users/someone"));
    }

    @Test
    public void exportDoesNotCreateMissingNodes() throws Exception
    {
        Preferences root = new InMemoryPreferences();
        assertTrue(PreferenceTransfer.exportPreferences(root).isEmpty());
        assertFalse(root.nodeExists("preference/application"));
    }

    @Test
    public void importRoundTrip() throws Exception
    {
        Map<String,Map<String,String>> exported = PreferenceTransfer.exportPreferences(createSourceRoot());
        Preferences target = new InMemoryPreferences();
        target.node("preference/application").put("channel.auto.start.timeout", "30");

        PreferenceTransfer.ImportResult result = PreferenceTransfer.importPreferences(target, exported);

        assertEquals(PreferenceTransfer.count(exported), result.imported());
        assertTrue(result.ignored().isEmpty());
        assertEquals("DARK", target.node("preference/application").get("ui.theme", null));
        assertEquals(1.25, target.node("preference/application").getDouble("ui.gui.scale", 0), 0.0001);
        assertEquals(2, target.node("preference/playback").getInt("audio.playback.channel.count", 0));
        assertEquals(500, target.node("preference/directory")
            .getInt(DirectoryPreference.PREFERENCE_KEY_DIRECTORY_MAX_USAGE_RECORDINGS, 0));
        assertEquals("30", target.node("preference/application").get("channel.auto.start.timeout", null),
            "Existing values that are not in the import are kept");
        assertEquals(PreferenceTransfer.exportPreferences(target).get("preference/playback"),
            exported.get("preference/playback"));
    }

    @Test
    public void importIgnoresUnknownAndExcludedValues() throws Exception
    {
        Map<String,Map<String,String>> imported = new LinkedHashMap<>();
        imported.put("preference/application", Map.of("ui.theme", "LIGHT"));
        imported.put("preference/radioreference", Map.of(RadioReferencePreference.USER_NAME, "someone",
            RadioReferencePreference.PASSWORD, "secret", "preferred.country", "1"));
        imported.put("preference/directory", Map.of("directory.playlist", "/elsewhere",
            DirectoryPreference.PREFERENCE_KEY_DIRECTORY_MAX_USAGE_EVENT_LOGS, "75"));
        imported.put("preference/decoder", Map.of("path.jmbe.library.1.0.0", "/elsewhere/jmbe.jar"));
        imported.put("preference/calibration", Map.of("vector.enabled", "false"));
        imported.put("preference/playlist", Map.of("playlist.path", "/elsewhere/default.xml"));
        imported.put("../outside", Map.of("key", "value"));
        imported.put("unknown/node", Map.of("key", "value"));

        Preferences target = new InMemoryPreferences();
        PreferenceTransfer.ImportResult result = PreferenceTransfer.importPreferences(target, imported);

        assertEquals(3, result.imported());
        assertEquals("LIGHT", target.node("preference/application").get("ui.theme", null));
        assertEquals("1", target.node("preference/radioreference").get("preferred.country", null));
        assertNull(target.node("preference/radioreference").get(RadioReferencePreference.USER_NAME, null));
        assertNull(target.node("preference/radioreference").get(RadioReferencePreference.PASSWORD, null));
        assertEquals("75", target.node("preference/directory")
            .get(DirectoryPreference.PREFERENCE_KEY_DIRECTORY_MAX_USAGE_EVENT_LOGS, null));
        assertNull(target.node("preference/directory").get("directory.playlist", null));
        assertFalse(target.nodeExists("preference/decoder"));
        assertFalse(target.nodeExists("preference/calibration"));
        assertFalse(target.nodeExists("preference/playlist"));
        assertFalse(target.nodeExists("unknown"));
        assertTrue(result.ignored().contains("preference/decoder"));
        assertTrue(result.ignored().contains("preference/radioreference:" + RadioReferencePreference.PASSWORD));
        assertTrue(result.ignored().contains("preference/directory:directory.playlist"));
    }
}
