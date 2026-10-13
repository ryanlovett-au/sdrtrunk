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

import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.audio.playback.AudioChannel;
import io.github.dsheirer.console.AudioPan;
import io.github.dsheirer.console.ConsoleCard;
import io.github.dsheirer.console.ConsoleCardResolver;
import io.github.dsheirer.log.LoggingSuppressor;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Console audio engine.  Manages the set of playing voices (one per call) and produces one mixed stereo frame per
 * invocation of processFrame(), which is driven by the audio output thread at the output device's rate.
 *
 * Each frame: new audio segments are taken from the intake queue (linked continuations join the voice that is
 * playing the preceding segment, otherwise a new voice is created), cards are (re)resolved for voices whose identifiers
 * changed or after a layout change, each voice is read and mixed with its card's gain and pan (silent when the card or
 * the card's group is muted, see ConsoleCardResolver.isAudioMuted()), finished or stopped voices are released, and the
 * output buses are limited.  Per-card and per-channel levels are accumulated and handed to the level listener every
 * LEVEL_INTERVAL_FRAMES frames.
 *
 * Calls are not played (and their segments are released) when flagged as do-not-monitor, encrypted, or duplicate
 * while duplicate playback suppression is enabled.  These checks are repeated every frame because the flags can change
 * during a call.
 *
 * Clear audio (encryption slip) alert: when enabled, a voice that starts playing clear (non-encrypted) audio for a card
 * whose talkgroup is treated as normally encrypted (ConsoleCard.isTreatedAsEncrypted()) raises an alert once per voice
 * (i.e. once per call, including its linked continuation segments): the two-tone AlertTone is mixed on the card's pan
 * side(s), even when the card or its group is muted, and the alert listener is notified with the card identifier.
 * Encrypted segments never become voices, so any voice that plays audio is clear audio.
 *
 * Consumer counting: the engine owns the consumer count that the audio segment broadcaster increments for this
 * listener.  Each received segment's count is decremented exactly once: when it is discarded, or by the voice that
 * plays it.
 *
 * Threading: receive() and layoutChanged() may be invoked from any thread and do not block on the mixer.  All other
 * state is guarded by a lock that is held by processFrame() for the duration of one frame (microseconds).
 */
public class ConsoleAudioEngine
{
    private static final Logger mLog = LoggerFactory.getLogger(ConsoleAudioEngine.class);
    private static final LoggingSuppressor LOGGING_SUPPRESSOR = new LoggingSuppressor(mLog);

    /**
     * Samples per channel per frame: 20 ms at 8 kHz.
     */
    public static final int SAMPLES_PER_FRAME = AudioChannel.SAMPLES_PER_INTERVAL;
    /**
     * Number of frames per level measurement interval (40 ms)
     */
    public static final int LEVEL_INTERVAL_FRAMES = 2;
    /**
     * Interval for re-resolving the card of every voice, as a safety net in addition to the identifier update and
     * layout change triggers.
     */
    public static final long RESOLVE_INTERVAL_MS = 250;
    /**
     * Maximum simultaneous voices.  Segments beyond this limit are not played.
     */
    public static final int MAXIMUM_VOICES = 64;
    /**
     * Maximum simultaneous alert tones.
     */
    public static final int MAXIMUM_ALERTS = 4;

    private final LinkedTransferQueue<AudioSegment> mIntakeQueue = new LinkedTransferQueue<>();
    private final List<ConsoleVoice> mVoices = new ArrayList<>();
    private final ReentrantLock mLock = new ReentrantLock();
    private final ConsoleMixer mMixer = new ConsoleMixer(SAMPLES_PER_FRAME);
    private final LevelMeter mLeftMeter = new LevelMeter();
    private final LevelMeter mRightMeter = new LevelMeter();
    private final Map<String,CardAccumulator> mCardAccumulators = new LinkedHashMap<>();
    private final AtomicBoolean mLayoutChanged = new AtomicBoolean();
    private final ConsoleCardResolver mConsoleCardResolver;
    private final BooleanSupplier mDuplicateSuppression;
    private final List<AlertPlayback> mAlerts = new ArrayList<>();
    private volatile boolean mEnabled;
    private volatile boolean mClearAudioAlertEnabled = true;
    private Listener<String> mClearAudioAlertListener;
    private volatile long mLastFrameTimestamp;
    private Listener<ConsoleAudioLevels> mLevelListener;
    private float[] mTestAudio;
    private int mTestAudioOffset;
    private AudioPan mTestAudioPan;
    private long mLastResolveTimestamp;
    private int mLevelFrameCount;
    private boolean mLevelsPublished;

    /**
     * Constructs an instance.  The engine is initially disabled.
     * @param consoleCardResolver to resolve the console card for each call
     * @param duplicateSuppression supplies the current duplicate playback suppression preference
     */
    public ConsoleAudioEngine(ConsoleCardResolver consoleCardResolver, BooleanSupplier duplicateSuppression)
    {
        mConsoleCardResolver = consoleCardResolver;
        mDuplicateSuppression = duplicateSuppression;
    }

    /**
     * Receives a new audio segment.  The caller's consumer count for the segment is transferred to this engine.  When
     * the engine is disabled, the segment is released immediately.
     * @param audioSegment to play
     */
    public void receive(AudioSegment audioSegment)
    {
        mIntakeQueue.add(audioSegment);

        //Checked after adding so that a concurrent setEnabled(false) can't strand the segment in the queue: either
        //setEnabled() drains it, or we do.
        if(!mEnabled)
        {
            mLock.lock();

            try
            {
                discardIntake();
            }
            finally
            {
                mLock.unlock();
            }
        }
    }

    /**
     * Notifies the engine that the console layout changed, so that each voice's card (and card settings) are
     * re-resolved on the next frame.
     */
    public void layoutChanged()
    {
        mLayoutChanged.set(true);
    }

    /**
     * Enables or disables the engine.  Disabling releases all voices and queued segments.
     */
    public void setEnabled(boolean enabled)
    {
        mLastFrameTimestamp = System.currentTimeMillis();
        mEnabled = enabled;

        if(!enabled)
        {
            releaseAll();
        }
    }

    /**
     * Indicates if the engine is enabled.
     */
    public boolean isEnabled()
    {
        return mEnabled;
    }

    /**
     * Sets mono output mode.  See ConsoleMixer.
     */
    public void setMono(boolean mono)
    {
        mLock.lock();

        try
        {
            mMixer.setMono(mono);
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Sets the listener to receive level snapshots.  The listener is invoked on the thread that drives processFrame()
     * (the audio output thread) while the engine lock is held, so it must return immediately, for example by handing
     * the snapshot to another thread.
     * @param listener to receive levels, or null
     */
    public void setLevelListener(Listener<ConsoleAudioLevels> listener)
    {
        mLock.lock();

        try
        {
            mLevelListener = listener;
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Enables or disables the clear audio (encryption slip) alert.
     */
    public void setClearAudioAlertEnabled(boolean enabled)
    {
        mClearAudioAlertEnabled = enabled;
    }

    /**
     * Sets the listener to receive the card identifier of each clear audio alert.  The listener is invoked on the
     * thread that drives processFrame() (the audio output thread) while the engine lock is held, so it must return
     * immediately, for example by handing the card identifier to another thread.
     * @param listener to receive card identifiers, or null
     */
    public void setClearAudioAlertListener(Listener<String> listener)
    {
        mLock.lock();

        try
        {
            mClearAudioAlertListener = listener;
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Timestamp of the most recently processed frame (or of the last enable/disable), for detecting an output that is
     * not running.
     */
    public long getLastFrameTimestamp()
    {
        return mLastFrameTimestamp;
    }

    /**
     * Number of voices currently in the mixer
     */
    public int getVoiceCount()
    {
        mLock.lock();

        try
        {
            return mVoices.size();
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Plays test audio at unity gain, ahead of no other audio, on the specified side(s).  Replaces any test audio that
     * is currently playing.
     * @param audio samples at 8 kHz
     * @param pan output channel routing
     */
    public void playTest(float[] audio, AudioPan pan)
    {
        mLock.lock();

        try
        {
            mTestAudio = audio;
            mTestAudioOffset = 0;
            mTestAudioPan = pan != null ? pan : AudioPan.BOTH;
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Releases all voices and queued segments.
     */
    public void releaseAll()
    {
        mLock.lock();

        try
        {
            discardIntake();

            for(ConsoleVoice voice: mVoices)
            {
                voice.release();
            }

            mVoices.clear();
            mAlerts.clear();
            mTestAudio = null;
            mMixer.reset();
            mCardAccumulators.clear();
            mLeftMeter.reset();
            mRightMeter.reset();
            mLevelFrameCount = 0;

            if(mLevelsPublished)
            {
                publish(new ConsoleAudioLevels(System.currentTimeMillis(), 0.0f, 0.0f, 0.0f, 0.0f,
                    Collections.emptyList()));
                mLevelsPublished = false;
            }
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Processes one frame: mixes all voices and writes the limited output to the left and right arrays.
     * @param timestamp current time in milliseconds
     * @param left array of SAMPLES_PER_FRAME length to receive the left channel (or mono) output
     * @param right array of SAMPLES_PER_FRAME length to receive the right channel output
     */
    public void processFrame(long timestamp, float[] left, float[] right)
    {
        mLock.lock();

        try
        {
            mLastFrameTimestamp = timestamp;

            if(!mEnabled)
            {
                discardIntake();
                Arrays.fill(left, 0.0f);
                Arrays.fill(right, 0.0f);
                return;
            }

            boolean suppressDuplicates = mDuplicateSuppression.getAsBoolean();

            processIntake(timestamp, suppressDuplicates);
            resolveCards(timestamp);

            mMixer.startFrame();

            Iterator<ConsoleVoice> it = mVoices.iterator();

            while(it.hasNext())
            {
                ConsoleVoice voice = it.next();

                if(!voice.isStopping() && voice.isThrowaway(suppressDuplicates))
                {
                    //Mix one more frame with the gain ramping to zero, then release
                    voice.stop();
                }

                float[] audio = voice.read(timestamp);

                if(audio != null)
                {
                    checkClearAudio(voice);
                }

                mMixer.mix(audio, voice.getGain(), voice.getLevelMeter());
                accumulate(voice);

                if(voice.isStopping() || voice.isFinished(timestamp))
                {
                    voice.release();
                    it.remove();
                }
            }

            mixTestAudio();
            mixAlerts();
            mMixer.finishFrame();

            System.arraycopy(mMixer.getLeft(), 0, left, 0, SAMPLES_PER_FRAME);
            System.arraycopy(mMixer.getRight(), 0, right, 0, SAMPLES_PER_FRAME);
            mLeftMeter.add(left);
            mRightMeter.add(right);

            if(++mLevelFrameCount >= LEVEL_INTERVAL_FRAMES)
            {
                publishLevels(timestamp);
            }
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Indicates if the segment should not be played: do-not-monitor, encrypted, or a suppressed duplicate.
     */
    private static boolean isThrowaway(AudioSegment audioSegment, boolean suppressDuplicates)
    {
        return audioSegment.isDoNotMonitor() || audioSegment.isEncrypted() ||
            (suppressDuplicates && audioSegment.isDuplicate());
    }

    /**
     * Releases all segments waiting in the intake queue.  Lock must be held.
     */
    private void discardIntake()
    {
        AudioSegment audioSegment = mIntakeQueue.poll();

        while(audioSegment != null)
        {
            audioSegment.decrementConsumerCount();
            audioSegment = mIntakeQueue.poll();
        }
    }

    /**
     * Transfers new segments from the intake queue to voices.  Lock must be held.
     */
    private void processIntake(long timestamp, boolean suppressDuplicates)
    {
        AudioSegment audioSegment = mIntakeQueue.poll();

        while(audioSegment != null)
        {
            if(isThrowaway(audioSegment, suppressDuplicates))
            {
                audioSegment.decrementConsumerCount();
            }
            else
            {
                ConsoleVoice continued = null;

                if(audioSegment.isLinked())
                {
                    for(ConsoleVoice voice: mVoices)
                    {
                        if(voice.isContinuation(audioSegment))
                        {
                            continued = voice;
                            break;
                        }
                    }
                }

                if(continued != null)
                {
                    continued.append(audioSegment);
                }
                else if(mVoices.size() >= MAXIMUM_VOICES)
                {
                    LOGGING_SUPPRESSOR.info("console voice limit", 3, "Console audio mixer voice limit [" +
                        MAXIMUM_VOICES + "] reached - call will not be played");
                    audioSegment.decrementConsumerCount();
                }
                else
                {
                    mVoices.add(new ConsoleVoice(audioSegment, SAMPLES_PER_FRAME, timestamp));
                }
            }

            audioSegment = mIntakeQueue.poll();
        }
    }

    /**
     * Resolves the card for voices with updated identifiers, or for all voices after a layout change and periodically.
     * Lock must be held.
     */
    private void resolveCards(long timestamp)
    {
        boolean all = mLayoutChanged.getAndSet(false) || (timestamp - mLastResolveTimestamp) >= RESOLVE_INTERVAL_MS;

        if(all)
        {
            mLastResolveTimestamp = timestamp;
        }

        for(ConsoleVoice voice: mVoices)
        {
            if(all || voice.isIdentifiersUpdated())
            {
                voice.resolve(mConsoleCardResolver);
            }
        }
    }

    /**
     * Raises the clear audio alert, once per voice, when the voice is playing clear audio for a card that is treated
     * as normally encrypted.  Lock must be held.
     */
    private void checkClearAudio(ConsoleVoice voice)
    {
        ConsoleCard card = voice.getCard();

        if(!mClearAudioAlertEnabled || voice.isClearAudioAlerted() || voice.isStopping() || card == null ||
           !card.isTreatedAsEncrypted())
        {
            return;
        }

        AudioSegment segment = voice.getCurrentSegment();

        if(segment == null || segment.isEncrypted())
        {
            return;
        }

        voice.setClearAudioAlerted(true);

        if(mAlerts.size() < MAXIMUM_ALERTS)
        {
            mAlerts.add(new AlertPlayback(AlertTone.getClearAudioAlertShared(), card.getPan()));
        }

        if(mClearAudioAlertListener != null)
        {
            try
            {
                mClearAudioAlertListener.receive(card.getId());
            }
            catch(Exception e)
            {
                LOGGING_SUPPRESSOR.error("console alert listener", 3, "Error dispatching clear audio alert", e);
            }
        }
    }

    /**
     * Mixes the next frame of each playing alert tone.  Lock must be held.
     */
    private void mixAlerts()
    {
        Iterator<AlertPlayback> it = mAlerts.iterator();

        while(it.hasNext())
        {
            AlertPlayback alert = it.next();
            int end = Math.min(alert.mOffset + SAMPLES_PER_FRAME, alert.mSamples.length);
            float[] samples = Arrays.copyOfRange(alert.mSamples, alert.mOffset, end);
            mMixer.mixDirect(samples, alert.mPan != AudioPan.RIGHT, alert.mPan != AudioPan.LEFT);
            alert.mOffset = end;

            if(alert.mOffset >= alert.mSamples.length)
            {
                it.remove();
            }
        }
    }

    /**
     * Number of alert tones currently playing.
     */
    public int getAlertCount()
    {
        mLock.lock();

        try
        {
            return mAlerts.size();
        }
        finally
        {
            mLock.unlock();
        }
    }

    /**
     * Mixes the next frame of test audio, if any.  Lock must be held.
     */
    private void mixTestAudio()
    {
        if(mTestAudio != null)
        {
            int end = Math.min(mTestAudioOffset + SAMPLES_PER_FRAME, mTestAudio.length);
            float[] samples = Arrays.copyOfRange(mTestAudio, mTestAudioOffset, end);
            mMixer.mixDirect(samples, mTestAudioPan != AudioPan.RIGHT, mTestAudioPan != AudioPan.LEFT);
            mTestAudioOffset = end;

            if(mTestAudioOffset >= mTestAudio.length)
            {
                mTestAudio = null;
            }
        }
    }

    /**
     * Accumulates the voice's frame level into its card's level and resets the voice meter.  Lock must be held.
     */
    private void accumulate(ConsoleVoice voice)
    {
        ConsoleCard card = voice.getCard();

        if(card != null && card.getId() != null)
        {
            CardAccumulator accumulator = mCardAccumulators.computeIfAbsent(card.getId(), id -> new CardAccumulator());
            accumulator.mPan = card.getPan();
            accumulator.mVoices.add(voice);
            accumulator.mMeter.add(voice.getLevelMeter());
            accumulator.mAudible |= !voice.isStopping() && voice.isPlaying() && voice.getGain().isAudible();
        }

        voice.getLevelMeter().reset();
    }

    /**
     * Publishes a level snapshot when cards are active, plus one trailing snapshot after the last card goes idle.
     * Lock must be held.
     */
    private void publishLevels(long timestamp)
    {
        boolean active = !mCardAccumulators.isEmpty() || mLeftMeter.getPeak() > 0.0f || mRightMeter.getPeak() > 0.0f;

        if(active || mLevelsPublished)
        {
            List<ConsoleAudioLevels.CardLevel> cardLevels = new ArrayList<>();

            for(Map.Entry<String,CardAccumulator> entry: mCardAccumulators.entrySet())
            {
                CardAccumulator accumulator = entry.getValue();
                cardLevels.add(new ConsoleAudioLevels.CardLevel(entry.getKey(), accumulator.mPan,
                    accumulator.mAudible, accumulator.mVoices.size(), accumulator.mMeter.getPeak(),
                    accumulator.mMeter.getRms()));
            }

            publish(new ConsoleAudioLevels(timestamp, mLeftMeter.getPeak(), mLeftMeter.getRms(),
                mRightMeter.getPeak(), mRightMeter.getRms(), cardLevels));
            mLevelsPublished = active;
        }

        mCardAccumulators.clear();
        mLeftMeter.reset();
        mRightMeter.reset();
        mLevelFrameCount = 0;
    }

    /**
     * Sends the levels to the listener.  Lock must be held.
     */
    private void publish(ConsoleAudioLevels levels)
    {
        if(mLevelListener != null)
        {
            try
            {
                mLevelListener.receive(levels);
            }
            catch(Exception e)
            {
                LOGGING_SUPPRESSOR.error("console level listener", 3, "Error dispatching console audio levels", e);
            }
        }
    }

    /**
     * Playback position of an alert tone.
     */
    private static class AlertPlayback
    {
        private final float[] mSamples;
        private final AudioPan mPan;
        private int mOffset;

        private AlertPlayback(float[] samples, AudioPan pan)
        {
            mSamples = samples;
            mPan = pan != null ? pan : AudioPan.BOTH;
        }
    }

    /**
     * Accumulates level measurements for a card across the frames of a level interval.
     */
    private static class CardAccumulator
    {
        private final LevelMeter mMeter = new LevelMeter();
        private final Set<ConsoleVoice> mVoices = new HashSet<>();
        private AudioPan mPan = AudioPan.BOTH;
        private boolean mAudible;
    }
}
