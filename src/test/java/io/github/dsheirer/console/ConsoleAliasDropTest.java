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
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the alias drag and drop payload and the add/move logic for dropping aliases onto a console group.
 */
public class ConsoleAliasDropTest
{
    @TempDir
    Path mTempDir;

    private AliasModel mAliasModel;
    private ConsoleLayoutManager mManager;
    private ConsoleGroup mFire;
    private ConsoleGroup mPolice;
    private final List<Alias> mAliases = new ArrayList<>();

    @BeforeEach
    void setup()
    {
        mAliasModel = new AliasModel();

        for(int x = 1; x <= 4; x++)
        {
            Alias alias = new Alias("Talkgroup " + x);
            alias.setAliasListName("List");
            alias.addAliasID(new Talkgroup(Protocol.APCO25, x));
            mAliasModel.addAlias(alias);
            mAliases.add(alias);
        }

        mManager = new ConsoleLayoutManager(mTempDir.resolve("playlist.console.json"));
        mManager.setAliasModel(mAliasModel);
        mManager.load();
        mFire = mManager.addGroup("Fire");
        mPolice = mManager.addGroup("Police");
    }

    private String uuid(int index)
    {
        return mAliases.get(index).getUuid();
    }

    private boolean exists(String uuid)
    {
        return mAliasModel.getAliasByUuid(uuid) != null;
    }

    private List<String> cardAliases(ConsoleGroup group)
    {
        List<String> uuids = new ArrayList<>();

        for(ConsoleCard card: mManager.getGroup(group.getId()).getCards())
        {
            uuids.add(card.getAliasUuid());
        }

        return uuids;
    }

    @Test
    void payloadRoundTrip()
    {
        String payload = ConsoleAliasDrop.encode(List.of("a", " b ", "", "a", "c"));
        assertEquals("a\nb\nc", payload);
        assertEquals(List.of("a", "b", "c"), ConsoleAliasDrop.parse(payload));
    }

    @Test
    void payloadParsing()
    {
        assertEquals(List.of("a", "b", "c", "d"), ConsoleAliasDrop.parse("a\r\nb, c\n\n  d  \na"));
        assertEquals(List.of("x", "y"), ConsoleAliasDrop.parse(List.of("x", " y", "x")));
        assertEquals(List.of(), ConsoleAliasDrop.parse(null));
        assertEquals(List.of(), ConsoleAliasDrop.parse(""));
        assertEquals(List.of(), ConsoleAliasDrop.parse(42));
        assertEquals("1 alias", ConsoleAliasDrop.getDragLabel(1));
        assertEquals("3 aliases", ConsoleAliasDrop.getDragLabel(3));
    }

    @Test
    void dropAddsCardsInOrder()
    {
        ConsoleAliasDrop.Result result = ConsoleAliasDrop.apply(mManager, mFire.getId(),
            List.of(uuid(2), uuid(0), uuid(1)), this::exists);
        assertEquals(new ConsoleAliasDrop.Result(3, 0, 0, 0), result);
        assertTrue(result.isChanged());
        assertEquals(List.of(uuid(2), uuid(0), uuid(1)), cardAliases(mFire));
        assertEquals("3 added - Fire", result.describe("Fire"));
    }

    @Test
    void dropMovesCardsFromOtherGroupsAndKeepsSettings()
    {
        ConsoleCard policeCard = mManager.addCard(mPolice.getId(), uuid(0));
        mManager.setCardVolume(policeCard.getId(), 0.25f);
        mManager.setCardPan(policeCard.getId(), AudioPan.RIGHT);
        mManager.addCard(mFire.getId(), uuid(1));

        ConsoleAliasDrop.Result result = ConsoleAliasDrop.apply(mManager, mFire.getId(),
            List.of(uuid(0), uuid(1), uuid(3)), this::exists);
        assertEquals(new ConsoleAliasDrop.Result(1, 1, 1, 0), result);
        assertEquals(List.of(uuid(1), uuid(0), uuid(3)), cardAliases(mFire),
            "Expected the card already in the group to stay in place and the others to be appended");
        assertTrue(cardAliases(mPolice).isEmpty());

        ConsoleCard moved = mManager.getLayout().getCardForAlias(uuid(0));
        assertEquals(policeCard.getId(), moved.getId(), "Expected the card to be moved, not re-created");
        assertEquals(0.25f, moved.getVolume());
        assertEquals(AudioPan.RIGHT, moved.getPan());
        assertEquals("1 added, 1 moved, 1 already there - Fire", result.describe("Fire"));
    }

    @Test
    void unknownAliasesAndGroupsAreSkipped()
    {
        ConsoleAliasDrop.Result result = ConsoleAliasDrop.apply(mManager, mFire.getId(),
            List.of("no-such-alias", uuid(0)), this::exists);
        assertEquals(new ConsoleAliasDrop.Result(1, 0, 0, 1), result);
        assertEquals(List.of(uuid(0)), cardAliases(mFire), "Expected no unlinked card for the unknown alias");

        ConsoleAliasDrop.Result noGroup = ConsoleAliasDrop.apply(mManager, "no-such-group", List.of(uuid(1)),
            this::exists);
        assertEquals(new ConsoleAliasDrop.Result(0, 0, 0, 1), noGroup);
        assertFalse(noGroup.isChanged());

        ConsoleAliasDrop.Result again = ConsoleAliasDrop.apply(mManager, mFire.getId(), List.of(uuid(0)),
            this::exists);
        assertEquals(new ConsoleAliasDrop.Result(0, 0, 1, 0), again);
        assertFalse(again.isChanged());
    }
}
