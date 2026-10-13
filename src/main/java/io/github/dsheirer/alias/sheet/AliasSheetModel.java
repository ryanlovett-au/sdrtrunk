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
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupFormat;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupFormatter;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.console.ConsoleCard;
import io.github.dsheirer.console.ConsoleGroup;
import io.github.dsheirer.console.ConsoleLayout;
import io.github.dsheirer.console.ConsoleLayoutManager;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Data and editing logic for the alias sheet editor, independent of the JavaFX controls.
 *
 * Each sheet row is an alias from the alias model and each column is an AliasSheetColumn.  Cell values are exchanged
 * as text (the same text that is displayed, copied to the clipboard and exported to CSV), and all edits are applied
 * directly to the alias via the alias setters, in the same way as the alias editor, so that alias model listeners
 * (playlist save scheduling, alias lists, console card re-binding) observe the changes.  Alias identifier changes
 * (talkgroup) are followed by AliasList.updateAlias() and alias list changes move the alias between alias lists, as the
 * alias editor does.
 *
 * The protocol and talkgroup columns edit the alias's single talkgroup identifier.  An alias that has other
 * identifiers (ranges, radio IDs, tones, etc.), more than one talkgroup or alias actions is 'complex': those two
 * columns show a read-only summary and the alias must be edited with the alias editor.
 *
 * Invalid values are not applied to the alias.  The invalid text is retained by this model (not saved to the
 * playlist) so that it can be shown (and corrected) in the sheet with an error message.
 *
 * The console group column binds the alias to a card in a group of the console layout: one card per alias.  Changing
 * the group adds, moves or removes the alias's card via the console layout manager.  A console group name that does
 * not exist is created.  Card settings (volume, mute, pan) are restored when a card that was removed by the sheet is
 * added back (e.g. undo).
 *
 * Sheet operations (cell edits, paste, fill down, clear, add rows, delete rows, CSV import) are recorded in an undo
 * stack.  This class is not thread safe and should be accessed on the JavaFX application thread.
 */
public class AliasSheetModel
{
    public static final String DEFAULT_ALIAS_NAME = "New Alias";
    private static final int SUMMARY_ITEM_COUNT = 2;
    private static final Set<AliasSheetColumn> TALKGROUP_COLUMNS =
        EnumSet.of(AliasSheetColumn.PROTOCOL, AliasSheetColumn.TALKGROUP);
    private static final Set<AliasSheetColumn> SIGNATURE_COLUMNS = EnumSet.of(AliasSheetColumn.ALIAS_LIST,
        AliasSheetColumn.NAME, AliasSheetColumn.PROTOCOL, AliasSheetColumn.TALKGROUP);

    private final AliasModel mAliasModel;
    private final ConsoleLayoutManager mConsoleLayoutManager;
    private final SheetUndoStack mUndoStack = new SheetUndoStack();
    private final Map<String,Map<AliasSheetColumn,InvalidValue>> mInvalidValues = new HashMap<>();
    private final Map<String,Protocol> mPendingProtocols = new HashMap<>();
    private final Map<String,ConsoleCard> mRemovedCards = new HashMap<>();
    private final Map<String,RadioReferenceMode> mRadioReferenceModes = new HashMap<>();
    private Supplier<Collection<String>> mIconNameSupplier;
    private Supplier<Collection<String>> mBroadcastChannelSupplier;
    private Map<String,ConsoleAssignment> mConsoleAssignments;
    private volatile boolean mConsoleAssignmentsValid = false;

    /**
     * Constructs an instance
     * @param aliasModel containing the aliases
     * @param consoleLayoutManager for console group assignment, or null if console groups are not supported
     */
    public AliasSheetModel(AliasModel aliasModel, ConsoleLayoutManager consoleLayoutManager)
    {
        mAliasModel = Objects.requireNonNull(aliasModel, "Alias model is required");
        mConsoleLayoutManager = consoleLayoutManager;

        if(mConsoleLayoutManager != null)
        {
            mConsoleLayoutManager.addListener(event -> mConsoleAssignmentsValid = false);
        }
    }

    /**
     * Sets the supplier of valid icon names.  When not set, any icon name is accepted.
     */
    public void setIconNameSupplier(Supplier<Collection<String>> supplier)
    {
        mIconNameSupplier = supplier;
    }

    /**
     * Sets the supplier of configured broadcast (streaming) channel names.  When not set, any name is accepted.
     */
    public void setBroadcastChannelSupplier(Supplier<Collection<String>> supplier)
    {
        mBroadcastChannelSupplier = supplier;
    }

    /**
     * Alias model
     */
    public AliasModel getAliasModel()
    {
        return mAliasModel;
    }

    /**
     * Console layout manager, or null.
     */
    public ConsoleLayoutManager getConsoleLayoutManager()
    {
        return mConsoleLayoutManager;
    }

    /**
     * Undo stack for sheet operations
     */
    public SheetUndoStack getUndoStack()
    {
        return mUndoStack;
    }

    /**
     * Clears the undo history and any invalid (unapplied) cell values, e.g. when a different playlist is loaded.
     */
    public void reset()
    {
        mUndoStack.clear();
        mInvalidValues.clear();
        mPendingProtocols.clear();
        mRemovedCards.clear();
        mRadioReferenceModes.clear();
        mConsoleAssignmentsValid = false;
    }

    /**
     * Undoes the most recent sheet operation.
     * @return true if an operation was undone.
     */
    public boolean undo()
    {
        return mUndoStack.undo() != null;
    }

    /**
     * Redoes the most recently undone sheet operation.
     * @return true if an operation was redone.
     */
    public boolean redo()
    {
        return mUndoStack.redo() != null;
    }

    /**
     * Aliases that belong to the alias list, in alias model order.
     * @param aliasListName to match, or null for all aliases
     */
    public List<Alias> getAliases(String aliasListName)
    {
        List<Alias> aliases = new ArrayList<>();

        for(Alias alias: mAliasModel.getAliases())
        {
            if(aliasListName == null || alias.matchesAliasList(aliasListName))
            {
                aliases.add(alias);
            }
        }

        return aliases;
    }

    /**
     * Finds talkgroup conflicts across all aliases in the alias model.
     */
    public TalkgroupConflictDetector.Report findConflicts()
    {
        return TalkgroupConflictDetector.findConflicts(mAliasModel.getAliases());
    }

    /**
     * Cell text for the alias and column.  When the cell contains an invalid (unapplied) value, the invalid text is
     * returned.
     */
    public String getText(Alias alias, AliasSheetColumn column)
    {
        InvalidValue invalid = getInvalidValue(alias, column);

        if(invalid != null)
        {
            return invalid.text();
        }

        return getAppliedText(alias, column);
    }

    /**
     * Cell text for the value that is applied to the alias (ignoring any invalid, unapplied value).
     */
    private String getAppliedText(Alias alias, AliasSheetColumn column)
    {
        switch(column)
        {
            case ALIAS_LIST:
                return getListName(alias.getAliasListName());
            case GROUP:
                return nonNull(alias.getGroup());
            case NAME:
                return nonNull(alias.getName());
            case PROTOCOL:
                if(isComplex(alias))
                {
                    return getProtocolSummary(alias);
                }

                Protocol pending = mPendingProtocols.get(alias.getUuid());

                if(pending != null)
                {
                    return AliasSheetValues.formatProtocol(pending);
                }

                Talkgroup talkgroup = getTalkgroup(alias);
                return talkgroup != null ? AliasSheetValues.formatProtocol(talkgroup.getProtocol()) : "";
            case TALKGROUP:
                if(isComplex(alias))
                {
                    return getIdentifierSummary(alias);
                }

                Talkgroup tg = getTalkgroup(alias);
                return tg != null ? formatTalkgroup(tg.getProtocol(), tg.getValue()) : "";
            case COLOUR:
                return AliasSheetValues.formatColour(alias.getColor());
            case ICON:
                return nonNull(alias.getIconName());
            case LISTEN:
                return AliasSheetValues.formatListen(alias.getPlaybackPriority());
            case RECORD:
                return AliasSheetValues.formatRecord(alias.isRecordable());
            case STREAM:
                return AliasSheetValues.formatStreams(getStreams(alias));
            case CONSOLE_GROUP:
                ConsoleAssignment assignment = getConsoleAssignment(alias);
                return assignment != null ? nonNull(assignment.groupName()) : "";
            default:
                return "";
        }
    }

    /**
     * Indicates if the cell can be edited.  The protocol and talkgroup cells of a complex alias are read-only and the
     * console group cell is read-only when there is no console layout manager.
     */
    public boolean isEditable(Alias alias, AliasSheetColumn column)
    {
        if(TALKGROUP_COLUMNS.contains(column))
        {
            return !isComplex(alias);
        }
        else if(column == AliasSheetColumn.CONSOLE_GROUP)
        {
            return mConsoleLayoutManager != null;
        }

        return true;
    }

    /**
     * Error message for the cell: an invalid (unapplied) value, a missing name or an invalid talkgroup.
     * @return error message or null if the cell is valid.
     */
    public String getError(Alias alias, AliasSheetColumn column)
    {
        InvalidValue invalid = getInvalidValue(alias, column);

        if(invalid != null)
        {
            return invalid.message();
        }

        if(column == AliasSheetColumn.NAME && (alias.getName() == null || alias.getName().isBlank()))
        {
            return "Name is required";
        }

        if(column == AliasSheetColumn.TALKGROUP && !isComplex(alias))
        {
            Talkgroup talkgroup = getTalkgroup(alias);

            if(talkgroup != null && !talkgroup.isValid())
            {
                return invalidTalkgroupMessage(talkgroup.getProtocol(),
                    formatTalkgroup(talkgroup.getProtocol(), talkgroup.getValue()));
            }
        }

        return null;
    }

    /**
     * Invalid (unapplied) value for the cell, or null.
     */
    public InvalidValue getInvalidValue(Alias alias, AliasSheetColumn column)
    {
        Map<AliasSheetColumn,InvalidValue> invalidValues = mInvalidValues.get(alias.getUuid());
        return invalidValues != null ? invalidValues.get(column) : null;
    }

    /**
     * Indicates if the alias is complex: it has identifiers other than a single talkgroup (and audio settings) or it
     * has alias actions.  The protocol and talkgroup cells are read-only for a complex alias.
     */
    public static boolean isComplex(Alias alias)
    {
        if(alias.hasActions())
        {
            return true;
        }

        int talkgroups = 0;

        for(AliasID aliasID: alias.getAliasIdentifiers())
        {
            if(aliasID.isAudioIdentifier())
            {
                continue;
            }

            if(aliasID.getType() == AliasIDType.TALKGROUP)
            {
                talkgroups++;
            }
            else
            {
                return true;
            }
        }

        return talkgroups > 1;
    }

    /**
     * First (plain) talkgroup identifier for the alias, or null.
     */
    public static Talkgroup getTalkgroup(Alias alias)
    {
        for(AliasID aliasID: alias.getAliasIdentifiers())
        {
            if(aliasID.getType() == AliasIDType.TALKGROUP && aliasID instanceof Talkgroup talkgroup)
            {
                return talkgroup;
            }
        }

        return null;
    }

    /**
     * Summary of the alias identifiers and actions, e.g. '2001, 2002 +1 more'
     */
    public static String getIdentifierSummary(Alias alias)
    {
        List<String> items = new ArrayList<>();

        for(AliasID aliasID: alias.getAliasIdentifiers())
        {
            if(!aliasID.isAudioIdentifier())
            {
                items.add(describe(aliasID));
            }
        }

        int actions = alias.getAliasActions().size();

        StringBuilder sb = new StringBuilder();
        int shown = Math.min(SUMMARY_ITEM_COUNT, items.size());

        for(int x = 0; x < shown; x++)
        {
            if(x > 0)
            {
                sb.append(", ");
            }

            sb.append(items.get(x));
        }

        int remaining = items.size() - shown;

        if(shown == 0 && actions > 0)
        {
            sb.append(actions).append(actions == 1 ? " action" : " actions");
        }
        else if(remaining + actions > 0)
        {
            sb.append(" +").append(remaining + actions).append(" more");
        }

        return sb.toString();
    }

    /**
     * Short description of the identifier for the summary.
     */
    private static String describe(AliasID aliasID)
    {
        if(aliasID.getType() == AliasIDType.TALKGROUP && aliasID instanceof Talkgroup talkgroup)
        {
            return formatTalkgroup(talkgroup.getProtocol(), talkgroup.getValue());
        }
        else if(aliasID instanceof TalkgroupRange range)
        {
            return formatTalkgroup(range.getProtocol(), range.getMinTalkgroup()) + "-" +
                formatTalkgroup(range.getProtocol(), range.getMaxTalkgroup());
        }
        else if(aliasID instanceof Radio radio)
        {
            return "Radio " + radio.getValue();
        }
        else if(aliasID instanceof RadioRange range)
        {
            return "Radios " + range.getMinRadio() + "-" + range.getMaxRadio();
        }

        return aliasID.getType() != null ? aliasID.getType().toString() : aliasID.toString();
    }

    /**
     * Protocol summary for a complex alias: the protocol shared by all talkgroup and radio identifiers, 'Mixed' when
     * the identifiers use different protocols, or empty.
     */
    private static String getProtocolSummary(Alias alias)
    {
        Set<Protocol> protocols = new LinkedHashSet<>();

        for(AliasID aliasID: alias.getAliasIdentifiers())
        {
            if(aliasID instanceof Talkgroup talkgroup)
            {
                protocols.add(talkgroup.getProtocol());
            }
            else if(aliasID instanceof TalkgroupRange range)
            {
                protocols.add(range.getProtocol());
            }
            else if(aliasID instanceof Radio radio)
            {
                protocols.add(radio.getProtocol());
            }
            else if(aliasID instanceof RadioRange range)
            {
                protocols.add(range.getProtocol());
            }
        }

        protocols.remove(null);

        if(protocols.size() == 1)
        {
            return AliasSheetValues.formatProtocol(protocols.iterator().next());
        }

        return protocols.isEmpty() ? "" : "Mixed";
    }

    /**
     * Formats the talkgroup value using the protocol-specific talkgroup format.
     */
    public static String formatTalkgroup(Protocol protocol, int value)
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

    /**
     * Names of the broadcast channels for the alias, sorted.
     */
    private static List<String> getStreams(Alias alias)
    {
        List<String> streams = new ArrayList<>();

        for(BroadcastChannel channel: alias.getBroadcastChannels())
        {
            if(channel.getChannelName() != null && !channel.getChannelName().isEmpty())
            {
                streams.add(channel.getChannelName());
            }
        }

        return streams;
    }

    /**
     * Validates the cell text without applying it, e.g. for an import preview.
     * @param column of the cell
     * @param text to validate
     * @param protocolText for a talkgroup value (from the same row), or null to use the default protocol
     * @return error message or null if the text is valid.
     */
    public String validate(AliasSheetColumn column, String text, String protocolText)
    {
        String value = text != null ? text.trim() : "";

        switch(column)
        {
            case NAME:
                return value.isEmpty() ? "Name is required" : null;
            case PROTOCOL:
                return !value.isEmpty() && AliasSheetValues.parseProtocol(value) == null ?
                    invalidProtocolMessage(value) : null;
            case TALKGROUP:
                if(value.isEmpty())
                {
                    return null;
                }

                Protocol protocol = protocolText == null || protocolText.isBlank() ? Protocol.APCO25 :
                    AliasSheetValues.parseProtocol(protocolText);

                if(protocol == null)
                {
                    return "Select a valid protocol for talkgroup '" + value + "'";
                }

                return parseTalkgroup(protocol, value) == null ? invalidTalkgroupMessage(protocol, value) : null;
            case COLOUR:
                return AliasSheetValues.parseColour(value) == null ? invalidColourMessage(value) : null;
            case ICON:
                return !value.isEmpty() && resolveIconName(value) == null ? "Unknown icon '" + value + "'" : null;
            case LISTEN:
                return AliasSheetValues.parseListen(value) == null ? invalidListenMessage(value) : null;
            case RECORD:
                return AliasSheetValues.parseRecord(value) == null ? invalidRecordMessage(value) : null;
            case STREAM:
                for(String stream: AliasSheetValues.splitStreams(value))
                {
                    if(resolveStreamName(stream, null) == null)
                    {
                        return unknownStreamMessage(stream);
                    }
                }
                return null;
            default:
                return null;
        }
    }

    /**
     * Indicates if the two text values represent the same cell value, e.g. '#ff0000' and '#FF0000', or 'P25' and
     * 'APCO-25'.
     */
    public boolean isSameValue(AliasSheetColumn column, String a, String b)
    {
        return canonicalize(column, a).equals(canonicalize(column, b));
    }

    /**
     * Canonical text for the value, used to detect unchanged values.
     */
    private String canonicalize(AliasSheetColumn column, String text)
    {
        String value = text != null ? text.trim() : "";

        switch(column)
        {
            case ALIAS_LIST:
                return getListName(value);
            case PROTOCOL:
                Protocol protocol = AliasSheetValues.parseProtocol(value);
                return protocol != null ? protocol.name() : value;
            case COLOUR:
                Integer colour = AliasSheetValues.parseColour(value);
                return colour != null ? AliasSheetValues.formatColour(colour) : value;
            case LISTEN:
                Integer priority = AliasSheetValues.parseListen(value);
                return priority != null ? AliasSheetValues.formatListen(priority) : value;
            case RECORD:
                Boolean record = AliasSheetValues.parseRecord(value);
                return record != null ? AliasSheetValues.formatRecord(record) : value;
            case STREAM:
                return AliasSheetValues.splitStreams(value).stream().sorted().collect(Collectors.joining(";"));
            case CONSOLE_GROUP:
                return AliasSheetValues.isNotOnConsole(value) ? "" : value.toLowerCase(Locale.ROOT);
            case ICON:
                return value.toLowerCase(Locale.ROOT);
            default:
                return value;
        }
    }

    /**
     * Names of the console groups in the current console layout, in display order.
     */
    public List<String> getConsoleGroupNames()
    {
        List<String> names = new ArrayList<>();

        if(mConsoleLayoutManager != null)
        {
            for(ConsoleGroup group: mConsoleLayoutManager.getLayout().getGroups())
            {
                names.add(group.getName());
            }
        }

        return names;
    }

    /**
     * Indicates if the alias is bound to a console card.
     */
    public boolean isOnConsole(Alias alias)
    {
        return getConsoleAssignment(alias) != null;
    }

    /**
     * Console card assignment for the alias, or null.
     */
    private ConsoleAssignment getConsoleAssignment(Alias alias)
    {
        if(mConsoleLayoutManager == null)
        {
            return null;
        }

        if(!mConsoleAssignmentsValid || mConsoleAssignments == null)
        {
            //Flag is set before the snapshot so that a concurrent layout change invalidates this snapshot
            mConsoleAssignmentsValid = true;
            Map<String,ConsoleAssignment> assignments = new HashMap<>();
            ConsoleLayout layout = mConsoleLayoutManager.getLayout();

            for(ConsoleGroup group: layout.getGroups())
            {
                for(ConsoleCard card: group.getCards())
                {
                    if(card.getAliasUuid() != null)
                    {
                        assignments.put(card.getAliasUuid(), new ConsoleAssignment(group.getId(), group.getName(),
                            card.getId()));
                    }
                }
            }

            mConsoleAssignments = assignments;
        }

        return mConsoleAssignments.get(alias.getUuid());
    }

    /**
     * Applies cell edits as a single undoable operation.
     * @param description of the operation for undo/redo
     * @param edits to apply
     * @return number of cells that were changed
     */
    public int applyEdits(String description, List<CellEdit> edits)
    {
        List<CellChange> changes = applyCellEdits(edits);

        if(!changes.isEmpty())
        {
            mUndoStack.push(new CellChangesEdit(description, changes));
        }

        return changes.size();
    }

    /**
     * Applies a single cell edit as an undoable operation.
     * @return true if the cell was changed.
     */
    public boolean editCell(Alias alias, AliasSheetColumn column, String text)
    {
        return applyEdits("edit " + column.getLabel(), List.of(new CellEdit(alias, column, text))) > 0;
    }

    /**
     * Formats the range of cells as tab separated values for the clipboard.
     * @param rows in view order
     * @param columns in view order
     * @param range of cells to copy
     * @return tab separated values
     */
    public String copy(List<Alias> rows, List<AliasSheetColumn> columns, SheetRange range)
    {
        List<List<String>> records = new ArrayList<>();

        for(int row = Math.max(0, range.firstRow()); row <= range.lastRow() && row < rows.size(); row++)
        {
            List<String> record = new ArrayList<>();

            for(int column = Math.max(0, range.firstColumn()); column <= range.lastColumn() && column < columns.size();
                column++)
            {
                record.add(getText(rows.get(row), columns.get(column)));
            }

            records.add(record);
        }

        return DelimitedText.formatTsv(records);
    }

    /**
     * Pastes tabular data (e.g. parsed from tab separated values copied from a spreadsheet) into the sheet as a single
     * undoable operation.
     *
     * The data is pasted at the top-left cell of the selection.  When the selection is an exact multiple of the data
     * size in a dimension (e.g. a single value pasted into a multi-cell selection), the data is repeated to fill the
     * selection.  Rows beyond the last sheet row are added as new aliases in the specified alias list.  Columns beyond
     * the last sheet column, missing values in short (ragged) data rows and read-only cells are skipped.  Invalid
     * values are retained as invalid cell values (see getError()).
     *
     * @param rows in view order
     * @param columns in view order
     * @param selection range, or null to paste at the first cell
     * @param data rows of cell values
     * @param newRowAliasList alias list for added rows, or null
     * @return paste result
     */
    public PasteResult paste(List<Alias> rows, List<AliasSheetColumn> columns, SheetRange selection,
                             List<List<String>> data, String newRowAliasList)
    {
        if(data == null || data.isEmpty() || columns.isEmpty())
        {
            return new PasteResult(0, 0, 0, 0, null);
        }

        SheetRange target = selection != null ? selection : SheetRange.cell(0, 0);
        int firstRow = Math.max(0, Math.min(target.firstRow(), rows.size()));
        int firstColumn = Math.clamp(target.firstColumn(), 0, columns.size() - 1);

        int dataRows = data.size();
        int dataColumns = 0;

        for(List<String> record: data)
        {
            dataColumns = Math.max(dataColumns, record.size());
        }

        if(dataColumns == 0)
        {
            return new PasteResult(0, 0, 0, 0, null);
        }

        int rowCount = target.rowCount() > dataRows && target.rowCount() % dataRows == 0 ? target.rowCount() : dataRows;
        int columnCount = target.columnCount() > dataColumns && target.columnCount() % dataColumns == 0 ?
            target.columnCount() : dataColumns;
        columnCount = Math.min(columnCount, columns.size() - firstColumn);

        int addCount = Math.max(0, firstRow + rowCount - rows.size());
        SheetUndoStack.CompoundEdit compound = new SheetUndoStack.CompoundEdit("paste");
        List<Alias> added = createAliases(addCount, newRowAliasList);

        if(!added.isEmpty())
        {
            compound.add(addAliases(added));
        }

        List<Alias> extended = new ArrayList<>(rows);
        extended.addAll(added);

        List<CellEdit> edits = new ArrayList<>();
        int skipped = 0;

        for(int r = 0; r < rowCount; r++)
        {
            Alias alias = extended.get(firstRow + r);
            List<String> record = data.get(r % dataRows);

            for(int c = 0; c < columnCount; c++)
            {
                int dataColumn = c % dataColumns;

                if(dataColumn >= record.size())
                {
                    continue;
                }

                AliasSheetColumn column = columns.get(firstColumn + c);

                if(!isEditable(alias, column))
                {
                    skipped++;
                    continue;
                }

                edits.add(new CellEdit(alias, column, record.get(dataColumn)));
            }
        }

        List<CellChange> changes = applyCellEdits(edits);

        if(!changes.isEmpty())
        {
            compound.add(new CellChangesEdit("paste", changes));
        }

        if(!compound.isEmpty())
        {
            mUndoStack.push(compound);
        }

        int invalid = 0;

        for(CellEdit edit: edits)
        {
            if(getInvalidValue(edit.alias(), edit.column()) != null)
            {
                invalid++;
            }
        }

        return new PasteResult(changes.size(), added.size(), invalid, skipped,
            new SheetRange(firstRow, firstRow + rowCount - 1, firstColumn, firstColumn + columnCount - 1));
    }

    /**
     * Fills down the range as a single undoable operation: the value of the first row of the range is copied to the
     * other rows of the range for each column.  When the range is a single row, the value from the row above the range
     * is copied into the range.  Read-only cells are skipped.
     *
     * @param rows in view order
     * @param columns in view order
     * @param range to fill
     * @return number of cells that were changed
     */
    public int fillDown(List<Alias> rows, List<AliasSheetColumn> columns, SheetRange range)
    {
        int sourceRow;
        int firstTarget;

        if(range.rowCount() == 1)
        {
            sourceRow = range.firstRow() - 1;
            firstTarget = range.firstRow();
        }
        else
        {
            sourceRow = range.firstRow();
            firstTarget = range.firstRow() + 1;
        }

        if(sourceRow < 0 || sourceRow >= rows.size())
        {
            return 0;
        }

        Alias source = rows.get(sourceRow);
        List<CellEdit> edits = new ArrayList<>();

        for(int c = Math.max(0, range.firstColumn()); c <= range.lastColumn() && c < columns.size(); c++)
        {
            AliasSheetColumn column = columns.get(c);

            if(!isEditable(source, column))
            {
                continue;
            }

            String text = getText(source, column);

            for(int r = firstTarget; r <= range.lastRow() && r < rows.size(); r++)
            {
                if(isEditable(rows.get(r), column))
                {
                    edits.add(new CellEdit(rows.get(r), column, text));
                }
            }
        }

        return applyEdits("fill down", edits);
    }

    /**
     * Clears the cells in the range as a single undoable operation.  Cleared values revert to the column default
     * (e.g. Default listen priority, not recorded, not on the console).  Read-only cells are skipped.
     *
     * @param rows in view order
     * @param columns in view order
     * @param range to clear
     * @return number of cells that were changed
     */
    public int clear(List<Alias> rows, List<AliasSheetColumn> columns, SheetRange range)
    {
        List<CellEdit> edits = new ArrayList<>();

        for(int r = Math.max(0, range.firstRow()); r <= range.lastRow() && r < rows.size(); r++)
        {
            for(int c = Math.max(0, range.firstColumn()); c <= range.lastColumn() && c < columns.size(); c++)
            {
                if(isEditable(rows.get(r), columns.get(c)))
                {
                    edits.add(new CellEdit(rows.get(r), columns.get(c), ""));
                }
            }
        }

        return applyEdits("clear", edits);
    }

    /**
     * Adds a new alias (row) to the alias list as an undoable operation.
     * @param aliasListName for the alias, or null
     * @return the new alias
     */
    public Alias addRow(String aliasListName)
    {
        List<Alias> aliases = createAliases(1, aliasListName);
        mUndoStack.push(addAliases(aliases));
        return aliases.getFirst();
    }

    /**
     * Deletes the aliases (rows) from the alias model as an undoable operation.  Console cards for the aliases are
     * removed and are restored (with their volume, mute and pan settings) when the operation is undone.
     * @param aliases to delete
     * @return number of aliases deleted
     */
    public int deleteRows(Collection<Alias> aliases)
    {
        List<Alias> toDelete = new ArrayList<>();

        for(Alias alias: aliases)
        {
            if(alias != null && !containsIdentity(toDelete, alias))
            {
                toDelete.add(alias);
            }
        }

        if(toDelete.isEmpty())
        {
            return 0;
        }

        DeleteAliasesEdit edit = new DeleteAliasesEdit(toDelete);
        edit.redo();
        mUndoStack.push(edit);
        return toDelete.size();
    }

    /**
     * Applies a CSV import plan as a single undoable operation.
     * @param plan to apply
     * @return number of cells that were changed (including the values of added aliases)
     */
    public int applyImport(AliasSheetCsv.ImportPlan plan)
    {
        SheetUndoStack.CompoundEdit compound = new SheetUndoStack.CompoundEdit("import CSV");
        List<Alias> added = new ArrayList<>();
        List<CellEdit> edits = new ArrayList<>();

        for(AliasSheetCsv.PlannedRow row: plan.getRows())
        {
            if(row.isSkipped())
            {
                continue;
            }

            Alias alias = row.getExisting();

            if(alias == null)
            {
                alias = new Alias(DEFAULT_ALIAS_NAME);
                alias.setAliasListName(toModelListName(row.getAliasListName()));
                added.add(alias);
            }

            for(AliasSheetCsv.PlannedChange change: row.getChanges())
            {
                edits.add(new CellEdit(alias, change.column(), change.newText()));
            }
        }

        if(!added.isEmpty())
        {
            compound.add(addAliases(added));
        }

        List<CellChange> changes = applyCellEdits(edits);

        if(!changes.isEmpty())
        {
            compound.add(new CellChangesEdit("import CSV", changes));
        }

        if(!compound.isEmpty())
        {
            mUndoStack.push(compound);
        }

        return changes.size();
    }

    /**
     * Applies a RadioReference talkgroup import plan (see RadioReferenceImport) as a single undoable operation: adds
     * the new aliases, updates the name and group of existing aliases and adds console cards (creating console groups
     * that don't exist).  Undo removes the added aliases and their cards, restores the updated names and groups, and
     * removes the console groups that were created by the import (when they are empty).
     *
     * The RadioReference mode of each added, updated or duplicate alias is returned in the result and retained for the
     * session (see getRadioReferenceMode()); it is not saved in the playlist.
     *
     * @param plan to apply
     * @return result of the import
     */
    public RadioReferenceImport.Result applyRadioReferenceImport(RadioReferenceImport.Plan plan)
    {
        if(!plan.canApply())
        {
            return new RadioReferenceImport.Result(0, 0, plan.getRows().size(), 0, List.of(), List.of());
        }

        String description = "paste from RadioReference";
        Protocol protocol = plan.getOptions().getProtocol();
        String aliasList = toModelListName(plan.getAliasList());
        List<String> createdGroups = new ArrayList<>();

        if(mConsoleLayoutManager != null)
        {
            for(String group: plan.getNewConsoleGroups())
            {
                if(findConsoleGroup(group) == null)
                {
                    createdGroups.add(group);
                }
            }
        }

        List<Alias> added = new ArrayList<>();
        List<CellEdit> edits = new ArrayList<>();
        List<RadioReferenceImport.ModeAssignment> modes = new ArrayList<>();
        int updated = 0;
        int skipped = 0;

        for(RadioReferenceImport.PlannedRow row: plan.getRows())
        {
            Alias alias = null;

            switch(row.action())
            {
                case ADD:
                    alias = new Alias(row.name());
                    alias.setAliasListName(aliasList);
                    alias.setGroup(row.group().isEmpty() ? null : row.group());
                    alias.addAliasID(new Talkgroup(protocol, row.source().talkgroup()));

                    if(row.doNotMonitor())
                    {
                        alias.setCallPriority(Priority.DO_NOT_MONITOR);
                    }

                    added.add(alias);
                    break;
                case UPDATE:
                    alias = row.existing();
                    edits.add(new CellEdit(alias, AliasSheetColumn.NAME, row.name()));
                    edits.add(new CellEdit(alias, AliasSheetColumn.GROUP, row.group()));
                    updated++;
                    break;
                case SKIP_DUPLICATE:
                case SKIP_UNCHANGED:
                    //Same talkgroup: the mode applies to the existing alias
                    alias = row.existing();
                    skipped++;
                    break;
                default:
                    skipped++;
                    break;
            }

            if(alias != null && !row.action().isSkipped() && row.consoleGroup() != null &&
                mConsoleLayoutManager != null)
            {
                edits.add(new CellEdit(alias, AliasSheetColumn.CONSOLE_GROUP, row.consoleGroup()));
            }

            if(alias != null && row.source().mode() != null)
            {
                modes.add(new RadioReferenceImport.ModeAssignment(alias.getUuid(), row.source().mode()));
            }
        }

        //The created groups edit is first so that it is undone last, after the cards were removed from the groups
        SheetUndoStack.CompoundEdit compound = new SheetUndoStack.CompoundEdit(description);

        if(!createdGroups.isEmpty())
        {
            compound.add(new CreatedConsoleGroupsEdit(createdGroups));
        }

        if(!added.isEmpty())
        {
            compound.add(addAliases(added));
        }

        List<CellChange> changes = applyCellEdits(edits);

        if(!changes.isEmpty())
        {
            compound.add(new CellChangesEdit(description, changes));
        }

        if(!added.isEmpty() || !changes.isEmpty())
        {
            mUndoStack.push(compound);
        }

        int cards = (int)changes.stream().filter(change -> change.column() == AliasSheetColumn.CONSOLE_GROUP).count();

        for(RadioReferenceImport.ModeAssignment assignment: modes)
        {
            mRadioReferenceModes.put(assignment.aliasUuid(), assignment.mode());
        }

        return new RadioReferenceImport.Result(added.size(), updated, skipped, cards, List.copyOf(createdGroups),
            List.copyOf(modes));
    }

    /**
     * RadioReference talkgroup mode recorded for the alias by a RadioReference import during this session, or null.
     */
    public RadioReferenceMode getRadioReferenceMode(Alias alias)
    {
        return alias != null ? mRadioReferenceModes.get(alias.getUuid()) : null;
    }

    /**
     * RadioReference talkgroup modes recorded by RadioReference imports during this session, by alias UUID.
     */
    public Map<String,RadioReferenceMode> getRadioReferenceModes()
    {
        return Map.copyOf(mRadioReferenceModes);
    }

    /**
     * Finds the aliases in the alias list with the name, ignoring case and surrounding whitespace.
     */
    public List<Alias> findByName(String aliasListName, String name)
    {
        List<Alias> matches = new ArrayList<>();
        String trimmed = name != null ? name.trim() : "";

        for(Alias alias: mAliasModel.getAliases())
        {
            if(alias.matchesAliasList(toModelListName(aliasListName)) && alias.getName() != null &&
                alias.getName().trim().equalsIgnoreCase(trimmed))
            {
                matches.add(alias);
            }
        }

        return matches;
    }

    /**
     * Creates new (detached) aliases with the default name in the alias list.
     */
    private static List<Alias> createAliases(int count, String aliasListName)
    {
        List<Alias> aliases = new ArrayList<>();

        for(int x = 0; x < count; x++)
        {
            Alias alias = new Alias(DEFAULT_ALIAS_NAME);
            alias.setAliasListName(toModelListName(aliasListName));
            aliases.add(alias);
        }

        return aliases;
    }

    /**
     * Adds the aliases to the alias model and returns the (applied) undoable edit.
     */
    private SheetUndoStack.Edit addAliases(List<Alias> aliases)
    {
        AddAliasesEdit edit = new AddAliasesEdit(aliases);
        edit.redo();
        return edit;
    }

    /**
     * Applies the cell edits, grouped per alias, and returns the changes (with the previous text) for the cells that
     * were changed.
     */
    private List<CellChange> applyCellEdits(List<CellEdit> edits)
    {
        Map<Alias,Map<AliasSheetColumn,String>> grouped = new IdentityHashMap<>();
        List<Alias> order = new ArrayList<>();

        for(CellEdit edit: edits)
        {
            if(edit.alias() == null || edit.column() == null || !isEditable(edit.alias(), edit.column()))
            {
                continue;
            }

            Map<AliasSheetColumn,String> values = grouped.get(edit.alias());

            if(values == null)
            {
                values = new EnumMap<>(AliasSheetColumn.class);
                grouped.put(edit.alias(), values);
                order.add(edit.alias());
            }

            values.put(edit.column(), edit.text() != null ? edit.text() : "");
        }

        List<CellChange> changes = new ArrayList<>();
        boolean rebind = false;

        for(Alias alias: order)
        {
            Map<AliasSheetColumn,String> values = grouped.get(alias);
            Map<AliasSheetColumn,String> changed = new EnumMap<>(AliasSheetColumn.class);

            for(Map.Entry<AliasSheetColumn,String> entry: values.entrySet())
            {
                String oldText = getText(alias, entry.getKey());
                boolean invalid = getInvalidValue(alias, entry.getKey()) != null;

                //An invalid value is always re-applied so that it can be corrected by entering the same text
                if(invalid || !isSameValue(entry.getKey(), oldText, entry.getValue()))
                {
                    changed.put(entry.getKey(), entry.getValue());
                    changes.add(new CellChange(alias, entry.getKey(), oldText, entry.getValue()));
                }
            }

            if(!changed.isEmpty())
            {
                applyValues(alias, changed);
                rebind |= requiresRebind(alias, changed.keySet());
            }
        }

        if(rebind)
        {
            mConsoleLayoutManager.refreshAliasBindings();
        }

        return changes;
    }

    /**
     * Applies the changes in old (undo) or new (redo) direction.
     */
    private void applyChanges(List<CellChange> changes, boolean undo)
    {
        Map<Alias,Map<AliasSheetColumn,String>> grouped = new IdentityHashMap<>();
        List<Alias> order = new ArrayList<>();

        for(CellChange change: changes)
        {
            Map<AliasSheetColumn,String> values = grouped.get(change.alias());

            if(values == null)
            {
                values = new EnumMap<>(AliasSheetColumn.class);
                grouped.put(change.alias(), values);
                order.add(change.alias());
            }

            values.put(change.column(), undo ? change.oldText() : change.newText());
        }

        boolean rebind = false;

        for(Alias alias: order)
        {
            applyValues(alias, grouped.get(alias));
            rebind |= requiresRebind(alias, grouped.get(alias).keySet());
        }

        if(rebind)
        {
            mConsoleLayoutManager.refreshAliasBindings();
        }
    }

    /**
     * Indicates if the console card signature for the alias should be refreshed after changing the columns.
     */
    private boolean requiresRebind(Alias alias, Set<AliasSheetColumn> columns)
    {
        if(mConsoleLayoutManager == null || !isOnConsole(alias))
        {
            return false;
        }

        for(AliasSheetColumn column: columns)
        {
            if(SIGNATURE_COLUMNS.contains(column))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Applies the column text values to the alias.  The alias list is applied first, followed by the protocol and
     * talkgroup (as a pair), and then the remaining columns.
     */
    private void applyValues(Alias alias, Map<AliasSheetColumn,String> values)
    {
        if(values.containsKey(AliasSheetColumn.ALIAS_LIST))
        {
            applyAliasList(alias, values.get(AliasSheetColumn.ALIAS_LIST));
        }

        if(values.containsKey(AliasSheetColumn.PROTOCOL) || values.containsKey(AliasSheetColumn.TALKGROUP))
        {
            applyTalkgroup(alias, values.get(AliasSheetColumn.PROTOCOL), values.get(AliasSheetColumn.TALKGROUP));
        }

        for(Map.Entry<AliasSheetColumn,String> entry: values.entrySet())
        {
            switch(entry.getKey())
            {
                case ALIAS_LIST:
                case PROTOCOL:
                case TALKGROUP:
                    //Applied above
                    break;
                default:
                    applyValue(alias, entry.getKey(), entry.getValue());
                    break;
            }
        }
    }

    /**
     * Applies the text value to a single (non talkgroup) column of the alias.
     */
    private void applyValue(Alias alias, AliasSheetColumn column, String text)
    {
        String value = text != null ? text.trim() : "";

        switch(column)
        {
            case GROUP:
                clearInvalid(alias, column);
                alias.setGroup(value.isEmpty() ? null : value);
                break;
            case NAME:
                if(value.isEmpty())
                {
                    setInvalid(alias, column, text, "Name is required");
                }
                else
                {
                    clearInvalid(alias, column);
                    alias.setName(value);
                }
                break;
            case COLOUR:
                Integer colour = AliasSheetValues.parseColour(value);

                if(colour == null)
                {
                    setInvalid(alias, column, text, invalidColourMessage(value));
                }
                else
                {
                    clearInvalid(alias, column);
                    alias.setColor(colour);
                }
                break;
            case ICON:
                if(value.isEmpty())
                {
                    clearInvalid(alias, column);
                    alias.setIconName(null);
                }
                else
                {
                    String iconName = resolveIconName(value);

                    if(iconName == null)
                    {
                        setInvalid(alias, column, text, "Unknown icon '" + value + "'");
                    }
                    else
                    {
                        clearInvalid(alias, column);
                        alias.setIconName(iconName);
                    }
                }
                break;
            case LISTEN:
                Integer priority = AliasSheetValues.parseListen(value);

                if(priority == null)
                {
                    setInvalid(alias, column, text, invalidListenMessage(value));
                }
                else
                {
                    clearInvalid(alias, column);

                    if(priority != alias.getPlaybackPriority())
                    {
                        alias.setCallPriority(priority);
                    }
                }
                break;
            case RECORD:
                Boolean record = AliasSheetValues.parseRecord(value);

                if(record == null)
                {
                    setInvalid(alias, column, text, invalidRecordMessage(value));
                }
                else
                {
                    clearInvalid(alias, column);

                    if(record != alias.isRecordable())
                    {
                        alias.setRecordable(record);
                    }
                }
                break;
            case STREAM:
                applyStreams(alias, text);
                break;
            case CONSOLE_GROUP:
                applyConsoleGroup(alias, value);
                break;
            default:
                break;
        }
    }

    /**
     * Moves the alias to the alias list, following the alias editor's 'move to' behavior.
     */
    private void applyAliasList(Alias alias, String text)
    {
        clearInvalid(alias, AliasSheetColumn.ALIAS_LIST);
        String newList = toModelListName(text);
        String currentList = alias.getAliasListName();

        if(Objects.equals(newList, currentList == null || currentList.isEmpty() ? null : currentList))
        {
            return;
        }

        AliasList existing = mAliasModel.getAliasList(currentList);
        existing.removeAlias(alias);
        alias.setAliasListName(newList);
        mAliasModel.getAliasList(newList).addAlias(alias);
        mAliasModel.addAliasList(newList);
    }

    /**
     * Applies the protocol and/or talkgroup text to the alias's talkgroup identifier.
     * @param alias to update
     * @param protocolText new protocol text, or null if unchanged
     * @param talkgroupText new talkgroup text, or null if unchanged
     */
    private void applyTalkgroup(Alias alias, String protocolText, String talkgroupText)
    {
        if(isComplex(alias))
        {
            return;
        }

        String uuid = alias.getUuid();
        Talkgroup existing = getTalkgroup(alias);

        //Resolve the protocol
        Protocol protocol = null;
        String protocolError = null;
        boolean protocolBlank = false;

        if(protocolText != null)
        {
            String value = protocolText.trim();

            if(value.isEmpty())
            {
                //A blank protocol keeps the current protocol (a talkgroup always has a protocol) or the default
                protocolBlank = true;
                protocol = mPendingProtocols.get(uuid);

                if(protocol == null && existing != null)
                {
                    protocol = existing.getProtocol();
                }
            }
            else
            {
                protocol = AliasSheetValues.parseProtocol(value);

                if(protocol == null)
                {
                    protocolError = invalidProtocolMessage(value);
                }
            }

            if(protocolError != null)
            {
                setInvalid(alias, AliasSheetColumn.PROTOCOL, protocolText, protocolError);
            }
            else
            {
                clearInvalid(alias, AliasSheetColumn.PROTOCOL);
            }
        }
        else
        {
            InvalidValue invalidProtocol = getInvalidValue(alias, AliasSheetColumn.PROTOCOL);

            if(invalidProtocol != null)
            {
                protocolError = invalidProtocol.message();
            }
            else
            {
                protocol = mPendingProtocols.get(uuid);

                if(protocol == null && existing != null)
                {
                    protocol = existing.getProtocol();
                }
            }
        }

        //Resolve the talkgroup text
        String talkgroup;

        if(talkgroupText != null)
        {
            talkgroup = talkgroupText.trim();
        }
        else
        {
            InvalidValue invalidTalkgroup = getInvalidValue(alias, AliasSheetColumn.TALKGROUP);

            if(invalidTalkgroup != null)
            {
                talkgroup = invalidTalkgroup.text().trim();
            }
            else if(existing != null)
            {
                talkgroup = null;
            }
            else
            {
                talkgroup = "";
            }
        }

        //No talkgroup: remove any existing talkgroup and remember the protocol for a talkgroup entered later
        if(talkgroup != null && talkgroup.isEmpty())
        {
            clearInvalid(alias, AliasSheetColumn.TALKGROUP);

            if(existing != null)
            {
                alias.removeAliasID(existing);
                updateAliasList(alias);
            }

            if(protocol != null && protocolError == null && !protocolBlank)
            {
                mPendingProtocols.put(uuid, protocol);
            }
            else if(protocolText != null || existing != null)
            {
                mPendingProtocols.remove(uuid);
            }

            return;
        }

        String talkgroupDisplay = talkgroup != null ? talkgroup :
            formatTalkgroup(existing.getProtocol(), existing.getValue());

        if(protocolError != null)
        {
            if(talkgroupText != null)
            {
                setInvalid(alias, AliasSheetColumn.TALKGROUP, talkgroupText,
                    "Select a valid protocol for talkgroup '" + talkgroupDisplay + "'");
            }

            return;
        }

        if(protocol == null)
        {
            protocol = getDefaultProtocol(alias.getAliasListName());
        }

        Integer value;

        if(talkgroup == null)
        {
            //Existing talkgroup value with a (possibly) new protocol
            value = TalkgroupFormat.get(protocol).isValid(existing.getValue()) ? existing.getValue() : null;
        }
        else
        {
            value = parseTalkgroup(protocol, talkgroup);
        }

        if(value == null)
        {
            setInvalid(alias, AliasSheetColumn.TALKGROUP, talkgroupText != null ? talkgroupText : talkgroupDisplay,
                invalidTalkgroupMessage(protocol, talkgroupDisplay));

            //Show the selected protocol until the talkgroup is corrected
            mPendingProtocols.put(uuid, protocol);
            return;
        }

        clearInvalid(alias, AliasSheetColumn.TALKGROUP);
        clearInvalid(alias, AliasSheetColumn.PROTOCOL);
        mPendingProtocols.remove(uuid);

        if(existing != null && existing.getProtocol() == protocol && existing.getValue() == value)
        {
            return;
        }

        if(existing != null)
        {
            alias.removeAliasID(existing);
        }

        alias.addAliasID(new Talkgroup(protocol, value));
        updateAliasList(alias);
    }

    /**
     * Parses and validates the talkgroup text for the protocol.
     * @return talkgroup value or null if the text is not a valid talkgroup for the protocol.
     */
    private static Integer parseTalkgroup(Protocol protocol, String text)
    {
        if(text == null || text.isBlank())
        {
            return null;
        }

        try
        {
            int value = TalkgroupFormatter.parse(protocol, text.trim());

            if(TalkgroupFormat.get(protocol).isValid(value))
            {
                return value;
            }
        }
        catch(Exception e)
        {
            //Not valid
        }

        return null;
    }

    /**
     * Default protocol for a new talkgroup: the most common talkgroup protocol in the alias list, or APCO-25.
     */
    private Protocol getDefaultProtocol(String aliasListName)
    {
        Map<Protocol,Integer> counts = new EnumMap<>(Protocol.class);

        for(Alias alias: mAliasModel.getAliases())
        {
            if(alias.matchesAliasList(aliasListName))
            {
                Talkgroup talkgroup = getTalkgroup(alias);

                if(talkgroup != null && talkgroup.getProtocol() != null)
                {
                    counts.merge(talkgroup.getProtocol(), 1, Integer::sum);
                }
            }
        }

        Protocol protocol = Protocol.APCO25;
        int max = 0;

        for(Map.Entry<Protocol,Integer> entry: counts.entrySet())
        {
            if(entry.getValue() > max)
            {
                max = entry.getValue();
                protocol = entry.getKey();
            }
        }

        return protocol;
    }

    /**
     * Updates the alias in its alias list after an identifier change, as the alias editor does.
     */
    private void updateAliasList(Alias alias)
    {
        AliasList aliasList = mAliasModel.getAliasList(alias.getAliasListName());

        if(aliasList != null)
        {
            aliasList.updateAlias(alias);
        }
    }

    /**
     * Applies the stream (broadcast channel) list text to the alias.
     */
    private void applyStreams(Alias alias, String text)
    {
        List<String> current = getStreams(alias);
        List<String> streams = new ArrayList<>();

        for(String stream: AliasSheetValues.splitStreams(text))
        {
            String resolved = resolveStreamName(stream, current);

            if(resolved == null)
            {
                setInvalid(alias, AliasSheetColumn.STREAM, text, unknownStreamMessage(stream));
                return;
            }

            if(!streams.contains(resolved))
            {
                streams.add(resolved);
            }
        }

        clearInvalid(alias, AliasSheetColumn.STREAM);

        if(new LinkedHashSet<>(streams).equals(new LinkedHashSet<>(current)))
        {
            return;
        }

        alias.removeAllBroadcastChannels();

        for(String stream: streams)
        {
            alias.addAliasID(new BroadcastChannel(stream));
        }
    }

    /**
     * Adds, moves or removes the alias's console card for the console group name.
     */
    private void applyConsoleGroup(Alias alias, String groupName)
    {
        if(mConsoleLayoutManager == null)
        {
            return;
        }

        clearInvalid(alias, AliasSheetColumn.CONSOLE_GROUP);
        String uuid = alias.getUuid();
        ConsoleCard card = mConsoleLayoutManager.getCardForAlias(uuid);

        if(AliasSheetValues.isNotOnConsole(groupName))
        {
            if(card != null)
            {
                mRemovedCards.put(uuid, card);
                mConsoleLayoutManager.removeCard(card.getId());
            }

            return;
        }

        ConsoleGroup group = findConsoleGroup(groupName);

        if(group == null)
        {
            group = mConsoleLayoutManager.addGroup(groupName.trim());
        }

        if(card == null)
        {
            ConsoleCard added = mConsoleLayoutManager.addCard(group.getId(), uuid);
            ConsoleCard removed = mRemovedCards.remove(uuid);

            if(removed != null)
            {
                mConsoleLayoutManager.setCardVolume(added.getId(), removed.getVolume());
                mConsoleLayoutManager.setCardMuted(added.getId(), removed.isMuted());
                mConsoleLayoutManager.setCardPan(added.getId(), removed.getPan());
            }
        }
        else
        {
            ConsoleAssignment assignment = getConsoleAssignment(alias);

            if(assignment == null || !group.getId().equals(assignment.groupId()))
            {
                mConsoleLayoutManager.moveCard(card.getId(), group.getId(), Integer.MAX_VALUE);
            }
        }
    }

    /**
     * Finds the console group with the name, ignoring case and surrounding whitespace.
     */
    private ConsoleGroup findConsoleGroup(String name)
    {
        String trimmed = name.trim();

        for(ConsoleGroup group: mConsoleLayoutManager.getLayout().getGroups())
        {
            if(group.getName() != null && group.getName().trim().equalsIgnoreCase(trimmed))
            {
                return group;
            }
        }

        return null;
    }

    /**
     * Resolves the icon name to the icon model's name, ignoring case.
     * @return icon name or null if there is no such icon
     */
    private String resolveIconName(String name)
    {
        if(mIconNameSupplier == null)
        {
            return name;
        }

        for(String iconName: mIconNameSupplier.get())
        {
            if(iconName != null && iconName.equalsIgnoreCase(name.trim()))
            {
                return iconName;
            }
        }

        return null;
    }

    /**
     * Resolves the stream name to a configured broadcast channel name (or a name already assigned to the alias),
     * ignoring case.
     * @return stream name or null if the stream is not configured.
     */
    private String resolveStreamName(String name, List<String> current)
    {
        if(current != null)
        {
            for(String stream: current)
            {
                if(stream.equalsIgnoreCase(name))
                {
                    return stream;
                }
            }
        }

        if(mBroadcastChannelSupplier == null)
        {
            return name;
        }

        for(String stream: mBroadcastChannelSupplier.get())
        {
            if(stream != null && stream.equalsIgnoreCase(name))
            {
                return stream;
            }
        }

        return null;
    }

    private void setInvalid(Alias alias, AliasSheetColumn column, String text, String message)
    {
        mInvalidValues.computeIfAbsent(alias.getUuid(), key -> new EnumMap<>(AliasSheetColumn.class))
            .put(column, new InvalidValue(text != null ? text : "", message));
    }

    private void clearInvalid(Alias alias, AliasSheetColumn column)
    {
        Map<AliasSheetColumn,InvalidValue> invalidValues = mInvalidValues.get(alias.getUuid());

        if(invalidValues != null)
        {
            invalidValues.remove(column);

            if(invalidValues.isEmpty())
            {
                mInvalidValues.remove(alias.getUuid());
            }
        }
    }

    private static String invalidProtocolMessage(String value)
    {
        return "Unknown talkgroup protocol '" + value + "'.  Valid protocols: " +
            Protocol.TALKGROUP_PROTOCOLS.stream().map(Protocol::toString).collect(Collectors.joining(", "));
    }

    private static String invalidTalkgroupMessage(Protocol protocol, String value)
    {
        return "Talkgroup '" + value + "' is not valid for " + protocol + " (valid range: " +
            TalkgroupFormat.get(protocol).getValidRangeDescription() + ")";
    }

    private static String invalidColourMessage(String value)
    {
        return "Colour '" + value + "' is not valid - use #RRGGBB (e.g. #FF8A3D)";
    }

    private static String invalidListenMessage(String value)
    {
        return "Listen value '" + value + "' is not valid - use Default, Do not monitor or Priority 1-99";
    }

    private static String invalidRecordMessage(String value)
    {
        return "Record value '" + value + "' is not valid - use Yes or No";
    }

    private static String unknownStreamMessage(String value)
    {
        return "Unknown stream '" + value + "' - streams must be configured in the Streaming tab first";
    }

    private static String nonNull(String value)
    {
        return value != null ? value : "";
    }

    /**
     * Display name for an alias list name: empty for no alias list.
     */
    private static String getListName(String aliasListName)
    {
        if(aliasListName == null || aliasListName.isBlank() || aliasListName.trim().equals(AliasModel.NO_ALIAS_LIST))
        {
            return "";
        }

        return aliasListName.trim();
    }

    /**
     * Alias list name to apply to an alias: null for no alias list.
     */
    static String toModelListName(String aliasListName)
    {
        String name = getListName(aliasListName);
        return name.isEmpty() ? null : name;
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

    /**
     * Invalid (unapplied) cell value.
     * @param text entered by the user
     * @param message describing the error
     */
    public record InvalidValue(String text, String message)
    {
    }

    /**
     * Request to set the text of a cell.
     * @param alias for the row
     * @param column of the cell
     * @param text to apply
     */
    public record CellEdit(Alias alias, AliasSheetColumn column, String text)
    {
    }

    /**
     * Applied change to a cell, for undo and redo.
     */
    record CellChange(Alias alias, AliasSheetColumn column, String oldText, String newText)
    {
    }

    /**
     * Result of a paste operation.
     * @param changedCells number of cells that were changed
     * @param addedRows number of aliases that were added
     * @param invalidCells number of pasted cells that contain an invalid value
     * @param skippedCells number of read-only cells that were skipped
     * @param range of pasted cells, or null if nothing was pasted
     */
    public record PasteResult(int changedCells, int addedRows, int invalidCells, int skippedCells, SheetRange range)
    {
    }

    /**
     * Console card assignment for an alias.
     */
    private record ConsoleAssignment(String groupId, String groupName, String cardId)
    {
    }

    /**
     * Undoable cell changes
     */
    private class CellChangesEdit implements SheetUndoStack.Edit
    {
        private final String mDescription;
        private final List<CellChange> mChanges;

        CellChangesEdit(String description, List<CellChange> changes)
        {
            mDescription = description;
            mChanges = changes;
        }

        @Override
        public String getDescription()
        {
            return mDescription;
        }

        @Override
        public void undo()
        {
            applyChanges(mChanges, true);
        }

        @Override
        public void redo()
        {
            applyChanges(mChanges, false);
        }
    }

    /**
     * Undoable addition of aliases to the alias model.
     */
    private class AddAliasesEdit implements SheetUndoStack.Edit
    {
        private final List<Alias> mAliases;

        AddAliasesEdit(List<Alias> aliases)
        {
            mAliases = new ArrayList<>(aliases);
        }

        @Override
        public String getDescription()
        {
            return mAliases.size() == 1 ? "add row" : "add rows";
        }

        @Override
        public void undo()
        {
            mAliasModel.removeAliases(mAliases);
        }

        @Override
        public void redo()
        {
            mAliasModel.addAliases(mAliases);
        }
    }

    /**
     * Removes the console groups that were created by an operation when the operation is undone (when the groups are
     * empty by then).  The groups are re-created (by name) when the operation's console group cell changes are redone.
     */
    private class CreatedConsoleGroupsEdit implements SheetUndoStack.Edit
    {
        private final List<String> mGroupNames;

        CreatedConsoleGroupsEdit(List<String> groupNames)
        {
            mGroupNames = new ArrayList<>(groupNames);
        }

        @Override
        public String getDescription()
        {
            return "create console groups";
        }

        @Override
        public void undo()
        {
            if(mConsoleLayoutManager == null)
            {
                return;
            }

            for(String name: mGroupNames)
            {
                ConsoleGroup group = findConsoleGroup(name);

                if(group != null && group.getCards().isEmpty())
                {
                    mConsoleLayoutManager.removeGroup(group.getId());
                }
            }
        }

        @Override
        public void redo()
        {
            //Groups are re-created by the console group cell changes
        }
    }

    /**
     * Undoable deletion of aliases and their console cards.
     */
    private class DeleteAliasesEdit implements SheetUndoStack.Edit
    {
        private final List<Alias> mAliases;
        private final Map<Alias,String> mConsoleGroups = new LinkedHashMap<>();

        DeleteAliasesEdit(List<Alias> aliases)
        {
            mAliases = aliases;
        }

        @Override
        public String getDescription()
        {
            return mAliases.size() == 1 ? "delete row" : "delete rows";
        }

        @Override
        public void undo()
        {
            mAliasModel.addAliases(mAliases);

            for(Map.Entry<Alias,String> entry: mConsoleGroups.entrySet())
            {
                applyConsoleGroup(entry.getKey(), entry.getValue());
            }
        }

        @Override
        public void redo()
        {
            mConsoleGroups.clear();

            for(Alias alias: mAliases)
            {
                ConsoleAssignment assignment = getConsoleAssignment(alias);

                if(assignment != null)
                {
                    mConsoleGroups.put(alias, assignment.groupName());
                    applyConsoleGroup(alias, "");
                }
            }

            mAliasModel.removeAliases(mAliases);
        }
    }
}
