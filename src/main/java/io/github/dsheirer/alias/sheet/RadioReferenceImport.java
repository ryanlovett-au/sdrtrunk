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
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupFormat;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Plans the import of talkgroups pasted from a RadioReference.com talkgroup table (see RadioReferenceTable) into the
 * alias sheet.  The plan is previewed by the user and applied with AliasSheetModel.applyRadioReferenceImport() as a
 * single undoable operation.
 *
 * Conventions follow the RadioReference API import (RadioReferenceDecoder.createAlias() and the talkgroup selection
 * editor): one alias per talkgroup with a single talkgroup identifier in the selected alias list, the alias group from
 * the talkgroup's category/tag, no colour, and an option to set encrypted talkgroups to 'Do not monitor'.  The alias
 * name defaults to the description (the API import uses the alpha tag, which is available as an option).
 *
 * Duplicates: an existing alias in the same alias list that has a talkgroup identifier with the same protocol and
 * value is a duplicate.  It is skipped (default) or its name and group are updated.  A talkgroup that appears more than
 * once in the pasted table is imported once (later rows are skipped).
 *
 * Console: aliases can be added to the console, either in one console group per tag (aliases without a tag go to
 * the 'Other' group) or all in a single group.  Groups that don't exist are created (matched by name, ignoring case).
 * An alias may have one console card: aliases that are already on the console stay in their current group.
 *
 * RadioReference mode: the alias XML has no field for the RadioReference talkgroup mode and the console layout is
 * owned by the console, so the mode is not persisted by the import.  It is returned in the import Result (alias UUID
 * and mode for each imported, updated or duplicate alias) and retained by the sheet model for the session (see
 * AliasSheetModel.getRadioReferenceMode()) so that a later step can apply mode-specific settings, e.g. a console
 * clear-audio alert for encrypted (E, TE) talkgroups.
 */
public class RadioReferenceImport
{
    /**
     * Console group for aliases without a tag when adding one console group per tag.
     */
    public static final String UNTAGGED_CONSOLE_GROUP = "Other";

    private RadioReferenceImport()
    {
        //Static utility class
    }

    /**
     * Source of the alias name
     */
    public enum NameSource
    {
        DESCRIPTION("Description"),
        ALPHA_TAG("Alpha tag");

        private final String mLabel;

        NameSource(String label)
        {
            mLabel = label;
        }

        @Override
        public String toString()
        {
            return mLabel;
        }
    }

    /**
     * Source of the alias group
     */
    public enum GroupSource
    {
        TAG("Tag"),
        CATEGORY("Category"),
        NONE("None");

        private final String mLabel;

        GroupSource(String label)
        {
            mLabel = label;
        }

        @Override
        public String toString()
        {
            return mLabel;
        }
    }

    /**
     * Handling of existing aliases with the same alias list, protocol and talkgroup
     */
    public enum ExistingAliasAction
    {
        SKIP("Skip"),
        UPDATE("Update name and group");

        private final String mLabel;

        ExistingAliasAction(String label)
        {
            mLabel = label;
        }

        @Override
        public String toString()
        {
            return mLabel;
        }
    }

    /**
     * Handling of encrypted (E, TE) talkgroups
     */
    public enum EncryptedAction
    {
        IMPORT("Import"),
        IMPORT_DO_NOT_MONITOR("Import as 'Do not monitor'"),
        SKIP("Skip");

        private final String mLabel;

        EncryptedAction(String label)
        {
            mLabel = label;
        }

        @Override
        public String toString()
        {
            return mLabel;
        }
    }

    /**
     * Console assignment
     */
    public enum ConsoleMode
    {
        NONE("No"),
        GROUP_PER_TAG("One console group per tag"),
        SINGLE_GROUP("All into group");

        private final String mLabel;

        ConsoleMode(String label)
        {
            mLabel = label;
        }

        @Override
        public String toString()
        {
            return mLabel;
        }
    }

    /**
     * Planned action for a row
     */
    public enum Action
    {
        ADD("Add"),
        UPDATE("Update"),
        SKIP_DUPLICATE("Skip duplicate"),
        SKIP_UNCHANGED("Skip (no changes)"),
        SKIP_ENCRYPTED("Skip encrypted"),
        SKIP_INVALID("Skip invalid");

        private final String mLabel;

        Action(String label)
        {
            mLabel = label;
        }

        /**
         * Indicates if the row is not applied.
         */
        public boolean isSkipped()
        {
            return this != ADD && this != UPDATE;
        }

        @Override
        public String toString()
        {
            return mLabel;
        }
    }

    /**
     * Import options.  Defaults: APCO-25, name from the description, group from the tag, skip existing aliases,
     * import encrypted talkgroups and don't add to the console.
     */
    public static class Options
    {
        private String mAliasList;
        private Protocol mProtocol = Protocol.APCO25;
        private NameSource mNameSource = NameSource.DESCRIPTION;
        private GroupSource mGroupSource = GroupSource.TAG;
        private ExistingAliasAction mExistingAliasAction = ExistingAliasAction.SKIP;
        private EncryptedAction mEncryptedAction = EncryptedAction.IMPORT;
        private ConsoleMode mConsoleMode = ConsoleMode.NONE;
        private String mConsoleGroup;

        public String getAliasList()
        {
            return mAliasList;
        }

        public Options setAliasList(String aliasList)
        {
            mAliasList = aliasList;
            return this;
        }

        public Protocol getProtocol()
        {
            return mProtocol;
        }

        public Options setProtocol(Protocol protocol)
        {
            mProtocol = protocol != null ? protocol : Protocol.APCO25;
            return this;
        }

        public NameSource getNameSource()
        {
            return mNameSource;
        }

        public Options setNameSource(NameSource nameSource)
        {
            mNameSource = nameSource != null ? nameSource : NameSource.DESCRIPTION;
            return this;
        }

        public GroupSource getGroupSource()
        {
            return mGroupSource;
        }

        public Options setGroupSource(GroupSource groupSource)
        {
            mGroupSource = groupSource != null ? groupSource : GroupSource.TAG;
            return this;
        }

        public ExistingAliasAction getExistingAliasAction()
        {
            return mExistingAliasAction;
        }

        public Options setExistingAliasAction(ExistingAliasAction action)
        {
            mExistingAliasAction = action != null ? action : ExistingAliasAction.SKIP;
            return this;
        }

        public EncryptedAction getEncryptedAction()
        {
            return mEncryptedAction;
        }

        public Options setEncryptedAction(EncryptedAction action)
        {
            mEncryptedAction = action != null ? action : EncryptedAction.IMPORT;
            return this;
        }

        public ConsoleMode getConsoleMode()
        {
            return mConsoleMode;
        }

        public Options setConsoleMode(ConsoleMode consoleMode)
        {
            mConsoleMode = consoleMode != null ? consoleMode : ConsoleMode.NONE;
            return this;
        }

        /**
         * Console group name for ConsoleMode.SINGLE_GROUP
         */
        public String getConsoleGroup()
        {
            return mConsoleGroup;
        }

        public Options setConsoleGroup(String consoleGroup)
        {
            mConsoleGroup = consoleGroup;
            return this;
        }

        /**
         * Copy of these options
         */
        public Options copy()
        {
            return new Options().setAliasList(mAliasList).setProtocol(mProtocol).setNameSource(mNameSource)
                .setGroupSource(mGroupSource).setExistingAliasAction(mExistingAliasAction)
                .setEncryptedAction(mEncryptedAction).setConsoleMode(mConsoleMode).setConsoleGroup(mConsoleGroup);
        }
    }

    /**
     * Planned import of a table row.
     * @param source table row
     * @param name for the alias (new or updated)
     * @param group for the alias, empty for no group
     * @param action to apply
     * @param existing alias with the same talkgroup, or null
     * @param consoleGroup console group for a new card, or null for no (new) card
     * @param currentConsoleGroup console group of the existing alias's card, or empty
     * @param doNotMonitor to set the new alias's listen priority to 'Do not monitor'
     * @param message explaining the action, or empty
     */
    public record PlannedRow(RadioReferenceTable.Row source, String name, String group, Action action, Alias existing,
                             String consoleGroup, String currentConsoleGroup, boolean doNotMonitor, String message)
    {
        /**
         * Console text for the preview: the new card's group, the group of an existing card, or empty.
         */
        public String getConsoleText()
        {
            if(consoleGroup != null)
            {
                return consoleGroup;
            }

            if(currentConsoleGroup != null && !currentConsoleGroup.isEmpty())
            {
                return currentConsoleGroup + " (already on console)";
            }

            return "";
        }
    }

    /**
     * Import plan
     */
    public static class Plan
    {
        private final RadioReferenceTable mTable;
        private final Options mOptions;
        private final String mAliasList;
        private final List<PlannedRow> mRows;
        private final List<String> mNewConsoleGroups;
        private final String mError;

        Plan(RadioReferenceTable table, Options options, String aliasList, List<PlannedRow> rows,
             List<String> newConsoleGroups, String error)
        {
            mTable = table;
            mOptions = options;
            mAliasList = aliasList;
            mRows = Collections.unmodifiableList(rows);
            mNewConsoleGroups = Collections.unmodifiableList(newConsoleGroups);
            mError = error;
        }

        public RadioReferenceTable getTable()
        {
            return mTable;
        }

        /**
         * Options (copy) used for the plan
         */
        public Options getOptions()
        {
            return mOptions;
        }

        /**
         * Alias list name for the aliases (resolved to an existing alias list name ignoring case), or null
         */
        public String getAliasList()
        {
            return mAliasList;
        }

        public List<PlannedRow> getRows()
        {
            return mRows;
        }

        /**
         * Console groups that will be created, in creation order.
         */
        public List<String> getNewConsoleGroups()
        {
            return mNewConsoleGroups;
        }

        /**
         * Error that prevents the import (e.g. no alias list), or null.
         */
        public String getError()
        {
            return mError;
        }

        public int getAddCount()
        {
            return count(Action.ADD);
        }

        public int getUpdateCount()
        {
            return count(Action.UPDATE);
        }

        public int getSkippedCount()
        {
            return mRows.size() - getAddCount() - getUpdateCount();
        }

        /**
         * Number of console cards that will be added.
         */
        public int getConsoleCardCount()
        {
            return (int)mRows.stream().filter(row -> !row.action().isSkipped() && row.consoleGroup() != null).count();
        }

        private int count(Action action)
        {
            return (int)mRows.stream().filter(row -> row.action() == action).count();
        }

        /**
         * Indicates if the plan can be applied: no error and at least one alias to add or update.
         */
        public boolean canApply()
        {
            return mError == null && getAddCount() + getUpdateCount() > 0;
        }

        /**
         * Summary, e.g. '12 to add, 2 to update, 3 skipped'
         */
        public String getSummary()
        {
            return getAddCount() + " to add, " + getUpdateCount() + " to update, " + getSkippedCount() + " skipped";
        }

        /**
         * Console summary, e.g. '14 console cards in 3 groups (2 new)', or empty when nothing is added to the console.
         */
        public String getConsoleSummary()
        {
            int cards = getConsoleCardCount();

            if(cards == 0)
            {
                return "";
            }

            long groups = mRows.stream().filter(row -> !row.action().isSkipped() && row.consoleGroup() != null)
                .map(row -> row.consoleGroup().toLowerCase(Locale.ROOT)).distinct().count();

            return cards + " console card" + (cards == 1 ? "" : "s") + " in " + groups + " group" +
                (groups == 1 ? "" : "s") + (mNewConsoleGroups.isEmpty() ? "" : " (" + mNewConsoleGroups.size() +
                " new)");
        }
    }

    /**
     * RadioReference mode of an imported alias.
     * @param aliasUuid alias unique identifier
     * @param mode RadioReference talkgroup mode
     */
    public record ModeAssignment(String aliasUuid, RadioReferenceMode mode)
    {
    }

    /**
     * Result of applying an import plan.
     * @param added number of aliases added
     * @param updated number of aliases updated
     * @param skipped number of rows skipped
     * @param consoleCards number of console cards added
     * @param createdConsoleGroups console groups that were created
     * @param modes RadioReference mode for each added, updated and duplicate (skipped) alias that has a mode
     */
    public record Result(int added, int updated, int skipped, int consoleCards, List<String> createdConsoleGroups,
                         List<ModeAssignment> modes)
    {
        /**
         * Summary for the status line
         */
        public String getSummary()
        {
            StringBuilder sb = new StringBuilder();
            sb.append(added).append(" added, ").append(updated).append(" updated, ").append(skipped).append(" skipped");

            if(consoleCards > 0)
            {
                sb.append(", ").append(consoleCards).append(" added to the console");
            }

            return sb.toString();
        }
    }

    /**
     * Plans the import of the table into the sheet.
     * @param model of the alias sheet (aliases and console layout)
     * @param table parsed from the clipboard
     * @param options for the import
     * @return plan for preview and applyRadioReferenceImport()
     */
    public static Plan plan(AliasSheetModel model, RadioReferenceTable table, Options options)
    {
        Options opts = options != null ? options.copy() : new Options();
        AliasModel aliasModel = model.getAliasModel();
        String aliasList = resolveAliasList(aliasModel, opts.getAliasList());
        Protocol protocol = opts.getProtocol();
        boolean console = model.getConsoleLayoutManager() != null && opts.getConsoleMode() != ConsoleMode.NONE;
        String error = null;

        if(aliasList == null)
        {
            error = "Select or enter an alias list";
        }
        else if(console && opts.getConsoleMode() == ConsoleMode.SINGLE_GROUP &&
            (opts.getConsoleGroup() == null || opts.getConsoleGroup().isBlank()))
        {
            error = "Select or enter a console group";
        }

        //Existing aliases in the alias list by talkgroup value, for the protocol
        Map<Integer,Alias> existingByTalkgroup = new HashMap<>();

        if(aliasList != null)
        {
            for(Alias alias: aliasModel.getAliases())
            {
                if(alias.matchesAliasList(aliasList))
                {
                    for(AliasID aliasID: alias.getAliasIdentifiers())
                    {
                        if(aliasID instanceof Talkgroup talkgroup && talkgroup.getProtocol() == protocol)
                        {
                            existingByTalkgroup.putIfAbsent(talkgroup.getValue(), alias);
                        }
                    }
                }
            }
        }

        List<String> existingGroups = model.getConsoleGroupNames();
        List<String> newGroups = new ArrayList<>();
        Map<Integer,Integer> firstLineByTalkgroup = new HashMap<>();
        List<PlannedRow> rows = new ArrayList<>();
        TalkgroupFormat format = TalkgroupFormat.get(protocol);

        for(RadioReferenceTable.Row row: table.getRows())
        {
            String name = getName(row, opts.getNameSource(), protocol);
            String group = getGroup(row, opts.getGroupSource());
            Alias existing = existingByTalkgroup.get(row.talkgroup());
            String currentConsoleGroup = existing != null ? model.getText(existing, AliasSheetColumn.CONSOLE_GROUP) : "";
            Integer firstLine = firstLineByTalkgroup.putIfAbsent(row.talkgroup(), row.lineNumber());

            Action action;
            String message = "";
            String consoleGroup = null;

            if(firstLine != null)
            {
                action = Action.SKIP_DUPLICATE;
                message = "Talkgroup is also on line " + firstLine;
            }
            else if(!format.isValid(row.talkgroup()))
            {
                action = Action.SKIP_INVALID;
                message = "Talkgroup " + row.talkgroup() + " is not valid for " + protocol + " (valid range: " +
                    format.getValidRangeDescription() + ")";
            }
            else if(row.mode() != null && row.mode().isEncrypted() && opts.getEncryptedAction() == EncryptedAction.SKIP)
            {
                action = Action.SKIP_ENCRYPTED;
                message = "Encrypted (" + row.mode().getCode() + ")";
            }
            else if(existing != null)
            {
                if(opts.getExistingAliasAction() == ExistingAliasAction.SKIP)
                {
                    action = Action.SKIP_DUPLICATE;
                    message = "Alias '" + existing.getName() + "' has this talkgroup";
                }
                else
                {
                    consoleGroup = console && currentConsoleGroup.isEmpty() ? getConsoleGroup(row, opts) : null;
                    boolean changed = !name.equals(nonNull(existing.getName())) ||
                        !group.equals(nonNull(existing.getGroup()).trim()) || consoleGroup != null;
                    action = changed ? Action.UPDATE : Action.SKIP_UNCHANGED;

                    if(changed)
                    {
                        message = "Updates alias '" + existing.getName() + "'";
                    }
                }
            }
            else
            {
                action = Action.ADD;
                consoleGroup = console ? getConsoleGroup(row, opts) : null;
            }

            if(consoleGroup != null && !containsIgnoreCase(existingGroups, consoleGroup) &&
                !containsIgnoreCase(newGroups, consoleGroup))
            {
                newGroups.add(consoleGroup);
            }

            boolean doNotMonitor = action == Action.ADD && row.mode() != null && row.mode().isEncrypted() &&
                opts.getEncryptedAction() == EncryptedAction.IMPORT_DO_NOT_MONITOR;

            rows.add(new PlannedRow(row, name, group, action, existing, consoleGroup, currentConsoleGroup, doNotMonitor,
                message));
        }

        return new Plan(table, opts, aliasList, rows, newGroups, error);
    }

    /**
     * Alias name for the row: the description or alpha tag (falling back to the other when empty), or 'TG nnn'.
     */
    static String getName(RadioReferenceTable.Row row, NameSource source, Protocol protocol)
    {
        String primary = source == NameSource.ALPHA_TAG ? row.alphaTag() : row.description();
        String secondary = source == NameSource.ALPHA_TAG ? row.description() : row.alphaTag();
        String name = RadioReferenceTable.clean(primary);

        if(name.isEmpty())
        {
            name = RadioReferenceTable.clean(secondary);
        }

        return name.isEmpty() ? "TG " + AliasSheetModel.formatTalkgroup(protocol, row.talkgroup()) : name;
    }

    /**
     * Alias group for the row, or empty.
     */
    static String getGroup(RadioReferenceTable.Row row, GroupSource source)
    {
        return switch(source)
        {
            case TAG -> RadioReferenceTable.clean(row.tag());
            case CATEGORY -> RadioReferenceTable.clean(row.category());
            default -> "";
        };
    }

    /**
     * Console group for a new card for the row
     */
    private static String getConsoleGroup(RadioReferenceTable.Row row, Options options)
    {
        if(options.getConsoleMode() == ConsoleMode.SINGLE_GROUP)
        {
            String group = options.getConsoleGroup() != null ? options.getConsoleGroup().trim() : "";
            return group.isEmpty() ? null : group;
        }

        String tag = RadioReferenceTable.clean(row.tag());
        return tag.isEmpty() ? UNTAGGED_CONSOLE_GROUP : tag;
    }

    /**
     * Resolves the alias list name to an existing alias list name (ignoring case and surrounding whitespace).
     * @return alias list name, or null for none
     */
    static String resolveAliasList(AliasModel aliasModel, String aliasList)
    {
        String name = AliasSheetModel.toModelListName(aliasList);

        if(name == null)
        {
            return null;
        }

        for(String existing: aliasModel.aliasListNames())
        {
            if(existing != null && existing.trim().equalsIgnoreCase(name) &&
                !existing.equals(AliasModel.NO_ALIAS_LIST))
            {
                return existing;
            }
        }

        return name;
    }

    private static boolean containsIgnoreCase(List<String> values, String value)
    {
        for(String existing: values)
        {
            if(existing != null && existing.trim().equalsIgnoreCase(value.trim()))
            {
                return true;
            }
        }

        return false;
    }

    private static String nonNull(String value)
    {
        return Objects.requireNonNullElse(value, "");
    }
}
