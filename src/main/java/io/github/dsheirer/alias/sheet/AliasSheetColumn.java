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

import java.util.Locale;

/**
 * Columns of the alias sheet editor.  The enumeration order is the display order and the CSV export column order.
 */
public enum AliasSheetColumn
{
    ALIAS_LIST("Alias list", "list", "aliaslist"),
    GROUP("Group", "aliasgroup"),
    NAME("Alias", "name", "aliasname"),
    PROTOCOL("Protocol"),
    TALKGROUP("Talkgroup", "tg", "tgid", "talkgroupid"),
    COLOUR("Color", "colour"),
    ICON("Icon", "iconname"),
    LISTEN("Listen", "priority", "playbackpriority", "monitor"),
    RECORD("Record", "recordable"),
    STREAM("Stream", "streams", "broadcast", "broadcastchannel", "broadcastchannels"),
    CONSOLE_GROUP("Console group", "console", "consolecard");

    private final String mLabel;
    private final String[] mAlternateNames;

    AliasSheetColumn(String label, String... alternateNames)
    {
        mLabel = label;
        mAlternateNames = alternateNames;
    }

    /**
     * Column header label
     */
    public String getLabel()
    {
        return mLabel;
    }

    @Override
    public String toString()
    {
        return mLabel;
    }

    /**
     * Finds the column that matches a (CSV) header value.  Matching ignores case, spaces, dashes and underscores and
     * accepts a few alternate names (e.g. 'color' or 'TG').
     * @param header value to match
     * @return matching column or null.
     */
    public static AliasSheetColumn fromHeader(String header)
    {
        String normalized = normalize(header);

        if(normalized.isEmpty())
        {
            return null;
        }

        for(AliasSheetColumn column: values())
        {
            if(normalize(column.getLabel()).equals(normalized) || normalize(column.name()).equals(normalized))
            {
                return column;
            }

            for(String alternate: column.mAlternateNames)
            {
                if(alternate.equals(normalized))
                {
                    return column;
                }
            }
        }

        return null;
    }

    /**
     * Normalizes the value for loose matching: lower case with spaces, dashes and underscores removed.
     */
    private static String normalize(String value)
    {
        if(value == null)
        {
            return "";
        }

        return value.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "")
            .replace("_", "");
    }
}
