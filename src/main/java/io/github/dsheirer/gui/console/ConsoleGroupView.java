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
package io.github.dsheirer.gui.console;

import io.github.dsheirer.console.ConsoleGroup;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Console group panel: navy header with the group name and active call count, and the group's cards in a vertical
 * list.  In edit mode the header has move, rename and delete buttons, the panel has an add talkgroup button, and
 * cards can be dropped onto the panel (appended to the group).  Invoke all methods on the JavaFX thread.
 */
public class ConsoleGroupView extends VBox
{
    private final ConsoleWindow mConsoleWindow;
    private final ConsoleGroup mGroup;
    private final Label mActiveLabel = new Label();
    private final VBox mCardsBox = new VBox();
    private final Label mEmptyLabel = new Label("No talkgroups in this group");
    private final Button mAddCardButton = new Button("+ Add talkgroup");
    private final HBox mEditButtons;
    private int mActiveCount = -1;

    /**
     * Constructs an instance
     * @param consoleWindow that owns this view
     * @param group snapshot
     * @param groupCount total number of groups (to enable the move buttons)
     */
    public ConsoleGroupView(ConsoleWindow consoleWindow, ConsoleGroup group, int groupCount)
    {
        mConsoleWindow = consoleWindow;
        mGroup = group;
        getStyleClass().add("group");

        Label nameLabel = ConsoleFonts.condensed(new Label(group.getName().toUpperCase()));
        nameLabel.getStyleClass().add("group-name");
        nameLabel.setMinWidth(0);
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);
        mActiveLabel.getStyleClass().add("group-active");
        mActiveLabel.setMinWidth(Region.USE_PREF_SIZE);

        Button moveLeft = editButton("◀", "Move group left");
        moveLeft.setDisable(group.getDisplayOrder() <= 0);
        moveLeft.setOnAction(e -> mConsoleWindow.getLayoutManager().moveGroup(mGroup.getId(),
            mGroup.getDisplayOrder() - 1));
        Button moveRight = editButton("▶", "Move group right");
        moveRight.setDisable(group.getDisplayOrder() >= groupCount - 1);
        moveRight.setOnAction(e -> mConsoleWindow.getLayoutManager().moveGroup(mGroup.getId(),
            mGroup.getDisplayOrder() + 1));
        Button rename = editButton("\u270E", "Rename group");
        rename.setOnAction(e -> mConsoleWindow.renameGroup(mGroup));
        Button delete = editButton("\u2715", "Delete group");
        delete.setOnAction(e -> mConsoleWindow.deleteGroup(mGroup));
        mEditButtons = new HBox(4, moveLeft, moveRight, rename, delete);
        mEditButtons.setAlignment(Pos.CENTER_RIGHT);
        mEditButtons.setMinWidth(Region.USE_PREF_SIZE);

        HBox header = new HBox(8, nameLabel, mActiveLabel, mEditButtons);
        header.getStyleClass().add("group-header");

        mCardsBox.getStyleClass().add("group-cards");
        mEmptyLabel.getStyleClass().add("group-empty");
        mAddCardButton.getStyleClass().add("add-card-button");
        mAddCardButton.setMaxWidth(Double.MAX_VALUE);
        mAddCardButton.setOnAction(e -> mConsoleWindow.addCard(mGroup.getId()));

        getChildren().addAll(header, mCardsBox);
        setupDropTarget();
    }

    /**
     * Creates a header edit button.
     */
    private static Button editButton(String text, String tooltip)
    {
        Button button = new Button(text);
        button.getStyleClass().add("group-edit-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setAccessibleText(tooltip);
        return button;
    }

    /**
     * Group identifier
     */
    public String getGroupId()
    {
        return mGroup.getId();
    }

    /**
     * Sets the card views for this group, in display order.
     */
    public void setCardViews(List<ConsoleCardView> cardViews, boolean editMode)
    {
        mCardsBox.getChildren().setAll(cardViews);

        if(cardViews.isEmpty() && !editMode)
        {
            mCardsBox.getChildren().add(mEmptyLabel);
        }

        if(editMode)
        {
            mCardsBox.getChildren().add(mAddCardButton);
        }

        mEditButtons.setVisible(editMode);
        mEditButtons.setManaged(editMode);
    }

    /**
     * Updates the active call count shown in the header.
     */
    public void setActiveCount(int activeCount)
    {
        if(activeCount != mActiveCount)
        {
            mActiveCount = activeCount;
            mActiveLabel.setText(activeCount > 0 ? activeCount + " ACTIVE" : "");
        }
    }

    /**
     * Cards dropped onto the group panel (outside of another card) are appended to the group.
     */
    private void setupDropTarget()
    {
        mCardsBox.setOnDragOver(event -> {
            if(mConsoleWindow.isEditMode() && ConsoleCardView.getDraggedCardId(event.getDragboard()) != null)
            {
                event.acceptTransferModes(TransferMode.MOVE);

                if(!mCardsBox.getStyleClass().contains("drop-target"))
                {
                    mCardsBox.getStyleClass().add("drop-target");
                }

                event.consume();
            }
        });

        mCardsBox.setOnDragExited(event -> mCardsBox.getStyleClass().remove("drop-target"));

        mCardsBox.setOnDragDropped(event -> {
            mCardsBox.getStyleClass().remove("drop-target");
            String cardId = ConsoleCardView.getDraggedCardId(event.getDragboard());

            if(cardId != null)
            {
                mConsoleWindow.moveCard(cardId, mGroup.getId(), Integer.MAX_VALUE);
                event.setDropCompleted(true);
            }
            else
            {
                event.setDropCompleted(false);
            }

            event.consume();
        });
    }
}
