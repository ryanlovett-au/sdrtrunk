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
package io.github.dsheirer.gui;

import java.awt.Component;
import java.awt.Desktop;
import java.io.File;
import javax.swing.JOptionPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens a directory (e.g. the application log, audio recording or event log directory) in the platform's file
 * explorer.  Used by the main window's View menu and the console window's View menu.
 */
public final class DirectoryOpener
{
    private static final Logger mLog = LoggerFactory.getLogger(DirectoryOpener.class);
    public static final String ERROR_TITLE = "Can't launch file explorer";

    /**
     * Prevents instantiation
     */
    private DirectoryOpener()
    {
    }

    /**
     * Opens the directory in the platform's file explorer.  Invoke on the Swing event dispatch thread.
     * @param directory to open
     * @return true if the file explorer was launched, false if it could not be launched (the error is logged).
     */
    public static boolean open(File directory)
    {
        try
        {
            Desktop.getDesktop().open(directory);
            return true;
        }
        catch(Exception e)
        {
            mLog.error("Couldn't open file explorer");
            return false;
        }
    }

    /**
     * Opens the directory in the platform's file explorer and shows a Swing error dialog with the directory location
     * when the file explorer can't be launched.  Invoke on the Swing event dispatch thread.
     * @param directory to open
     * @param parent component for the error dialog, or null
     */
    public static void open(File directory, Component parent)
    {
        if(!open(directory))
        {
            JOptionPane.showMessageDialog(parent, getErrorMessage(directory), ERROR_TITLE, JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Error message for a directory that could not be opened.
     */
    public static String getErrorMessage(File directory)
    {
        return "Can't launch file explorer - files are located at: " + directory;
    }
}
