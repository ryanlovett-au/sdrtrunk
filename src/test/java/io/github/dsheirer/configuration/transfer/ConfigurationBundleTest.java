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
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.playlist.PlaylistV2;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.configuration.DisabledTuner;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationState;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for configuration bundle export, validation and playlist import (temporary directories only).
 */
public class ConfigurationBundleTest
{
    private static final String LAYOUT_A = "{ \"version\" : 1, \"consoleModeEnabled\" : true, \"groups\" : [ ] }";
    private static final String LAYOUT_B = "{ \"version\" : 1, \"consoleModeEnabled\" : false, \"groups\" : [ ] }";
    private static final String VALID_MANIFEST = "{ \"format\" : \"sdrtrunk-configuration\", \"formatVersion\" : 1, " +
        "\"playlists\" : [ ] }";

    @TempDir
    Path mTempDir;

    /**
     * Writes a playlist file containing one alias, serialized as the playlist manager does.
     */
    private static void writePlaylist(Path path, String aliasName) throws Exception
    {
        Alias alias = new Alias(aliasName);
        alias.setAliasListName("Test List");
        PlaylistV2 playlist = new PlaylistV2();
        playlist.setAliases(new ArrayList<>(List.of(alias)));

        JacksonXmlModule xmlModule = new JacksonXmlModule();
        xmlModule.setDefaultUseWrapper(false);
        ObjectMapper objectMapper = new XmlMapper(xmlModule);
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        Files.createDirectories(path.getParent());

        try(OutputStream out = Files.newOutputStream(path))
        {
            objectMapper.writeValue(out, playlist);
        }
    }

    /**
     * Writes a zip file with the entries (name to content).
     */
    private Path writeZip(String fileName, Map<String,String> entries) throws Exception
    {
        Path zip = mTempDir.resolve(fileName);

        try(ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip)))
        {
            for(Map.Entry<String,String> entry: entries.entrySet())
            {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }

        return zip;
    }

    /**
     * Exports two playlists (one current, with a console layout) plus tuner settings and preferences.
     */
    private Path exportBundle(Path source) throws Exception
    {
        writePlaylist(source.resolve("default.xml"), "Fire Dispatch");
        Files.writeString(source.resolve("default.console.json"), LAYOUT_A);
        writePlaylist(source.resolve("other.xml"), "Police");

        TunerConfigurationState tunerState = new TunerConfigurationState();
        AirspyTunerConfiguration airspy = new AirspyTunerConfiguration("A1B2C3");
        airspy.setFrequencyCorrection(1.5);
        tunerState.getTunerConfigurations().add(airspy);
        tunerState.getDisabledTuners().add(new DisabledTuner(TunerClass.RTL2832, "0001"));

        Map<String,Map<String,String>> preferences = Map.of("preference/application", Map.of("ui.theme", "DARK"));

        Path bundle = mTempDir.resolve(ConfigurationBundle.getDefaultFileName(LocalDate.of(2026, 10, 29)));
        ConfigurationExporter.ExportResult result = ConfigurationExporter.export(bundle,
            List.of(new ConfigurationExporter.PlaylistSource(source.resolve("default.xml"), true),
                new ConfigurationExporter.PlaylistSource(source.resolve("other.xml"), false)),
            tunerState, preferences, "1.2.3", ZonedDateTime.now());

        assertEquals(List.of("default.xml", "other.xml"), result.playlists());
        assertEquals(1, result.consoleLayouts());
        assertEquals(1, result.tunerConfigurations());
        assertEquals(1, result.preferences());
        return bundle;
    }

    @Test
    public void defaultFileName()
    {
        assertEquals("sdrtrunk-configuration-2026-10-29.zip",
            ConfigurationBundle.getDefaultFileName(LocalDate.of(2026, 10, 29)));
    }

    @Test
    public void playlistNamesAreSanitized()
    {
        assertEquals("default.xml", ConfigurationBundle.sanitizePlaylistName("default.xml"));
        assertEquals("My Playlist (2).xml", ConfigurationBundle.sanitizePlaylistName("My Playlist (2).xml"));
        assertEquals("radio.xml", ConfigurationBundle.sanitizePlaylistName("radio"));
        assertEquals("___x_y_z.xml", ConfigurationBundle.sanitizePlaylistName("../x/y:z.xml"));
        assertEquals("_hidden.xml", ConfigurationBundle.sanitizePlaylistName(".hidden.xml"));
        assertEquals("playlist.xml", ConfigurationBundle.sanitizePlaylistName(""));
        assertTrue(ConfigurationBundle.isValidPlaylistName("default.xml"));
        assertFalse(ConfigurationBundle.isValidPlaylistName("../default.xml"));
        assertFalse(ConfigurationBundle.isValidPlaylistName("a/b.xml"));
        assertFalse(ConfigurationBundle.isValidPlaylistName(".."));
        assertFalse(ConfigurationBundle.isValidPlaylistName("default.console.json"));
        assertEquals("default.console.json", ConfigurationBundle.getConsoleLayoutName("default.xml"));
    }

    @Test
    public void exportAndReadRoundTrip() throws Exception
    {
        Path source = mTempDir.resolve("source");
        Path bundlePath = exportBundle(source);

        ConfigurationBundle bundle = ConfigurationBundleReader.read(bundlePath);

        assertEquals(ConfigurationManifest.CURRENT_FORMAT_VERSION, bundle.getManifest().getFormatVersion());
        assertEquals("1.2.3", bundle.getManifest().getApplicationVersion());
        assertNotNull(bundle.getManifest().getCreated());
        assertTrue(bundle.getManifest().isTuners());
        assertTrue(bundle.getManifest().isPreferences());
        assertEquals(List.of("default.xml", "other.xml"), bundle.getPlaylistNames());
        assertEquals("default.xml", bundle.getCurrentPlaylist());
        assertTrue(bundle.getManifest().getPlaylist("default.xml").isConsoleLayout());
        assertFalse(bundle.getManifest().getPlaylist("other.xml").isConsoleLayout());
        assertArrayEquals(Files.readAllBytes(source.resolve("default.xml")), bundle.getPlaylist("default.xml"));
        assertEquals(LAYOUT_A, new String(bundle.getConsoleLayout("default.xml"), StandardCharsets.UTF_8));
        assertNull(bundle.getConsoleLayout("other.xml"));
        assertEquals(1, bundle.getTunerState().getTunerConfigurations().size());
        assertEquals("A1B2C3", bundle.getTunerState().getTunerConfigurations().getFirst().getUniqueID());
        assertEquals(1.5, bundle.getTunerState().getTunerConfigurations().getFirst().getFrequencyCorrection());
        assertEquals(new DisabledTuner(TunerClass.RTL2832, "0001"), bundle.getTunerState().getDisabledTuners()
            .getFirst());
        assertEquals("DARK", bundle.getPreferences().get("preference/application").get("ui.theme"));
    }

    @Test
    public void exportExcludesUnselectedSections() throws Exception
    {
        Path source = mTempDir.resolve("source");
        writePlaylist(source.resolve("default.xml"), "Fire Dispatch");
        Path bundlePath = mTempDir.resolve("playlists-only.zip");
        ConfigurationExporter.export(bundlePath, List.of(new ConfigurationExporter.PlaylistSource(
            source.resolve("default.xml"), false)), null, null, null, ZonedDateTime.now());

        ConfigurationBundle bundle = ConfigurationBundleReader.read(bundlePath);
        assertFalse(bundle.hasTunerState());
        assertFalse(bundle.hasPreferences());
        assertNull(bundle.getCurrentPlaylist());
        assertNull(bundle.getManifest().getApplicationVersion());
    }

    @Test
    public void exportMakesPlaylistNamesUnique() throws Exception
    {
        writePlaylist(mTempDir.resolve("a").resolve("default.xml"), "A");
        writePlaylist(mTempDir.resolve("b").resolve("default.xml"), "B");
        Files.writeString(mTempDir.resolve("b").resolve("default.console.json"), LAYOUT_B);
        Path bundlePath = mTempDir.resolve("unique.zip");

        ConfigurationExporter.ExportResult result = ConfigurationExporter.export(bundlePath, List.of(
            new ConfigurationExporter.PlaylistSource(mTempDir.resolve("a").resolve("default.xml"), false),
            new ConfigurationExporter.PlaylistSource(mTempDir.resolve("b").resolve("default.xml"), true)),
            null, null, null, ZonedDateTime.now());

        assertEquals(List.of("default.xml", "default-2.xml"), result.playlists());
        ConfigurationBundle bundle = ConfigurationBundleReader.read(bundlePath);
        assertEquals("default-2.xml", bundle.getCurrentPlaylist());
        assertEquals(LAYOUT_B, new String(bundle.getConsoleLayout("default-2.xml"), StandardCharsets.UTF_8));
        assertFalse(bundle.hasConsoleLayout("default.xml"));
    }

    @Test
    public void importWritesPlaylistsAndConsoleLayouts() throws Exception
    {
        ConfigurationBundle bundle = ConfigurationBundleReader.read(exportBundle(mTempDir.resolve("source")));
        Path target = mTempDir.resolve("target");

        PlaylistImporter.Result result = PlaylistImporter.writePlaylists(bundle, bundle.getPlaylistNames(), target,
            "20261029_101500");

        assertEquals(2, result.written().size());
        assertTrue(result.backups().isEmpty());
        assertArrayEquals(bundle.getPlaylist("default.xml"), Files.readAllBytes(target.resolve("default.xml")));
        assertArrayEquals(bundle.getPlaylist("other.xml"), Files.readAllBytes(target.resolve("other.xml")));
        assertEquals(LAYOUT_A, Files.readString(target.resolve("default.console.json")));
        assertFalse(Files.exists(target.resolve("other.console.json")));

        try(Stream<Path> files = Files.list(target))
        {
            assertEquals(3, files.count(), "No temporary files are left behind");
        }
    }

    @Test
    public void importBacksUpReplacedFiles() throws Exception
    {
        ConfigurationBundle bundle = ConfigurationBundleReader.read(exportBundle(mTempDir.resolve("source")));
        Path target = mTempDir.resolve("target");
        writePlaylist(target.resolve("default.xml"), "Existing");
        Files.writeString(target.resolve("default.console.json"), LAYOUT_B);
        writePlaylist(target.resolve("other.xml"), "Existing Other");
        Files.writeString(target.resolve("other.console.json"), LAYOUT_B);
        Files.writeString(target.resolve("default.xml.lck"), "");
        byte[] existingDefault = Files.readAllBytes(target.resolve("default.xml"));

        assertEquals(List.of("default.xml", "other.xml"),
            PlaylistImporter.getExistingPlaylists(bundle.getPlaylistNames(), target));

        PlaylistImporter.Result result = PlaylistImporter.writePlaylists(bundle, bundle.getPlaylistNames(), target,
            "20261029_101500");

        //Replaced playlist and console layout files are backed up
        Path defaultBackup = target.resolve("default.xml.pre-import-20261029_101500");
        Path defaultLayoutBackup = target.resolve("default.console.json.pre-import-20261029_101500");
        Path otherLayoutBackup = target.resolve("other.console.json.pre-import-20261029_101500");
        assertTrue(result.backups().contains(defaultBackup));
        assertTrue(result.backups().contains(defaultLayoutBackup));
        assertTrue(result.backups().contains(otherLayoutBackup));
        assertEquals(4, result.backups().size());
        assertArrayEquals(existingDefault, Files.readAllBytes(defaultBackup));
        assertEquals(LAYOUT_B, Files.readString(defaultLayoutBackup));

        //Imported files replace the existing files
        assertArrayEquals(bundle.getPlaylist("default.xml"), Files.readAllBytes(target.resolve("default.xml")));
        assertEquals(LAYOUT_A, Files.readString(target.resolve("default.console.json")));

        //The bundle has no console layout for 'other' - the existing layout is moved aside
        assertFalse(Files.exists(target.resolve("other.console.json")));
        assertEquals(LAYOUT_B, Files.readString(otherLayoutBackup));

        //A stale lock file would cause the playlist manager to restore the backup over the imported playlist
        assertFalse(Files.exists(target.resolve("default.xml.lck")));

        //A second import on the same timestamp doesn't overwrite the first backup
        PlaylistImporter.Result second = PlaylistImporter.writePlaylists(bundle, List.of("default.xml"), target,
            "20261029_101500");
        assertTrue(second.backups().contains(target.resolve("default.xml.pre-import-20261029_101500-2")));
        assertArrayEquals(existingDefault, Files.readAllBytes(defaultBackup));
    }

    @Test
    public void importOnlySelectedPlaylists() throws Exception
    {
        ConfigurationBundle bundle = ConfigurationBundleReader.read(exportBundle(mTempDir.resolve("source")));
        Path target = mTempDir.resolve("target");

        PlaylistImporter.writePlaylists(bundle, List.of("other.xml"), target, "20261029_101500");

        assertTrue(Files.exists(target.resolve("other.xml")));
        assertFalse(Files.exists(target.resolve("default.xml")));
        assertFalse(Files.exists(target.resolve("default.console.json")));
    }

    @Test
    public void targetPathRejectsTraversal()
    {
        Path target = mTempDir.resolve("target");
        assertThrows(ConfigurationBundleException.class, () -> PlaylistImporter.getTargetPath(target, "../x.xml"));
        assertThrows(ConfigurationBundleException.class, () -> PlaylistImporter.getTargetPath(target, "a/b.xml"));
        assertThrows(ConfigurationBundleException.class, () -> PlaylistImporter.getTargetPath(target, ".."));
    }

    @Test
    public void rejectsPathTraversalAndUnexpectedEntries() throws Exception
    {
        String[] invalid = {"../evil.xml", "playlists/../../evil.xml", "/etc/evil.xml", "playlists/sub/evil.xml",
            "playlists\\..\\evil.xml", "C:/evil.xml", "playlists/./default.xml", "notes.txt", "playlists/evil.sh",
            "tuners/other.json", "preferences/../manifest.json"};

        for(String name: invalid)
        {
            Map<String,String> entries = new LinkedHashMap<>();
            entries.put(ConfigurationBundle.MANIFEST_ENTRY, VALID_MANIFEST);
            entries.put(name, "content");
            Path zip = writeZip("invalid.zip", entries);
            assertThrows(ConfigurationBundleException.class, () -> ConfigurationBundleReader.read(zip),
                "Entry should be rejected: " + name);
        }
    }

    @Test
    public void rejectsPlaylistsThatAreNotInTheManifest() throws Exception
    {
        Path source = mTempDir.resolve("source");
        writePlaylist(source.resolve("default.xml"), "A");
        Map<String,String> entries = new LinkedHashMap<>();
        entries.put(ConfigurationBundle.MANIFEST_ENTRY, VALID_MANIFEST);
        entries.put("playlists/default.xml", Files.readString(source.resolve("default.xml")));
        Path zip = writeZip("unlisted.zip", entries);
        assertThrows(ConfigurationBundleException.class, () -> ConfigurationBundleReader.read(zip));
    }

    @Test
    public void rejectsInvalidBundles() throws Exception
    {
        //Not a zip file
        Path text = mTempDir.resolve("text.zip");
        Files.writeString(text, "not a zip file");
        assertThrows(ConfigurationBundleException.class, () -> ConfigurationBundleReader.read(text));

        //No manifest
        Path noManifest = writeZip("no-manifest.zip", Map.of("tuners/tuner_configuration.json", "{}"));
        assertThrows(ConfigurationBundleException.class, () -> ConfigurationBundleReader.read(noManifest));

        //Another format
        Path otherFormat = writeZip("other-format.zip", Map.of(ConfigurationBundle.MANIFEST_ENTRY,
            "{ \"format\" : \"something-else\", \"formatVersion\" : 1 }"));
        assertThrows(ConfigurationBundleException.class, () -> ConfigurationBundleReader.read(otherFormat));

        //Newer format version
        Path newer = writeZip("newer.zip", Map.of(ConfigurationBundle.MANIFEST_ENTRY,
            "{ \"format\" : \"sdrtrunk-configuration\", \"formatVersion\" : 99 }"));
        ConfigurationBundleException exception = assertThrows(ConfigurationBundleException.class,
            () -> ConfigurationBundleReader.read(newer));
        assertTrue(exception.getMessage().contains("newer version"));

        //Manifest lists a playlist with an unsafe name
        Path unsafeName = writeZip("unsafe-name.zip", Map.of(ConfigurationBundle.MANIFEST_ENTRY,
            "{ \"format\" : \"sdrtrunk-configuration\", \"formatVersion\" : 1, \"playlists\" : [ { \"name\" : " +
                "\"../evil.xml\" } ] }"));
        assertThrows(ConfigurationBundleException.class, () -> ConfigurationBundleReader.read(unsafeName));

        //Manifest lists a playlist that is missing
        Path missing = writeZip("missing.zip", Map.of(ConfigurationBundle.MANIFEST_ENTRY,
            "{ \"format\" : \"sdrtrunk-configuration\", \"formatVersion\" : 1, \"playlists\" : [ { \"name\" : " +
                "\"default.xml\" } ] }"));
        assertThrows(ConfigurationBundleException.class, () -> ConfigurationBundleReader.read(missing));

        //Playlist is not a playlist
        Map<String,String> entries = new LinkedHashMap<>();
        entries.put(ConfigurationBundle.MANIFEST_ENTRY, "{ \"format\" : \"sdrtrunk-configuration\", " +
            "\"formatVersion\" : 1, \"playlists\" : [ { \"name\" : \"default.xml\" } ] }");
        entries.put("playlists/default.xml", "<<< not xml");
        Path notPlaylist = writeZip("not-playlist.zip", entries);
        assertThrows(ConfigurationBundleException.class, () -> ConfigurationBundleReader.read(notPlaylist));

        //Preferences with nested values
        Path badPreferences = writeZip("bad-preferences.zip", Map.of(ConfigurationBundle.MANIFEST_ENTRY,
            VALID_MANIFEST, ConfigurationBundle.PREFERENCES_ENTRY, "{ \"preference/application\" : { \"a\" : " +
                "{ \"b\" : 1 } } }"));
        assertThrows(ConfigurationBundleException.class, () -> ConfigurationBundleReader.read(badPreferences));
    }

    @Test
    public void readsMinimalBundle() throws Exception
    {
        Path zip = writeZip("minimal.zip", Map.of(ConfigurationBundle.MANIFEST_ENTRY, VALID_MANIFEST));
        ConfigurationBundle bundle = ConfigurationBundleReader.read(zip);
        assertTrue(bundle.getPlaylistNames().isEmpty());
        assertFalse(bundle.hasTunerState());
        assertFalse(bundle.hasPreferences());
        assertEquals(0, bundle.getPreferenceCount());
    }
}
