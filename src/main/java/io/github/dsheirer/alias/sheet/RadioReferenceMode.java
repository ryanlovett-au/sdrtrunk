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

import java.util.Locale;

/**
 * Talkgroup mode codes from the RadioReference.com talkgroup tables (the Mode column of a trunked system's talkgroup
 * list on the web site).
 *
 * Codes: A (analog), D (digital), E (digital, encrypted), DE (digital, partially encrypted / mixed), T (TDMA),
 * TE (TDMA, encrypted), TDE (TDMA, partially encrypted / mixed) and M (mixed analog/digital).  Codes are matched
 * ignoring case, except that a lowercase trailing 'e' after D or T (e.g. 'De', 'Te') is read as partially encrypted.
 */
public enum RadioReferenceMode
{
    ANALOG("A", "Analog"),
    DIGITAL("D", "Digital"),
    DIGITAL_ENCRYPTED("DE", "Digital, encrypted"),
    DIGITAL_PARTIALLY_ENCRYPTED("De", "Digital, partially encrypted"),
    TDMA("T", "TDMA"),
    TDMA_ENCRYPTED("TE", "TDMA, encrypted"),
    TDMA_PARTIALLY_ENCRYPTED("Te", "TDMA, partially encrypted"),
    MIXED("M", "Analog/digital");

    private final String mCode;
    private final String mLabel;

    RadioReferenceMode(String code, String label)
    {
        mCode = code;
        mLabel = label;
    }

    /**
     * RadioReference mode code, e.g. 'TE'
     */
    public String getCode()
    {
        return mCode;
    }

    /**
     * Descriptive label, e.g. 'TDMA, encrypted'
     */
    public String getLabel()
    {
        return mLabel;
    }

    /**
     * Indicates if the talkgroup is (fully, full time) encrypted: DE or TE.
     */
    public boolean isEncrypted()
    {
        return this == DIGITAL_ENCRYPTED || this == TDMA_ENCRYPTED;
    }

    /**
     * Indicates if the talkgroup is partially (part time) encrypted: De or Te.
     */
    public boolean isPartiallyEncrypted()
    {
        return this == DIGITAL_PARTIALLY_ENCRYPTED || this == TDMA_PARTIALLY_ENCRYPTED;
    }

    /**
     * Indicates if the talkgroup uses TDMA (e.g. P25 phase 2): T, TE or Te.
     */
    public boolean isTdma()
    {
        return this == TDMA || this == TDMA_ENCRYPTED || this == TDMA_PARTIALLY_ENCRYPTED;
    }

    @Override
    public String toString()
    {
        return mCode;
    }

    /**
     * Parses a RadioReference mode.  RadioReference uses D (digital/FDMA), T (TDMA), A (analog) and M (mixed), with an
     * uppercase E for full time encryption and a lowercase e for part time (partial) encryption, e.g. 'DE', 'De', 'TE'
     * and 'Te'.  The current RadioReference site shows the encryption as a separate ENC token, e.g. 'D ENC' (or 'D enc'
     * for part time), and older pages used a bare 'E' for an encrypted digital talkgroup.
     * @param text to parse, e.g. 'D', 'TE', 'De' or 'D ENC'
     * @return mode or null if the text is not a recognised mode.
     */
    public static RadioReferenceMode parse(String text)
    {
        if(text == null)
        {
            return null;
        }

        String code = text.replace('\u00A0', ' ').strip().replaceAll("\\s+", " ");

        if(code.isEmpty())
        {
            return null;
        }

        //Separate encryption token: 'D ENC' (full time) or 'D enc' (part time)
        String[] parts = code.split(" ");

        if(parts.length == 2 && parts[1].equalsIgnoreCase("ENC"))
        {
            boolean full = parts[1].equals("ENC");
            code = parts[0].toUpperCase(Locale.ROOT) + (full ? "E" : "e");
        }
        else if(parts.length != 1)
        {
            return null;
        }

        //Legacy bare 'E': encrypted digital
        if(code.equalsIgnoreCase("E"))
        {
            return DIGITAL_ENCRYPTED;
        }

        //Legacy 'TDE' / 'TDe': TDMA, full time / part time encryption
        if(code.equalsIgnoreCase("TDE"))
        {
            code = "T" + code.charAt(2);
        }

        String base = code.substring(0, 1).toUpperCase(Locale.ROOT);
        String encryption = code.substring(1);

        if(encryption.isEmpty())
        {
            return switch(base)
            {
                case "A" -> ANALOG;
                case "D" -> DIGITAL;
                case "T" -> TDMA;
                case "M" -> MIXED;
                default -> null;
            };
        }

        if(encryption.length() == 1 && (base.equals("D") || base.equals("T")))
        {
            boolean full = encryption.equals("E");
            boolean partial = encryption.equals("e");

            if(full || partial)
            {
                return base.equals("D") ? (full ? DIGITAL_ENCRYPTED : DIGITAL_PARTIALLY_ENCRYPTED) :
                    (full ? TDMA_ENCRYPTED : TDMA_PARTIALLY_ENCRYPTED);
            }
        }

        return null;
    }
}
