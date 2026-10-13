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

import com.formdev.flatlaf.util.UIScale;
import java.awt.Color;
import java.awt.Font;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.swing.UIManager;

/**
 * Console window menu bar theme: the colours, font and metrics of the active Swing (FlatLaf) look and feel, so that the
 * console window's JavaFX menus look like the main window's Swing menus in light and dark themes.
 *
 * The values are read from the Swing UIManager defaults (read them on the Swing event dispatch thread, after the
 * theme's look and feel is installed) and converted to a stylesheet that is added to the console window's scene.  All
 * rules are scoped to the console menu bar ('.console-menu-bar') and its menu popups, which inherit the menu bar's
 * style classes.  Menu item icons are font glyph nodes with the 'menu-icon' style class, coloured like the item text.
 *
 * @param barBackground menu bar background (MenuBar.background)
 * @param barForeground menu bar text (MenuBar.foreground)
 * @param barBorder menu bar bottom border (MenuBar.borderColor)
 * @param barHoverBackground hovered or open top level menu background (MenuBar.selectionBackground or
 * MenuBar.hoverBackground)
 * @param barSelectionForeground hovered or open top level menu text (MenuBar.selectionForeground or MenuBar.foreground)
 * @param popupBackground menu popup background (PopupMenu.background)
 * @param popupBorder menu popup border (PopupMenu.borderColor)
 * @param itemForeground menu item text and icons (MenuItem.foreground)
 * @param selectionBackground armed (hovered or keyboard selected) item background (MenuItem.selectionBackground)
 * @param selectionForeground armed item text and icons (MenuItem.selectionForeground)
 * @param acceleratorForeground accelerator text (MenuItem.acceleratorForeground)
 * @param acceleratorSelectionForeground armed item accelerator text (MenuItem.acceleratorSelectionForeground)
 * @param disabledForeground disabled item text and icons (MenuItem.disabledForeground)
 * @param separator popup separator line (Separator.foreground)
 * @param checkmark check and radio marks (CheckBoxMenuItem.icon.checkmarkColor or Component.accentColor)
 * @param arrow sub-menu arrow (Menu.icon.arrowColor)
 * @param shadowOpacity popup drop shadow opacity (Popup.dropShadowOpacity), 0 for none
 * @param cornerRadius popup corner radius in pixels (PopupMenu.borderCornerRadius)
 * @param fontFamily menu font family (MenuItem.font), or null for the default font
 * @param fontSize menu font size in pixels, scaled by the user interface scale
 * @param scale user interface scale factor applied to the metrics (padding, radius)
 */
public record ConsoleMenuTheme(int barBackground, int barForeground, int barBorder, int barHoverBackground,
                               int barSelectionForeground, int popupBackground, int popupBorder, int itemForeground,
                               int selectionBackground, int selectionForeground, int acceleratorForeground,
                               int acceleratorSelectionForeground, int disabledForeground, int separator,
                               int checkmark, int arrow, double shadowOpacity, int cornerRadius, String fontFamily,
                               double fontSize, double scale)
{
    /**
     * Glyph size of the menu item icons, as used by the Swing menus (ThemedFontIcon size 12).
     */
    public static final int ICON_SIZE = 12;
    /**
     * Style class of the menu item icon nodes.
     */
    public static final String ICON_STYLE_CLASS = "menu-icon";

    /**
     * Reads the menu theme from the Swing UIManager defaults of the installed look and feel.  Invoke on the Swing
     * event dispatch thread.
     * @return theme, with FlatLaf light defaults for any values that the look and feel does not define.
     */
    public static ConsoleMenuTheme fromUIManager()
    {
        double scale = 1.0;

        try
        {
            scale = UIScale.getUserScaleFactor();
        }
        catch(Throwable t)
        {
            //Not a FlatLaf look and feel
        }

        return from(UIManager::get, scale);
    }

    /**
     * Creates the menu theme from look and feel default values.
     * @param defaults provides the value for a UIManager key, or null when the key is not defined
     * @param scale user interface scale factor
     * @return theme, with FlatLaf light defaults for any values that are not defined.
     */
    public static ConsoleMenuTheme from(Function<String,Object> defaults, double scale)
    {
        int barBackground = color(defaults, 0xFFFFFF, "MenuBar.background");
        int barForeground = color(defaults, 0x000000, "MenuBar.foreground");
        int itemForeground = color(defaults, 0x000000, "MenuItem.foreground", "PopupMenu.foreground");
        int selectionBackground = color(defaults, 0x2675BF, "MenuItem.selectionBackground");
        int selectionForeground = color(defaults, 0xFFFFFF, "MenuItem.selectionForeground");
        Font font = defaults.apply("MenuItem.font") instanceof Font menuFont ? menuFont :
            (defaults.apply("defaultFont") instanceof Font defaultFont ? defaultFont : null);
        double effectiveScale = scale > 0 ? scale : 1.0;

        return new ConsoleMenuTheme(
            barBackground,
            barForeground,
            color(defaults, 0xCECECE, "MenuBar.borderColor", "Separator.foreground"),
            color(defaults, 0xE6E6E6, "MenuBar.selectionBackground", "MenuBar.hoverBackground"),
            color(defaults, barForeground, "MenuBar.selectionForeground", "MenuBar.foreground"),
            color(defaults, 0xFFFFFF, "PopupMenu.background", "MenuItem.background"),
            color(defaults, 0xAEAEAE, "PopupMenu.borderColor"),
            itemForeground,
            selectionBackground,
            selectionForeground,
            color(defaults, 0x4D4D4D, "MenuItem.acceleratorForeground"),
            color(defaults, selectionForeground, "MenuItem.acceleratorSelectionForeground"),
            color(defaults, 0x808080, "MenuItem.disabledForeground", "Label.disabledForeground"),
            color(defaults, 0xCECECE, "Separator.foreground"),
            color(defaults, selectionBackground, "CheckBoxMenuItem.icon.checkmarkColor", "Component.accentColor"),
            color(defaults, itemForeground, "Menu.icon.arrowColor"),
            number(defaults, 0.15, "Popup.dropShadowOpacity"),
            (int)number(defaults, 4, "PopupMenu.borderCornerRadius", "Popup.borderCornerRadius"),
            font != null ? font.getFamily() : null,
            Math.round((font != null ? font.getSize2D() : 13.0) * effectiveScale * 10.0) / 10.0,
            effectiveScale);
    }

    /**
     * First defined colour for the keys, as a 24-bit RGB value, or the fallback.
     */
    private static int color(Function<String,Object> defaults, int fallback, String... keys)
    {
        for(String key: keys)
        {
            if(defaults.apply(key) instanceof Color color)
            {
                return color.getRGB() & 0xFFFFFF;
            }
        }

        return fallback;
    }

    /**
     * First defined number for the keys, or the fallback.
     */
    private static double number(Function<String,Object> defaults, double fallback, String... keys)
    {
        for(String key: keys)
        {
            if(defaults.apply(key) instanceof Number number)
            {
                return number.doubleValue();
            }
        }

        return fallback;
    }

    /**
     * CSS size for an unscaled (FlatLaf) metric, scaled by the user interface scale.  The metrics follow FlatLaf:
     * popup insets (PopupMenu.borderInsets 4), menu bar item margins (MenuBar.itemMargins 3/8), item margins
     * (MenuItem.margin 3/6 plus the item border) and the text to accelerator gap (MenuItem.textAcceleratorGap 24).
     */
    private String px(double value)
    {
        return String.format(Locale.ROOT, "%.1f", value * scale);
    }

    /**
     * CSS colour
     */
    private static String hex(int rgb)
    {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    /**
     * Creates the stylesheet for the console menu bar and its menu popups.
     * @param fontInstalled tests if a font family is available to JavaFX
     * @return stylesheet
     */
    public String toStylesheet(Predicate<String> fontInstalled)
    {
        String font = "-fx-font-size: " + String.format(Locale.ROOT, "%.1fpx", fontSize) + ";" +
            (fontFamily != null && fontInstalled != null && fontInstalled.test(fontFamily) ?
                " -fx-font-family: \"" + fontFamily + "\";" : "");
        String bar = ".console-menu-bar";
        String topMenu = bar + " > .container > .menu-button";
        String popup = bar + " .context-menu";
        String item = popup + " .menu-item";
        StringBuilder css = new StringBuilder();

        //Menu bar and top level menus
        css.append(bar).append(" { -fx-background-color: ").append(hex(barBackground))
            .append("; -fx-border-color: transparent transparent ").append(hex(barBorder))
            .append(" transparent; -fx-border-width: 0 0 1 0; -fx-padding: 0 ").append(px(2)).append(" 0 ")
            .append(px(2)).append("; }\n");
        css.append(topMenu).append(" { -fx-background-color: transparent; -fx-background-radius: 0; -fx-padding: ")
            .append(px(3)).append(" ").append(px(8)).append(" ").append(px(3)).append(" ").append(px(8))
            .append("; }\n");
        css.append(topMenu).append(" > .label { -fx-padding: 0; ").append(font).append(" }\n");
        text(css, barForeground, topMenu + " > .label");
        css.append(topMenu).append(":hover, ").append(topMenu).append(":focused, ").append(topMenu)
            .append(":showing { -fx-background-color: ").append(hex(barHoverBackground)).append("; }\n");
        text(css, barSelectionForeground, topMenu + ":hover > .label", topMenu + ":focused > .label",
            topMenu + ":showing > .label");

        //Popups: border, corner radius and drop shadow
        css.append(popup).append(" { -fx-background-color: ").append(hex(popupBackground))
            .append("; -fx-background-insets: 0; -fx-background-radius: ").append(px(cornerRadius))
            .append("; -fx-border-color: ").append(hex(popupBorder))
            .append("; -fx-border-width: 1; -fx-border-radius: ").append(px(cornerRadius)).append("; -fx-padding: ").append(px(4)).append(" 0 ").append(px(4))
            .append(" 0; -fx-effect: ");
        css.append(shadowOpacity > 0 ? String.format(Locale.ROOT,
            "dropshadow(gaussian, rgba(0, 0, 0, %.2f), %s, 0, 0, %s)", Math.min(1.0, shadowOpacity * 1.6), px(10),
            px(2)) : "null");
        css.append("; }\n");

        //Items: text, accelerator, icon, check and arrow colours, and the armed (focused) item.  Label text colours
        //are also applied to the label's text node, which the dark JavaFX stylesheet styles directly ('.text').
        css.append(item).append(" { -fx-background-color: transparent; -fx-background-insets: 0; -fx-padding: ")
            .append(px(4)).append(" ").append(px(12)).append(" ").append(px(4)).append(" ").append(px(8))
            .append("; }\n");
        css.append(item).append(" .label { ").append(font).append(" }\n");
        text(css, itemForeground, item + " .label");
        css.append(item).append(" .accelerator-text { -fx-padding: 0 0 0 ").append(px(24)).append("; }\n");
        text(css, acceleratorForeground, item + " .accelerator-text");
        css.append(item).append(" .").append(ICON_STYLE_CLASS).append(" { -fx-fill: ").append(hex(itemForeground))
            .append("; }\n");

        //Check column: a fixed width icon column (FlatLaf MenuItem.minimumIconSize 16) so that check marks have the
        //same gap to the text as the icons of the View menu
        css.append(item).append(" > .left-container { -fx-min-width: ").append(px(22)).append("; -fx-pref-width: ")
            .append(px(22)).append("; -fx-padding: 0 ").append(px(6)).append(" 0 0; -fx-alignment: center-left; }\n");
        css.append(item).append(" > .graphic-container { -fx-padding: 0 ").append(px(6)).append(" 0 0; }\n");
        css.append(item).append(":focused { -fx-background-color: ").append(hex(selectionBackground)).append("; }\n");
        text(css, selectionForeground, item + ":focused .label");
        text(css, acceleratorSelectionForeground, item + ":focused .accelerator-text");
        css.append(item).append(":focused .").append(ICON_STYLE_CLASS).append(" { -fx-fill: ")
            .append(hex(selectionForeground)).append("; }\n");
        text(css, disabledForeground, item + ":disabled .label", item + ":disabled .accelerator-text");
        css.append(item).append(":disabled .").append(ICON_STYLE_CLASS).append(" { -fx-fill: ")
            .append(hex(disabledForeground)).append("; }\n");
        css.append(item).append(":disabled { -fx-opacity: 1; }\n");
        css.append(popup).append(" .check-menu-item:checked > .left-container > .check, ").append(popup)
            .append(" .radio-menu-item:checked > .left-container > .radio { -fx-background-color: ")
            .append(hex(checkmark)).append("; }\n");
        css.append(popup).append(" .menu > .right-container > .arrow { -fx-background-color: ").append(hex(arrow))
            .append("; }\n");
        css.append(popup).append(" .check-menu-item:focused:checked > .left-container > .check, ").append(popup)
            .append(" .radio-menu-item:focused:checked > .left-container > .radio, ").append(popup)
            .append(" .menu:focused > .right-container > .arrow { -fx-background-color: ")
            .append(hex(selectionForeground)).append("; }\n");

        //Separators: a full width 1 px line with 4 px above and below (FlatLaf PopupMenuSeparator.height 9)
        css.append(popup).append(" .separator { -fx-padding: ").append(px(4)).append(" 0 ").append(px(4))
            .append(" 0; }\n");
        css.append(popup).append(" .separator .line { -fx-border-color: ").append(hex(separator))
            .append(" transparent transparent transparent; -fx-border-width: 1 0 0 0; -fx-border-insets: 0; ")
            .append("-fx-background-color: transparent; -fx-padding: 0; }\n");

        return css.toString();
    }

    /**
     * Appends rules that set the text colour of labels (and of the labels' text nodes).
     * @param css to append to
     * @param color RGB text colour
     * @param selectors of the labels
     */
    private static void text(StringBuilder css, int color, String... selectors)
    {
        css.append(String.join(", ", selectors)).append(" { -fx-text-fill: ").append(hex(color)).append("; }\n");
        css.append(String.join(", ", Arrays.stream(selectors).map(selector -> selector + " > .text")
            .toList())).append(" { -fx-fill: ").append(hex(color)).append("; }\n");
    }

    /**
     * Creates the stylesheet as a data URI that can be added to a scene's stylesheets.
     * @param fontInstalled tests if a font family is available to JavaFX
     * @return stylesheet URI
     */
    public String toStylesheetUri(Predicate<String> fontInstalled)
    {
        return "data:text/css;base64," + Base64.getEncoder().encodeToString(toStylesheet(fontInstalled)
            .getBytes(StandardCharsets.UTF_8));
    }
}
