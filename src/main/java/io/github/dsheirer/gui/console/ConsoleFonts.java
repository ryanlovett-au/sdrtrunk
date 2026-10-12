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

import java.util.List;
import javafx.scene.Node;
import javafx.scene.text.Font;

/**
 * Font selection for the console window.  Uses a condensed system font for card and group names when one is
 * installed, otherwise the default font.  No fonts are bundled.
 */
public final class ConsoleFonts
{
    /**
     * Condensed font families in order of preference (cross-platform).
     */
    private static final List<String> CONDENSED_FAMILIES = List.of("Barlow Condensed", "Roboto Condensed",
        "Avenir Next Condensed", "DIN Condensed", "Arial Narrow", "Liberation Sans Narrow", "Ubuntu Condensed",
        "DejaVu Sans Condensed", "Noto Sans Display Condensed");

    private static String sCondensedFamily;
    private static boolean sResolved;

    private ConsoleFonts()
    {
    }

    /**
     * Installed condensed font family, or null when none is installed.  Invoke on the JavaFX application thread.
     */
    public static synchronized String getCondensedFamily()
    {
        if(!sResolved)
        {
            sResolved = true;
            List<String> installed = Font.getFamilies();

            for(String family: CONDENSED_FAMILIES)
            {
                if(installed.contains(family))
                {
                    sCondensedFamily = family;
                    break;
                }
            }
        }

        return sCondensedFamily;
    }

    /**
     * Applies the condensed font family (when installed) to the node via an inline style.  The node's font size and
     * weight continue to come from the stylesheet.
     * @param node to style
     * @return the node, for chaining
     */
    public static <T extends Node> T condensed(T node)
    {
        String family = getCondensedFamily();

        if(family != null)
        {
            node.setStyle("-fx-font-family: \"" + family + "\";");
        }

        return node;
    }
}
