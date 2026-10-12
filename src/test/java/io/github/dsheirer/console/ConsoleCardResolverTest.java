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
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the console card resolver, which decides if a call is on the console (talkgroup filter).
 */
public class ConsoleCardResolverTest
{
    private static final int TALKGROUP_ON_CARD = 100;
    private static final int TALKGROUP_OFF_CARD = 200;
    private static final int TALKGROUP_UNALIASED = 300;
    private static final int RADIO_ON_CARD = 1234;
    private static final int RADIO_OFF_CARD = 5678;

    @TempDir
    Path mTempDir;

    private Alias mTalkgroupOnCard;
    private Alias mTalkgroupOffCard;
    private Alias mRadioOnCard;
    private Alias mRadioOffCard;

    /**
     * Creates the alias list with talkgroup and radio aliases.
     */
    private AliasList createAliasList()
    {
        AliasList aliasList = new AliasList("Test List");

        mTalkgroupOnCard = new Alias("Fire Dispatch");
        mTalkgroupOnCard.addAliasID(new Talkgroup(Protocol.APCO25, TALKGROUP_ON_CARD));
        aliasList.addAlias(mTalkgroupOnCard);

        mTalkgroupOffCard = new Alias("Police Dispatch");
        mTalkgroupOffCard.addAliasID(new Talkgroup(Protocol.APCO25, TALKGROUP_OFF_CARD));
        aliasList.addAlias(mTalkgroupOffCard);

        mRadioOnCard = new Alias("Engine 1");
        mRadioOnCard.addAliasID(new Radio(Protocol.APCO25, RADIO_ON_CARD));
        aliasList.addAlias(mRadioOnCard);

        mRadioOffCard = new Alias("Engine 2");
        mRadioOffCard.addAliasID(new Radio(Protocol.APCO25, RADIO_OFF_CARD));
        aliasList.addAlias(mRadioOffCard);

        return aliasList;
    }

    /**
     * Creates a console layout manager with console mode and talkgroup filter enabled and a card for the talkgroup and
     * radio 'on card' aliases.
     */
    private ConsoleLayoutManager createManager()
    {
        ConsoleLayoutManager manager = new ConsoleLayoutManager(mTempDir.resolve("playlist.console.json"));
        manager.load();
        manager.setConsoleModeEnabled(true);
        manager.setTalkgroupFilterEnabled(true);
        ConsoleGroup group = manager.addGroup("Fire");
        manager.addCard(group.getId(), mTalkgroupOnCard.getUuid());
        manager.addCard(group.getId(), mRadioOnCard.getUuid());
        return manager;
    }

    private static IdentifierCollection call(Identifier to)
    {
        return new IdentifierCollection(List.of(to, APCO25RadioIdentifier.createFrom(999)));
    }

    @Test
    void talkgroupOnAndOffCard()
    {
        AliasList aliasList = createAliasList();
        ConsoleLayoutManager manager = createManager();
        ConsoleCardResolver resolver = new ConsoleCardResolver(manager);

        assertTrue(resolver.isFilterActive(), "Expected filter to be active");
        assertTrue(resolver.isAllowed(aliasList, call(APCO25Talkgroup.create(TALKGROUP_ON_CARD))),
            "Expected talkgroup on a card to be allowed");
        assertFalse(resolver.isAllowed(aliasList, call(APCO25Talkgroup.create(TALKGROUP_OFF_CARD))),
            "Expected aliased talkgroup that is not on a card to be filtered");
        assertFalse(resolver.isAllowed(aliasList, call(APCO25Talkgroup.create(TALKGROUP_UNALIASED))),
            "Expected unaliased talkgroup to be filtered");
        assertFalse(resolver.isAllowed(aliasList, new IdentifierCollection()),
            "Expected call without a TO identifier to be filtered");
        assertFalse(resolver.isAllowed(null, call(APCO25Talkgroup.create(TALKGROUP_ON_CARD))),
            "Expected call without an alias list to be filtered");

        ConsoleCard card = resolver.getCard(aliasList, APCO25Talkgroup.create(TALKGROUP_ON_CARD));
        assertNotNull(card, "Expected card for talkgroup");
        assertEquals(mTalkgroupOnCard.getUuid(), card.getAliasUuid(), "Card alias");
        manager.saveNow();
    }

    @Test
    void patchGroupWithMemberOnCard()
    {
        AliasList aliasList = createAliasList();
        ConsoleLayoutManager manager = createManager();
        ConsoleCardResolver resolver = new ConsoleCardResolver(manager);

        //Supergroup is not aliased, patched talkgroup 100 is on a card
        PatchGroup patchGroup = new PatchGroup(APCO25Talkgroup.create(TALKGROUP_UNALIASED));
        patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(TALKGROUP_OFF_CARD));
        patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(TALKGROUP_ON_CARD));
        assertTrue(resolver.isAllowed(aliasList, call(APCO25PatchGroup.create(patchGroup))),
            "Expected patch group with a patched talkgroup on a card to be allowed");

        PatchGroup patchGroupOff = new PatchGroup(APCO25Talkgroup.create(TALKGROUP_UNALIASED));
        patchGroupOff.addPatchedTalkgroup(APCO25Talkgroup.create(TALKGROUP_OFF_CARD));
        assertFalse(resolver.isAllowed(aliasList, call(APCO25PatchGroup.create(patchGroupOff))),
            "Expected patch group without a patched talkgroup on a card to be filtered");
        manager.saveNow();
    }

    @Test
    void radioToIdentifier()
    {
        AliasList aliasList = createAliasList();
        ConsoleLayoutManager manager = createManager();
        ConsoleCardResolver resolver = new ConsoleCardResolver(manager);

        assertTrue(resolver.isAllowed(aliasList, call(APCO25RadioIdentifier.createTo(RADIO_ON_CARD))),
            "Expected private call to a radio on a card to be allowed");
        assertFalse(resolver.isAllowed(aliasList, call(APCO25RadioIdentifier.createTo(RADIO_OFF_CARD))),
            "Expected private call to a radio that is not on a card to be filtered");
        manager.saveNow();
    }

    @Test
    void filterInactiveAllowsEverything()
    {
        AliasList aliasList = createAliasList();
        ConsoleLayoutManager manager = createManager();
        ConsoleCardResolver resolver = new ConsoleCardResolver(manager);
        IdentifierCollection offCard = call(APCO25Talkgroup.create(TALKGROUP_OFF_CARD));

        assertFalse(resolver.isAllowed(aliasList, offCard), "Expected filter active");

        manager.setConsoleModeEnabled(false);
        assertFalse(resolver.isFilterActive(), "Expected filter inactive when console mode is off");
        assertTrue(resolver.isAllowed(aliasList, offCard), "Expected call allowed when console mode is off");
        assertTrue(resolver.isAllowed(null, new IdentifierCollection()), "Expected anything allowed when inactive");

        manager.setConsoleModeEnabled(true);
        manager.setTalkgroupFilterEnabled(false);
        assertFalse(resolver.isFilterActive(), "Expected filter inactive when the talkgroup filter is off");
        assertTrue(resolver.isAllowed(aliasList, offCard), "Expected call allowed when the talkgroup filter is off");

        //Card lookup still works when the filter is inactive (used by the console mixer)
        assertNotNull(resolver.getCard(aliasList, APCO25Talkgroup.create(TALKGROUP_ON_CARD)), "Expected card");
        assertNull(resolver.getCard(aliasList, APCO25Talkgroup.create(TALKGROUP_OFF_CARD)), "Expected no card");
        manager.saveNow();
    }

    @Test
    void resolverFollowsCardChangesAndLayoutSwitch()
    {
        AliasList aliasList = createAliasList();
        ConsoleLayoutManager manager = createManager();
        ConsoleCardResolver resolver = new ConsoleCardResolver(manager);
        IdentifierCollection offCard = call(APCO25Talkgroup.create(TALKGROUP_OFF_CARD));

        assertFalse(resolver.isAllowed(aliasList, offCard), "Expected filtered before card is added");
        ConsoleGroup group = manager.getLayout().getGroups().getFirst();
        ConsoleCard card = manager.addCard(group.getId(), mTalkgroupOffCard.getUuid());
        assertTrue(resolver.isAllowed(aliasList, offCard), "Expected allowed after card is added");
        manager.removeCard(card.getId());
        assertFalse(resolver.isAllowed(aliasList, offCard), "Expected filtered after card is removed");

        //Switching to another playlist's (empty) layout disables console mode, so everything is allowed
        manager.load(mTempDir.resolve("other.console.json"));
        assertFalse(resolver.isConsoleModeEnabled(), "Expected console mode off for the new layout");
        assertTrue(resolver.isAllowed(aliasList, offCard), "Expected allowed with new layout");
        assertNull(resolver.getCard(aliasList, APCO25Talkgroup.create(TALKGROUP_ON_CARD)),
            "Expected no cards in new layout");

        //Switching back restores the cards
        manager.load(mTempDir.resolve("playlist.console.json"));
        assertTrue(resolver.isFilterActive(), "Expected filter active for the original layout");
        assertNotNull(resolver.getCard(aliasList, APCO25Talkgroup.create(TALKGROUP_ON_CARD)),
            "Expected card in original layout");
        resolver.dispose();
    }
}
