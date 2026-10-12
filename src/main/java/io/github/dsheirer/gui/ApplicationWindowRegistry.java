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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application window lifecycle registry.  Determines what happens when the main application window is closed and
 * provides a single, run-once application exit sequence.
 *
 * Top-level windows that should keep the application running when the main window is closed (e.g. the console window)
 * register as a keep-alive window.  When the user closes the main window:
 * - If a registered keep-alive window is showing, the main window is hidden and the application continues to run.
 * - Otherwise, the application shuts down and exits (the original behavior).
 *
 * A keep-alive window should:
 * - register via registerKeepAliveWindow() when created and unregister via unregisterKeepAliveWindow() when disposed.
 * - invoke keepAliveWindowClosed() when the user closes it, so that the application exits if the main window is hidden
 *   and no other keep-alive window is showing (otherwise the application would be left running with no windows).
 * - invoke showMainWindow() to re-show a hidden main window (e.g. from a menu item or button).
 *
 * Threading: the main window implementation is responsible for dispatching show/hide requests to its UI thread.
 * The exitApplication() shutdown sequence runs on the calling thread and should be invoked on the Swing event dispatch
 * thread (e.g. from a JavaFX window use EventQueue.invokeLater(registry::exitApplication)).
 */
public class ApplicationWindowRegistry
{
    private final static Logger mLog = LoggerFactory.getLogger(ApplicationWindowRegistry.class);

    private final List<IKeepAliveWindow> mKeepAliveWindows = new CopyOnWriteArrayList<>();
    private final AtomicBoolean mShutdownStarted = new AtomicBoolean();
    private final Runnable mShutdownAction;
    private final Runnable mExitAction;
    private IMainWindow mMainWindow;

    /**
     * Constructs an instance
     * @param shutdownAction to perform application shutdown processing (invoked once)
     * @param exitAction to terminate the application (e.g. System.exit(0)), invoked after the shutdown action
     */
    public ApplicationWindowRegistry(Runnable shutdownAction, Runnable exitAction)
    {
        mShutdownAction = shutdownAction;
        mExitAction = exitAction;
    }

    /**
     * Sets the main application window controller.
     * @param mainWindow controller
     */
    public void setMainWindow(IMainWindow mainWindow)
    {
        mMainWindow = mainWindow;
    }

    /**
     * Registers a window that keeps the application running while it is showing and the main window is closed.
     * @param window to register
     */
    public void registerKeepAliveWindow(IKeepAliveWindow window)
    {
        if(window != null && !mKeepAliveWindows.contains(window))
        {
            mKeepAliveWindows.add(window);
        }
    }

    /**
     * Deregisters a keep-alive window.
     * @param window to deregister
     */
    public void unregisterKeepAliveWindow(IKeepAliveWindow window)
    {
        if(window != null)
        {
            mKeepAliveWindows.remove(window);
        }
    }

    /**
     * Indicates if any registered keep-alive window is currently showing.
     */
    public boolean hasShowingKeepAliveWindow()
    {
        return hasShowingKeepAliveWindowExcluding(null);
    }

    /**
     * Indicates if any registered keep-alive window, other than the excluded window, is currently showing.
     * @param excluded window to ignore, or null
     */
    private boolean hasShowingKeepAliveWindowExcluding(IKeepAliveWindow excluded)
    {
        for(IKeepAliveWindow window: mKeepAliveWindows)
        {
            if(window != excluded)
            {
                try
                {
                    if(window.isKeepAliveWindowShowing())
                    {
                        return true;
                    }
                }
                catch(Exception e)
                {
                    mLog.error("Error checking keep-alive window showing state", e);
                }
            }
        }

        return false;
    }

    /**
     * Indicates if the main application window is visible.
     */
    public boolean isMainWindowVisible()
    {
        return mMainWindow != null && mMainWindow.isMainWindowVisible();
    }

    /**
     * Shows the main application window (restoring it if hidden or minimized) and brings it to the front.
     */
    public void showMainWindow()
    {
        if(mMainWindow != null && !isShutdownStarted())
        {
            mMainWindow.showMainWindow();
        }
    }

    /**
     * Processes a user request to close the main window.  Hides the main window when a keep-alive window is showing,
     * otherwise shuts down and exits the application.
     */
    public void mainWindowCloseRequested()
    {
        if(hasShowingKeepAliveWindow() && mMainWindow != null)
        {
            mLog.info("Main window closed - hiding main window while other application windows remain open");
            mMainWindow.hideMainWindow();
        }
        else
        {
            exitApplication();
        }
    }

    /**
     * Processes the closing of a keep-alive window.  Exits the application when the main window is hidden and no
     * other keep-alive window is showing.
     * @param window that is closing
     */
    public void keepAliveWindowClosed(IKeepAliveWindow window)
    {
        if(!isMainWindowVisible() && !hasShowingKeepAliveWindowExcluding(window))
        {
            exitApplication();
        }
    }

    /**
     * Indicates if the application shutdown sequence has started.
     */
    public boolean isShutdownStarted()
    {
        return mShutdownStarted.get();
    }

    /**
     * Shuts down and exits the application.  The shutdown action runs at most once, regardless of how many times
     * this method is invoked.  Subsequent invocations are ignored.
     */
    public void exitApplication()
    {
        if(mShutdownStarted.compareAndSet(false, true))
        {
            try
            {
                if(mShutdownAction != null)
                {
                    mShutdownAction.run();
                }
            }
            catch(Exception e)
            {
                mLog.error("Error during application shutdown", e);
            }

            if(mExitAction != null)
            {
                mExitAction.run();
            }
        }
        else
        {
            mLog.debug("Application shutdown already started - ignoring exit request");
        }
    }

    /**
     * Main application window controller
     */
    public interface IMainWindow
    {
        /**
         * Indicates if the main window is currently visible.
         */
        boolean isMainWindowVisible();

        /**
         * Shows (and restores, if minimized) the main window and brings it to the front.
         */
        void showMainWindow();

        /**
         * Hides the main window without disposing it.
         */
        void hideMainWindow();
    }

    /**
     * Application window that keeps the application running while it is showing, when the main window is closed.
     */
    public interface IKeepAliveWindow
    {
        /**
         * Indicates if this window is currently showing.
         */
        boolean isKeepAliveWindowShowing();
    }
}
