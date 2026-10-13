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

package io.github.dsheirer.alias.sheet;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.action.beep.BeepAction;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.console.AudioPan;
import io.github.dsheirer.console.ConsoleCard;
import io.github.dsheirer.console.ConsoleGroup;
import io.github.dsheirer.console.ConsoleLayoutManager;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.collections.ListChangeListener;
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
 * Tests for the alias sheet model: cell text, editing, validation, paste, fill down, undo/redo and console group
 * assignment.
 */
public class AliasSheetModelTest
{
    private static final String LIST = "Metro";
    private static final List<AliasSheetColumn> COLUMNS = List.of(AliasSheetColumn.values());

    @TempDir
    Path mTempDir;

    private AliasModel mAliasModel;
    private ConsoleLayoutManager mConsoleLayoutManager;
    private AliasSheetModel mModel;
    private Alias mFire;
    private Alias mAmbulance;
    private Alias mPolice;

    @BeforeEach
    void setup()
    {
        mAliasModel = new AliasModel();
        mFire = alias("Fire Dispatch", 1001);
        mAmbulance = alias("Amb Dispatch", 2001);
        mPolice = alias("Police Ops", 3001);
        mAliasModel.addAliases(new ArrayList<>(List.of(mFire, mAmbulance, mPolice)));

        mConsoleLayoutManager = new ConsoleLayoutManager(mTempDir.resolve("layout.console.json"));
        mConsoleLayoutManager.setAliasModel(mAliasModel);
        mConsoleLayoutManager.load();

        mModel = new AliasSheetModel(mAliasModel, mConsoleLayoutManager);
        mModel.setIconNameSupplier(() -> List.of("Fire", "Police", "Ambulance"));
        mModel.setBroadcastChannelSupplier(() -> List.of("Broadcastify 1", "Rdio"));
    }

    private static Alias alias(String name, int talkgroup)
    {
        Alias alias = new Alias(name);
        alias.setAliasListName(LIST);
        alias.addAliasID(new Talkgroup(Protocol.APCO25, talkgroup));
        return alias;
    }

    private List<Alias> rows()
    {
        return new ArrayList<>(mAliasModel.aliasList());
    }

    private static int col(AliasSheetColumn column)
    {
        return COLUMNS.indexOf(column);
    }

    private static Talkgroup talkgroup(Alias alias)
    {
        return AliasSheetModel.getTalkgroup(alias);
    }

    @Test
    void cellText()
    {
        assertEquals(LIST, mModel.getText(mFire, AliasSheetColumn.ALIAS_LIST));
        assertEquals("Fire Dispatch", mModel.getText(mFire, AliasSheetColumn.NAME));
        assertEquals("APCO-25", mModel.getText(mFire, AliasSheetColumn.PROTOCOL));
        assertEquals("1001", mModel.getText(mFire, AliasSheetColumn.TALKGROUP));
        assertEquals("", mModel.getText(mFire, AliasSheetColumn.COLOUR));
        assertEquals("Default", mModel.getText(mFire, AliasSheetColumn.LISTEN));
        assertEquals("No", mModel.getText(mFire, AliasSheetColumn.RECORD));
        assertEquals("", mModel.getText(mFire, AliasSheetColumn.STREAM));
        assertEquals("", mModel.getText(mFire, AliasSheetColumn.CONSOLE_GROUP));
    }

    @Test
    void editsAreAppliedToTheAlias()
    {
        assertTrue(mModel.editCell(mFire, AliasSheetColumn.TALKGROUP, "1002"));
        assertEquals(1002, talkgroup(mFire).getValue());
        assertEquals(1, mFire.getAliasIdentifiers().stream().filter(id -> id instanceof Talkgroup).count());

        mModel.editCell(mFire, AliasSheetColumn.COLOUR, "#ff8a3d");
        assertEquals(0xFFFF8A3D, mFire.getColor());
        assertEquals("#FF8A3D", mModel.getText(mFire, AliasSheetColumn.COLOUR));

        mModel.editCell(mFire, AliasSheetColumn.LISTEN, "Priority 5");
        assertEquals(5, mFire.getPlaybackPriority());
        mModel.editCell(mFire, AliasSheetColumn.LISTEN, "Do not monitor");
        assertEquals(Priority.DO_NOT_MONITOR, mFire.getPlaybackPriority());

        mModel.editCell(mFire, AliasSheetColumn.RECORD, "yes");
        assertTrue(mFire.isRecordable());

        mModel.editCell(mFire, AliasSheetColumn.ICON, "fire");
        assertEquals("Fire", mFire.getIconName());

        mModel.editCell(mFire, AliasSheetColumn.STREAM, "broadcastify 1; Rdio");
        assertTrue(mFire.hasBroadcastChannel("Broadcastify 1"));
        assertTrue(mFire.hasBroadcastChannel("Rdio"));
        assertEquals("Broadcastify 1; Rdio", mModel.getText(mFire, AliasSheetColumn.STREAM));

        mModel.editCell(mFire, AliasSheetColumn.GROUP, "Fire");
        assertEquals("Fire", mFire.getGroup());
    }

    @Test
    void editsNotifyAliasModelListeners()
    {
        List<String> events = new ArrayList<>();
        mAliasModel.aliasList().addListener((ListChangeListener<Alias>)change -> events.add("change"));

        mModel.editCell(mFire, AliasSheetColumn.NAME, "Fire Main");
        assertFalse(events.isEmpty(), "Alias model listeners (e.g. playlist save) should be notified");
    }

    @Test
    void aliasListChangeMovesTheAlias()
    {
        mModel.editCell(mFire, AliasSheetColumn.ALIAS_LIST, "Regional");
        assertEquals("Regional", mFire.getAliasListName());
        assertTrue(mAliasModel.getAliasList("Regional").aliases().contains(mFire));
        assertFalse(mAliasModel.getAliasList(LIST).aliases().contains(mFire));
        assertTrue(mAliasModel.aliasListNames().contains("Regional"));
    }

    @Test
    void invalidTalkgroupIsRetainedAndNotApplied()
    {
        mModel.editCell(mFire, AliasSheetColumn.TALKGROUP, "70000");

        assertEquals(1001, talkgroup(mFire).getValue(), "Invalid value should not be applied");
        assertEquals("70000", mModel.getText(mFire, AliasSheetColumn.TALKGROUP));
        assertNotNull(mModel.getError(mFire, AliasSheetColumn.TALKGROUP));
        assertTrue(mModel.getError(mFire, AliasSheetColumn.TALKGROUP).contains("0 to 65,535"));

        //Changing the protocol to one that accepts the value applies the pending talkgroup value
        mModel.editCell(mFire, AliasSheetColumn.PROTOCOL, "DMR");
        assertEquals(Protocol.DMR, talkgroup(mFire).getProtocol());
        assertEquals(70000, talkgroup(mFire).getValue());
        assertNull(mModel.getError(mFire, AliasSheetColumn.TALKGROUP));
    }

    @Test
    void invalidProtocolAndValues()
    {
        mModel.editCell(mFire, AliasSheetColumn.PROTOCOL, "FOO");
        assertNotNull(mModel.getError(mFire, AliasSheetColumn.PROTOCOL));
        assertEquals(Protocol.APCO25, talkgroup(mFire).getProtocol());

        mModel.editCell(mFire, AliasSheetColumn.PROTOCOL, "P25");
        assertNull(mModel.getError(mFire, AliasSheetColumn.PROTOCOL));
        assertEquals("APCO-25", mModel.getText(mFire, AliasSheetColumn.PROTOCOL));

        mModel.editCell(mFire, AliasSheetColumn.COLOUR, "orange");
        assertNotNull(mModel.getError(mFire, AliasSheetColumn.COLOUR));
        mModel.editCell(mFire, AliasSheetColumn.STREAM, "Unknown Stream");
        assertNotNull(mModel.getError(mFire, AliasSheetColumn.STREAM));
        mModel.editCell(mFire, AliasSheetColumn.NAME, " ");
        assertEquals("Fire Dispatch", mFire.getName());
        assertNotNull(mModel.getError(mFire, AliasSheetColumn.NAME));
    }

    @Test
    void protocolWithoutTalkgroupIsKeptUntilTalkgroupEntered()
    {
        Alias alias = mModel.addRow(LIST);
        mModel.editCell(alias, AliasSheetColumn.PROTOCOL, "NXDN");
        assertEquals("NXDN", mModel.getText(alias, AliasSheetColumn.PROTOCOL));
        assertNull(talkgroup(alias));

        mModel.editCell(alias, AliasSheetColumn.TALKGROUP, "123");
        assertEquals(Protocol.NXDN, talkgroup(alias).getProtocol());
        assertEquals(123, talkgroup(alias).getValue());

        mModel.editCell(alias, AliasSheetColumn.TALKGROUP, "");
        assertNull(talkgroup(alias));
        assertEquals("NXDN", mModel.getText(alias, AliasSheetColumn.PROTOCOL));
    }

    @Test
    void complexAliasIsReadOnly()
    {
        Alias complex = alias("Patch", 1001);
        complex.addAliasID(new Talkgroup(Protocol.APCO25, 1002));
        complex.addAliasID(new TalkgroupRange(Protocol.APCO25, 5000, 5100));
        mAliasModel.addAlias(complex);

        assertFalse(mModel.isEditable(complex, AliasSheetColumn.TALKGROUP));
        assertFalse(mModel.isEditable(complex, AliasSheetColumn.PROTOCOL));
        assertTrue(mModel.isEditable(complex, AliasSheetColumn.NAME));
        assertEquals("1001, 1002 +1 more", mModel.getText(complex, AliasSheetColumn.TALKGROUP));
        assertEquals("APCO-25", mModel.getText(complex, AliasSheetColumn.PROTOCOL));

        assertFalse(mModel.editCell(complex, AliasSheetColumn.TALKGROUP, "9"));
        assertEquals(3, complex.getAliasIdentifiers().size());

        Alias withAction = alias("Beeper", 4000);
        withAction.addAliasAction(new BeepAction());
        assertTrue(AliasSheetModel.isComplex(withAction));
        assertEquals("4000 +1 more", AliasSheetModel.getIdentifierSummary(withAction));
    }

    @Test
    void pasteIntoSelection()
    {
        List<Alias> rows = rows();
        AliasSheetModel.PasteResult result = mModel.paste(rows, COLUMNS, SheetRange.cell(0, col(AliasSheetColumn.NAME)),
            DelimitedText.parseTsv("Fire Main\t\t1101\r\nAmb Main\tP25\t2101\r\n"), LIST);

        assertEquals(0, result.addedRows());
        assertEquals("Fire Main", mFire.getName());
        assertEquals(1101, talkgroup(mFire).getValue());
        assertEquals("Amb Main", mAmbulance.getName());
        assertEquals(2101, talkgroup(mAmbulance).getValue());
        assertEquals("Police Ops", mPolice.getName());
        assertEquals(new SheetRange(0, 1, col(AliasSheetColumn.NAME), col(AliasSheetColumn.TALKGROUP)), result.range());
    }

    @Test
    void pasteSingleValueFillsSelection()
    {
        List<Alias> rows = rows();
        int colour = col(AliasSheetColumn.COLOUR);
        mModel.paste(rows, COLUMNS, new SheetRange(0, 2, colour, colour), List.of(List.of("#00FF00")), LIST);

        for(Alias alias: rows)
        {
            assertEquals("#00FF00", mModel.getText(alias, AliasSheetColumn.COLOUR));
        }
    }

    @Test
    void pasteRaggedRowsSkipsMissingValues()
    {
        List<Alias> rows = rows();
        mModel.paste(rows, COLUMNS, SheetRange.cell(0, col(AliasSheetColumn.GROUP)),
            DelimitedText.parseTsv("Fire\tFire Main\nAmb\n"), LIST);

        assertEquals("Fire", mFire.getGroup());
        assertEquals("Fire Main", mFire.getName());
        assertEquals("Amb", mAmbulance.getGroup());
        assertEquals("Amb Dispatch", mAmbulance.getName(), "Missing ragged value should not change the cell");
    }

    @Test
    void pastePastLastRowAddsRowsWithValidation()
    {
        List<Alias> rows = rows();
        AliasSheetModel.PasteResult result = mModel.paste(rows, COLUMNS,
            SheetRange.cell(2, col(AliasSheetColumn.NAME)),
            DelimitedText.parseTsv("Police Main\tAPCO-25\t3101\nRail Ops\tP25\t5100\nBad Row\tP25\tabc\n"), LIST);

        assertEquals(2, result.addedRows());
        assertEquals(1, result.invalidCells());
        assertEquals(5, mAliasModel.aliasList().size());
        assertEquals("Police Main", mPolice.getName());

        Alias rail = mAliasModel.aliasList().get(3);
        Alias bad = mAliasModel.aliasList().get(4);
        assertEquals("Rail Ops", rail.getName());
        assertEquals(LIST, rail.getAliasListName());
        assertEquals(5100, talkgroup(rail).getValue());
        assertEquals("Bad Row", bad.getName());
        assertNull(talkgroup(bad));
        assertEquals("abc", mModel.getText(bad, AliasSheetColumn.TALKGROUP));
        assertNotNull(mModel.getError(bad, AliasSheetColumn.TALKGROUP));
    }

    @Test
    void pasteSkipsReadOnlyCellsAndExtraColumns()
    {
        Alias complex = alias("Patch", 1001);
        complex.addAliasID(new TalkgroupRange(Protocol.APCO25, 5000, 5100));
        mAliasModel.addAlias(complex);

        List<Alias> rows = List.of(complex);
        int last = COLUMNS.size() - 1;
        AliasSheetModel.PasteResult result = mModel.paste(rows, COLUMNS,
            SheetRange.cell(0, col(AliasSheetColumn.NAME)), List.of(List.of("Patch 2", "P25", "9")), LIST);
        assertEquals("Patch 2", complex.getName());
        assertEquals(2, result.skippedCells());

        //Values beyond the last column are ignored
        result = mModel.paste(rows, COLUMNS, SheetRange.cell(0, last), List.of(List.of("", "extra")), LIST);
        assertEquals(new SheetRange(0, 0, last, last), result.range());
    }

    @Test
    void fillDown()
    {
        List<Alias> rows = rows();
        int colour = col(AliasSheetColumn.COLOUR);
        int listen = col(AliasSheetColumn.LISTEN);
        mModel.editCell(mFire, AliasSheetColumn.COLOUR, "#FF0000");
        mModel.editCell(mFire, AliasSheetColumn.LISTEN, "Priority 2");

        assertEquals(4, mModel.fillDown(rows, COLUMNS, new SheetRange(0, 2, colour, listen)));
        assertEquals(0xFFFF0000, mPolice.getColor());
        assertEquals(2, mPolice.getPlaybackPriority());

        //A single row range is filled from the row above
        mModel.editCell(mAmbulance, AliasSheetColumn.GROUP, "EMS");
        mModel.fillDown(rows, COLUMNS, SheetRange.cell(2, col(AliasSheetColumn.GROUP)));
        assertEquals("EMS", mPolice.getGroup());

        //Fill down undo is a single operation
        mModel.undo();
        assertNull(mPolice.getGroup());
    }

    @Test
    void undoRedoCellEdits()
    {
        mModel.editCell(mFire, AliasSheetColumn.NAME, "Renamed");
        mModel.editCell(mFire, AliasSheetColumn.TALKGROUP, "1500");
        mModel.editCell(mFire, AliasSheetColumn.PROTOCOL, "DMR");

        assertTrue(mModel.undo());
        assertEquals(Protocol.APCO25, talkgroup(mFire).getProtocol());
        assertTrue(mModel.undo());
        assertEquals(1001, talkgroup(mFire).getValue());
        assertTrue(mModel.undo());
        assertEquals("Fire Dispatch", mFire.getName());
        assertFalse(mModel.undo());

        assertTrue(mModel.redo());
        assertEquals("Renamed", mFire.getName());
        assertTrue(mModel.redo());
        assertEquals(1500, talkgroup(mFire).getValue());

        //A new edit clears the redo stack
        mModel.editCell(mFire, AliasSheetColumn.GROUP, "G");
        assertFalse(mModel.redo());
    }

    @Test
    void undoInvalidEditClearsError()
    {
        mModel.editCell(mFire, AliasSheetColumn.TALKGROUP, "abc");
        assertNotNull(mModel.getError(mFire, AliasSheetColumn.TALKGROUP));
        mModel.undo();
        assertNull(mModel.getError(mFire, AliasSheetColumn.TALKGROUP));
        assertEquals("1001", mModel.getText(mFire, AliasSheetColumn.TALKGROUP));
    }

    @Test
    void undoRedoPasteWithAddedRows()
    {
        List<Alias> rows = rows();
        mModel.paste(rows, COLUMNS, SheetRange.cell(2, col(AliasSheetColumn.NAME)),
            DelimitedText.parseTsv("Police Main\nNew One\nNew Two\n"), LIST);
        assertEquals(5, mAliasModel.aliasList().size());

        mModel.undo();
        assertEquals(3, mAliasModel.aliasList().size());
        assertEquals("Police Ops", mPolice.getName());

        mModel.redo();
        assertEquals(5, mAliasModel.aliasList().size());
        assertEquals("Police Main", mPolice.getName());
        assertEquals("New Two", mAliasModel.aliasList().get(4).getName());
    }

    @Test
    void addAndDeleteRowsWithUndo()
    {
        Alias added = mModel.addRow(LIST);
        assertEquals(AliasSheetModel.DEFAULT_ALIAS_NAME, added.getName());
        assertEquals(LIST, added.getAliasListName());
        assertTrue(mAliasModel.aliasList().contains(added));
        mModel.undo();
        assertFalse(mAliasModel.aliasList().contains(added));
        mModel.redo();
        assertTrue(mAliasModel.aliasList().contains(added));

        ConsoleGroup group = mConsoleLayoutManager.addGroup("Fire & Rescue");
        ConsoleCard card = mConsoleLayoutManager.addCard(group.getId(), mFire.getUuid());
        mConsoleLayoutManager.setCardVolume(card.getId(), 0.5f);
        mConsoleLayoutManager.setCardPan(card.getId(), AudioPan.LEFT);

        assertEquals(2, mModel.deleteRows(List.of(mFire, mAmbulance)));
        assertFalse(mAliasModel.aliasList().contains(mFire));
        assertFalse(mAliasModel.aliasList().contains(mAmbulance));
        assertNull(mConsoleLayoutManager.getCardForAlias(mFire.getUuid()), "Card is removed with the alias");

        mModel.undo();
        assertTrue(mAliasModel.aliasList().contains(mFire));
        assertTrue(mAliasModel.aliasList().contains(mAmbulance));
        ConsoleCard restored = mConsoleLayoutManager.getCardForAlias(mFire.getUuid());
        assertNotNull(restored, "Card is restored on undo");
        assertEquals(0.5f, restored.getVolume());
        assertEquals(AudioPan.LEFT, restored.getPan());
        assertEquals("Fire & Rescue", mModel.getText(mFire, AliasSheetColumn.CONSOLE_GROUP));
    }

    @Test
    void consoleGroupAssignmentAddsMovesAndRemovesCards()
    {
        ConsoleGroup fire = mConsoleLayoutManager.addGroup("Fire & Rescue");
        ConsoleGroup police = mConsoleLayoutManager.addGroup("Police");
        assertEquals(List.of("Fire & Rescue", "Police"), mModel.getConsoleGroupNames());

        //Add
        mModel.editCell(mFire, AliasSheetColumn.CONSOLE_GROUP, "fire & rescue");
        ConsoleCard card = mConsoleLayoutManager.getCardForAlias(mFire.getUuid());
        assertNotNull(card);
        assertEquals(fire.getId(), mConsoleLayoutManager.getLayout().getGroupForCard(card.getId()).getId());
        assertEquals("Fire & Rescue", mModel.getText(mFire, AliasSheetColumn.CONSOLE_GROUP));
        assertTrue(mModel.isOnConsole(mFire));
        mConsoleLayoutManager.setCardMuted(card.getId(), true);

        //Move - the same card moves to the other group
        mModel.editCell(mFire, AliasSheetColumn.CONSOLE_GROUP, "Police");
        ConsoleCard moved = mConsoleLayoutManager.getCardForAlias(mFire.getUuid());
        assertEquals(card.getId(), moved.getId());
        assertEquals(police.getId(), mConsoleLayoutManager.getLayout().getGroupForCard(card.getId()).getId());
        assertEquals(1, mConsoleLayoutManager.getLayout().getCardCount(), "One card per alias");

        //Remove
        mModel.editCell(mFire, AliasSheetColumn.CONSOLE_GROUP, AliasSheetValues.NOT_ON_CONSOLE);
        assertNull(mConsoleLayoutManager.getCardForAlias(mFire.getUuid()));
        assertEquals("", mModel.getText(mFire, AliasSheetColumn.CONSOLE_GROUP));
        assertFalse(mModel.isOnConsole(mFire));

        //Undo the removal restores the card settings
        mModel.undo();
        ConsoleCard restored = mConsoleLayoutManager.getCardForAlias(mFire.getUuid());
        assertNotNull(restored);
        assertTrue(restored.isMuted());
        assertEquals("Police", mModel.getText(mFire, AliasSheetColumn.CONSOLE_GROUP));

        //A group that does not exist is created
        mModel.editCell(mAmbulance, AliasSheetColumn.CONSOLE_GROUP, "Ambulance");
        assertEquals(List.of("Fire & Rescue", "Police", "Ambulance"), mModel.getConsoleGroupNames());
        assertEquals("Ambulance", mModel.getText(mAmbulance, AliasSheetColumn.CONSOLE_GROUP));
    }

    @Test
    void fillDownConsoleGroupAssignsCards()
    {
        mConsoleLayoutManager.addGroup("Dispatch");
        List<Alias> rows = rows();
        int console = col(AliasSheetColumn.CONSOLE_GROUP);
        mModel.editCell(mFire, AliasSheetColumn.CONSOLE_GROUP, "Dispatch");
        mModel.fillDown(rows, COLUMNS, new SheetRange(0, 2, console, console));

        assertEquals(3, mConsoleLayoutManager.getLayout().getCardCount());

        mModel.undo();
        assertEquals(1, mConsoleLayoutManager.getLayout().getCardCount());
    }

    @Test
    void consoleCardSignatureFollowsRename()
    {
        ConsoleGroup group = mConsoleLayoutManager.addGroup("Dispatch");
        ConsoleCard card = mConsoleLayoutManager.addCard(group.getId(), mFire.getUuid());
        mModel.editCell(mFire, AliasSheetColumn.NAME, "Fire Main");
        mModel.editCell(mFire, AliasSheetColumn.TALKGROUP, "1111");

        ConsoleCard updated = mConsoleLayoutManager.getCard(card.getId());
        assertEquals("Fire Main", updated.getSignature().getAliasName());
        assertEquals(1111, updated.getSignature().getTalkgroup());
    }

    @Test
    void clearAndCopy()
    {
        List<Alias> rows = rows();
        mModel.editCell(mFire, AliasSheetColumn.RECORD, "Yes");
        mModel.editCell(mFire, AliasSheetColumn.LISTEN, "Priority 3");

        String copied = mModel.copy(rows, COLUMNS, new SheetRange(0, 1, col(AliasSheetColumn.NAME),
            col(AliasSheetColumn.TALKGROUP)));
        assertEquals("Fire Dispatch\tAPCO-25\t1001\nAmb Dispatch\tAPCO-25\t2001\n", copied);

        mModel.clear(rows, COLUMNS, new SheetRange(0, 0, col(AliasSheetColumn.LISTEN), col(AliasSheetColumn.RECORD)));
        assertFalse(mFire.isRecordable());
        assertEquals(Priority.DEFAULT_PRIORITY, mFire.getPlaybackPriority());
    }

    @Test
    void resetClearsUndoHistoryAndInvalidValues()
    {
        mModel.editCell(mFire, AliasSheetColumn.TALKGROUP, "abc");
        assertTrue(mModel.getUndoStack().canUndoProperty().get());
        mModel.reset();
        assertFalse(mModel.getUndoStack().canUndoProperty().get());
        assertNull(mModel.getError(mFire, AliasSheetColumn.TALKGROUP));
        assertEquals("1001", mModel.getText(mFire, AliasSheetColumn.TALKGROUP));
    }

    @Test
    void conflictsAcrossModel()
    {
        mModel.editCell(mAmbulance, AliasSheetColumn.TALKGROUP, "1001");
        TalkgroupConflictDetector.Report report = mModel.findConflicts();
        assertEquals(1, report.getConflicts().size());
        assertTrue(report.hasConflict(mFire));
        assertTrue(report.hasConflict(mAmbulance));
        assertFalse(report.hasConflict(mPolice));
        assertSame(mFire, report.getConflicts().getFirst().aliases().getFirst());
    }
}
