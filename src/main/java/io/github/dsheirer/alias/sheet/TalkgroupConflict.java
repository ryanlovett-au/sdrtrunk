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
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Talkgroup conflict: two or more aliases in the same alias list that use the same talkgroup value, or overlapping
 * talkgroup ranges, for the same protocol.  Only one of the aliases will be matched to calls for the talkgroup.
 *
 * @param aliasListName of the conflicting aliases (empty for aliases without an alias list)
 * @param protocol of the talkgroup(s)
 * @param description of the talkgroup value or ranges, e.g. 'TG 3002' or 'TG ranges 100-200 and 150-250'
 * @param aliases that conflict, in alias model order
 */
public record TalkgroupConflict(String aliasListName, Protocol protocol, String description, List<Alias> aliases)
{
    /**
     * Indicates if the alias is part of this conflict.
     */
    public boolean contains(Alias alias)
    {
        for(Alias conflicting: aliases)
        {
            if(conflicting == alias)
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Summary message, e.g. 'TG 3002 (APCO-25) is used by 2 aliases: Police Ops South, Police South Alt'
     */
    public String getMessage()
    {
        return description + " (" + protocol + ") is used by " + aliases.size() + " aliases: " +
            aliases.stream().map(alias -> alias.getName() != null ? alias.getName() : "(unnamed)")
                .collect(Collectors.joining(", "));
    }
}
