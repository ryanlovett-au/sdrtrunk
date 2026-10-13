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
    void logModeAndClearAudioAlertSettingsRoundTrip()
    {
        ConsoleLayoutManager manager = new ConsoleLayoutManager(getLayoutPath());
        List<ConsoleLayoutEvent> events = new ArrayList<>();
        manager.addListener(events::add);
        manager.load();
        assertFalse(manager.isActivityLogShowAll(), "Default log mode is console only");
        assertTrue(manager.isClearAudioAlertEnabled(), "Clear audio alert is enabled by default");

        ConsoleGroup group = manager.addGroup("Fire");
        ConsoleCard card1 = manager.addCard(group.getId(), "alias-uuid-1");
        ConsoleCard card2 = manager.addCard(group.getId(), "alias-uuid-2");
        assertEquals(ClearAudioAlert.AUTO, card1.getClearAudioAlert(), "Default clear audio alert");
        events.clear();

        manager.setActivityLogShowAll(true);
        manager.setActivityLogShowAll(true); //No change - no event
        manager.setClearAudioAlertEnabled(false);
        manager.setCardClearAudioAlert(card1.getId(), ClearAudioAlert.NEVER);

        for(int x = 0; x < ConsoleCard.ENCRYPTED_CALLS_TO_LEARN; x++)
        {
            manager.recordCardCall(card2.getId(), true);
        }

        assertEquals(List.of(ConsoleLayoutEvent.Type.SETTINGS_CHANGED, ConsoleLayoutEvent.Type.SETTINGS_CHANGED,
            ConsoleLayoutEvent.Type.CARD_CHANGED, ConsoleLayoutEvent.Type.CARD_CHANGED,
            ConsoleLayoutEvent.Type.CARD_CHANGED, ConsoleLayoutEvent.Type.CARD_CHANGED),
            events.stream().map(ConsoleLayoutEvent::getType).toList(), "Event sequence");
        assertTrue(events.getLast().getCard().isEncryptedLearned(), "Changed card snapshot learned state");

        //Further encrypted calls do not change the (capped) state - no event
        manager.recordCardCall(card2.getId(), true);
        assertEquals(6, events.size(), "Expected no event for an unchanged card");
        manager.saveNow();

        ConsoleLayoutManager reloaded = new ConsoleLayoutManager(getLayoutPath());
        reloaded.load();
        assertTrue(reloaded.isActivityLogShowAll(), "Log mode");
        assertFalse(reloaded.isClearAudioAlertEnabled(), "Clear audio alert");
        ConsoleCard loaded1 = reloaded.getCard(card1.getId());
        ConsoleCard loaded2 = reloaded.getCard(card2.getId());
        assertEquals(ClearAudioAlert.NEVER, loaded1.getClearAudioAlert(), "Clear audio alert override");
        assertFalse(loaded1.isTreatedAsEncrypted(), "NEVER is not treated as encrypted");
        assertTrue(loaded2.isEncryptedLearned(), "Learned state");
        assertEquals(ConsoleCard.ENCRYPTED_CALLS_TO_LEARN, loaded2.getEncryptedCallStreak(), "Encrypted call streak");
        assertTrue(loaded2.isTreatedAsEncrypted(), "Learned card is treated as encrypted");
    }

    @Test
    void encryptionLearningRules()
    {
        ConsoleCard card = new ConsoleCard("alias-uuid-1");
        card.recordCall(true);
        card.recordCall(true);
        card.recordCall(false);
        card.recordCall(true);
        card.recordCall(true);
        assertFalse(card.isEncryptedLearned(), "Expected consecutive encrypted calls to be required");
        card.recordCall(true);
        assertTrue(card.isEncryptedLearned(), "Expected learned after three consecutive encrypted calls");

        //Clear calls (slips) keep the learned state until CLEAR_CALLS_TO_UNLEARN consecutive clear calls
        for(int x = 0; x < ConsoleCard.CLEAR_CALLS_TO_UNLEARN - 1; x++)
        {
            card.recordCall(false);
            assertTrue(card.isEncryptedLearned(), "Expected learned state retained after clear call " + (x + 1));
        }

        card.recordCall(false);
        assertFalse(card.isEncryptedLearned(), "Expected learned state cleared after consecutive clear calls");

        card.setClearAudioAlert(ClearAudioAlert.ALWAYS);
        assertTrue(card.isTreatedAsEncrypted(), "ALWAYS override");
        card.setClearAudioAlert(null);
        assertEquals(ClearAudioAlert.AUTO, card.getClearAudioAlert(), "Null is AUTO");
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

    @Test
    void groupCollapsedAndMutedStatePersistsAndBroadcastsEvents() throws Exception
    {
        ConsoleLayoutManager manager = new ConsoleLayoutManager(getLayoutPath());
        List<ConsoleLayoutEvent> events = new ArrayList<>();
        manager.load();
        ConsoleGroup fire = manager.addGroup("Fire");
        ConsoleGroup police = manager.addGroup("Police");
        ConsoleCard card = manager.addCard(fire.getId(), "alias-uuid-1");
        manager.setCardMuted(card.getId(), true);
        assertFalse(fire.isCollapsed(), "Default expanded");
        assertFalse(fire.isMuted(), "Default not muted");
        manager.addListener(events::add);

        assertTrue(manager.setGroupCollapsed(fire.getId(), true), "Group found");
        assertTrue(manager.setGroupCollapsed(fire.getId(), true), "Group found"); //No change - no event
        assertTrue(manager.setGroupMuted(police.getId(), true), "Group found");
        assertFalse(manager.setGroupMuted("missing", true), "Expected unknown group");
        assertEquals(List.of(ConsoleLayoutEvent.Type.GROUP_STATE_CHANGED, ConsoleLayoutEvent.Type.GROUP_STATE_CHANGED),
            events.stream().map(ConsoleLayoutEvent::getType).toList(), "Event sequence");
        assertEquals(List.of(fire.getId(), police.getId()),
            events.stream().map(ConsoleLayoutEvent::getGroupId).toList(), "Event group identifiers");
        assertTrue(manager.getGroup(fire.getId()).isCollapsed(), "Collapsed");
        assertTrue(manager.getGroup(police.getId()).isMuted(), "Muted");
        assertTrue(manager.getCard(card.getId()).isMuted(), "Expected the card's own mute setting to be unchanged");

        //Collapse all: one event for each group that changes
        events.clear();
        manager.setAllGroupsCollapsed(true);
        assertEquals(List.of(police.getId()), events.stream().map(ConsoleLayoutEvent::getGroupId).toList(),
            "Expected an event only for the group that was expanded");
        manager.setAllGroupsCollapsed(false);
        assertEquals(3, events.size(), "Expected an event for each expanded group");
        assertFalse(manager.getLayout().getGroups().stream().anyMatch(ConsoleGroup::isCollapsed), "All expanded");

        manager.setGroupCollapsed(fire.getId(), true);
        assertTrue(manager.isSavePending(), "Expected a group state change to schedule a save");
        manager.saveNow();
        String json = Files.readString(getLayoutPath());
        assertTrue(json.contains("\"collapsed\" : true"), "Expected collapsed in JSON: " + json);
        assertTrue(json.contains("\"muted\" : true"), "Expected muted in JSON: " + json);

        ConsoleLayoutManager reloaded = new ConsoleLayoutManager(getLayoutPath());
        reloaded.load();
        assertTrue(reloaded.getGroup(fire.getId()).isCollapsed(), "Collapsed after reload");
        assertFalse(reloaded.getGroup(fire.getId()).isMuted(), "Not muted after reload");
        assertFalse(reloaded.getGroup(police.getId()).isCollapsed(), "Expanded after reload");
        assertTrue(reloaded.getGroup(police.getId()).isMuted(), "Muted after reload");
        assertTrue(reloaded.getCard(card.getId()).isMuted(), "Card mute after reload");
    }

    @Test
    void layoutWithoutGroupStateLoadsExpandedAndUnmuted() throws Exception
    {
        Files.createDirectories(getLayoutPath().getParent());
        Files.writeString(getLayoutPath(), "{\"version\":1,\"groups\":[{\"id\":\"g1\",\"name\":\"Fire\"," +
            "\"displayOrder\":0,\"cards\":[]}]}", StandardCharsets.UTF_8);
        ConsoleLayoutManager manager = new ConsoleLayoutManager(getLayoutPath());
        manager.load();
        ConsoleGroup group = manager.getGroup("g1");
        assertNotNull(group, "Group");
        assertFalse(group.isCollapsed(), "Expected expanded by default");
        assertFalse(group.isMuted(), "Expected not muted by default");
        assertEquals(1, group.getColumnSpan(), "Expected one column by default");
        manager.shutdown();
    }

    @Test
    void groupColumnSpanPersistsAndBroadcastsEvents() throws Exception
    {
        ConsoleLayoutManager manager = new ConsoleLayoutManager(getLayoutPath());
        List<ConsoleLayoutEvent> events = new ArrayList<>();
        manager.load();
        ConsoleGroup fire = manager.addGroup("Fire");
        ConsoleGroup police = manager.addGroup("Police");
        assertEquals(1, fire.getColumnSpan(), "Default span");
        manager.addListener(events::add);

        assertTrue(manager.setGroupColumnSpan(fire.getId(), 2), "Group found");
        assertTrue(manager.setGroupColumnSpan(fire.getId(), 2), "Group found"); //No change - no event
        assertTrue(manager.setGroupColumnSpan(police.getId(), ConsoleGroup.FULL_WIDTH), "Group found");
        assertFalse(manager.setGroupColumnSpan("missing", 2), "Expected unknown group");
        assertEquals(List.of(ConsoleLayoutEvent.Type.GROUP_STATE_CHANGED, ConsoleLayoutEvent.Type.GROUP_STATE_CHANGED),
            events.stream().map(ConsoleLayoutEvent::getType).toList(), "Event sequence");
        assertEquals(2, manager.getGroup(fire.getId()).getColumnSpan(), "Span");
        assertEquals(ConsoleGroup.FULL_WIDTH, manager.getGroup(police.getId()).getColumnSpan(), "Full width");
        assertEquals(2, manager.getLayout().getGroup(fire.getId()).getColumnSpan(), "Span in a layout snapshot");

        //Out of range spans are constrained
        manager.setGroupColumnSpan(fire.getId(), -3);
        assertEquals(1, manager.getGroup(fire.getId()).getColumnSpan(), "Negative span");
        manager.setGroupColumnSpan(fire.getId(), 99);
        assertEquals(ConsoleGroup.MAXIMUM_COLUMN_SPAN, manager.getGroup(fire.getId()).getColumnSpan(), "Large span");
        manager.setGroupColumnSpan(fire.getId(), 3);

        assertTrue(manager.isSavePending(), "Expected a span change to schedule a save");
        manager.saveNow();
        String json = Files.readString(getLayoutPath());
        assertTrue(json.contains("\"columnSpan\" : 3"), "Expected the span in JSON: " + json);

        ConsoleLayoutManager reloaded = new ConsoleLayoutManager(getLayoutPath());
        reloaded.load();
        assertEquals(3, reloaded.getGroup(fire.getId()).getColumnSpan(), "Span after reload");
        assertEquals(ConsoleGroup.FULL_WIDTH, reloaded.getGroup(police.getId()).getColumnSpan(),
            "Full width after reload");
        reloaded.shutdown();
    }

    @Test
    void cardResolverAudioMuteCombinesCardAndGroupMute()
    {
        ConsoleLayoutManager manager = new ConsoleLayoutManager(getLayoutPath());
        manager.load();
        ConsoleGroup group = manager.addGroup("Fire");
        ConsoleCard card = manager.addCard(group.getId(), "alias-uuid-1");
        ConsoleCardResolver resolver = new ConsoleCardResolver(manager);

        assertFalse(resolver.isAudioMuted(manager.getCard(card.getId())), "Not muted");
        manager.setGroupMuted(group.getId(), true);
        assertTrue(resolver.isGroupMuted(card.getId()), "Group muted");
        assertTrue(resolver.isAudioMuted(manager.getCard(card.getId())), "Muted by group");
        manager.setCardMuted(card.getId(), true);
        manager.setGroupMuted(group.getId(), false);
        assertFalse(resolver.isGroupMuted(card.getId()), "Group unmuted");
        assertTrue(resolver.isAudioMuted(manager.getCard(card.getId())), "Expected the card's own mute to remain");
        manager.setCardMuted(card.getId(), false);
        assertFalse(resolver.isAudioMuted(manager.getCard(card.getId())), "Not muted");
        assertFalse(resolver.isAudioMuted(null), "Null card");
        resolver.dispose();
        manager.saveNow();
    }
}
