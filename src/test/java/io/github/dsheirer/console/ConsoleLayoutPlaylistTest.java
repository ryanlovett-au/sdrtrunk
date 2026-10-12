/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
package io.github.dsheirer.console;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for console layouts per playlist (sidecar files and legacy migration) and for alias re-binding by signature.
 */
public class ConsoleLayoutPlaylistTest
{
    private static final String LIST = "Test List";

    @TempDir
    Path mTempDir;

    private static Alias createAlias(String list, String name, Integer talkgroup)
    {
        Alias alias = new Alias(name);
        alias.setAliasListName(list);

        if(talkgroup != null)
        {
            alias.addAliasID(new Talkgroup(Protocol.APCO25, talkgroup));
        }

        return alias;
    }

    /**
     * Writes a layout file with one group that contains the cards (alias uuid and optional signature JSON).
     */
    private static void writeLayout(Path path, String... cards) throws Exception
    {
        StringBuilder sb = new StringBuilder();
        sb.append("{ \"version\" : 1, \"groups\" : [ { \"id\" : \"g1\", \"name\" : \"Fire\", \"displayOrder\" : 0, ");
        sb.append("\"cards\" : [ ");

        for(int x = 0; x < cards.length; x++)
        {
            if(x > 0)
            {
                sb.append(", ");
            }

            sb.append(cards[x]);
        }

        sb.append(" ] } ] }");
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String card(String id, String aliasUuid, String list, String name, Integer talkgroup)
    {
        return "{ \"id\" : \"" + id + "\", \"aliasUuid\" : \"" + aliasUuid + "\", \"signature\" : { " +
            "\"aliasListName\" : \"" + list + "\", \"aliasName\" : \"" + name + "\"" +
            (talkgroup != null ? ", \"protocol\" : \"APCO25\", \"talkgroup\" : " + talkgroup : "") + " } }";
    }

    @Test
    void sidecarPath()
    {
        assertEquals(Path.of("/path/foo.console.json"), ConsoleLayoutManager.getSidecarPath(Path.of("/path/foo.xml")),
            "Extension replaced");
        assertEquals(Path.of("/path/foo.bar.console.json"),
            ConsoleLayoutManager.getSidecarPath(Path.of("/path/foo.bar.xml")), "Only last extension replaced");
        assertEquals(Path.of("/path/foo.console.json"), ConsoleLayoutManager.getSidecarPath(Path.of("/path/foo")),
            "Suffix appended without extension");
        assertEquals(Path.of("/path/.hidden.console.json"),
            ConsoleLayoutManager.getSidecarPath(Path.of("/path/.hidden")), "Leading dot is not an extension");
        assertNull(ConsoleLayoutManager.getSidecarPath(null), "Null playlist");
    }

    @Test
    void legacyLayoutMigratedOnce() throws Exception
    {
        Path legacy = mTempDir.resolve(ConsoleLayoutManager.CONSOLE_DIRECTORY)
            .resolve(ConsoleLayoutManager.LAYOUT_FILE_NAME);
        Files.createDirectories(legacy.getParent());
        writeLayout(legacy, card("c1", "uuid-1", LIST, "Fire Dispatch", 100));
        Path migrated = legacy.resolveSibling(ConsoleLayoutManager.LAYOUT_FILE_NAME +
            ConsoleLayoutManager.MIGRATED_FILE_SUFFIX);

        Path playlistA = ConsoleLayoutManager.getSidecarPath(mTempDir.resolve("a.xml"));
        Path playlistB = ConsoleLayoutManager.getSidecarPath(mTempDir.resolve("b.xml"));

        assertTrue(ConsoleLayoutManager.migrateLegacyLayout(legacy, playlistA), "Expected legacy layout adopted");
        assertTrue(Files.exists(playlistA), "Expected sidecar created");
        assertFalse(Files.exists(legacy), "Expected legacy file renamed");
        assertTrue(Files.exists(migrated), "Expected migrated file");

        ConsoleLayoutManager manager = new ConsoleLayoutManager();
        manager.load(playlistA);
        assertEquals(1, manager.getLayout().getCardCount(), "Expected adopted layout");

        //Migration only happens once
        assertFalse(ConsoleLayoutManager.migrateLegacyLayout(legacy, playlistB), "Expected no second migration");
        assertFalse(Files.exists(playlistB), "Expected no sidecar for second playlist");
    }

    @Test
    void legacyLayoutNotAdoptedWhenSidecarExists() throws Exception
    {
        Path legacy = mTempDir.resolve(ConsoleLayoutManager.LAYOUT_FILE_NAME);
        writeLayout(legacy, card("c1", "uuid-1", LIST, "Fire Dispatch", 100));
        Path sidecar = mTempDir.resolve("a.console.json");
        writeLayout(sidecar);

        assertFalse(ConsoleLayoutManager.migrateLegacyLayout(legacy, sidecar), "Expected sidecar retained");
        assertFalse(Files.exists(legacy), "Expected legacy file renamed");

        ConsoleLayoutManager manager = new ConsoleLayoutManager();
        manager.load(sidecar);
        assertEquals(0, manager.getLayout().getCardCount(), "Expected existing sidecar layout");
    }

    @Test
    void playlistSwitchSavesPendingChangesAndLoadsNewLayout() throws Exception
    {
        Path layoutA = mTempDir.resolve("a.console.json");
        Path layoutB = mTempDir.resolve("b.console.json");
        ConsoleLayoutManager manager = new ConsoleLayoutManager();
        List<ConsoleLayoutEvent.Type> events = new ArrayList<>();
        manager.addListener(event -> events.add(event.getType()));

        manager.load(layoutA);
        ConsoleGroup group = manager.addGroup("Fire");
        manager.addCard(group.getId(), "uuid-1");
        assertTrue(manager.isSavePending(), "Expected pending save");

        manager.load(layoutB);
        assertTrue(Files.exists(layoutA), "Expected pending changes saved to the previous playlist layout");
        assertFalse(Files.exists(layoutB), "Expected no file for the new (empty) layout");
        assertEquals(layoutB, manager.getLayoutPath(), "Layout path");
        assertEquals(0, manager.getLayout().getCardCount(), "Expected empty layout for new playlist");

        //Changes to the new layout are saved to the new playlist's file only
        manager.addGroup("Police");
        manager.saveNow();
        assertTrue(Files.exists(layoutB), "Expected new layout saved");

        manager.load(layoutA);
        assertEquals(1, manager.getLayout().getCardCount(), "Expected original layout restored");
        assertEquals("Fire", manager.getLayout().getGroups().getFirst().getName(), "Group name");
        assertEquals(3, events.stream().filter(type -> type == ConsoleLayoutEvent.Type.LAYOUT_LOADED).count(),
            "Expected LAYOUT_LOADED for each load");
    }

    @Test
    void addCardCapturesSignature()
    {
        AliasModel aliasModel = new AliasModel();
        Alias alias = createAlias(LIST, "Fire Dispatch", 100);
        aliasModel.addAlias(alias);

        ConsoleLayoutManager manager = new ConsoleLayoutManager(mTempDir.resolve("a.console.json"));
        manager.setAliasModel(aliasModel);
        manager.load();
        ConsoleGroup group = manager.addGroup("Fire");
        ConsoleCard card = manager.addCard(group.getId(), alias.getUuid());

        AliasSignature signature = card.getSignature();
        assertNotNull(signature, "Expected signature");
        assertEquals(LIST, signature.getAliasListName(), "List");
        assertEquals("Fire Dispatch", signature.getAliasName(), "Name");
        assertEquals("APCO25", signature.getProtocol(), "Protocol");
        assertEquals(100, signature.getTalkgroup(), "Talkgroup");
        assertTrue(manager.isCardLinked(card.getId()), "Expected linked card");

        //Signature survives a JSON round trip
        manager.saveNow();
        ConsoleLayoutManager reloaded = new ConsoleLayoutManager(mTempDir.resolve("a.console.json"));
        reloaded.load();
        assertEquals(signature, reloaded.getCard(card.getId()).getSignature(), "Signature round trip");
        assertFalse(reloaded.isCardLinked(card.getId()), "Expected unlinked without an alias model");
    }

    @Test
    void loadRefreshesSignatureOfLinkedCard() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        Alias alias = createAlias(LIST, "Renamed Dispatch", 101);
        aliasModel.addAlias(alias);

        Path path = mTempDir.resolve("a.console.json");
        writeLayout(path, card("c1", alias.getUuid(), LIST, "Fire Dispatch", 100));

        ConsoleLayoutManager manager = new ConsoleLayoutManager(path);
        manager.setAliasModel(aliasModel);
        manager.load();

        AliasSignature signature = manager.getCard("c1").getSignature();
        assertEquals("Renamed Dispatch", signature.getAliasName(), "Expected refreshed alias name");
        assertEquals(101, signature.getTalkgroup(), "Expected refreshed talkgroup");
        assertEquals(alias.getUuid(), manager.getCard("c1").getAliasUuid(), "Alias uuid unchanged");
        assertTrue(manager.isSavePending(), "Expected refreshed signature to be saved");
        manager.saveNow();
    }

    @Test
    void rebindByListNameAndTalkgroup() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        //Same name in another list and same talkgroup with another name - only one alias matches all three
        aliasModel.addAlias(createAlias("Other List", "Fire Dispatch", 100));
        aliasModel.addAlias(createAlias(LIST, "Fire Tac", 100));
        Alias expected = createAlias(LIST, "Fire Dispatch", 100);
        aliasModel.addAlias(expected);

        Path path = mTempDir.resolve("a.console.json");
        writeLayout(path, card("c1", "missing-uuid", LIST, "Fire Dispatch", 100));

        ConsoleLayoutManager manager = new ConsoleLayoutManager(path);
        manager.setAliasModel(aliasModel);
        manager.load();

        assertEquals(expected.getUuid(), manager.getCard("c1").getAliasUuid(), "Expected re-bound card");
        assertTrue(manager.isCardLinked("c1"), "Expected linked card");
        assertTrue(manager.isSavePending(), "Expected re-bound layout to be saved");
        manager.saveNow();
        assertTrue(Files.readString(path).contains(expected.getUuid()), "Expected saved re-bound uuid");
    }

    @Test
    void rebindFallsBackToListAndNameThenListAndTalkgroup() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        Alias byName = createAlias(LIST, "Fire Dispatch", 555);
        aliasModel.addAlias(byName);
        Alias byTalkgroup = createAlias(LIST, "Police Renamed", 200);
        aliasModel.addAlias(byTalkgroup);

        Path path = mTempDir.resolve("a.console.json");
        writeLayout(path, card("c1", "missing-1", LIST, "Fire Dispatch", 100),
            card("c2", "missing-2", LIST, "Police Dispatch", 200));

        ConsoleLayoutManager manager = new ConsoleLayoutManager(path);
        manager.setAliasModel(aliasModel);
        manager.load();

        assertEquals(byName.getUuid(), manager.getCard("c1").getAliasUuid(), "Expected list+name match");
        assertEquals(byTalkgroup.getUuid(), manager.getCard("c2").getAliasUuid(), "Expected list+talkgroup match");
        manager.saveNow();
    }

    @Test
    void ambiguousOrMissingSignatureLeavesCardUnlinked() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        aliasModel.addAlias(createAlias(LIST, "Fire Dispatch", 100));
        aliasModel.addAlias(createAlias(LIST, "Fire Dispatch", 100));

        Path path = mTempDir.resolve("a.console.json");
        writeLayout(path, card("c1", "missing-1", LIST, "Fire Dispatch", 100),
            card("c2", "missing-2", LIST, "Nobody", 999),
            "{ \"id\" : \"c3\", \"aliasUuid\" : \"missing-3\" }");

        ConsoleLayoutManager manager = new ConsoleLayoutManager(path);
        manager.setAliasModel(aliasModel);
        manager.load();

        assertEquals(3, manager.getLayout().getCardCount(), "Expected unlinked cards to be kept");
        assertEquals("missing-1", manager.getCard("c1").getAliasUuid(), "Ambiguous card unchanged");
        assertEquals("missing-2", manager.getCard("c2").getAliasUuid(), "Unmatched card unchanged");
        assertEquals("missing-3", manager.getCard("c3").getAliasUuid(), "Card without signature unchanged");
        assertFalse(manager.isCardLinked("c1"), "Expected unlinked card");
        assertFalse(manager.isCardLinked("c2"), "Expected unlinked card");
        assertFalse(manager.isCardLinked("c3"), "Expected unlinked card");
        assertFalse(manager.isCardLinked("no-such-card"), "Expected false for unknown card");
        assertFalse(manager.isSavePending(), "Expected no save when nothing changed");
    }

    @Test
    void neverRebindToAliasThatAlreadyHasACard() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        Alias alias = createAlias(LIST, "Fire Dispatch", 100);
        aliasModel.addAlias(alias);

        Path path = mTempDir.resolve("a.console.json");
        writeLayout(path, card("c1", "missing-1", LIST, "Fire Dispatch", 100),
            card("c2", alias.getUuid(), LIST, "Fire Dispatch", 100));

        ConsoleLayoutManager manager = new ConsoleLayoutManager(path);
        manager.setAliasModel(aliasModel);
        manager.load();

        assertEquals("missing-1", manager.getCard("c1").getAliasUuid(), "Expected card to stay unlinked");
        assertEquals(alias.getUuid(), manager.getCard("c2").getAliasUuid(), "Expected linked card unchanged");
        assertFalse(manager.isCardLinked("c1"), "Expected unlinked");
        assertTrue(manager.isCardLinked("c2"), "Expected linked");
    }

    @Test
    void twoUnlinkedCardsCompetingForOneAlias() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        Alias alias = createAlias(LIST, "Fire Dispatch", 100);
        aliasModel.addAlias(alias);

        Path path = mTempDir.resolve("a.console.json");
        writeLayout(path, card("c1", "missing-1", LIST, "Fire Dispatch", 100),
            card("c2", "missing-2", LIST, "Fire Dispatch", 100));

        ConsoleLayoutManager manager = new ConsoleLayoutManager(path);
        manager.setAliasModel(aliasModel);
        manager.load();

        assertEquals(alias.getUuid(), manager.getCard("c1").getAliasUuid(), "Expected first card re-bound");
        assertEquals("missing-2", manager.getCard("c2").getAliasUuid(), "Expected second card unlinked");
        manager.saveNow();
    }

    @Test
    void refreshAliasBindingsRebindsAliasesAddedLater() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        Path path = mTempDir.resolve("a.console.json");
        writeLayout(path, card("c1", "missing-1", LIST, "Fire Dispatch", 100));

        ConsoleLayoutManager manager = new ConsoleLayoutManager(path);
        manager.setAliasModel(aliasModel);
        manager.load();
        ConsoleCardResolver resolver = new ConsoleCardResolver(manager);
        List<ConsoleLayoutEvent.Type> events = new ArrayList<>();
        manager.addListener(event -> events.add(event.getType()));

        assertFalse(manager.isCardLinked("c1"), "Expected unlinked");
        manager.refreshAliasBindings();
        assertTrue(events.isEmpty(), "Expected no event when nothing is re-bound");

        Alias alias = createAlias(LIST, "Fire Dispatch", 100);
        aliasModel.addAlias(alias);
        manager.refreshAliasBindings();

        assertTrue(manager.isCardLinked("c1"), "Expected linked after alias is added");
        assertEquals(List.of(ConsoleLayoutEvent.Type.LAYOUT_LOADED), events, "Expected layout loaded event");
        assertNotNull(resolver.getCard(aliasModel.getAliasList(LIST), APCO25Talkgroup.create(100)),
            "Expected resolver to see the re-bound card");
        assertNotEquals("missing-1", manager.getCard("c1").getAliasUuid(), "Expected new uuid");
        manager.saveNow();
    }

    @Test
    void relinkUnlinkedCard() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        Alias alias = createAlias(LIST, "Fire Dispatch", 100);
        Alias other = createAlias(LIST, "Fireground", 101);
        aliasModel.addAlias(alias);
        aliasModel.addAlias(other);

        Path path = mTempDir.resolve("a.console.json");
        writeLayout(path, card("c1", "missing-1", LIST, "Nobody", 999),
            card("c2", other.getUuid(), LIST, "Fireground", 101));

        ConsoleLayoutManager manager = new ConsoleLayoutManager(path);
        manager.setAliasModel(aliasModel);
        manager.load();
        manager.setCardMuted("c1", true);
        assertFalse(manager.isCardLinked("c1"), "Expected unlinked card");

        List<ConsoleLayoutEvent> events = new ArrayList<>();
        manager.addListener(events::add);

        assertTrue(manager.relinkCard("c1", alias.getUuid()), "Expected card to be found");
        assertTrue(manager.isCardLinked("c1"), "Expected card to be linked");
        assertEquals(alias.getUuid(), manager.getCard("c1").getAliasUuid());
        assertEquals("Fire Dispatch", manager.getCard("c1").getSignature().getAliasName(), "Expected new signature");
        assertTrue(manager.getCard("c1").isMuted(), "Expected card settings to be kept");
        assertEquals(1, events.size());
        assertEquals(ConsoleLayoutEvent.Type.CARD_REBOUND, events.getFirst().getType());

        assertThrows(IllegalStateException.class, () -> manager.relinkCard("c1", other.getUuid()),
            "Expected alias bound to another card to be rejected");
        assertFalse(manager.relinkCard("no-such-card", alias.getUuid()), "Expected false for unknown card");
        manager.saveNow();
    }
}
