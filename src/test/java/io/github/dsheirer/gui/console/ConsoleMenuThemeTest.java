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

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Font;
import java.util.Map;
import javax.swing.UIDefaults;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the console menu theme derived from the Swing (FlatLaf) look and feel defaults.
 */
public class ConsoleMenuThemeTest
{
    @Test
    void lightThemeColoursFollowFlatLaf()
    {
        UIDefaults defaults = new FlatLightLaf().getDefaults();
        ConsoleMenuTheme theme = ConsoleMenuTheme.from(defaults::get, 1.0);
        assertEquals(0xFFFFFF, theme.barBackground(), "Menu bar background");
        assertEquals(0x000000, theme.itemForeground(), "Item foreground");
        assertEquals(0x2675BF, theme.selectionBackground(), "Selection background");
        assertEquals(0xFFFFFF, theme.selectionForeground(), "Selection foreground");
        assertEquals(0x4D4D4D, theme.acceleratorForeground(), "Accelerator foreground");
        assertEquals(0x4E9DE7, theme.checkmark(), "Check mark (accent)");
        assertEquals(4, theme.cornerRadius(), "Popup corner radius");
        assertEquals(defaults.getFont("MenuItem.font").getSize2D(), theme.fontSize(), 0.01, "Font size");
    }

    @Test
    void darkThemeColoursFollowFlatLaf()
    {
        UIDefaults defaults = new FlatDarkLaf().getDefaults();
        ConsoleMenuTheme theme = ConsoleMenuTheme.from(defaults::get, 1.0);
        assertEquals(0x303234, theme.barBackground(), "Menu bar background");
        assertEquals(0x303234, theme.popupBackground(), "Popup background");
        assertEquals(0xDDDDDD, theme.itemForeground(), "Item foreground");
        assertEquals(0x4B6EAF, theme.selectionBackground(), "Selection background");
    }

    @Test
    void missingValuesUseDefaultsAndScaleMetrics()
    {
        ConsoleMenuTheme theme = ConsoleMenuTheme.from(Map.<String,Object>of()::get, 1.5);
        assertEquals(0xFFFFFF, theme.popupBackground(), "Fallback popup background");
        assertEquals(19.5, theme.fontSize(), 0.01, "Scaled default font size");
        String css = theme.toStylesheet(family -> null);
        assertFalse(css.contains("-fx-font-family"), "Expected no font family when none is defined");
        assertTrue(css.contains("-fx-background-radius: 6.0"), "Expected a scaled corner radius: " + css);
    }

    @Test
    void fontSizeIsNotScaledTwiceAndRowsMatchSwing()
    {
        //FlatLaf fonts are already scaled by the user interface scale (flatlaf.uiScale)
        Font font = new Font("Dialog", Font.PLAIN, 20);
        ConsoleMenuTheme theme = ConsoleMenuTheme.from(Map.<String,Object>of("MenuItem.font", font)::get, 1.5);
        assertEquals(20.0, theme.fontSize(), 0.01, "Expected the (already scaled) Swing font size");
        assertEquals(Math.round(theme.fontHeight() + 9.0), theme.getItemHeight(), 0.01,
            "Expected the font height plus the scaled 3 px item margins");

        String css = theme.toStylesheet(family -> "Mapped Family");
        String height = String.format(java.util.Locale.ROOT, "%.1f", theme.getItemHeight());
        assertTrue(css.contains("-fx-font-family: \"Mapped Family\""), "Expected the mapped font family: " + css);
        assertTrue(css.contains("-fx-pref-height: " + height), "Expected the Swing row height: " + css);
    }

    @Test
    void stylesheetIsScopedToTheConsoleMenuBar()
    {
        ConsoleMenuTheme theme = ConsoleMenuTheme.from(new FlatLightLaf().getDefaults()::get, 1.0);
        String css = theme.toStylesheet(family -> family);

        for(String line: css.split("\n"))
        {
            for(String selector: line.substring(0, line.indexOf('{')).split(","))
            {
                assertTrue(selector.trim().startsWith(".console-menu-bar"), "Expected the selector to be scoped: " +
                    line);
            }
        }

        assertTrue(css.contains(".console-menu-bar .context-menu .menu-item .label > .text { -fx-fill: #000000; }"),
            "Expected the label text node colour (dark stylesheet '.text' rule): " + css);

        assertTrue(css.contains(".menu-item:focused { -fx-background-color: #2675BF; }"), "Selection: " + css);
        assertTrue(css.contains(".menu-icon { -fx-fill: #000000; }"), "Icon colour: " + css);
        assertTrue(css.contains("-fx-font-family: \"" + theme.fontFamily() + "\""), "Font family: " + css);
        assertTrue(theme.toStylesheetUri(family -> family).startsWith("data:text/css;base64,"), "Data URI");
    }
}
