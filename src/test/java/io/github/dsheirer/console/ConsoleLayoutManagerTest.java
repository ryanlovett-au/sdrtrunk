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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the console layout manager and console layout persistence.
 */
public class ConsoleLayoutManagerTest
{
    @TempDir
    Path mTempDir;

    private Path getLayoutPath()
    {
        return mTempDir.resolve(ConsoleLayoutManager.CONSOLE_DIRECTORY).resolve(ConsoleLayoutManager.LAYOUT_FILE_NAME);
    }

    @Test
    void missingFileStartsEmpty()
    {
        ConsoleLayoutManager manager = new ConsoleLayoutManager(getLayoutPath());
        manager.load();

        ConsoleLayout layout = manager.getLayout();
        assertTrue(layout.getGroups().isEmpty(), "Expected empty layout");
        assertEquals(ConsoleLayout.DEFAULT_ACTIVITY_HANG_TIME_MS, layout.getActivityHangTimeMs(), "Default hang time");
        assertTrue(layout.isTalkgroupFilterEnabled(), "Default talkgroup filter enabled");
        assertFalse(manager.isSavePending(), "Expected no save for a missing file");
        assertFalse(Files.exists(getLayoutPath()), "Expected file to not be created");
    }

    @Test
    void jsonRoundTrip() throws Exception
    {
        ConsoleLayoutManager manager = new ConsoleLayoutManager(getLayoutPath());
        manager.load();

        manager.setActivityHangTimeMs(3500);
        manager.setTalkgroupFilterEnabled(false);
        ConsoleGroup fire = manager.addGroup("Fire");
        ConsoleGroup police = manager.addGroup("Police");
        ConsoleCard card1 = manager.addCard(fire.getId(), "alias-uuid-1");
        ConsoleCard card2 = manager.addCard(fire.getId(), "alias-uuid-2");
        ConsoleCard card3 = manager.addCard(police.getId(), "alias-uuid-3");
        manager.setCardVolume(card1.getId(), 0.25f);
        manager.setCardMuted(card2.getId(), true);
        manager.setCardPan(card3.getId(), AudioPan.LEFT);
        manager.moveGroup(police.getId(), 0);

        assertTrue(manager.isSavePending(), "Expected changes to schedule a save");
        manager.saveNow();
        assertFalse(manager.isSavePending(), "Expected save to complete");
        assertTrue(Files.exists(getLayoutPath()), "Expected layout file to be saved");

        String json = Files.readString(getLayoutPath());
        assertTrue(json.contains("\"aliasUuid\" : \"alias-uuid-1\""), "Expected alias uuid in JSON: " + json);
        assertTrue(json.contains("\"pan\" : \"LEFT\""), "Expected pan enum in JSON: " + json);

        //No temporary file should remain after an atomic save
        try(Stream<Path> files = Files.list(getLayoutPath().getParent()))
        {
            assertEquals(1, files.count(), "Expected only the layout file in the console directory");
        }

        ConsoleLayoutManager reloaded = new ConsoleLayoutManager(getLayoutPath());
        reloaded.load();
        assertFalse(reloaded.isSavePending(), "Expected a valid file to load without corrections");

        ConsoleLayout layout = reloaded.getLayout();
        assertEquals(3500, layout.getActivityHangTimeMs(), "Hang time");
        assertFalse(layout.isTalkgroupFilterEnabled(), "Talkgroup filter");
        assertEquals(2, layout.getGroups().size(), "Group count");

        ConsoleGroup group0 = layout.getGroups().get(0);
        ConsoleGroup group1 = layout.getGroups().get(1);
        assertEquals("Police", group0.getName(), "Expected moved group first");
        assertEquals(0, group0.getDisplayOrder(), "Display order");
        assertEquals(police.getId(), group0.getId(), "Group id");
        assertEquals("Fire", group1.getName(), "Group name");
        assertEquals(1, group1.getDisplayOrder(), "Display order");

        assertEquals(2, group1.getCards().size(), "Fire card count");
        ConsoleCard loaded1 = group1.getCards().get(0);
        ConsoleCard loaded2 = group1.getCards().get(1);
        ConsoleCard loaded3 = group0.getCards().get(0);

        assertEquals(card1.getId(), loaded1.getId(), "Card id");
        assertEquals("alias-uuid-1", loaded1.getAliasUuid(), "Alias uuid");
        assertEquals(0.25f, loaded1.getVolume(), 0.0001f, "Volume");
        assertFalse(loaded1.isMuted(), "Muted");
        assertEquals(AudioPan.BOTH, loaded1.getPan(), "Default pan");

        assertEquals("alias-uuid-2", loaded2.getAliasUuid(), "Alias uuid");
        assertEquals(ConsoleCard.DEFAULT_VOLUME, loaded2.getVolume(), 0.0001f, "Default volume");
        assertTrue(loaded2.isMuted(), "Muted");

        assertEquals("alias-uuid-3", loaded3.getAliasUuid(), "Alias uuid");
        assertEquals(AudioPan.LEFT, loaded3.getPan(), "Pan");
    }

    @Test
    void corruptFileIsBackedUpAndStartsEmpty() throws Exception
    {
        Path path = getLayoutPath();
        Files.createDirectories(path.getParent());
        String corrupt = "{ \"groups\" : [ { \"name\" : \"Fire\", \"cards\" : [ { ";
        Files.writeString(path, corrupt, StandardCharsets.UTF_8);

        ConsoleLayoutManager manager = new ConsoleLayoutManager(path);
        manager.load();

        assertTrue(manager.getLayout().getGroups().isEmpty(), "Expected empty layout after corrupt file");
        assertFalse(Files.exists(path), "Expected corrupt file to be moved aside");

        List<Path> backups = new ArrayList<>();

        try(Stream<Path> files = Files.list(path.getParent()))
        {
            files.filter(p -> p.getFileName().toString().startsWith(ConsoleLayoutManager.LAYOUT_FILE_NAME + ".corrupt"))
                .forEach(backups::add);
        }

        assertEquals(1, backups.size(), "Expected one corrupt file backup");
        assertEquals(corrupt, Files.readString(backups.getFirst()), "Expected backup to retain corrupt content");

        //Manager remains usable and saves a new valid layout
        ConsoleGroup group = manager.addGroup("Fire");
        manager.addCard(group.getId(), "alias-uuid-1");
        manager.saveNow();

        ConsoleLayoutManager reloaded = new ConsoleLayoutManager(path);
        reloaded.load();
        assertEquals(1, reloaded.getLayout().getCardCount(), "Expected new layout to be saved");
    }

    @Test
    void emptyFileIsTreatedAsCorrupt() throws Exception
    {
        Path path = getLayoutPath();
        Files.createDirectories(path.getParent());
        Files.writeString(path, "", StandardCharsets.UTF_8);

        ConsoleLayoutManager manager = new ConsoleLayoutManager(path);
        manager.load();

        assertTrue(manager.getLayout().getGroups().isEmpty(), "Expected empty layout");
        assertFalse(Files.exists(path), "Expected empty file to be moved aside");
    }

    @Test
    void aliasMayAppearOnOnlyOneCard()
    {
        ConsoleLayoutManager manager = new ConsoleLayoutManager(getLayoutPath());
        manager.load();

        ConsoleGroup fire = manager.addGroup("Fire");
        ConsoleGroup police = manager.addGroup("Police");
        ConsoleCard card = manager.addCard(fire.getId(), "alias-uuid-1");

        assertTrue(manager.isAliasAssigned("alias-uuid-1"), "Expected alias to be assigned");
        assertThrows(IllegalStateException.class, () -> manager.addCard(fire.getId(), "alias-uuid-1"),
            "Expected duplicate alias in same group to be rejected");
        assertThrows(IllegalStateException.class, () -> manager.addCard(police.getId(), "alias-uuid-1"),
            "Expected duplicate alias in another group to be rejected");
        assertEquals(1, manager.getLayout().getCardCount(), "Expected a single card");

        //Moving the card between groups is allowed
        assertTrue(manager.moveCard(card.getId(), police.getId(), 0), "Expected card move");
        assertEquals(police.getId(), manager.getLayout().getGroupForCard(card.getId()).getId(), "Card group");

        //After removal, the alias can be assigned again
        assertTrue(manager.removeCard(card.getId()), "Expected card removal");
        assertFalse(manager.isAliasAssigned("alias-uuid-1"), "Expected alias to be unassigned");
        assertNotNull(manager.addCard(fire.getId(), "alias-uuid-1"), "Expected alias to be assignable again");

        //Removing a group releases the group's aliases
        assertTrue(manager.removeGroup(fire.getId()), "Expected group removal");
        assertFalse(manager.isAliasAssigned("alias-uuid-1"), "Expected alias to be released with group");
    }

    @Test
    void duplicateAliasCardsInFileAreRemovedOnLoad() throws Exception
    {
        Path path = getLayoutPath();
        Files.createDirectories(path.getParent());
        String json = """
            {
              "version" : 1,
              "groups" : [
                { "id" : "g1", "name" : "Fire", "displayOrder" : 0, "cards" : [
                  { "id" : "c1", "aliasUuid" : "alias-uuid-1", "volume" : 0.5 },
                  { "id" : "c2", "aliasUuid" : "alias-uuid-1" } ] },
                { "id" : "g2", "name" : "Police", "displayOrder" : 1, "cards" : [
                  { "id" : "c3", "aliasUuid" : "alias-uuid-1" },
                  { "id" : "c4", "aliasUuid" : "alias-uuid-2", "volume" : 7.0, "pan" : "UNKNOWN_PAN" } ] }
              ]
            }
            """;
        Files.writeString(path, json, StandardCharsets.UTF_8);

        ConsoleLayoutManager manager = new ConsoleLayoutManager(path);
        manager.load();

        ConsoleLayout layout = manager.getLayout();
        assertEquals(2, layout.getCardCount(), "Expected duplicate alias cards to be removed");
        assertNotNull(layout.getCard("c1"), "Expected first card for alias to be retained");
        assertNull(layout.getCard("c2"), "Expected duplicate card to be removed");
        assertNull(layout.getCard("c3"), "Expected duplicate card to be removed");
        assertEquals(ConsoleCard.MAXIMUM_VOLUME, layout.getCard("c4").getVolume(), 0.0001f, "Volume clamped");
        assertEquals(AudioPan.BOTH, layout.getCard("c4").getPan(), "Unknown pan uses default");
        assertTrue(manager.isSavePending(), "Expected corrected layout to be saved");
        manager.saveNow();
    }

    @Test
    void listenersReceiveEvents()
    {
        ConsoleLayoutManager manager = new ConsoleLayoutManager(getLayoutPath());
        List<ConsoleLayoutEvent> events = new ArrayList<>();
        manager.addListener(events::add);
        manager.load();

        ConsoleGroup group = manager.addGroup("Fire");
        ConsoleCard card = manager.addCard(group.getId(), "alias-uuid-1");
        manager.setCardVolume(card.getId(), 0.5f);
        manager.setCardVolume(card.getId(), 0.5f); //No change - no event
        manager.removeGroup(group.getId());

        List<ConsoleLayoutEvent.Type> types = events.stream().map(ConsoleLayoutEvent::getType).toList();
        assertEquals(List.of(ConsoleLayoutEvent.Type.LAYOUT_LOADED, ConsoleLayoutEvent.Type.GROUP_ADDED,
            ConsoleLayoutEvent.Type.CARD_ADDED, ConsoleLayoutEvent.Type.CARD_CHANGED,
            ConsoleLayoutEvent.Type.CARD_REMOVED, ConsoleLayoutEvent.Type.GROUP_REMOVED), types, "Event sequence");
        assertEquals(0.5f, events.get(3).getCard().getVolume(), 0.0001f, "Changed card snapshot volume");
        manager.saveNow();
    }

    @Test
    void snapshotsDoNotModifyManagedLayout()
    {
        ConsoleLayoutManager manager = new ConsoleLayoutManager(getLayoutPath());
        manager.load();
        ConsoleGroup group = manager.addGroup("Fire");
        manager.addCard(group.getId(), "alias-uuid-1");

        ConsoleLayout snapshot = manager.getLayout();
        snapshot.getGroups().getFirst().getCards().add(new ConsoleCard("alias-uuid-1"));
        snapshot.getGroups().clear();

        assertEquals(1, manager.getLayout().getCardCount(), "Expected managed layout to be unchanged");
        manager.saveNow();
    }
}
