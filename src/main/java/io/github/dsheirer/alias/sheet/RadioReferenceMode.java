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
    DIGITAL_ENCRYPTED("E", "Digital, encrypted"),
    DIGITAL_PARTIALLY_ENCRYPTED("DE", "Digital, partially encrypted"),
    TDMA("T", "TDMA"),
    TDMA_ENCRYPTED("TE", "TDMA, encrypted"),
    TDMA_PARTIALLY_ENCRYPTED("TDE", "TDMA, partially encrypted"),
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
     * Indicates if the talkgroup is (fully) encrypted: E or TE.
     */
    public boolean isEncrypted()
    {
        return this == DIGITAL_ENCRYPTED || this == TDMA_ENCRYPTED;
    }

    /**
     * Indicates if the talkgroup is partially encrypted (mixed clear and encrypted traffic): DE or TDE.
     */
    public boolean isPartiallyEncrypted()
    {
        return this == DIGITAL_PARTIALLY_ENCRYPTED || this == TDMA_PARTIALLY_ENCRYPTED;
    }

    /**
     * Indicates if the talkgroup uses TDMA (e.g. P25 phase 2): T, TE or TDE.
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
     * Parses a RadioReference mode code.
     * @param text to parse, e.g. 'D', 'TE' or ' de '
     * @return mode or null if the text is not a recognised mode code.
     */
    public static RadioReferenceMode parse(String text)
    {
        if(text == null)
        {
            return null;
        }

        String code = text.replace(' ', ' ').strip();

        //A lowercase trailing 'e' is used for partial encryption on some RadioReference pages
        if(code.equals("De"))
        {
            return DIGITAL_PARTIALLY_ENCRYPTED;
        }
        else if(code.equals("Te") || code.equals("TDe"))
        {
            return TDMA_PARTIALLY_ENCRYPTED;
        }

        code = code.toUpperCase(Locale.ROOT);

        for(RadioReferenceMode mode: values())
        {
            if(mode.mCode.equals(code))
            {
                return mode;
            }
        }

        return null;
    }
}
