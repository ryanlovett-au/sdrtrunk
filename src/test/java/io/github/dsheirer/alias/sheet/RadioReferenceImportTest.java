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

package io.github.dsheirer.alias.sheet;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.console.ConsoleCard;
import io.github.dsheirer.console.ConsoleGroup;
import io.github.dsheirer.console.ConsoleLayoutManager;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for planning and applying a RadioReference talkgroup import into the alias sheet.
 */
public class RadioReferenceImportTest
{
    private static final String LIST = "Metro";
    private static final String TABLE = """
        DEC\tHEX\tMODE\tALPHA TAG\tDESCRIPTION\tTAG
        749\t2ed\tD\t01 Travel\t01 Travel Ops\tPublic Works
        753\t2f1\tD\t19 Resource Pro\t19 Resource Protection\tPublic Works
        1001\t3e9\tE\tPD Tac\tPolice Tactical\tLaw Tac
        1002\t3ea\tTE\tPD Tac 2\t\tLaw Tac
        1003\t3eb\tDE\tPD Disp\tPolice Dispatch\tLaw Dispatch
        2585\ta19\tD\tUnknown\tUnknown\t
        """;

    @TempDir
    Path mTempDir;

    private AliasModel mAliasModel;
    private ConsoleLayoutManager mConsoleLayoutManager;
    private AliasSheetModel mModel;
    private Alias mExisting;
    private RadioReferenceTable mTable;

    @BeforeEach
    void setup()
    {
        mAliasModel = new AliasModel();
        mExisting = new Alias("Old Travel");
        mExisting.setAliasListName(LIST);
        mExisting.setGroup("Old group");
        mExisting.addAliasID(new Talkgroup(Protocol.APCO25, 749));
        mAliasModel.addAliases(new ArrayList<>(List.of(mExisting)));

        mConsoleLayoutManager = new ConsoleLayoutManager(mTempDir.resolve("layout.console.json"));
        mConsoleLayoutManager.setAliasModel(mAliasModel);
        mConsoleLayoutManager.load();

        mModel = new AliasSheetModel(mAliasModel, mConsoleLayoutManager);
        mTable = RadioReferenceTable.parse(TABLE);
    }

    private RadioReferenceImport.Options options()
    {
        return new RadioReferenceImport.Options().setAliasList(LIST);
    }

    private RadioReferenceImport.PlannedRow row(RadioReferenceImport.Plan plan, int talkgroup)
    {
        return plan.getRows().stream().filter(row -> row.source().talkgroup() == talkgroup).findFirst().orElseThrow();
    }

    private Alias aliasFor(int talkgroup)
    {
        for(Alias alias: mAliasModel.getAliases())
        {
            Talkgroup tg = AliasSheetModel.getTalkgroup(alias);

            if(tg != null && tg.getValue() == talkgroup)
            {
                return alias;
            }
        }

        return null;
    }

    private String consoleGroupOf(Alias alias)
    {
        ConsoleCard card = mConsoleLayoutManager.getCardForAlias(alias.getUuid());

        if(card == null)
        {
            return null;
        }

        for(ConsoleGroup group: mConsoleLayoutManager.getLayout().getGroups())
        {
            if(group.getCard(card.getId()) != null)
            {
                return group.getName();
            }
        }

        return null;
    }

    @Test
    void defaultPlan()
    {
        RadioReferenceImport.Plan plan = RadioReferenceImport.plan(mModel, mTable, options());
        assertNull(plan.getError());
        assertTrue(plan.canApply());
        assertEquals(LIST, plan.getAliasList());

        //749 exists: skipped by default
        assertSame(RadioReferenceImport.Action.SKIP_DUPLICATE, row(plan, 749).action());
        assertSame(mExisting, row(plan, 749).existing());
        assertSame(RadioReferenceImport.Action.ADD, row(plan, 753).action());

        //Name from the description, falling back to the alpha tag; group from the tag
        assertEquals("19 Resource Protection", row(plan, 753).name());
        assertEquals("Public Works", row(plan, 753).group());
        assertEquals("PD Tac 2", row(plan, 1002).name());
        assertEquals("", row(plan, 2585).group());

        //Encrypted talkgroups are imported by default
        assertSame(RadioReferenceImport.Action.ADD, row(plan, 1001).action());
        assertFalse(row(plan, 1001).doNotMonitor());

        assertEquals("5 to add, 0 to update, 1 skipped", plan.getSummary());
        assertEquals("", plan.getConsoleSummary());
    }

    @Test
    void nameAndGroupSelection()
    {
        RadioReferenceImport.Plan plan = RadioReferenceImport.plan(mModel, mTable, options()
            .setNameSource(RadioReferenceImport.NameSource.ALPHA_TAG)
            .setGroupSource(RadioReferenceImport.GroupSource.NONE));
        assertEquals("19 Resource Pro", row(plan, 753).name());
        assertEquals("", row(plan, 753).group());

        //Category from a sub-heading
        RadioReferenceTable table = RadioReferenceTable.parse("Police\n" + TABLE);
        plan = RadioReferenceImport.plan(mModel, table, options()
            .setGroupSource(RadioReferenceImport.GroupSource.CATEGORY));
        assertEquals("Police", row(plan, 753).group());

        //Both description and alpha tag empty
        table = RadioReferenceTable.parse("DEC\tHEX\tMode\tAlpha Tag\tDescription\tTag\n4000\tfa0\tD\t \t\tX\n");
        plan = RadioReferenceImport.plan(mModel, table, options());
        assertEquals("TG 4000", row(plan, 4000).name());
    }

    @Test
    void duplicateUpdate()
    {
        RadioReferenceImport.Plan plan = RadioReferenceImport.plan(mModel, mTable, options()
            .setExistingAliasAction(RadioReferenceImport.ExistingAliasAction.UPDATE));
        assertSame(RadioReferenceImport.Action.UPDATE, row(plan, 749).action());
        assertEquals("5 to add, 1 to update, 0 skipped", plan.getSummary());

        RadioReferenceImport.Result result = mModel.applyRadioReferenceImport(plan);
        assertEquals(5, result.added());
        assertEquals(1, result.updated());
        assertEquals("01 Travel Ops", mExisting.getName());
        assertEquals("Public Works", mExisting.getGroup());
        assertEquals(6, mAliasModel.getAliases().size());

        //Re-planning the same table: nothing left to change
        plan = RadioReferenceImport.plan(mModel, mTable, options()
            .setExistingAliasAction(RadioReferenceImport.ExistingAliasAction.UPDATE));
        assertSame(RadioReferenceImport.Action.SKIP_UNCHANGED, row(plan, 749).action());
        assertFalse(plan.canApply());
    }

    @Test
    void duplicateInOtherListOrProtocolIsNotADuplicate()
    {
        RadioReferenceImport.Plan plan = RadioReferenceImport.plan(mModel, mTable, options().setAliasList("Other"));
        assertSame(RadioReferenceImport.Action.ADD, row(plan, 749).action());

        plan = RadioReferenceImport.plan(mModel, mTable, options().setProtocol(Protocol.DMR));
        assertSame(RadioReferenceImport.Action.ADD, row(plan, 749).action());
    }

    @Test
    void aliasListResolvedIgnoringCase()
    {
        RadioReferenceImport.Plan plan = RadioReferenceImport.plan(mModel, mTable, options().setAliasList(" metro "));
        assertEquals(LIST, plan.getAliasList());
        assertSame(RadioReferenceImport.Action.SKIP_DUPLICATE, row(plan, 749).action());

        plan = RadioReferenceImport.plan(mModel, mTable, options().setAliasList(" "));
        assertNotNull(plan.getError());
        assertFalse(plan.canApply());
    }

    @Test
    void duplicateRowsInTableAreImportedOnce()
    {
        RadioReferenceTable table = RadioReferenceTable.parse(TABLE + "753\t2f1\tD\tAgain\tAgain\tPublic Works\n");
        RadioReferenceImport.Plan plan = RadioReferenceImport.plan(mModel, table, options());
        List<RadioReferenceImport.PlannedRow> rows753 = plan.getRows().stream()
            .filter(row -> row.source().talkgroup() == 753).toList();
        assertEquals(2, rows753.size());
        assertSame(RadioReferenceImport.Action.ADD, rows753.get(0).action());
        assertSame(RadioReferenceImport.Action.SKIP_DUPLICATE, rows753.get(1).action());
    }

    @Test
    void invalidTalkgroupForProtocolIsSkipped()
    {
        RadioReferenceTable table = RadioReferenceTable.parse("DEC\tHEX\tMode\tAlpha Tag\tDescription\tTag\n" +
            "70000\t11170\tD\tBig\tBig\tX\n");
        RadioReferenceImport.Plan plan = RadioReferenceImport.plan(mModel, table, options());
        assertSame(RadioReferenceImport.Action.SKIP_INVALID, plan.getRows().getFirst().action());
        assertFalse(plan.canApply());
    }

    @Test
    void encryptedSkipAndDoNotMonitor()
    {
        RadioReferenceImport.Plan plan = RadioReferenceImport.plan(mModel, mTable, options()
            .setEncryptedAction(RadioReferenceImport.EncryptedAction.SKIP));
        assertSame(RadioReferenceImport.Action.SKIP_ENCRYPTED, row(plan, 1001).action());
        assertSame(RadioReferenceImport.Action.SKIP_ENCRYPTED, row(plan, 1002).action());
        //Partially encrypted talkgroups are imported
        assertSame(RadioReferenceImport.Action.ADD, row(plan, 1003).action());
        assertEquals("3 to add, 0 to update, 3 skipped", plan.getSummary());

        plan = RadioReferenceImport.plan(mModel, mTable, options()
            .setEncryptedAction(RadioReferenceImport.EncryptedAction.IMPORT_DO_NOT_MONITOR));
        assertTrue(row(plan, 1001).doNotMonitor());
        assertFalse(row(plan, 1003).doNotMonitor());
        mModel.applyRadioReferenceImport(plan);
        assertEquals(Priority.DO_NOT_MONITOR, aliasFor(1001).getPlaybackPriority());
        assertEquals("Do not monitor", mModel.getText(aliasFor(1002), AliasSheetColumn.LISTEN));
        assertEquals("Default", mModel.getText(aliasFor(1003), AliasSheetColumn.LISTEN));
    }

    @Test
    void applyIsOneUndoableOperation()
    {
        RadioReferenceImport.Plan plan = RadioReferenceImport.plan(mModel, mTable, options()
            .setExistingAliasAction(RadioReferenceImport.ExistingAliasAction.UPDATE)
            .setConsoleMode(RadioReferenceImport.ConsoleMode.GROUP_PER_TAG));
        RadioReferenceImport.Result result = mModel.applyRadioReferenceImport(plan);
        assertEquals(6, mAliasModel.getAliases().size());
        assertEquals(6, result.consoleCards());
        assertEquals(List.of("Public Works", "Law Tac", "Law Dispatch", RadioReferenceImport.UNTAGGED_CONSOLE_GROUP),
            result.createdConsoleGroups());

        Alias added = aliasFor(753);
        assertNotNull(added);
        assertEquals(LIST, added.getAliasListName());
        assertEquals(Protocol.APCO25, AliasSheetModel.getTalkgroup(added).getProtocol());
        assertEquals("paste from RadioReference", mModel.getUndoStack().getUndoDescription());

        assertTrue(mModel.undo());
        assertEquals(1, mAliasModel.getAliases().size());
        assertEquals("Old Travel", mExisting.getName());
        assertEquals("Old group", mExisting.getGroup());
        assertNull(mConsoleLayoutManager.getCardForAlias(mExisting.getUuid()));
        assertTrue(mConsoleLayoutManager.getLayout().getGroups().isEmpty(), "Created console groups are removed");
        assertFalse(mModel.getUndoStack().canUndoProperty().get());

        assertTrue(mModel.redo());
        assertEquals(6, mAliasModel.getAliases().size());
        assertEquals("01 Travel Ops", mExisting.getName());
        assertEquals("Public Works", consoleGroupOf(mExisting));
        assertEquals("Law Tac", consoleGroupOf(aliasFor(1001)));
        assertEquals(4, mConsoleLayoutManager.getLayout().getGroups().size());
    }

    @Test
    void consoleGroupPerTag()
    {
        //An existing group (different case) is reused and an alias that is already on a card stays where it is
        ConsoleGroup lawTac = mConsoleLayoutManager.addGroup("LAW TAC");
        ConsoleGroup favourites = mConsoleLayoutManager.addGroup("Favourites");
        mConsoleLayoutManager.addCard(favourites.getId(), mExisting.getUuid());

        RadioReferenceImport.Plan plan = RadioReferenceImport.plan(mModel, mTable, options()
            .setExistingAliasAction(RadioReferenceImport.ExistingAliasAction.UPDATE)
            .setConsoleMode(RadioReferenceImport.ConsoleMode.GROUP_PER_TAG));
        assertNull(row(plan, 749).consoleGroup());
        assertEquals("Favourites (already on console)", row(plan, 749).getConsoleText());
        assertEquals("Law Tac", row(plan, 1001).consoleGroup());
        assertEquals(RadioReferenceImport.UNTAGGED_CONSOLE_GROUP, row(plan, 2585).consoleGroup());
        assertEquals(List.of("Public Works", "Law Dispatch", RadioReferenceImport.UNTAGGED_CONSOLE_GROUP),
            plan.getNewConsoleGroups());
        assertEquals("5 console cards in 4 groups (3 new)", plan.getConsoleSummary());

        mModel.applyRadioReferenceImport(plan);
        assertEquals("Favourites", consoleGroupOf(mExisting));
        assertEquals("LAW TAC", consoleGroupOf(aliasFor(1001)));
        assertEquals("LAW TAC", consoleGroupOf(aliasFor(1002)));
        assertEquals("Public Works", consoleGroupOf(aliasFor(753)));
        assertEquals(5, mConsoleLayoutManager.getLayout().getGroups().size());

        //One card per alias
        Map<String,Long> cardsPerAlias = mConsoleLayoutManager.getLayout().getGroups().stream()
            .flatMap(group -> group.getCards().stream())
            .collect(Collectors.groupingBy(ConsoleCard::getAliasUuid, Collectors.counting()));
        assertTrue(cardsPerAlias.values().stream().allMatch(count -> count == 1));
        assertEquals(6, cardsPerAlias.size());

        //Undo keeps the pre-existing groups and the existing alias's card
        mModel.undo();
        assertEquals(List.of("LAW TAC", "Favourites"), mModel.getConsoleGroupNames());
        assertEquals("Favourites", consoleGroupOf(mExisting));
        assertTrue(mConsoleLayoutManager.getLayout().getGroup(lawTac.getId()).getCards().isEmpty());
    }

    @Test
    void consoleSingleGroup()
    {
        RadioReferenceImport.Plan plan = RadioReferenceImport.plan(mModel, mTable, options()
            .setConsoleMode(RadioReferenceImport.ConsoleMode.SINGLE_GROUP).setConsoleGroup(" Metro TGs "));
        assertEquals(List.of("Metro TGs"), plan.getNewConsoleGroups());
        //749 is skipped (existing alias) so it doesn't get a card
        assertNull(row(plan, 749).consoleGroup());

        RadioReferenceImport.Result result = mModel.applyRadioReferenceImport(plan);
        assertEquals(5, result.consoleCards());
        assertEquals(List.of("Metro TGs"), mModel.getConsoleGroupNames());
        assertEquals(5, mConsoleLayoutManager.getLayout().getGroups().getFirst().getCards().size());
        assertNull(mConsoleLayoutManager.getCardForAlias(mExisting.getUuid()));

        //A blank group name is an error
        plan = RadioReferenceImport.plan(mModel, mTable, options()
            .setConsoleMode(RadioReferenceImport.ConsoleMode.SINGLE_GROUP).setConsoleGroup(""));
        assertNotNull(plan.getError());
        assertFalse(plan.canApply());
    }

    @Test
    void consoleNotAvailable()
    {
        AliasSheetModel model = new AliasSheetModel(mAliasModel, null);
        RadioReferenceImport.Plan plan = RadioReferenceImport.plan(model, mTable, options()
            .setConsoleMode(RadioReferenceImport.ConsoleMode.GROUP_PER_TAG));
        assertTrue(plan.getRows().stream().allMatch(row -> row.consoleGroup() == null));
        assertEquals(5, model.applyRadioReferenceImport(plan).added());
    }

    @Test
    void modesAreExposed()
    {
        RadioReferenceImport.Plan plan = RadioReferenceImport.plan(mModel, mTable, options());
        RadioReferenceImport.Result result = mModel.applyRadioReferenceImport(plan);

        Map<String,RadioReferenceMode> modes = result.modes().stream().collect(Collectors.toMap(
            RadioReferenceImport.ModeAssignment::aliasUuid, RadioReferenceImport.ModeAssignment::mode));
        assertEquals(6, modes.size(), "Added aliases and the skipped duplicate");
        assertSame(RadioReferenceMode.DIGITAL_ENCRYPTED, modes.get(aliasFor(1001).getUuid()));
        assertSame(RadioReferenceMode.TDMA_ENCRYPTED, modes.get(aliasFor(1002).getUuid()));
        assertSame(RadioReferenceMode.DIGITAL, modes.get(mExisting.getUuid()));
        assertSame(RadioReferenceMode.TDMA_ENCRYPTED, mModel.getRadioReferenceMode(aliasFor(1002)));

        mModel.reset();
        assertNull(mModel.getRadioReferenceMode(aliasFor(1002)));
    }
}
