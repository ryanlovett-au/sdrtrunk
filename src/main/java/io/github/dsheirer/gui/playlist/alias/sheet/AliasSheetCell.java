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

package io.github.dsheirer.gui.playlist.alias.sheet;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.sheet.AliasSheetColumn;
import io.github.dsheirer.alias.sheet.AliasSheetModel;
import io.github.dsheirer.alias.sheet.AliasSheetValues;
import io.github.dsheirer.alias.sheet.TalkgroupConflict;
import io.github.dsheirer.gui.playlist.alias.ColorUtil;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Control;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableCell;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * Alias sheet table cell.  Displays the cell text with column specific decorations (colour swatch, icon, record check
 * box, 'More...' link for complex aliases), invalid (red) and conflict (amber) highlighting with a tooltip, and
 * provides the in-place editor for the column: a text field, a choice box, or a colour picker.
 *
 * Edits are committed when editing ends for any reason other than Escape (Enter, Tab, clicking another cell, or focus
 * loss), so that a spreadsheet-like 'type and move on' workflow does not lose values.  All changes are applied via the
 * alias sheet editor so that they are undoable.
 */
public class AliasSheetCell extends TableCell<Alias,String>
{
    private static final String INVALID_COLOR = "rgba(220,53,69,0.30)";
    private static final String INVALID_BORDER = "#DC3545";
    private static final String CONFLICT_COLOR = "rgba(232,170,40,0.35)";
    private static final String CONFLICT_BORDER = "#E8AA28";
    private static final String MUTED_TEXT = "-fx-opacity: 0.6;";

    private final AliasSheetEditor mEditor;
    private final AliasSheetColumn mColumn;
    private Control mEditorControl;
    private Alias mEditingAlias;
    private String mOriginalText;
    private boolean mEscapePressed;
    private String mHighlightColor;
    private String mHighlightBorder;
    private String mTextStyle = "";

    /**
     * Constructs an instance
     * @param editor that owns the table
     * @param column for this cell
     */
    public AliasSheetCell(AliasSheetEditor editor, AliasSheetColumn column)
    {
        mEditor = editor;
        mColumn = column;

        setOnMouseClicked(event -> {
            if(event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !isEmpty())
            {
                Alias alias = getAlias();

                if(alias == null)
                {
                    return;
                }

                if(!getModel().isEditable(alias, mColumn) &&
                    (mColumn == AliasSheetColumn.TALKGROUP || mColumn == AliasSheetColumn.PROTOCOL))
                {
                    mEditor.openAliasEditor(alias);
                }
                else if(mColumn == AliasSheetColumn.STREAM)
                {
                    mEditor.editStreams(alias);
                }
            }
        });
    }

    private AliasSheetModel getModel()
    {
        return mEditor.getModel();
    }

    /**
     * Alias for this cell's row, or null.
     */
    private Alias getAlias()
    {
        TableView<Alias> table = getTableView();
        int index = getIndex();

        if(table != null && index >= 0 && index < table.getItems().size())
        {
            return table.getItems().get(index);
        }

        return null;
    }

    @Override
    protected void updateItem(String item, boolean empty)
    {
        super.updateItem(item, empty);

        if(isEditing() && mEditorControl != null)
        {
            setText(null);
            setGraphic(mEditorControl);
            return;
        }

        render(item, empty);
    }

    @Override
    public void updateSelected(boolean selected)
    {
        super.updateSelected(selected);
        applyStyle();
    }

    /**
     * Renders the display (non-editing) state of the cell.
     */
    private void render(String item, boolean empty)
    {
        setTooltip(null);
        mHighlightColor = null;
        mHighlightBorder = null;
        mTextStyle = "";
        setContentDisplay(ContentDisplay.LEFT);
        setAlignment(Pos.CENTER_LEFT);

        Alias alias = getAlias();

        if(empty || alias == null)
        {
            setText(null);
            setGraphic(null);
            applyStyle();
            return;
        }

        String text = item != null ? item : "";
        Node graphic = null;
        String error = getModel().getError(alias, mColumn);
        boolean editable = getModel().isEditable(alias, mColumn);

        switch(mColumn)
        {
            case COLOUR:
                if(error == null && alias.getColor() != 0)
                {
                    Rectangle swatch = new Rectangle(14, 14);
                    swatch.setArcWidth(4);
                    swatch.setArcHeight(4);
                    swatch.setFill(ColorUtil.fromInteger(alias.getColor()));
                    swatch.setStroke(Color.gray(0.5, 0.5));
                    graphic = swatch;
                }
                break;
            case ICON:
                if(error == null)
                {
                    Image image = mEditor.getIconImage(alias.getIconName());

                    if(image != null)
                    {
                        ImageView imageView = new ImageView(image);
                        imageView.setPreserveRatio(true);
                        imageView.setFitHeight(16);
                        graphic = imageView;
                    }
                }
                break;
            case RECORD:
                if(error == null)
                {
                    CheckBox checkBox = new CheckBox();
                    checkBox.setSelected(alias.isRecordable());
                    checkBox.setOnAction(event -> mEditor.commitCell(alias, AliasSheetColumn.RECORD,
                        AliasSheetValues.formatRecord(checkBox.isSelected())));
                    graphic = checkBox;
                    text = null;
                    setAlignment(Pos.CENTER);
                }
                break;
            case STREAM:
                if(text.isEmpty())
                {
                    text = "—";
                    mTextStyle = MUTED_TEXT;
                }
                break;
            case CONSOLE_GROUP:
                if(text.isEmpty())
                {
                    text = AliasSheetValues.NOT_ON_CONSOLE;
                    mTextStyle = MUTED_TEXT;
                }
                break;
            case TALKGROUP:
                if(!editable)
                {
                    Hyperlink more = new Hyperlink("More…");
                    more.setFocusTraversable(false);
                    more.setOnAction(event -> mEditor.openAliasEditor(alias));
                    graphic = more;
                    setContentDisplay(ContentDisplay.RIGHT);
                    mTextStyle = "-fx-font-style: italic;";
                    setTooltip(new Tooltip("This alias has multiple identifiers or actions: " +
                        AliasSheetModel.getIdentifierSummary(alias) + "\nUse More… (or double-click) to " +
                        "edit it in the alias editor."));
                }
                break;
            case PROTOCOL:
                if(!editable)
                {
                    mTextStyle = "-fx-font-style: italic;";
                }
                break;
            default:
                break;
        }

        if(error != null)
        {
            mHighlightColor = INVALID_COLOR;
            mHighlightBorder = INVALID_BORDER;
            setTooltip(new Tooltip(error));
        }
        else if(mColumn == AliasSheetColumn.TALKGROUP)
        {
            List<TalkgroupConflict> conflicts = mEditor.getConflictReport().getConflicts(alias);

            if(!conflicts.isEmpty())
            {
                mHighlightColor = CONFLICT_COLOR;
                mHighlightBorder = CONFLICT_BORDER;
                setTooltip(new Tooltip(conflicts.stream().map(TalkgroupConflict::getMessage)
                    .collect(Collectors.joining("\n")) + "\nOnly the first matching alias will be used for calls."));
            }
        }

        setText(text);
        setGraphic(graphic);
        applyStyle();
    }

    /**
     * Applies the highlight style.  A selected cell shows the highlight as a bottom border so that the selection
     * colour remains visible.
     */
    private void applyStyle()
    {
        if(mHighlightColor == null || isEditing())
        {
            setStyle(mTextStyle);
        }
        else if(isSelected())
        {
            setStyle(mTextStyle + "-fx-border-color: " + mHighlightBorder + "; -fx-border-width: 0 0 3 0;");
        }
        else
        {
            setStyle(mTextStyle + "-fx-background-color: " + mHighlightColor + ";");
        }
    }

    @Override
    public void startEdit()
    {
        Alias alias = getAlias();
        boolean keyboard = mEditor.consumeKeyboardEditRequest();

        if(isEmpty() || alias == null || !getModel().isEditable(alias, mColumn))
        {
            mEditor.consumePendingEditText();
            resetTableEditingCell();
            return;
        }

        //Record toggles and stream opens a dialog - only from the keyboard (Enter, F2, typing) so that a mouse click on
        //a selected cell doesn't change the value.  The record check box and a double-click work with the mouse.
        if(mColumn == AliasSheetColumn.RECORD)
        {
            mEditor.consumePendingEditText();
            resetTableEditingCell();

            if(keyboard)
            {
                mEditor.commitCell(alias, AliasSheetColumn.RECORD,
                    AliasSheetValues.formatRecord(!alias.isRecordable()));
            }

            return;
        }

        if(mColumn == AliasSheetColumn.STREAM)
        {
            mEditor.consumePendingEditText();
            resetTableEditingCell();

            if(keyboard)
            {
                Platform.runLater(() -> mEditor.editStreams(alias));
            }

            return;
        }

        super.startEdit();

        if(!isEditing())
        {
            return;
        }

        mEditingAlias = alias;
        mOriginalText = getModel().getText(alias, mColumn);
        mEscapePressed = false;
        String pending = mEditor.consumePendingEditText();

        switch(mColumn)
        {
            case ALIAS_LIST:
            case PROTOCOL:
            case ICON:
            case LISTEN:
            case CONSOLE_GROUP:
                mEditorControl = createChoiceEditor(pending);
                break;
            case COLOUR:
                mEditorControl = pending != null ? createTextEditor(pending) : createColourEditor(alias);
                break;
            default:
                mEditorControl = createTextEditor(pending);
                break;
        }

        setText(null);
        setGraphic(mEditorControl);
        applyStyle();
        mEditorControl.requestFocus();
    }

    /**
     * Clears the table's editing cell when this cell declines to start editing, so that the table does not remain in an
     * editing state without an editing cell.
     */
    private void resetTableEditingCell()
    {
        TableView<Alias> table = getTableView();

        if(table != null && table.getEditingCell() != null)
        {
            TablePosition<Alias,?> position = table.getEditingCell();

            Platform.runLater(() -> {
                if(Objects.equals(table.getEditingCell(), position) && !isEditing())
                {
                    table.edit(-1, null);
                }
            });
        }
    }

    /**
     * Text field editor.
     * @param pending typed text that started the edit, or null to edit the current value
     */
    private TextField createTextEditor(String pending)
    {
        TextField textField = new TextField(pending != null ? pending : mOriginalText);
        textField.addEventFilter(KeyEvent.KEY_PRESSED, this::handleEditorKey);
        textField.focusedProperty().addListener((observable, wasFocused, focused) -> {
            if(!focused && isEditing() && mEditorControl == textField)
            {
                cancelEdit();
            }
        });

        Platform.runLater(() -> {
            if(pending != null)
            {
                textField.deselect();
                textField.end();
            }
            else
            {
                textField.selectAll();
            }
        });

        return textField;
    }

    /**
     * Choice (combo box) editor.  The alias list and console group editors accept typed values so that a new alias
     * list or console group can be entered.
     * @param pending typed text that started the edit, or null to edit the current value
     */
    private ComboBox<String> createChoiceEditor(String pending)
    {
        ComboBox<String> comboBox = new ComboBox<>();
        comboBox.getItems().setAll(mEditor.getChoices(mColumn));
        comboBox.setMaxWidth(Double.MAX_VALUE);
        boolean editable = mColumn == AliasSheetColumn.ALIAS_LIST || mColumn == AliasSheetColumn.CONSOLE_GROUP;
        comboBox.setEditable(editable);
        comboBox.setVisibleRowCount(15);

        String current = mOriginalText;

        if(mColumn == AliasSheetColumn.CONSOLE_GROUP && (current == null || current.isEmpty()))
        {
            current = AliasSheetValues.NOT_ON_CONSOLE;
        }

        comboBox.setValue(current);

        if(mColumn == AliasSheetColumn.ICON)
        {
            comboBox.setCellFactory(listView -> new IconListCell());
            comboBox.setButtonCell(new IconListCell());
        }

        if(pending != null)
        {
            if(editable)
            {
                comboBox.getEditor().setText(pending);
                Platform.runLater(() -> comboBox.getEditor().end());
            }
            else
            {
                String lower = pending.toLowerCase(Locale.ROOT);

                for(String choice: comboBox.getItems())
                {
                    if(choice.toLowerCase(Locale.ROOT).startsWith(lower))
                    {
                        comboBox.setValue(choice);
                        break;
                    }
                }
            }
        }

        //Commit when a choice is made: the popup is hidden after a click or Enter, while arrow key navigation within
        //the open popup does not commit.  Enter in the (editable) text editor commits with the popup closed.
        comboBox.setOnAction(event -> {
            if(!comboBox.isShowing())
            {
                finishChoiceEdit(comboBox);
            }
        });
        comboBox.setOnHidden(event -> finishChoiceEdit(comboBox));

        comboBox.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if(event.getCode() == KeyCode.ESCAPE)
            {
                mEscapePressed = true;
                cancelEdit();
                mEditor.restoreTableFocus();
                event.consume();
            }
            else if(event.getCode() == KeyCode.TAB)
            {
                cancelEdit();
                mEditor.moveFocus(0, event.isShiftDown() ? -1 : 1);
                event.consume();
            }
        });

        if(editable)
        {
            comboBox.getEditor().focusedProperty().addListener((observable, wasFocused, focused) -> {
                if(!focused && !comboBox.isShowing() && isEditing() && mEditorControl == comboBox)
                {
                    cancelEdit();
                }
            });
        }

        Platform.runLater(() -> {
            if(isEditing() && mEditorControl == comboBox && comboBox.getScene() != null)
            {
                comboBox.show();
            }
        });

        return comboBox;
    }

    /**
     * Ends the choice edit (committing the chosen value) after the combo box finishes processing the current event.
     */
    private void finishChoiceEdit(ComboBox<String> comboBox)
    {
        Platform.runLater(() -> {
            if(isEditing() && mEditorControl == comboBox)
            {
                cancelEdit();
                mEditor.restoreTableFocus();
            }
        });
    }

    /**
     * Colour picker editor
     */
    private ColorPicker createColourEditor(Alias alias)
    {
        ColorPicker colorPicker = new ColorPicker(alias.getColor() != 0 ? ColorUtil.fromInteger(alias.getColor()) :
            Color.WHITE);
        colorPicker.setMaxWidth(Double.MAX_VALUE);
        colorPicker.setOnAction(event -> {
            String hex = toHex(colorPicker.getValue());

            if(isEditing() && mEditorControl == colorPicker)
            {
                cancelEdit();
                mEditor.restoreTableFocus();
            }
            else if(!Objects.equals(hex, mOriginalText))
            {
                //Value chosen from the custom colour dialog after editing ended
                mEditor.commitCell(alias, AliasSheetColumn.COLOUR, hex);
            }
        });
        colorPicker.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if(event.getCode() == KeyCode.ESCAPE)
            {
                mEscapePressed = true;
                cancelEdit();
                mEditor.restoreTableFocus();
                event.consume();
            }
        });

        Platform.runLater(() -> {
            if(isEditing() && mEditorControl == colorPicker && colorPicker.getScene() != null)
            {
                colorPicker.show();
            }
        });

        return colorPicker;
    }

    private static String toHex(Color color)
    {
        if(color == null)
        {
            return "";
        }

        return AliasSheetValues.formatColour(ColorUtil.toInteger(Color.color(color.getRed(), color.getGreen(),
            color.getBlue(), 1.0)));
    }

    /**
     * Keyboard handling for the text field editor: Enter commits and moves down, Tab commits and moves right (Shift+Tab
     * left), Escape cancels.
     */
    private void handleEditorKey(KeyEvent event)
    {
        switch(event.getCode())
        {
            case ENTER:
                cancelEdit();
                mEditor.moveFocus(event.isShiftDown() ? -1 : 1, 0);
                event.consume();
                break;
            case TAB:
                cancelEdit();
                mEditor.moveFocus(0, event.isShiftDown() ? -1 : 1);
                event.consume();
                break;
            case ESCAPE:
                mEscapePressed = true;
                cancelEdit();
                mEditor.restoreTableFocus();
                event.consume();
                break;
            default:
                break;
        }
    }

    /**
     * Current text of the editor control
     */
    private String getEditorText()
    {
        if(mEditorControl instanceof TextField textField)
        {
            return textField.getText();
        }
        else if(mEditorControl instanceof ComboBox<?> comboBox)
        {
            if(comboBox.isEditable())
            {
                return comboBox.getEditor().getText();
            }

            Object value = comboBox.getValue();
            return value != null ? value.toString() : null;
        }
        else if(mEditorControl instanceof ColorPicker colorPicker)
        {
            return toHex(colorPicker.getValue());
        }

        return null;
    }

    /**
     * Ends editing.  Unless editing was cancelled with Escape, the edited value is committed (as an undoable edit)
     * when it differs from the original value.
     */
    @Override
    public void cancelEdit()
    {
        if(!isEditing())
        {
            super.cancelEdit();
            return;
        }

        String text = mEscapePressed || mEditorControl == null ? null : getEditorText();
        Alias alias = mEditingAlias;
        String original = mOriginalText;

        super.cancelEdit();
        mEditorControl = null;
        mEditingAlias = null;
        mEscapePressed = false;
        render(getItem(), isEmpty());

        if(text != null && alias != null)
        {
            if(mColumn == AliasSheetColumn.CONSOLE_GROUP && AliasSheetValues.isNotOnConsole(text))
            {
                text = "";
            }

            if(!Objects.equals(text, original) || getModel().getInvalidValue(alias, mColumn) != null)
            {
                mEditor.commitCell(alias, mColumn, text);
            }
        }
    }

    /**
     * Icon choice list cell with the icon image.
     */
    private class IconListCell extends ListCell<String>
    {
        @Override
        protected void updateItem(String item, boolean empty)
        {
            super.updateItem(item, empty);

            if(empty || item == null)
            {
                setText(null);
                setGraphic(null);
            }
            else
            {
                setText(item.isEmpty() ? "(none)" : item);
                Image image = mEditor.getIconImage(item);

                if(image != null)
                {
                    ImageView imageView = new ImageView(image);
                    imageView.setPreserveRatio(true);
                    imageView.setFitHeight(16);
                    setGraphic(imageView);
                }
                else
                {
                    setGraphic(null);
                }
            }
        }
    }
}
