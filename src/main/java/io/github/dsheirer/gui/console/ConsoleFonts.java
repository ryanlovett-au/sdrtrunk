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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.text.Font;

/**
 * Font selection for the console window.  Uses a condensed system font for card and group names and a clear
 * monospace system font for identifiers when they are installed, otherwise the default fonts.  No fonts are bundled.
 */
public final class ConsoleFonts
{
    /**
     * Condensed font families in order of preference (cross-platform).
     */
    private static final List<String> CONDENSED_FAMILIES = List.of("Barlow Condensed", "Roboto Condensed",
        "Avenir Next Condensed", "DIN Condensed", "Arial Narrow", "Liberation Sans Narrow", "Ubuntu Condensed",
        "DejaVu Sans Condensed", "Noto Sans Display Condensed");

    /**
     * Monospace font families in order of preference (cross-platform).  The JavaFX generic 'monospace' family is a
     * thin font on some platforms, which reduces the legibility of small identifiers.
     */
    private static final List<String> MONOSPACE_FAMILIES = List.of("IBM Plex Mono", "SF Mono", "Menlo", "Consolas",
        "Cascadia Mono", "DejaVu Sans Mono", "Liberation Mono", "Ubuntu Mono", "Noto Sans Mono");

    private static String sCondensedFamily;
    private static String sMonospaceFamily;
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
            sCondensedFamily = getFirstInstalled(CONDENSED_FAMILIES, installed);
            sMonospaceFamily = getFirstInstalled(MONOSPACE_FAMILIES, installed);
        }

        return sCondensedFamily;
    }

    /**
     * Installed monospace font family, or null when none of the preferred families is installed.  Invoke on the
     * JavaFX application thread.
     */
    public static synchronized String getMonospaceFamily()
    {
        getCondensedFamily();
        return sMonospaceFamily;
    }

    /**
     * First font family from the preferred families that is installed, or null.
     */
    private static String getFirstInstalled(List<String> preferred, List<String> installed)
    {
        for(String family: preferred)
        {
            if(installed.contains(family))
            {
                return family;
            }
        }

        return null;
    }

    /**
     * Stylesheet (data URI) that applies the installed monospace font family to nodes with the 'mono' style class
     * in the console window and its dialogs.  Add after the console stylesheet.
     * @return stylesheet URI, or null when no preferred monospace family is installed.
     */
    public static String getMonospaceStylesheet()
    {
        String family = getMonospaceFamily();

        if(family == null)
        {
            return null;
        }

        String css = ".console-root .mono, .alias-picker .mono { -fx-font-family: \"" + family + "\"; }";
        return "data:text/css;base64," + Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8));
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
