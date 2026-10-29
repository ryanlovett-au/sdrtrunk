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

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for detaching the console layout file before it is replaced on disk (configuration import).
 */
public class ConsoleLayoutDetachTest
{
    private static final String IMPORTED_LAYOUT = "{ \"version\" : 1, \"consoleModeEnabled\" : true, " +
        "\"groups\" : [ { \"id\" : \"g1\", \"name\" : \"Imported\", \"displayOrder\" : 0, \"cards\" : [ ] } ] }";

    @TempDir
    Path mTempDir;

    @Test
    public void detachSavesPendingChangesAndStopsSaving() throws Exception
    {
        Path layoutPath = mTempDir.resolve("default.console.json");
        ConsoleLayoutManager manager = new ConsoleLayoutManager();
        manager.load(layoutPath);

        //A pending change is saved by detach
        manager.addGroup("Pending");
        assertTrue(manager.isSavePending());
        manager.detach();
        assertFalse(manager.isSavePending());
        assertNull(manager.getLayoutPath());
        assertTrue(Files.readString(layoutPath).contains("Pending"));

        //The layout file is replaced (import) while the old layout is still in memory and changes
        Files.writeString(layoutPath, IMPORTED_LAYOUT);
        manager.addGroup("After detach");
        manager.saveNow();
        assertTrue(Files.readString(layoutPath).contains("Imported"), "Detached layout must not overwrite the file");

        //Loading the replaced file doesn't save the old in-memory layout over it
        manager.load(layoutPath);
        assertTrue(manager.isConsoleModeEnabled());
        assertTrue(manager.getLayout().getGroups().stream().anyMatch(group -> group.getName().equals("Imported")));
        assertFalse(manager.getLayout().getGroups().stream().anyMatch(group -> group.getName().equals("After detach")));
        assertTrue(Files.readString(layoutPath).contains("Imported"));
        manager.shutdown();
    }
}
