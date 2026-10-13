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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;

/**
 * Bounded undo/redo stack of sheet edits.  Recording a new edit clears the redo stack.
 */
public class SheetUndoStack
{
    public static final int DEFAULT_MAXIMUM_SIZE = 200;

    private final Deque<Edit> mUndoStack = new ArrayDeque<>();
    private final Deque<Edit> mRedoStack = new ArrayDeque<>();
    private final ReadOnlyBooleanWrapper mCanUndo = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper mCanRedo = new ReadOnlyBooleanWrapper();
    private final int mMaximumSize;

    /**
     * Constructs an instance with the default maximum size.
     */
    public SheetUndoStack()
    {
        this(DEFAULT_MAXIMUM_SIZE);
    }

    /**
     * Constructs an instance
     * @param maximumSize number of edits retained for undo
     */
    public SheetUndoStack(int maximumSize)
    {
        mMaximumSize = Math.max(1, maximumSize);
    }

    /**
     * Records an edit that has already been applied.
     */
    public void push(Edit edit)
    {
        if(edit == null)
        {
            return;
        }

        mUndoStack.push(edit);

        while(mUndoStack.size() > mMaximumSize)
        {
            mUndoStack.removeLast();
        }

        mRedoStack.clear();
        updateProperties();
    }

    /**
     * Undoes the most recent edit.
     * @return the undone edit or null if there was nothing to undo.
     */
    public Edit undo()
    {
        if(mUndoStack.isEmpty())
        {
            return null;
        }

        Edit edit = mUndoStack.pop();
        edit.undo();
        mRedoStack.push(edit);
        updateProperties();
        return edit;
    }

    /**
     * Redoes the most recently undone edit.
     * @return the redone edit or null if there was nothing to redo.
     */
    public Edit redo()
    {
        if(mRedoStack.isEmpty())
        {
            return null;
        }

        Edit edit = mRedoStack.pop();
        edit.redo();
        mUndoStack.push(edit);
        updateProperties();
        return edit;
    }

    /**
     * Removes all edits.
     */
    public void clear()
    {
        mUndoStack.clear();
        mRedoStack.clear();
        updateProperties();
    }

    /**
     * Description of the edit that would be undone, or null.
     */
    public String getUndoDescription()
    {
        return mUndoStack.isEmpty() ? null : mUndoStack.peek().getDescription();
    }

    /**
     * Description of the edit that would be redone, or null.
     */
    public String getRedoDescription()
    {
        return mRedoStack.isEmpty() ? null : mRedoStack.peek().getDescription();
    }

    /**
     * Indicates if there is an edit that can be undone.
     */
    public ReadOnlyBooleanProperty canUndoProperty()
    {
        return mCanUndo.getReadOnlyProperty();
    }

    /**
     * Indicates if there is an edit that can be redone.
     */
    public ReadOnlyBooleanProperty canRedoProperty()
    {
        return mCanRedo.getReadOnlyProperty();
    }

    private void updateProperties()
    {
        mCanUndo.set(!mUndoStack.isEmpty());
        mCanRedo.set(!mRedoStack.isEmpty());
    }

    /**
     * An undoable edit.
     */
    public interface Edit
    {
        /**
         * Short description of the edit, e.g. 'paste' or 'fill down'.
         */
        String getDescription();

        /**
         * Reverts the edit.
         */
        void undo();

        /**
         * Re-applies the edit after it was undone.
         */
        void redo();
    }

    /**
     * Edit that is composed of a sequence of edits.  The edits are redone in order and undone in reverse order.
     */
    public static class CompoundEdit implements Edit
    {
        private final String mDescription;
        private final List<Edit> mEdits = new ArrayList<>();

        /**
         * Constructs an instance
         * @param description of the edit
         */
        public CompoundEdit(String description)
        {
            mDescription = description;
        }

        /**
         * Adds an (already applied) edit to this compound edit.
         */
        public void add(Edit edit)
        {
            if(edit != null)
            {
                mEdits.add(edit);
            }
        }

        /**
         * Indicates if this compound edit does not contain any edits.
         */
        public boolean isEmpty()
        {
            return mEdits.isEmpty();
        }

        @Override
        public String getDescription()
        {
            return mDescription;
        }

        @Override
        public void undo()
        {
            for(int x = mEdits.size() - 1; x >= 0; x--)
            {
                mEdits.get(x).undo();
            }
        }

        @Override
        public void redo()
        {
            for(Edit edit: mEdits)
            {
                edit.redo();
            }
        }
    }
}
