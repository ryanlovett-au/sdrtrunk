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
package io.github.dsheirer.gui.console;

import io.github.dsheirer.console.ConsoleAliasDrop;
import java.util.List;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;

/**
 * Drag and drop data format for dragging aliases (e.g. alias sheet rows) onto console groups.  The payload is the
 * list of alias unique identifiers (see ConsoleAliasDrop).
 */
public final class AliasDragFormat
{
    /**
     * Custom data format carrying the alias unique identifiers.
     */
    public static final DataFormat ALIAS_UUIDS = getOrCreate(ConsoleAliasDrop.MIME_TYPE);

    private AliasDragFormat()
    {
    }

    /**
     * Data formats are registered globally and can only be created once per MIME type.
     */
    private static DataFormat getOrCreate(String mimeType)
    {
        DataFormat existing = DataFormat.lookupMimeType(mimeType);
        return existing != null ? existing : new DataFormat(mimeType);
    }

    /**
     * Adds the alias unique identifiers to the drag content.
     */
    public static void put(ClipboardContent content, List<String> aliasUuids)
    {
        content.put(ALIAS_UUIDS, ConsoleAliasDrop.encode(aliasUuids));
    }

    /**
     * Indicates if the dragboard carries aliases.
     */
    public static boolean hasAliases(Dragboard dragboard)
    {
        return dragboard != null && dragboard.hasContent(ALIAS_UUIDS);
    }

    /**
     * Alias unique identifiers carried by the dragboard.
     * @return identifiers in drag order, or an empty list.
     */
    public static List<String> getAliasUuids(Dragboard dragboard)
    {
        return hasAliases(dragboard) ? ConsoleAliasDrop.parse(dragboard.getContent(ALIAS_UUIDS)) : List.of();
    }
}
