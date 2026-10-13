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
package io.github.dsheirer.console.audio;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.audio.playback.AudioOutput;
import io.github.dsheirer.audio.playback.AudioPlaybackDeviceDescriptor;
import io.github.dsheirer.audio.playback.AudioPlaybackManager;
import io.github.dsheirer.console.AudioPan;
import io.github.dsheirer.console.ConsoleCardResolver;
import io.github.dsheirer.console.ConsoleLayoutEvent;
import io.github.dsheirer.console.ConsoleLayoutManager;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.playback.PlayTestAudioRequest;
import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Console audio mixer.  When console mode is enabled, this mixer is the application's audio playback engine: it
 * suspends the traditional AudioPlaybackManager (which releases the output device) and opens its own AudioOutput on
 * the user's selected playback device, with a ConsoleAudioProvider that mixes every call that resolves to a console
 * card using the card's volume, mute and pan.  When console mode is disabled, the mixer closes its output and resumes
 * the AudioPlaybackManager, so behaviour is unchanged for users without a console.
 *
 * Register this mixer as an audio segment listener alongside the AudioPlaybackManager.  Both receive every segment:
 * the inactive engine releases segments without playing them.
 *
 * Threads:
 * - Decoder threads: receive() queues segments for the engine (never blocks on the mixer).
 * - Audio output thread (owned by AudioOutput): mixes one 20 ms frame per getAudio() call.
 * - Console mixer thread (this class): console mode switching and output device changes, a watchdog that releases
 *   audio when the output is not running (e.g. no playback device), and delivery of level snapshots to listeners.
 *
 * Device ownership: mode switches run sequentially on the console mixer thread.  Enabling suspends the traditional
 * playback (closing its device line) before the console output is opened; disabling closes the console output before
 * traditional playback is resumed.  Only one engine holds the device at any time.
 */
public class ConsoleAudioMixer implements Listener<AudioSegment>
{
    private static final Logger mLog = LoggerFactory.getLogger(ConsoleAudioMixer.class);
    private static final long WATCHDOG_INTERVAL_MS = 100;
    private static final long OUTPUT_STALLED_MS = 1000;

    private final Broadcaster<ConsoleAudioLevels> mLevelBroadcaster = new Broadcaster<>();
    private final Broadcaster<String> mClearAudioAlertBroadcaster = new Broadcaster<>();
    private final AtomicReference<ConsoleAudioLevels> mPendingLevels = new AtomicReference<>();
    private final UserPreferences mUserPreferences;
    private final ConsoleLayoutManager mConsoleLayoutManager;
    private final AudioPlaybackManager mAudioPlaybackManager;
    private final ConsoleAudioEngine mConsoleAudioEngine;
    private final ScheduledExecutorService mExecutorService;
    private final Listener<ConsoleLayoutEvent> mLayoutListener = this::layoutChanged;
    private AudioOutput mAudioOutput;
    private volatile boolean mActive;
    private boolean mDisposed;

    /**
     * Constructs an instance, registers for console layout and preference notifications, and applies the current
     * console mode setting.
     * @param userPreferences for the playback device and duplicate call suppression preferences
     * @param consoleLayoutManager for the console mode setting and layout change notifications
     * @param consoleCardResolver to resolve the console card for each call
     * @param audioPlaybackManager traditional playback engine, suspended while console mode is enabled
     */
    public ConsoleAudioMixer(UserPreferences userPreferences, ConsoleLayoutManager consoleLayoutManager,
                             ConsoleCardResolver consoleCardResolver, AudioPlaybackManager audioPlaybackManager)
    {
        mUserPreferences = userPreferences;
        mConsoleLayoutManager = consoleLayoutManager;
        mAudioPlaybackManager = audioPlaybackManager;
        mConsoleAudioEngine = new ConsoleAudioEngine(consoleCardResolver,
            () -> mUserPreferences.getCallManagementPreference().isDuplicatePlaybackSuppressionEnabled());
        mConsoleAudioEngine.setLevelListener(this::levelsAvailable);
        mConsoleAudioEngine.setClearAudioAlertListener(cardId -> execute(() ->
            mClearAudioAlertBroadcaster.broadcast(cardId)));
        updateSettings();

        mExecutorService = Executors.newSingleThreadScheduledExecutor(
            new NamingThreadFactory("sdrtrunk console audio mixer"));
        mExecutorService.scheduleAtFixedRate(this::watchdog, WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS,
            TimeUnit.MILLISECONDS);

        mConsoleLayoutManager.addListener(mLayoutListener);
        MyEventBus.getGlobalEventBus().register(this);
        execute(this::updateMode);
    }

    /**
     * Receives audio segments from channel audio modules.
     */
    @Override
    public void receive(AudioSegment audioSegment)
    {
        mConsoleAudioEngine.receive(audioSegment);
    }

    /**
     * Indicates if the console mixer is currently the active audio playback engine.
     */
    public boolean isActive()
    {
        return mActive;
    }

    /**
     * Adds a listener to receive console playback level snapshots.  Snapshots are delivered on the console mixer
     * thread approximately every 40 ms while any card has a call in the mixer, plus one final snapshot when the mixer
     * goes idle.  Snapshots are coalesced (only the latest is delivered) if a listener falls behind.  Listeners must
     * not block; UI listeners should hand off to the UI thread (e.g. Platform.runLater).
     * @param listener to add
     */
    public void addLevelListener(Listener<ConsoleAudioLevels> listener)
    {
        mLevelBroadcaster.addListener(listener);
    }

    /**
     * Removes the level listener.
     */
    public void removeLevelListener(Listener<ConsoleAudioLevels> listener)
    {
        mLevelBroadcaster.removeListener(listener);
    }

    /**
     * Adds a listener to receive the card identifier of each clear audio (encryption slip) alert raised by the mixer.
     * Alerts are delivered on the console mixer thread.  Listeners must not block.
     * @param listener to add
     */
    public void addClearAudioAlertListener(Listener<String> listener)
    {
        mClearAudioAlertBroadcaster.addListener(listener);
    }

    /**
     * Removes the clear audio alert listener.
     */
    public void removeClearAudioAlertListener(Listener<String> listener)
    {
        mClearAudioAlertBroadcaster.removeListener(listener);
    }

    /**
     * Stops the mixer and closes the console audio output.  Traditional playback is not resumed.
     */
    public void dispose()
    {
        mConsoleLayoutManager.removeListener(mLayoutListener);
        MyEventBus.getGlobalEventBus().unregister(this);

        try
        {
            mExecutorService.submit(() -> {
                mDisposed = true;
                mActive = false;
                mConsoleAudioEngine.setEnabled(false);
                closeOutput();
            }).get(2, TimeUnit.SECONDS);
        }
        catch(Exception e)
        {
            mLog.warn("Timeout or error while stopping console audio mixer", e);
        }

        mExecutorService.shutdownNow();
        mLevelBroadcaster.clear();
        mClearAudioAlertBroadcaster.clear();
    }

    /**
     * Console layout change listener.  Card changes are applied to playing voices on the next frame, and the clear
     * audio alert setting from the next frame.  Console mode changes are applied on the console mixer
     * thread.
     */
    private void layoutChanged(ConsoleLayoutEvent event)
    {
        mConsoleAudioEngine.layoutChanged();

        if(event.getType() == ConsoleLayoutEvent.Type.SETTINGS_CHANGED ||
           event.getType() == ConsoleLayoutEvent.Type.LAYOUT_LOADED)
        {
            updateSettings();
            execute(this::updateMode);
        }
    }

    /**
     * Applies the clear audio alert setting to the engine.
     */
    private void updateSettings()
    {
        mConsoleAudioEngine.setClearAudioAlertEnabled(mConsoleLayoutManager.isClearAudioAlertEnabled());
    }

    /**
     * Guava event bus notification of preference changes, to follow playback device changes while active.
     */
    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.PLAYBACK)
        {
            execute(() -> {
                if(mActive)
                {
                    AudioPlaybackDeviceDescriptor device =
                        mUserPreferences.getPlaybackPreference().getAudioPlaybackDevice();

                    if(device != null && (mAudioOutput == null ||
                        !device.equals(mAudioOutput.getAudioPlaybackDeviceDescriptor())))
                    {
                        closeOutput();
                        openOutput();
                    }
                }
            });
        }
    }

    /**
     * Guava event bus request to play test audio.  Plays through the console output while console mode is active.
     * Channel 0 is the left channel and channel 1 is the right channel.
     */
    @Subscribe
    public void playTestAudio(PlayTestAudioRequest request)
    {
        if(mActive)
        {
            AudioPan pan = AudioPan.BOTH;

            if(!request.isAllChannels())
            {
                pan = request.channel() == 0 ? AudioPan.LEFT : AudioPan.RIGHT;
            }

            mConsoleAudioEngine.playTest(request.audio(), pan);
        }
    }

    /**
     * Applies the current console mode setting.  Invoked on the console mixer thread.
     */
    private void updateMode()
    {
        if(mDisposed)
        {
            return;
        }

        boolean enabled = mConsoleLayoutManager.isConsoleModeEnabled();

        if(enabled == mActive)
        {
            return;
        }

        if(enabled)
        {
            mLog.info("Console mode enabled - console audio mixer is now the audio playback engine");
            mAudioPlaybackManager.setSuspended(true);
            mConsoleAudioEngine.setEnabled(true);
            mActive = true;
            openOutput();
        }
        else
        {
            mLog.info("Console mode disabled - resuming traditional audio playback");
            mActive = false;
            mConsoleAudioEngine.setEnabled(false);
            closeOutput();
            mAudioPlaybackManager.setSuspended(false);
        }
    }

    /**
     * Opens the console audio output on the user's preferred playback device.  Invoked on the console mixer thread.
     */
    private void openOutput()
    {
        AudioPlaybackDeviceDescriptor device = mUserPreferences.getPlaybackPreference().getAudioPlaybackDevice();

        if(device == null)
        {
            mLog.warn("No audio playback device available - console audio will not be played");
            return;
        }

        boolean mono = device.getAudioFormat().getChannels() == 1;

        if(mono)
        {
            mLog.info("Console audio output device is mono - card pan settings will be ignored");
        }

        mAudioOutput = new AudioOutput(device, new ConsoleAudioProvider(mConsoleAudioEngine, mono));
    }

    /**
     * Closes the console audio output.  Invoked on the console mixer thread.
     */
    private void closeOutput()
    {
        if(mAudioOutput != null)
        {
            mAudioOutput.dispose();
            mAudioOutput = null;
        }
    }

    /**
     * Releases queued and playing audio when console mode is active but the audio output is not running (no device,
     * or the device failed to open), so that audio segments are not held indefinitely.
     */
    private void watchdog()
    {
        try
        {
            if(mActive && System.currentTimeMillis() - mConsoleAudioEngine.getLastFrameTimestamp() > OUTPUT_STALLED_MS)
            {
                mConsoleAudioEngine.releaseAll();
            }
        }
        catch(Throwable t)
        {
            mLog.error("Error in console audio mixer watchdog", t);
        }
    }

    /**
     * Receives level snapshots on the audio output thread and hands the latest snapshot to the console mixer thread
     * for delivery to listeners, without blocking.
     */
    private void levelsAvailable(ConsoleAudioLevels levels)
    {
        if(mLevelBroadcaster.hasListeners() && mPendingLevels.getAndSet(levels) == null)
        {
            execute(this::dispatchLevels);
        }
    }

    /**
     * Delivers the latest level snapshot to listeners.  Invoked on the console mixer thread.
     */
    private void dispatchLevels()
    {
        ConsoleAudioLevels levels = mPendingLevels.getAndSet(null);

        if(levels != null)
        {
            mLevelBroadcaster.broadcast(levels);
        }
    }

    /**
     * Executes the task on the console mixer thread.
     */
    private void execute(Runnable task)
    {
        try
        {
            mExecutorService.execute(() -> {
                try
                {
                    task.run();
                }
                catch(Throwable t)
                {
                    mLog.error("Error in console audio mixer", t);
                }
            });
        }
        catch(RejectedExecutionException ree)
        {
            //Shutting down
        }
    }
}
