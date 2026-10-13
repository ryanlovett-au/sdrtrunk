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
package io.github.dsheirer.gui.console;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.scene.Node;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Font selection for the console window.  Uses a condensed system font for card and group names and a clear
 * monospace system font for identifiers when they are installed, otherwise the default fonts.  No fonts are bundled.
 * The console menus use the JavaFX font family that matches the Swing menu font (see getMatchingFamily()).
 */
public final class ConsoleFonts
{
    private final static Logger mLog = LoggerFactory.getLogger(ConsoleFonts.class);

    /**
     * JavaFX family of the platform's system user interface font (San Francisco on macOS).
     */
    public static final String SYSTEM_FAMILY = "System";

    /**
     * macOS system font files for font families that JavaFX resolves to a light face (e.g. 'Helvetica Neue', the
     * FlatLaf font on macOS, resolves to Helvetica Neue Thin).  The first font in each collection is the regular face.
     */
    private static final Map<String,String> MACOS_REGULAR_FONT_FILES = Map.of(
        "Helvetica Neue", "/System/Library/Fonts/HelveticaNeue.ttc",
        "Helvetica", "/System/Library/Fonts/Helvetica.ttc");

    /**
     * Face styles that are not a regular weight face.
     */
    private static final List<String> NON_REGULAR_STYLES = List.of("thin", "light", "hairline", "black", "heavy",
        "bold", "italic", "oblique", "condensed", "compressed");

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

    private static final Map<String,String> sMatchingFamilies = new HashMap<>();
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
     * JavaFX font family that renders the same font face as a Swing (AWT) font family, so that JavaFX text matches
     * Swing text (e.g. the console menus and the main window's FlatLaf menus).  Invoke on the JavaFX application
     * thread.
     *
     * macOS system user interface font names ('.AppleSystemUIFont', '.SF NS Text', 'System Font') map to the JavaFX
     * 'System' family, which is the macOS system font.  Installed families are used as is when JavaFX resolves the
     * family to a regular weight face.  On macOS, JavaFX resolves some families to a light face (e.g. 'Helvetica Neue',
     * FlatLaf's macOS font, resolves to Helvetica Neue Thin): the regular face is then loaded from the macOS system font
     * file, after which the family resolves to the regular face (in all JavaFX windows), or the 'System' family is used
     * when the regular face is not available.
     *
     * @param awtFamily Swing font family
     * @return JavaFX font family, or null to use the default JavaFX font (family not installed).
     */
    public static synchronized String getMatchingFamily(String awtFamily)
    {
        if(awtFamily == null || awtFamily.isBlank())
        {
            return null;
        }

        return sMatchingFamilies.computeIfAbsent(awtFamily, ConsoleFonts::resolveMatchingFamily);
    }

    /**
     * Resolves the JavaFX font family for a Swing font family (see getMatchingFamily()).
     */
    private static String resolveMatchingFamily(String awtFamily)
    {
        if(awtFamily.startsWith(".") || awtFamily.equalsIgnoreCase("System Font"))
        {
            return SYSTEM_FAMILY;
        }

        if(!Font.getFamilies().contains(awtFamily))
        {
            return null;
        }

        if(isRegularFace(awtFamily))
        {
            return awtFamily;
        }

        String fontFile = MACOS_REGULAR_FONT_FILES.get(awtFamily);

        if(fontFile != null && new File(fontFile).isFile())
        {
            try
            {
                Font.loadFont(new File(fontFile).toURI().toString(), 13);
            }
            catch(Exception e)
            {
                mLog.warn("Unable to load the regular face of font family [" + awtFamily + "] from " + fontFile, e);
            }

            if(isRegularFace(awtFamily))
            {
                return awtFamily;
            }
        }

        mLog.info("JavaFX resolves font family [" + awtFamily + "] to " + Font.font(awtFamily, 13).getName() +
            " - using the system font for the console menus");
        return SYSTEM_FAMILY;
    }

    /**
     * Indicates if JavaFX resolves the font family (at normal weight) to a regular weight, upright face.
     */
    private static boolean isRegularFace(String family)
    {
        Font font = Font.font(family, FontWeight.NORMAL, 13);
        String style = font.getStyle() != null ? font.getStyle().toLowerCase(Locale.ROOT) : "";
        return font.getFamily().equalsIgnoreCase(family) && NON_REGULAR_STYLES.stream().noneMatch(style::contains);
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
