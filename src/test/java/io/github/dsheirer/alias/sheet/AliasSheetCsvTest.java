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
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.console.ConsoleLayoutManager;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for alias sheet CSV export and import.
 */
public class AliasSheetCsvTest
{
    private static final String LIST = "Metro";

    @TempDir
    Path mTempDir;

    private AliasSheetModel createModel(AliasModel aliasModel, String layoutName)
    {
        ConsoleLayoutManager manager = new ConsoleLayoutManager(mTempDir.resolve(layoutName));
        manager.setAliasModel(aliasModel);
        manager.load();
        manager.addGroup("Fire & Rescue");
        AliasSheetModel model = new AliasSheetModel(aliasModel, manager);
        model.setBroadcastChannelSupplier(() -> List.of("Broadcastify 1"));
        return model;
    }

    private static Alias alias(String name, int talkgroup)
    {
        Alias alias = new Alias(name);
        alias.setAliasListName(LIST);
        alias.addAliasID(new Talkgroup(Protocol.APCO25, talkgroup));
        return alias;
    }

    private static List<String> texts(AliasSheetModel model, Alias alias)
    {
        List<String> texts = new ArrayList<>();

        for(AliasSheetColumn column: AliasSheetColumn.values())
        {
            texts.add(model.getText(alias, column));
        }

        return texts;
    }

    @Test
    void exportAndAppendImportRoundTrip()
    {
        AliasModel source = new AliasModel();
        Alias fire = alias("Fire, Dispatch", 1001);
        fire.setGroup("Fire");
        fire.setColor(0xFFFF8A3D);
        fire.setRecordable(true);
        fire.setCallPriority(2);
        Alias rail = alias("Rail \"Ops\"", 5100);
        rail.setCallPriority(-1);
        source.addAliases(new ArrayList<>(List.of(fire, rail)));
        AliasSheetModel sourceModel = createModel(source, "source.console.json");
        sourceModel.editCell(fire, AliasSheetColumn.STREAM, "Broadcastify 1");
        sourceModel.editCell(fire, AliasSheetColumn.CONSOLE_GROUP, "Fire & Rescue");

        String csv = AliasSheetCsv.export(sourceModel, List.of(fire, rail));
        assertTrue(csv.startsWith("Alias list,Group,Name,Protocol,Talkgroup,Colour,Icon,Listen,Record,Stream," +
            "Console group\r\n"));

        AliasModel target = new AliasModel();
        AliasSheetModel targetModel = createModel(target, "target.console.json");
        AliasSheetCsv.ImportPlan plan = AliasSheetCsv.plan(targetModel, csv, AliasSheetCsv.ImportMode.APPEND, null);

        assertTrue(plan.canApply(), plan.getSummary());
        assertEquals(2, plan.getNewCount());
        assertEquals(0, plan.getInvalidCount());

        targetModel.applyImport(plan);
        assertEquals(2, target.aliasList().size());
        assertEquals(texts(sourceModel, fire), texts(targetModel, target.aliasList().get(0)));
        assertEquals(texts(sourceModel, rail), texts(targetModel, target.aliasList().get(1)));
        assertTrue(targetModel.isOnConsole(target.aliasList().get(0)));

        //Import is a single undoable operation
        targetModel.undo();
        assertTrue(target.aliasList().isEmpty());
        assertEquals(0, targetModel.getConsoleLayoutManager().getLayout().getCardCount());
    }

    @Test
    void updateByNameImport()
    {
        AliasModel aliasModel = new AliasModel();
        Alias fire = alias("Fire Dispatch", 1001);
        Alias amb = alias("Amb Dispatch", 2001);
        aliasModel.addAliases(new ArrayList<>(List.of(fire, amb)));
        AliasSheetModel model = createModel(aliasModel, "update.console.json");

        String csv = "name,tg,color,Notes\r\n" +
            "fire dispatch,1002,#00FF00,ignored\r\n" +
            "Amb Dispatch,2001,,\r\n" +
            "Rail Ops,5100,,\r\n" +
            "Bad TG,abc,,\r\n" +
            ",,,\r\n";

        AliasSheetCsv.ImportPlan plan = AliasSheetCsv.plan(model, csv, AliasSheetCsv.ImportMode.UPDATE_BY_NAME, LIST);

        assertEquals(List.of("Notes"), plan.getIgnoredColumns());
        assertEquals(2, plan.getNewCount());
        assertEquals(1, plan.getUpdatedCount());
        assertEquals(2, plan.getChangedCellCount());
        assertEquals(1, plan.getUnchangedCount());
        assertEquals(1, plan.getInvalidCount());
        assertFalse(plan.getDetails(100).isEmpty());
        assertNotNull(plan.getSummary());

        model.applyImport(plan);
        assertEquals(1002, AliasSheetModel.getTalkgroup(fire).getValue());
        assertEquals(0xFF00FF00, fire.getColor());
        assertEquals("Fire Dispatch", fire.getName(), "Name match ignores case and keeps the existing name");
        assertEquals(2001, AliasSheetModel.getTalkgroup(amb).getValue());
        assertEquals(4, aliasModel.aliasList().size());

        Alias rail = aliasModel.aliasList().get(2);
        assertEquals("Rail Ops", rail.getName());
        assertEquals(LIST, rail.getAliasListName());
        assertEquals(5100, AliasSheetModel.getTalkgroup(rail).getValue());

        Alias bad = aliasModel.aliasList().get(3);
        assertNotNull(model.getError(bad, AliasSheetColumn.TALKGROUP), "Invalid imported value is flagged");

        model.undo();
        assertEquals(1001, AliasSheetModel.getTalkgroup(fire).getValue());
        assertEquals(0, fire.getColor());
        assertEquals(2, aliasModel.aliasList().size());
    }

    @Test
    void updateByNameSkipsAmbiguousNames()
    {
        AliasModel aliasModel = new AliasModel();
        aliasModel.addAliases(new ArrayList<>(List.of(alias("Dup", 1), alias("Dup", 2))));
        AliasSheetModel model = createModel(aliasModel, "dup.console.json");

        AliasSheetCsv.ImportPlan plan = AliasSheetCsv.plan(model, "Name,Talkgroup\nDup,3\n",
            AliasSheetCsv.ImportMode.UPDATE_BY_NAME, LIST);

        assertEquals(1, plan.getSkippedCount());
        assertFalse(plan.canApply());
    }

    @Test
    void importRequiresNameColumn()
    {
        AliasSheetModel model = createModel(new AliasModel(), "name.console.json");
        AliasSheetCsv.ImportPlan plan = AliasSheetCsv.plan(model, "Talkgroup\n1\n", AliasSheetCsv.ImportMode.APPEND,
            LIST);
        assertFalse(plan.getErrors().isEmpty());
        assertFalse(plan.canApply());
    }
}
