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
package io.github.dsheirer.alias;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.playlist.PlaylistUpdater;
import io.github.dsheirer.playlist.PlaylistV2;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the alias persistent unique identifier (UUID).
 */
public class AliasUuidTest
{
    /**
     * Creates an XML mapper configured the same as the playlist manager.
     */
    private static ObjectMapper createMapper()
    {
        JacksonXmlModule xmlModule = new JacksonXmlModule();
        xmlModule.setDefaultUseWrapper(false);
        return new XmlMapper(xmlModule).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static Alias createAlias(String name)
    {
        Alias alias = new Alias(name);
        alias.setAliasListName("Test List");
        alias.setGroup("Test Group");
        alias.addAliasID(new Talkgroup(Protocol.APCO25, 100));
        return alias;
    }

    @Test
    void newAliasHasUuid()
    {
        Alias alias = new Alias("Test");
        String uuid = alias.getUuid();
        assertNotNull(uuid, "Expected generated uuid");
        assertEquals(uuid, alias.getUuid(), "Expected uuid to be stable across invocations");
        assertNotEquals(uuid, new Alias("Test").getUuid(), "Expected each alias to have a distinct uuid");
    }

    @Test
    void uuidSerializedAsXmlAttributeAndRestored() throws Exception
    {
        Alias alias = createAlias("Fire Dispatch");
        String uuid = alias.getUuid();
        PlaylistV2 playlist = new PlaylistV2();
        playlist.setVersion(PlaylistManager.PLAYLIST_CURRENT_VERSION);
        playlist.setAliases(List.of(alias));

        ObjectMapper mapper = createMapper();
        String xml = mapper.writeValueAsString(playlist);
        assertTrue(xml.contains("uuid=\"" + uuid + "\""), "Expected uuid attribute in XML: " + xml);

        PlaylistV2 loaded = mapper.readValue(xml, PlaylistV2.class);
        Alias loadedAlias = loaded.getAliases().getFirst();
        assertTrue(loadedAlias.hasUuid(), "Expected loaded alias to have uuid");
        assertEquals(uuid, loadedAlias.getUuid(), "Expected uuid to be restored");
        assertEquals(1, loadedAlias.getAliasIdentifiers().size(), "Expected alias identifiers to be restored");
        assertFalse(PlaylistUpdater.assignAliasUniqueIdentifiers(loaded), "Expected no uuid updates required");
    }

    @Test
    void legacyAliasWithoutUuidIsAssignedUuidOnLoad() throws Exception
    {
        //Serialize a current alias and strip the uuid attribute to simulate a playlist created before alias uuids
        PlaylistV2 playlist = new PlaylistV2();
        playlist.setVersion(PlaylistManager.PLAYLIST_CURRENT_VERSION);
        playlist.setAliases(List.of(createAlias("Alias 1"), createAlias("Alias 2")));

        ObjectMapper mapper = createMapper();
        String xml = mapper.writeValueAsString(playlist).replaceAll(" uuid=\"[^\"]*\"", "");
        assertFalse(xml.contains("uuid"), "Test setup - expected uuid attribute to be removed");

        PlaylistV2 loaded = mapper.readValue(xml, PlaylistV2.class);
        assertEquals(2, loaded.getAliases().size(), "Expected legacy aliases to load");

        for(Alias alias: loaded.getAliases())
        {
            assertFalse(alias.hasUuid(), "Expected legacy alias to load without a uuid");
        }

        assertTrue(PlaylistUpdater.update(loaded), "Expected playlist update (save) to be required");

        Alias alias1 = loaded.getAliases().get(0);
        Alias alias2 = loaded.getAliases().get(1);
        assertTrue(alias1.hasUuid(), "Expected uuid to be assigned");
        assertTrue(alias2.hasUuid(), "Expected uuid to be assigned");
        assertNotEquals(alias1.getUuid(), alias2.getUuid(), "Expected distinct uuids");

        //Second pass should not require a save
        assertFalse(PlaylistUpdater.update(loaded), "Expected no further updates required");

        //Assigned uuids should be persisted on save
        String saved = mapper.writeValueAsString(loaded);
        assertTrue(saved.contains("uuid=\"" + alias1.getUuid() + "\""), "Expected assigned uuid to be saved");
    }

    @Test
    void minimalLegacyAliasXmlLoads() throws Exception
    {
        String xml = "<playlist version=\"4\"><alias name=\"Legacy\" list=\"List\" group=\"Group\" color=\"0\"/></playlist>";
        PlaylistV2 loaded = createMapper().readValue(xml, PlaylistV2.class);
        assertEquals(1, loaded.getAliases().size(), "Expected alias to load");
        assertFalse(loaded.getAliases().getFirst().hasUuid(), "Expected no uuid");
        assertTrue(PlaylistUpdater.update(loaded), "Expected update to be required");
        assertTrue(loaded.getAliases().getFirst().hasUuid(), "Expected uuid to be assigned");
    }

    @Test
    void duplicateUuidsAreReassignedOnLoad()
    {
        Alias alias1 = createAlias("Alias 1");
        Alias alias2 = createAlias("Alias 2");
        alias2.setUuid(alias1.getUuid());
        String original = alias1.getUuid();

        PlaylistV2 playlist = new PlaylistV2();
        playlist.setVersion(PlaylistManager.PLAYLIST_CURRENT_VERSION);
        playlist.setAliases(List.of(alias1, alias2));

        assertTrue(PlaylistUpdater.assignAliasUniqueIdentifiers(playlist), "Expected duplicate to be corrected");
        assertEquals(original, alias1.getUuid(), "Expected first alias to retain uuid");
        assertNotEquals(original, alias2.getUuid(), "Expected duplicate alias to get new uuid");
    }

    @Test
    void aliasCopyGetsNewUuid()
    {
        Alias original = createAlias("Original");
        String uuid = original.getUuid();

        Alias deepCopy = AliasFactory.copyOf(original);
        Alias shallowCopy = AliasFactory.shallowCopyOf(original);

        assertEquals(original.getName(), deepCopy.getName(), "Expected name to be copied");
        assertEquals(1, deepCopy.getAliasIdentifiers().size(), "Expected identifiers to be copied");
        assertNotEquals(uuid, deepCopy.getUuid(), "Expected deep copy to have a new uuid");
        assertNotEquals(uuid, shallowCopy.getUuid(), "Expected shallow copy to have a new uuid");
        assertNotEquals(deepCopy.getUuid(), shallowCopy.getUuid(), "Expected copies to have distinct uuids");
        assertEquals(uuid, original.getUuid(), "Expected original uuid to be unchanged");
    }

    @Test
    void aliasModelLookupByUuid()
    {
        AliasModel model = new AliasModel();
        Alias alias1 = createAlias("Alias 1");
        Alias alias2 = createAlias("Alias 2");
        model.addAliases(List.of(alias1, alias2));

        assertSame(alias1, model.getAliasByUuid(alias1.getUuid()), "Expected lookup to find alias 1");
        assertSame(alias2, model.getAliasByUuid(alias2.getUuid()), "Expected lookup to find alias 2");
        assertNull(model.getAliasByUuid("not-a-uuid"), "Expected no match");
        assertNull(model.getAliasByUuid(null), "Expected no match for null");

        model.removeAlias(alias1);
        assertNull(model.getAliasByUuid(alias1.getUuid()), "Expected removed alias to not be found");
    }

    @Test
    void aliasModelReassignsDuplicateUuidOnAdd()
    {
        AliasModel model = new AliasModel();
        Alias alias1 = createAlias("Alias 1");
        model.addAlias(alias1);

        Alias duplicate = createAlias("Duplicate");
        duplicate.setUuid(alias1.getUuid());
        model.addAlias(duplicate);

        assertNotEquals(alias1.getUuid(), duplicate.getUuid(), "Expected duplicate to get a new uuid");
        assertSame(alias1, model.getAliasByUuid(alias1.getUuid()), "Expected original alias to retain uuid");
        assertSame(duplicate, model.getAliasByUuid(duplicate.getUuid()), "Expected duplicate alias to be found");
    }
}
