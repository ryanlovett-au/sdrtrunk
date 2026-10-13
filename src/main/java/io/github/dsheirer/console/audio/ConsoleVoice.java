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
import io.github.dsheirer.console.ConsoleCard;
import io.github.dsheirer.console.ConsoleCardResolver;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A single call playing through the console mixer.  The voice reads fixed size frames from an audio segment while the
 * segment is still growing, and continues seamlessly into linked continuation segments of the same call.
 *
 * Buffering: the voice waits until PREBUFFER_SAMPLES are available before it starts playing.  When the segment runs
 * dry mid-call (underrun), the voice outputs silence and waits until REBUFFER_SAMPLES are available again.  The voice
 * is not dropped on underrun: it finishes once the segment (and any linked continuation) is complete and fully read,
 * or as a safety net when the segment receives no new audio for STALL_TIMEOUT_MS without completing.  A complete
 * segment shorter than the prebuffer plays immediately.
 *
 * Card: the console card is resolved from the current segment's alias list and identifier collection.  The voice
 * listens for identifier update notifications from its segments (e.g. a late TO talkgroup) and flags itself for
 * re-resolution.  A voice without a card is read at zero gain so that it stays in step with the live call and becomes
 * audible (ramped) as soon as it resolves to a card.
 *
 * Consumer count: the voice owns one consumer count on each of its segments.  Each segment's count is decremented
 * exactly once, either when the voice advances past a fully read segment or when the voice is released.
 *
 * Threading: identifier update notifications arrive on decoder threads and only set a volatile flag.  All other
 * methods must be invoked by the mixer thread.
 */
public class ConsoleVoice implements Listener<IdentifierUpdateNotification>
{
    /**
     * Audio required before a voice starts playing: 200 ms at 8 kHz.  This absorbs decoders that deliver audio in
     * bursts, for example a P25 Phase 1 LDU delivers 180 ms of audio at once.
     */
    public static final int PREBUFFER_SAMPLES = 1600;
    /**
     * Audio required to resume playing after a mid-call underrun: 100 ms at 8 kHz.
     */
    public static final int REBUFFER_SAMPLES = 800;
    /**
     * Safety net: a voice whose segment has not received audio and has not completed within this time is finished.
     */
    public static final long STALL_TIMEOUT_MS = 10000;

    private final List<AudioSegment> mSegments = new ArrayList<>();
    private final VoiceGain mGain = new VoiceGain();
    private final LevelMeter mLevelMeter = new LevelMeter();
    private final float[] mFrame;
    private int mBufferIndex;
    private int mBufferOffset;
    private int mReleasedBufferCount;
    private int mLastReceivedBufferCount;
    private long mLastProgressTimestamp;
    private boolean mPlaying;
    private boolean mStarted;
    private boolean mStopping;
    private boolean mReleased;
    private volatile boolean mIdentifiersUpdated = true;
    private boolean mClearAudioAlerted;
    private ConsoleCard mCard;

    /**
     * Constructs an instance.  The voice takes ownership of the consumer count already held for the segment by the
     * caller.
     * @param audioSegment to play
     * @param frameSize number of samples per frame
     * @param timestamp current time in milliseconds
     */
    public ConsoleVoice(AudioSegment audioSegment, int frameSize, long timestamp)
    {
        mFrame = new float[frameSize];
        mLastProgressTimestamp = timestamp;
        append(audioSegment);
    }

    /**
     * Indicates if the audio segment is the linked continuation of the last segment of this voice.
     */
    public boolean isContinuation(AudioSegment audioSegment)
    {
        return !mReleased && audioSegment.isLinked() && !mSegments.isEmpty() &&
            audioSegment.isLinkedTo(mSegments.getLast());
    }

    /**
     * Appends a linked continuation segment.  The voice takes ownership of the consumer count already held for the
     * segment by the caller.
     * @param audioSegment to append
     */
    public void append(AudioSegment audioSegment)
    {
        if(mReleased)
        {
            audioSegment.decrementConsumerCount();
            return;
        }

        audioSegment.addIdentifierUpdateNotificationListener(this);
        mSegments.add(audioSegment);
    }

    /**
     * Current audio segment being read, or null when the voice has been released.
     */
    public AudioSegment getCurrentSegment()
    {
        return mSegments.isEmpty() ? null : mSegments.getFirst();
    }

    /**
     * Receives identifier update notifications from the voice's audio segments and flags the voice for card
     * re-resolution.  Invoked on decoder threads.
     */
    @Override
    public void receive(IdentifierUpdateNotification identifierUpdateNotification)
    {
        mIdentifiersUpdated = true;
    }

    /**
     * Indicates if the identifiers have changed since the card was last resolved.
     */
    public boolean isIdentifiersUpdated()
    {
        return mIdentifiersUpdated;
    }

    /**
     * Resolves the console card for the current segment and updates the voice gain target from the card settings and
     * the card's group mute setting.
     * @param resolver to find the card
     * @return resolved card or null.
     */
    public ConsoleCard resolve(ConsoleCardResolver resolver)
    {
        //Clear the flag before resolving so that a concurrent update flags the voice again.
        mIdentifiersUpdated = false;
        AudioSegment segment = getCurrentSegment();
        mCard = segment != null ? resolver.getCard(segment.getAliasList(), segment.getIdentifierCollection()) : null;
        mGain.setTarget(mStopping ? null : mCard, mCard != null && resolver.isGroupMuted(mCard.getId()));
        return mCard;
    }

    /**
     * Console card for this voice, as of the last resolution, or null.
     */
    public ConsoleCard getCard()
    {
        return mCard;
    }

    /**
     * Indicates if the clear audio (encryption slip) alert has been raised for this voice (call).
     */
    public boolean isClearAudioAlerted()
    {
        return mClearAudioAlerted;
    }

    /**
     * Sets the clear audio alert state for this voice (call).
     */
    public void setClearAudioAlerted(boolean clearAudioAlerted)
    {
        mClearAudioAlerted = clearAudioAlerted;
    }

    /**
     * Voice gain
     */
    public VoiceGain getGain()
    {
        return mGain;
    }

    /**
     * Post-gain level meter for this voice, updated by the mixer.
     */
    public LevelMeter getLevelMeter()
    {
        return mLevelMeter;
    }

    /**
     * Indicates if the current segment should not be heard: do-not-monitor, encrypted, or a duplicate call when
     * duplicate playback suppression is enabled.  These flags can change during the life of a segment.
     * @param suppressDuplicates true when duplicate playback suppression is enabled.
     */
    public boolean isThrowaway(boolean suppressDuplicates)
    {
        AudioSegment segment = getCurrentSegment();

        return segment != null && (segment.isDoNotMonitor() || segment.isEncrypted() ||
            (suppressDuplicates && segment.isDuplicate()));
    }

    /**
     * Stops the voice: ramps the gain to silence.  The mixer releases the voice after the next frame so that the
     * ramp completes without a click.
     */
    public void stop()
    {
        mStopping = true;
        mGain.setSilent();
    }

    /**
     * Indicates if the voice has been stopped.
     */
    public boolean isStopping()
    {
        return mStopping;
    }

    /**
     * Indicates if the voice is currently playing, as opposed to (pre)buffering.
     */
    public boolean isPlaying()
    {
        return mPlaying;
    }

    /**
     * Indicates if every segment of this voice is complete, meaning no more audio will arrive.
     */
    public boolean isInputComplete()
    {
        for(AudioSegment segment: mSegments)
        {
            if(!segment.isComplete())
            {
                return false;
            }
        }

        return true;
    }

    /**
     * Number of samples available to read across the current segment and any linked continuation segments.
     */
    public int getAvailableSamples()
    {
        int available = 0;

        for(int s = 0; s < mSegments.size(); s++)
        {
            AudioSegment segment = mSegments.get(s);
            int count = segment.getAudioBufferCount();

            for(int b = (s == 0 ? mBufferIndex : 0); b < count; b++)
            {
                available += segment.getAudioBuffer(b).length;
            }

            if(s == 0 && mBufferIndex < count)
            {
                available -= mBufferOffset;
            }

            //Audio in a continuation segment can't be read until this segment is complete
            if(!segment.isComplete())
            {
                break;
            }
        }

        return available;
    }

    /**
     * Reads the next frame of audio.
     * @param timestamp current time in milliseconds
     * @return frame of audio (an internal buffer that is reused on each call), or null while buffering, during an
     * underrun or when there is no more audio.
     */
    public float[] read(long timestamp)
    {
        if(mReleased)
        {
            return null;
        }

        updateProgress(timestamp);

        boolean complete = isInputComplete();
        int available = getAvailableSamples();

        if(!mPlaying)
        {
            int threshold = mStarted ? REBUFFER_SAMPLES : PREBUFFER_SAMPLES;

            if(available >= threshold || (complete && available > 0))
            {
                mPlaying = true;
                mStarted = true;
            }
            else
            {
                return null;
            }
        }

        if(available < mFrame.length && !complete)
        {
            //Underrun - output silence and wait for more audio
            mPlaying = false;
            return null;
        }

        if(available == 0)
        {
            return null;
        }

        int filled = fill(mFrame);

        if(filled < mFrame.length)
        {
            Arrays.fill(mFrame, filled, mFrame.length, 0.0f);
        }

        return mFrame;
    }

    /**
     * Fills the frame from the current read position, advancing into linked continuation segments.
     * @return number of samples copied
     */
    private int fill(float[] frame)
    {
        int filled = 0;

        while(filled < frame.length && !mSegments.isEmpty())
        {
            AudioSegment segment = mSegments.getFirst();

            if(mBufferIndex < segment.getAudioBufferCount())
            {
                float[] buffer = segment.getAudioBuffer(mBufferIndex);
                int count = Math.min(buffer.length - mBufferOffset, frame.length - filled);
                System.arraycopy(buffer, mBufferOffset, frame, filled, count);
                filled += count;
                mBufferOffset += count;

                if(mBufferOffset >= buffer.length)
                {
                    mBufferIndex++;
                    mBufferOffset = 0;
                }
            }
            //Check the buffer count again after checking complete, in case the final buffer was added in between.
            else if(mSegments.size() > 1 && segment.isComplete() && mBufferIndex >= segment.getAudioBufferCount())
            {
                advanceSegment();
            }
            else
            {
                break;
            }
        }

        return filled;
    }

    /**
     * Releases the fully read current segment and moves to the linked continuation segment.
     */
    private void advanceSegment()
    {
        AudioSegment finished = mSegments.removeFirst();
        mReleasedBufferCount += finished.getAudioBufferCount();
        finished.removeIdentifierUpdateNotificationListener(this);
        finished.decrementConsumerCount();
        mBufferIndex = 0;
        mBufferOffset = 0;

        //Resolve against the new current segment's identifiers
        mIdentifiersUpdated = true;
    }

    /**
     * Updates the time that the voice last received audio, for stall detection.
     */
    private void updateProgress(long timestamp)
    {
        int received = mReleasedBufferCount;

        for(AudioSegment segment: mSegments)
        {
            received += segment.getAudioBufferCount();
        }

        if(received != mLastReceivedBufferCount)
        {
            mLastReceivedBufferCount = received;
            mLastProgressTimestamp = timestamp;
        }
    }

    /**
     * Indicates if the voice is finished and can be released: all input is complete and fully read, or the input has
     * stalled without completing.
     * @param timestamp current time in milliseconds
     */
    public boolean isFinished(long timestamp)
    {
        if(mReleased || mSegments.isEmpty())
        {
            return true;
        }

        //Check complete before available, in case audio is added in between.
        if(isInputComplete())
        {
            return getAvailableSamples() == 0;
        }

        updateProgress(timestamp);
        return timestamp - mLastProgressTimestamp > STALL_TIMEOUT_MS;
    }

    /**
     * Indicates if the voice has been released.
     */
    public boolean isReleased()
    {
        return mReleased;
    }

    /**
     * Releases all segments held by this voice, decrementing each segment's consumer count exactly once.  Safe to
     * invoke more than once.
     */
    public void release()
    {
        if(!mReleased)
        {
            mReleased = true;
            mPlaying = false;

            for(AudioSegment segment: mSegments)
            {
                segment.removeIdentifierUpdateNotificationListener(this);
                segment.decrementConsumerCount();
            }

            mSegments.clear();
        }
    }
}
