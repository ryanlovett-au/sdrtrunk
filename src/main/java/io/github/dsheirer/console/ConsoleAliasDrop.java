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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Drag and drop of aliases (e.g. rows of the alias sheet) onto a console group: the drag payload format and the logic
 * that adds or moves the aliases' console cards.  Independent of JavaFX so that it can be unit tested; the user
 * interface wraps the payload in a custom data format (MIME_TYPE).
 *
 * Dropping aliases onto a group gives each alias one card in that group, in drag order: aliases without a card get a
 * new card appended to the group, aliases whose card is in another group have the card moved (keeping its audio
 * settings) to the end of the group, and aliases whose card is already in the group are left in place.
 */
public final class ConsoleAliasDrop
{
    /**
     * MIME type of the drag and drop payload: the alias unique identifiers, one per line.
     */
    public static final String MIME_TYPE = "application/x-sdrtrunk-alias-uuids";

    private ConsoleAliasDrop()
    {
    }

    /**
     * Encodes the alias unique identifiers as a drag and drop payload: one identifier per line, in order, without
     * blank or duplicate identifiers.
     * @param aliasUuids to encode
     * @return payload
     */
    public static String encode(Collection<String> aliasUuids)
    {
        return String.join("\n", normalize(aliasUuids));
    }

    /**
     * Parses a drag and drop payload.
     * @param content payload: a string of identifiers separated by line breaks, commas or whitespace, or a collection
     * of identifiers; anything else (including null) yields an empty list
     * @return alias unique identifiers in order, without blanks or duplicates.
     */
    public static List<String> parse(Object content)
    {
        List<String> values = new ArrayList<>();

        if(content instanceof String text)
        {
            for(String value: text.split("[\\s,]+"))
            {
                values.add(value);
            }
        }
        else if(content instanceof Collection<?> collection)
        {
            for(Object value: collection)
            {
                if(value != null)
                {
                    values.add(value.toString());
                }
            }
        }

        return normalize(values);
    }

    /**
     * Trims the identifiers and removes blank and duplicate identifiers, keeping the first occurrence order.
     */
    private static List<String> normalize(Collection<String> aliasUuids)
    {
        Set<String> unique = new LinkedHashSet<>();

        if(aliasUuids != null)
        {
            for(String uuid: aliasUuids)
            {
                if(uuid != null && !uuid.isBlank())
                {
                    unique.add(uuid.trim());
                }
            }
        }

        return new ArrayList<>(unique);
    }

    /**
     * Label for the drag view, e.g. "1 alias" or "3 aliases".
     */
    public static String getDragLabel(int count)
    {
        return count + (count == 1 ? " alias" : " aliases");
    }

    /**
     * Adds or moves the aliases' console cards to the group (see the class description).
     *
     * @param layoutManager for the console layout
     * @param groupId of the target group
     * @param aliasUuids in drag order
     * @param aliasExists indicates if an alias exists for the unique identifier; aliases that do not exist are skipped
     * so that no unlinked cards are created
     * @return counts of added, moved, unchanged and skipped aliases
     */
    public static Result apply(ConsoleLayoutManager layoutManager, String groupId, List<String> aliasUuids,
                               Predicate<String> aliasExists)
    {
        List<String> uuids = normalize(aliasUuids);

        if(layoutManager == null || groupId == null || layoutManager.getGroup(groupId) == null)
        {
            return new Result(0, 0, 0, uuids.size());
        }

        int added = 0;
        int moved = 0;
        int unchanged = 0;
        int skipped = 0;

        for(String uuid: uuids)
        {
            if(aliasExists != null && !aliasExists.test(uuid))
            {
                skipped++;
                continue;
            }

            ConsoleLayout layout = layoutManager.getLayout();
            ConsoleCard card = layout.getCardForAlias(uuid);

            try
            {
                if(card == null)
                {
                    layoutManager.addCard(groupId, uuid);
                    added++;
                }
                else
                {
                    ConsoleGroup current = layout.getGroupForCard(card.getId());

                    if(current != null && groupId.equals(current.getId()))
                    {
                        unchanged++;
                    }
                    else if(layoutManager.moveCard(card.getId(), groupId, Integer.MAX_VALUE))
                    {
                        moved++;
                    }
                    else
                    {
                        skipped++;
                    }
                }
            }
            catch(IllegalArgumentException | IllegalStateException e)
            {
                //The group was removed, or the alias was assigned to a card concurrently
                skipped++;
            }
        }

        return new Result(added, moved, unchanged, skipped);
    }

    /**
     * Outcome of a drop.
     * @param added number of aliases that got a new card
     * @param moved number of aliases whose card was moved from another group
     * @param unchanged number of aliases whose card was already in the group
     * @param skipped number of aliases that were not found (or could not be placed)
     */
    public record Result(int added, int moved, int unchanged, int skipped)
    {
        /**
         * Indicates if any card was added or moved.
         */
        public boolean isChanged()
        {
            return added > 0 || moved > 0;
        }

        /**
         * Short description, e.g. "2 added, 1 moved to Fire".
         * @param groupName of the target group
         */
        public String describe(String groupName)
        {
            List<String> parts = new ArrayList<>();

            if(added > 0)
            {
                parts.add(added + " added");
            }

            if(moved > 0)
            {
                parts.add(moved + " moved");
            }

            if(unchanged > 0)
            {
                parts.add(unchanged + " already there");
            }

            if(skipped > 0)
            {
                parts.add(skipped + " not found");
            }

            return (parts.isEmpty() ? "Nothing to add" : String.join(", ", parts)) + " - " + groupName;
        }
    }
}
