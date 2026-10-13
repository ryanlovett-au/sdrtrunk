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
package io.github.dsheirer.gui;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the application window registry lifecycle behavior.
 */
public class ApplicationWindowRegistryTest
{
    private final AtomicInteger mShutdownCount = new AtomicInteger();
    private final AtomicInteger mExitCount = new AtomicInteger();
    private final TestMainWindow mMainWindow = new TestMainWindow();

    private ApplicationWindowRegistry createRegistry()
    {
        ApplicationWindowRegistry registry = new ApplicationWindowRegistry(mShutdownCount::incrementAndGet,
            mExitCount::incrementAndGet);
        registry.setMainWindow(mMainWindow);
        return registry;
    }

    @Test
    void closeWithoutKeepAliveWindowExits()
    {
        ApplicationWindowRegistry registry = createRegistry();
        registry.mainWindowCloseRequested();
        assertEquals(1, mShutdownCount.get(), "Expected shutdown");
        assertEquals(1, mExitCount.get(), "Expected exit");
    }

    @Test
    void closeWithHiddenKeepAliveWindowExits()
    {
        ApplicationWindowRegistry registry = createRegistry();
        registry.registerKeepAliveWindow(() -> false);
        registry.mainWindowCloseRequested();
        assertEquals(1, mShutdownCount.get(), "Expected shutdown");
        assertEquals(1, mExitCount.get(), "Expected exit");
    }

    @Test
    void closeWithShowingKeepAliveWindowHidesMainWindow()
    {
        ApplicationWindowRegistry registry = createRegistry();
        AtomicBoolean consoleShowing = new AtomicBoolean(true);
        ApplicationWindowRegistry.IKeepAliveWindow console = consoleShowing::get;
        registry.registerKeepAliveWindow(console);

        registry.mainWindowCloseRequested();
        assertEquals(0, mShutdownCount.get(), "Expected no shutdown");
        assertFalse(mMainWindow.isMainWindowVisible(), "Expected main window to be hidden");

        registry.showMainWindow();
        assertTrue(mMainWindow.isMainWindowVisible(), "Expected main window to be shown");

        //Closing the console while the main window is visible does not exit
        registry.keepAliveWindowClosed(console);
        assertEquals(0, mShutdownCount.get(), "Expected no shutdown");

        //Closing the console while the main window is hidden exits
        registry.mainWindowCloseRequested();
        assertFalse(mMainWindow.isMainWindowVisible(), "Expected main window to be hidden");
        registry.keepAliveWindowClosed(console);
        assertEquals(1, mShutdownCount.get(), "Expected shutdown");
        assertEquals(1, mExitCount.get(), "Expected exit");
    }

    @Test
    void shutdownRunsExactlyOnce()
    {
        ApplicationWindowRegistry registry = createRegistry();
        registry.exitApplication();
        registry.exitApplication();
        registry.mainWindowCloseRequested();
        assertTrue(registry.isShutdownStarted(), "Expected shutdown started");
        assertEquals(1, mShutdownCount.get(), "Expected a single shutdown");
        assertEquals(1, mExitCount.get(), "Expected a single exit");
    }

    @Test
    void exitRunsWhenShutdownFails()
    {
        ApplicationWindowRegistry registry = new ApplicationWindowRegistry(() -> {
            throw new IllegalStateException("test");
        }, mExitCount::incrementAndGet);
        registry.exitApplication();
        assertEquals(1, mExitCount.get(), "Expected exit after shutdown error");
    }

    /**
     * Main window test implementation
     */
    private static class TestMainWindow implements ApplicationWindowRegistry.IMainWindow
    {
        private boolean mVisible = true;

        @Override
        public boolean isMainWindowVisible()
        {
            return mVisible;
        }

        @Override
        public void showMainWindow()
        {
            mVisible = true;
        }

        @Override
        public void hideMainWindow()
        {
            mVisible = false;
        }
    }
}
