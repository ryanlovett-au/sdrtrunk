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
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupFormatter;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Detects talkgroup conflicts across a set of aliases:
 * - Two or more aliases in the same alias list with the same protocol and talkgroup value.
 * - Two aliases in the same alias list with overlapping talkgroup ranges for the same protocol.
 *
 * A talkgroup that falls within another alias's talkgroup range is not a conflict, since the exact talkgroup match
 * takes precedence over the range (see AliasList).  P25 fully qualified talkgroups are not checked.
 */
public class TalkgroupConflictDetector
{
    private TalkgroupConflictDetector()
    {
        //Static utility class
    }

    /**
     * Finds the talkgroup conflicts across the aliases.
     * @param aliases to check
     * @return conflict report
     */
    public static Report findConflicts(Collection<Alias> aliases)
    {
        Map<TalkgroupKey,List<Alias>> talkgroupMap = new LinkedHashMap<>();
        Map<RangeKey,List<RangeEntry>> rangeMap = new LinkedHashMap<>();

        for(Alias alias: aliases)
        {
            if(alias == null)
            {
                continue;
            }

            String listName = getListName(alias);

            for(AliasID aliasID: alias.getAliasIdentifiers())
            {
                if(aliasID.getType() == AliasIDType.TALKGROUP && aliasID instanceof Talkgroup talkgroup &&
                    talkgroup.getProtocol() != null)
                {
                    List<Alias> users = talkgroupMap.computeIfAbsent(new TalkgroupKey(listName,
                        talkgroup.getProtocol(), talkgroup.getValue()), key -> new ArrayList<>());

                    if(!containsIdentity(users, alias))
                    {
                        users.add(alias);
                    }
                }
                else if(aliasID instanceof TalkgroupRange range && range.getProtocol() != null)
                {
                    rangeMap.computeIfAbsent(new RangeKey(listName, range.getProtocol()), key -> new ArrayList<>())
                        .add(new RangeEntry(range, alias));
                }
            }
        }

        List<TalkgroupConflict> conflicts = new ArrayList<>();

        for(Map.Entry<TalkgroupKey,List<Alias>> entry: talkgroupMap.entrySet())
        {
            if(entry.getValue().size() > 1)
            {
                TalkgroupKey key = entry.getKey();
                conflicts.add(new TalkgroupConflict(key.listName(), key.protocol(),
                    "TG " + format(key.protocol(), key.value()), List.copyOf(entry.getValue())));
            }
        }

        for(Map.Entry<RangeKey,List<RangeEntry>> entry: rangeMap.entrySet())
        {
            List<RangeEntry> ranges = entry.getValue();

            for(int x = 0; x < ranges.size(); x++)
            {
                for(int y = x + 1; y < ranges.size(); y++)
                {
                    RangeEntry a = ranges.get(x);
                    RangeEntry b = ranges.get(y);

                    if(a.alias() != b.alias() && a.range().overlaps(b.range()))
                    {
                        conflicts.add(new TalkgroupConflict(entry.getKey().listName(), entry.getKey().protocol(),
                            "TG ranges " + format(a.range()) + " and " + format(b.range()),
                            List.of(a.alias(), b.alias())));
                    }
                }
            }
        }

        return new Report(conflicts);
    }

    /**
     * Alias list name for the alias, or an empty string when the alias does not have an alias list.
     */
    private static String getListName(Alias alias)
    {
        String name = alias.getAliasListName();

        if(name == null || name.isEmpty() || name.equals(AliasModel.NO_ALIAS_LIST))
        {
            return "";
        }

        return name;
    }

    private static boolean containsIdentity(List<Alias> aliases, Alias alias)
    {
        for(Alias existing: aliases)
        {
            if(existing == alias)
            {
                return true;
            }
        }

        return false;
    }

    private static String format(Protocol protocol, int value)
    {
        try
        {
            return TalkgroupFormatter.format(protocol, value);
        }
        catch(Exception e)
        {
            return String.valueOf(value);
        }
    }

    private static String format(TalkgroupRange range)
    {
        return format(range.getProtocol(), range.getMinTalkgroup()) + "-" +
            format(range.getProtocol(), range.getMaxTalkgroup());
    }

    private record TalkgroupKey(String listName, Protocol protocol, int value)
    {
    }

    private record RangeKey(String listName, Protocol protocol)
    {
    }

    private record RangeEntry(TalkgroupRange range, Alias alias)
    {
    }

    /**
     * Result of a conflict check.
     */
    public static class Report
    {
        private final List<TalkgroupConflict> mConflicts;
        private final Map<Alias,List<TalkgroupConflict>> mAliasConflicts = new IdentityHashMap<>();

        /**
         * Constructs an instance
         * @param conflicts that were detected
         */
        public Report(List<TalkgroupConflict> conflicts)
        {
            mConflicts = Collections.unmodifiableList(conflicts);

            for(TalkgroupConflict conflict: conflicts)
            {
                for(Alias alias: conflict.aliases())
                {
                    mAliasConflicts.computeIfAbsent(alias, key -> new ArrayList<>()).add(conflict);
                }
            }
        }

        /**
         * Empty report
         */
        public static Report empty()
        {
            return new Report(new ArrayList<>());
        }

        /**
         * All conflicts
         */
        public List<TalkgroupConflict> getConflicts()
        {
            return mConflicts;
        }

        /**
         * Conflicts that the alias is part of.
         */
        public List<TalkgroupConflict> getConflicts(Alias alias)
        {
            List<TalkgroupConflict> conflicts = mAliasConflicts.get(alias);
            return conflicts != null ? conflicts : Collections.emptyList();
        }

        /**
         * Indicates if the alias is part of a conflict.
         */
        public boolean hasConflict(Alias alias)
        {
            return mAliasConflicts.containsKey(alias);
        }

        /**
         * Conflicts for the alias list, or all conflicts when the alias list name is null.
         * @param aliasListName to match, or null for all alias lists
         */
        public List<TalkgroupConflict> getConflictsForList(String aliasListName)
        {
            if(aliasListName == null)
            {
                return mConflicts;
            }

            String listName = aliasListName.equals(AliasModel.NO_ALIAS_LIST) ? "" : aliasListName;
            List<TalkgroupConflict> conflicts = new ArrayList<>();

            for(TalkgroupConflict conflict: mConflicts)
            {
                if(Objects.equals(conflict.aliasListName(), listName))
                {
                    conflicts.add(conflict);
                }
            }

            return conflicts;
        }
    }
}
