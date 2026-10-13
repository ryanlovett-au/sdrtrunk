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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * CSV export and import for the alias sheet.
 *
 * Export writes a header row with the column labels followed by one row per alias using the sheet cell text.  For a
 * complex alias (see AliasSheetModel.isComplex()), the protocol and talkgroup cells contain a read-only summary that
 * is ignored when the file is imported to update the same alias.
 *
 * Import reads a header row (matched to columns by label, ignoring case and a few alternate names) and then plans the
 * changes without modifying any aliases, so that a preview can be shown.  The plan is applied with
 * AliasSheetModel.applyImport() as a single undoable operation.  Import modes:
 * - APPEND: each CSV row is added as a new alias.
 * - UPDATE_BY_NAME: each CSV row updates the alias with the same name (ignoring case) in the same alias list (the CSV
 *   alias list column, or the default alias list), or is added as a new alias when there is no such alias.  Rows that
 *   match more than one alias are skipped.
 *
 * Empty CSV cells clear the corresponding value of an updated alias, so that a file exported from the sheet, edited
 * and imported reproduces the edited values.
 */
public class AliasSheetCsv
{
    private AliasSheetCsv()
    {
        //Static utility class
    }

    /**
     * Import modes
     */
    public enum ImportMode
    {
        APPEND("Append all rows as new aliases"),
        UPDATE_BY_NAME("Update aliases with the same name (add the others)");

        private final String mLabel;

        ImportMode(String label)
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
     * Exports the aliases as CSV text with a header row.
     * @param model for cell text
     * @param aliases to export, in order
     * @return CSV text
     */
    public static String export(AliasSheetModel model, List<Alias> aliases)
    {
        List<List<String>> records = new ArrayList<>();
        List<String> header = new ArrayList<>();

        for(AliasSheetColumn column: AliasSheetColumn.values())
        {
            header.add(column.getLabel());
        }

        records.add(header);

        for(Alias alias: aliases)
        {
            List<String> record = new ArrayList<>();

            for(AliasSheetColumn column: AliasSheetColumn.values())
            {
                record.add(model.getText(alias, column));
            }

            records.add(record);
        }

        return DelimitedText.formatCsv(records);
    }

    /**
     * Plans the import of the CSV text without changing any aliases.
     * @param model for alias lookup, current values and validation
     * @param csv text with a header row
     * @param mode import mode
     * @param defaultAliasList for rows without an alias list value, or null
     * @return import plan
     */
    public static ImportPlan plan(AliasSheetModel model, String csv, ImportMode mode, String defaultAliasList)
    {
        List<List<String>> records = DelimitedText.parseCsv(csv);
        ImportPlan plan = new ImportPlan(mode);

        if(records.isEmpty())
        {
            plan.getErrors().add("The file is empty");
            return plan;
        }

        //Map header columns
        List<String> header = records.getFirst();
        Map<AliasSheetColumn,Integer> columnIndexes = new EnumMap<>(AliasSheetColumn.class);

        for(int x = 0; x < header.size(); x++)
        {
            AliasSheetColumn column = AliasSheetColumn.fromHeader(header.get(x));

            if(column == null)
            {
                if(!header.get(x).isBlank())
                {
                    plan.getIgnoredColumns().add(header.get(x));
                }
            }
            else if(!columnIndexes.containsKey(column))
            {
                columnIndexes.put(column, x);
            }
        }

        if(columnIndexes.isEmpty())
        {
            plan.getErrors().add("No recognised column headers - expected headers such as Name, Protocol and " +
                "Talkgroup in the first row");
            return plan;
        }

        if(!columnIndexes.containsKey(AliasSheetColumn.NAME))
        {
            plan.getErrors().add("A Name column is required");
            return plan;
        }

        for(int line = 1; line < records.size(); line++)
        {
            List<String> record = records.get(line);
            Map<AliasSheetColumn,String> values = new EnumMap<>(AliasSheetColumn.class);
            boolean empty = true;

            for(Map.Entry<AliasSheetColumn,Integer> entry: columnIndexes.entrySet())
            {
                String value = entry.getValue() < record.size() ? record.get(entry.getValue()).trim() : "";
                values.put(entry.getKey(), value);
                empty &= value.isEmpty();
            }

            if(empty)
            {
                continue;
            }

            plan.getRows().add(planRow(model, line + 1, values, mode, defaultAliasList));
        }

        return plan;
    }

    /**
     * Plans the import of a single CSV row.
     */
    private static PlannedRow planRow(AliasSheetModel model, int lineNumber, Map<AliasSheetColumn,String> values,
                                      ImportMode mode, String defaultAliasList)
    {
        String name = values.get(AliasSheetColumn.NAME);
        String listValue = values.get(AliasSheetColumn.ALIAS_LIST);
        String aliasList = listValue != null && !listValue.isBlank() ? listValue : defaultAliasList;
        aliasList = AliasSheetModel.toModelListName(aliasList);

        PlannedRow row = new PlannedRow(lineNumber, name, aliasList);

        if(mode == ImportMode.UPDATE_BY_NAME && name != null && !name.isBlank())
        {
            List<Alias> matches = model.findByName(aliasList, name);

            if(matches.size() > 1)
            {
                row.mSkipped = true;
                row.getMessages().add("Skipped: " + matches.size() + " aliases are named '" + name + "' in alias " +
                    "list '" + (aliasList != null ? aliasList : "(none)") + "'");
                return row;
            }
            else if(matches.size() == 1)
            {
                row.mExisting = matches.getFirst();
            }
        }

        Alias existing = row.getExisting();

        for(Map.Entry<AliasSheetColumn,String> entry: values.entrySet())
        {
            AliasSheetColumn column = entry.getKey();
            String newText = entry.getValue();

            if(existing == null)
            {
                //New alias - the alias list is assigned when the alias is created
                if(column == AliasSheetColumn.ALIAS_LIST || (newText.isEmpty() && column != AliasSheetColumn.NAME))
                {
                    continue;
                }

                String error = model.validate(column, newText, values.get(AliasSheetColumn.PROTOCOL));
                row.getChanges().add(new PlannedChange(column, "", newText, error));
            }
            else
            {
                String oldText = model.getText(existing, column);

                if(model.isSameValue(column, oldText, newText) && model.getInvalidValue(existing, column) == null)
                {
                    continue;
                }

                //The name is the match key (ignoring case) - don't change the case of the existing name
                if(column == AliasSheetColumn.NAME && mode == ImportMode.UPDATE_BY_NAME &&
                    oldText.trim().equalsIgnoreCase(newText.trim()))
                {
                    continue;
                }

                if(!model.isEditable(existing, column))
                {
                    row.getMessages().add(column.getLabel() + " not updated - alias has multiple identifiers (use " +
                        "the alias editor)");
                    continue;
                }

                String protocolText = values.containsKey(AliasSheetColumn.PROTOCOL) ?
                    values.get(AliasSheetColumn.PROTOCOL) : model.getText(existing, AliasSheetColumn.PROTOCOL);
                String error = model.validate(column, newText, protocolText);
                row.getChanges().add(new PlannedChange(column, oldText, newText, error));
            }
        }

        return row;
    }

    /**
     * Planned change to a cell.
     * @param column of the cell
     * @param oldText current text (empty for a new alias)
     * @param newText imported text
     * @param error validation error for the imported text, or null if valid
     */
    public record PlannedChange(AliasSheetColumn column, String oldText, String newText, String error)
    {
    }

    /**
     * Planned import of a CSV row: a new alias or an update to an existing alias.
     */
    public static class PlannedRow
    {
        private final int mLineNumber;
        private final String mName;
        private final String mAliasListName;
        private final List<PlannedChange> mChanges = new ArrayList<>();
        private final List<String> mMessages = new ArrayList<>();
        private Alias mExisting;
        private boolean mSkipped;

        PlannedRow(int lineNumber, String name, String aliasListName)
        {
            mLineNumber = lineNumber;
            mName = name;
            mAliasListName = aliasListName;
        }

        /**
         * Line number of the row in the CSV file (1 is the header row)
         */
        public int getLineNumber()
        {
            return mLineNumber;
        }

        /**
         * Alias name from the CSV row
         */
        public String getName()
        {
            return mName;
        }

        /**
         * Alias list for the row, or null
         */
        public String getAliasListName()
        {
            return mAliasListName;
        }

        /**
         * Existing alias that is updated, or null when the row is added as a new alias.
         */
        public Alias getExisting()
        {
            return mExisting;
        }

        /**
         * Indicates if the row adds a new alias.
         */
        public boolean isNew()
        {
            return !mSkipped && mExisting == null;
        }

        /**
         * Indicates if the row is skipped.
         */
        public boolean isSkipped()
        {
            return mSkipped;
        }

        /**
         * Planned cell changes
         */
        public List<PlannedChange> getChanges()
        {
            return mChanges;
        }

        /**
         * Informational messages and warnings for the row
         */
        public List<String> getMessages()
        {
            return mMessages;
        }

        /**
         * Number of planned changes with an invalid value
         */
        public int getErrorCount()
        {
            int count = 0;

            for(PlannedChange change: mChanges)
            {
                if(change.error() != null)
                {
                    count++;
                }
            }

            return count;
        }
    }

    /**
     * Import plan: the planned rows and summary counts.
     */
    public static class ImportPlan
    {
        private final ImportMode mMode;
        private final List<PlannedRow> mRows = new ArrayList<>();
        private final List<String> mErrors = new ArrayList<>();
        private final List<String> mIgnoredColumns = new ArrayList<>();

        ImportPlan(ImportMode mode)
        {
            mMode = mode;
        }

        /**
         * Import mode
         */
        public ImportMode getMode()
        {
            return mMode;
        }

        /**
         * Planned rows
         */
        public List<PlannedRow> getRows()
        {
            return mRows;
        }

        /**
         * File level errors that prevent the import
         */
        public List<String> getErrors()
        {
            return mErrors;
        }

        /**
         * Header values that were not recognised
         */
        public List<String> getIgnoredColumns()
        {
            return mIgnoredColumns;
        }

        /**
         * Indicates if the plan can be applied: no file level errors and at least one new or updated alias.
         */
        public boolean canApply()
        {
            return mErrors.isEmpty() && (getNewCount() > 0 || getUpdatedCount() > 0);
        }

        /**
         * Number of aliases that will be added
         */
        public int getNewCount()
        {
            return (int)mRows.stream().filter(PlannedRow::isNew).count();
        }

        /**
         * Number of existing aliases that will be changed
         */
        public int getUpdatedCount()
        {
            return (int)mRows.stream().filter(row -> !row.isSkipped() && row.getExisting() != null &&
                !row.getChanges().isEmpty()).count();
        }

        /**
         * Number of existing aliases that match a row but have no changes
         */
        public int getUnchangedCount()
        {
            return (int)mRows.stream().filter(row -> !row.isSkipped() && row.getExisting() != null &&
                row.getChanges().isEmpty()).count();
        }

        /**
         * Number of rows that are skipped
         */
        public int getSkippedCount()
        {
            return (int)mRows.stream().filter(PlannedRow::isSkipped).count();
        }

        /**
         * Number of cell changes for existing aliases
         */
        public int getChangedCellCount()
        {
            return mRows.stream().filter(row -> !row.isSkipped() && row.getExisting() != null)
                .mapToInt(row -> row.getChanges().size()).sum();
        }

        /**
         * Number of imported values that are not valid
         */
        public int getInvalidCount()
        {
            return mRows.stream().filter(row -> !row.isSkipped()).mapToInt(PlannedRow::getErrorCount).sum();
        }

        /**
         * One line summary of the plan
         */
        public String getSummary()
        {
            if(!mErrors.isEmpty())
            {
                return String.join("\n", mErrors);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(getNewCount()).append(" new alias").append(getNewCount() == 1 ? "" : "es");
            sb.append(", ").append(getUpdatedCount()).append(" updated (").append(getChangedCellCount())
                .append(" cell").append(getChangedCellCount() == 1 ? "" : "s").append(")");
            sb.append(", ").append(getUnchangedCount()).append(" unchanged");

            if(getSkippedCount() > 0)
            {
                sb.append(", ").append(getSkippedCount()).append(" skipped");
            }

            if(getInvalidCount() > 0)
            {
                sb.append(", ").append(getInvalidCount()).append(" invalid value")
                    .append(getInvalidCount() == 1 ? "" : "s").append(" (shown in red after import)");
            }

            if(!mIgnoredColumns.isEmpty())
            {
                sb.append(". Ignored columns: ").append(String.join(", ", mIgnoredColumns));
            }

            return sb.toString();
        }

        /**
         * Detailed, line-by-line description of the plan for a preview.
         * @param maximumLines to include before truncating
         */
        public List<String> getDetails(int maximumLines)
        {
            List<String> lines = new ArrayList<>();

            for(PlannedRow row: mRows)
            {
                if(lines.size() >= maximumLines)
                {
                    lines.add("...");
                    break;
                }

                String prefix = "Line " + row.getLineNumber() + ": ";

                if(row.isSkipped())
                {
                    lines.add(prefix + String.join("; ", row.getMessages()));
                    continue;
                }

                if(row.isNew())
                {
                    lines.add(prefix + "add '" + row.getName() + "'" + (row.getAliasListName() != null ?
                        " to " + row.getAliasListName() : ""));
                }
                else if(row.getChanges().isEmpty())
                {
                    continue;
                }
                else
                {
                    lines.add(prefix + "update '" + row.getExisting().getName() + "'");

                    for(PlannedChange change: row.getChanges())
                    {
                        lines.add("    " + change.column().getLabel() + ": '" + change.oldText() + "' → '" +
                            change.newText() + "'");
                    }
                }

                for(PlannedChange change: row.getChanges())
                {
                    if(change.error() != null)
                    {
                        lines.add("    ! " + change.error());
                    }
                }

                for(String message: row.getMessages())
                {
                    lines.add("    " + message);
                }
            }

            return Collections.unmodifiableList(lines);
        }
    }
}
