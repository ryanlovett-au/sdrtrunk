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

import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Text formatting and parsing of alias sheet cell values that are not plain text.  Parse methods return null when the
 * text is not valid.
 */
public class AliasSheetValues
{
    public static final String LISTEN_DEFAULT = "Default";
    public static final String LISTEN_DO_NOT_MONITOR = "Do not monitor";
    public static final String LISTEN_PRIORITY_PREFIX = "Priority ";
    public static final String RECORD_YES = "Yes";
    public static final String RECORD_NO = "No";
    public static final String NOT_ON_CONSOLE = "Not on console";
    public static final String STREAM_SEPARATOR = "; ";

    private AliasSheetValues()
    {
        //Static utility class
    }

    /**
     * Normalizes the value for loose matching: lower case with spaces, dashes and underscores removed.
     */
    static String normalize(String value)
    {
        if(value == null)
        {
            return "";
        }

        return value.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "")
            .replace("_", "");
    }

    /**
     * Protocols that can be assigned to a talkgroup, in display order.
     */
    public static List<Protocol> getTalkgroupProtocols()
    {
        return new ArrayList<>(Protocol.TALKGROUP_PROTOCOLS);
    }

    /**
     * Formats the protocol using the protocol display label (e.g. APCO-25)
     */
    public static String formatProtocol(Protocol protocol)
    {
        return protocol != null ? protocol.toString() : "";
    }

    /**
     * Parses a talkgroup protocol from the display label (APCO-25), the enumeration name (APCO25) or a common short
     * name (P25).  APCO-25 phase 2 values are mapped to APCO-25 since talkgroups use the APCO-25 protocol for both
     * phases.
     * @param text to parse
     * @return talkgroup protocol or null if the text is blank or not a talkgroup protocol.
     */
    public static Protocol parseProtocol(String text)
    {
        String normalized = normalize(text);

        if(normalized.isEmpty())
        {
            return null;
        }

        switch(normalized)
        {
            case "p25":
            case "p25p1":
            case "p25p2":
            case "p25phase1":
            case "p25phase2":
            case "apco25p2":
            case "apco25phase2":
            case "apco25phase1":
                return Protocol.APCO25;
            default:
                break;
        }

        for(Protocol protocol: Protocol.TALKGROUP_PROTOCOLS)
        {
            if(normalize(protocol.toString()).equals(normalized) || normalize(protocol.name()).equals(normalized))
            {
                return protocol;
            }
        }

        return null;
    }

    /**
     * Formats the audio playback priority
     * @param priority value, Priority.DO_NOT_MONITOR, or Priority.DEFAULT_PRIORITY
     */
    public static String formatListen(int priority)
    {
        if(priority == Priority.DO_NOT_MONITOR)
        {
            return LISTEN_DO_NOT_MONITOR;
        }
        else if(priority < Priority.MIN_PRIORITY || priority >= Priority.MAX_PRIORITY)
        {
            return LISTEN_DEFAULT;
        }

        return LISTEN_PRIORITY_PREFIX + priority;
    }

    /**
     * Parses an audio playback priority: Default (or blank), Do not monitor (or mute/off/no), or a priority value 1-99
     * with an optional 'Priority' or 'P' prefix.
     * @return priority value or null if the text is not valid.
     */
    public static Integer parseListen(String text)
    {
        if(text != null && text.trim().equals(String.valueOf(Priority.DO_NOT_MONITOR)))
        {
            return Priority.DO_NOT_MONITOR;
        }

        String normalized = normalize(text);

        switch(normalized)
        {
            case "":
            case "default":
            case "yes":
            case "on":
                return Priority.DEFAULT_PRIORITY;
            case "donotmonitor":
            case "dontmonitor":
            case "mute":
            case "muted":
            case "off":
            case "no":
            case "none":
                return Priority.DO_NOT_MONITOR;
            default:
                break;
        }

        if(normalized.startsWith("priority"))
        {
            normalized = normalized.substring("priority".length());
        }
        else if(normalized.startsWith("p"))
        {
            normalized = normalized.substring(1);
        }

        try
        {
            int priority = Integer.parseInt(normalized);

            if(priority == Priority.DEFAULT_PRIORITY)
            {
                return Priority.DEFAULT_PRIORITY;
            }

            if(Priority.MIN_PRIORITY <= priority && priority < Priority.MAX_PRIORITY)
            {
                return priority;
            }
        }
        catch(NumberFormatException nfe)
        {
            //Not valid
        }

        return null;
    }

    /**
     * Choices for the listen column, in display order.
     */
    public static List<String> getListenChoices()
    {
        List<String> choices = new ArrayList<>();
        choices.add(LISTEN_DEFAULT);
        choices.add(LISTEN_DO_NOT_MONITOR);

        for(int priority = Priority.MIN_PRIORITY; priority < Priority.MAX_PRIORITY; priority++)
        {
            choices.add(LISTEN_PRIORITY_PREFIX + priority);
        }

        return choices;
    }

    /**
     * Formats the record setting
     */
    public static String formatRecord(boolean record)
    {
        return record ? RECORD_YES : RECORD_NO;
    }

    /**
     * Parses a record setting: yes/no, true/false, 1/0, x, on/off.  Blank is no.
     * @return record setting or null if the text is not valid.
     */
    public static Boolean parseRecord(String text)
    {
        switch(normalize(text))
        {
            case "yes":
            case "y":
            case "true":
            case "1":
            case "x":
            case "on":
            case "record":
            case "✓":
                return true;
            case "":
            case "no":
            case "n":
            case "false":
            case "0":
            case "off":
                return false;
            default:
                return null;
        }
    }

    /**
     * Formats an alias colour as #RRGGBB, or an empty string for no colour (zero).
     * @param color value (ARGB)
     */
    public static String formatColour(int color)
    {
        if(color == 0)
        {
            return "";
        }

        return String.format("#%06X", (0xFFFFFF & color));
    }

    /**
     * Parses a colour from #RRGGBB, #RGB or #AARRGGBB (the # is optional).  Blank is no colour (zero).
     * @return colour (ARGB) value, or null if the text is not valid.
     */
    public static Integer parseColour(String text)
    {
        if(text == null || text.isBlank())
        {
            return 0;
        }

        String hex = text.trim();

        if(hex.startsWith("#"))
        {
            hex = hex.substring(1);
        }
        else if(hex.toLowerCase(Locale.ROOT).startsWith("0x"))
        {
            hex = hex.substring(2);
        }

        if(!hex.matches("[0-9a-fA-F]+"))
        {
            return null;
        }

        switch(hex.length())
        {
            case 3:
                StringBuilder sb = new StringBuilder();

                for(char c: hex.toCharArray())
                {
                    sb.append(c).append(c);
                }

                return 0xFF000000 | Integer.parseInt(sb.toString(), 16);
            case 6:
                return 0xFF000000 | Integer.parseInt(hex, 16);
            case 8:
                return (int)Long.parseLong(hex, 16);
            default:
                return null;
        }
    }

    /**
     * Splits a stream (broadcast channel) list on semicolons or commas, trimming each value and ignoring blanks.
     */
    public static List<String> splitStreams(String text)
    {
        List<String> streams = new ArrayList<>();

        if(text != null)
        {
            for(String value: text.split("[;,]"))
            {
                String trimmed = value.trim();

                if(!trimmed.isEmpty() && !trimmed.equals("—") && !streams.contains(trimmed))
                {
                    streams.add(trimmed);
                }
            }
        }

        return streams;
    }

    /**
     * Formats the stream (broadcast channel) names as a semicolon separated list.
     */
    public static String formatStreams(Collection<String> streams)
    {
        return String.join(STREAM_SEPARATOR, streams);
    }

    /**
     * Indicates if the text represents 'not on the console' (blank or 'Not on console').
     */
    public static boolean isNotOnConsole(String text)
    {
        String normalized = normalize(text);
        return normalized.isEmpty() || normalized.equals(normalize(NOT_ON_CONSOLE)) || normalized.equals("none");
    }
}
