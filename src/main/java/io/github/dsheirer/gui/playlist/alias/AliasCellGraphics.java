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

package io.github.dsheirer.gui.playlist.alias;

import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.icon.Icon;
import io.github.dsheirer.icon.IconModel;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import jiconfont.IconCode;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.javafx.IconNode;

/**
 * Graphics and text for displaying alias values in table cells, shared by the playlist editor's alias table
 * (AliasConfigurationEditor) and the alias sheet so that both show the colour, icon, listen (priority), record and
 * stream values in the same way.
 */
public final class AliasCellGraphics
{
    public static final int ICON_SIZE = 20;
    public static final IconCode RECORD_ICON = FontAwesome.SQUARE;
    public static final Color RECORD_COLOR = Color.RED;
    public static final IconCode STREAM_ICON = FontAwesome.VOLUME_UP;
    public static final Color STREAM_COLOR = Color.DARKBLUE;

    private AliasCellGraphics()
    {
        //Static utility class
    }

    /**
     * Creates the rounded colour swatch used in the Color column.
     */
    public static Rectangle createColorSwatch()
    {
        Rectangle rectangle = new Rectangle(20, 20);
        rectangle.setArcHeight(10);
        rectangle.setArcWidth(10);
        return rectangle;
    }

    /**
     * Creates the colour swatch filled with the alias colour value.
     * @param color alias colour value (see Alias.getColor())
     */
    public static Rectangle createColorSwatch(int color)
    {
        Rectangle rectangle = createColorSwatch();
        rectangle.setFill(ColorUtil.fromInteger(color));
        return rectangle;
    }

    /**
     * Creates the image for the alias icon: the named icon or the icon model's default icon.
     * @param iconModel for icon lookup
     * @param iconName of the alias, or null
     * @return image view or null if there is no icon image
     */
    public static Node createIconGraphic(IconModel iconModel, String iconName)
    {
        Icon icon = iconModel != null ? iconModel.getIcon(iconName) : null;

        if(icon != null && icon.getFxImage() != null)
        {
            return new ImageView(icon.getFxImage());
        }

        return null;
    }

    /**
     * Listen column text for the audio playback priority: Mute, Default or the priority value.
     */
    public static String getPriorityText(int priority)
    {
        if(priority == Priority.DO_NOT_MONITOR)
        {
            return "Mute";
        }
        else if(priority == Priority.DEFAULT_PRIORITY)
        {
            return "Default";
        }

        return String.valueOf(priority);
    }

    /**
     * Listen column graphic for the audio playback priority: a red muted speaker for do not monitor, otherwise a green
     * speaker.
     */
    public static IconNode createPriorityGraphic(int priority)
    {
        IconNode iconNode = new IconNode(priority == Priority.DO_NOT_MONITOR ? FontAwesome.VOLUME_OFF :
            FontAwesome.VOLUME_UP);
        iconNode.setIconSize(ICON_SIZE);
        iconNode.setFill(priority == Priority.DO_NOT_MONITOR ? Color.RED : Color.GREEN);
        return iconNode;
    }

    /**
     * Icon shown for a true boolean value, e.g. the record (red square) and stream (blue speaker) columns.
     */
    public static IconNode createBooleanIcon(IconCode iconCode, Color color)
    {
        IconNode iconNode = new IconNode(iconCode);
        iconNode.setIconSize(ICON_SIZE);
        iconNode.setFill(color);
        return iconNode;
    }
}
