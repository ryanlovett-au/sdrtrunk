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
package io.github.dsheirer.console.audio;

import java.util.Arrays;

/**
 * Console mixing core.  Sums voice frames into left and right output buses using each voice's (ramped) channel gains
 * and then limits each bus so that overlapping voices cannot clip.  This class has no threading or audio device
 * dependencies.
 *
 * Usage, once per output frame: startFrame(), mix() for each voice, then finishFrame() and read the left and right
 * buses.
 *
 * Each output channel has an independent limiter so that a loud call on one side does not reduce the level of calls on
 * the other side (each side is typically a separate dispatch speaker or ear).
 *
 * Mono mode (single channel output device): pan cannot be honoured, so each voice is mixed into the single (left) bus
 * at its card volume, ignoring pan, rather than averaging the left and right buses which would attenuate LEFT and
 * RIGHT cards by 6 dB relative to BOTH cards.  The right bus is a copy of the left bus in mono mode.
 *
 * Not thread safe: intended to be used only by the mixer thread.
 */
public class ConsoleMixer
{
    private final int mFrameSize;
    private final float[] mLeft;
    private final float[] mRight;
    private final SoftLimiter mLeftLimiter = new SoftLimiter();
    private final SoftLimiter mRightLimiter = new SoftLimiter();
    private boolean mMono;

    /**
     * Constructs an instance
     * @param frameSize number of samples per channel per frame
     */
    public ConsoleMixer(int frameSize)
    {
        mFrameSize = frameSize;
        mLeft = new float[frameSize];
        mRight = new float[frameSize];
    }

    /**
     * Number of samples per channel per frame
     */
    public int getFrameSize()
    {
        return mFrameSize;
    }

    /**
     * Sets mono mode, where all voices are mixed to a single bus ignoring pan.
     */
    public void setMono(boolean mono)
    {
        mMono = mono;
    }

    /**
     * Indicates if the mixer is in mono mode.
     */
    public boolean isMono()
    {
        return mMono;
    }

    /**
     * Clears the output buses to start a new frame.
     */
    public void startFrame()
    {
        Arrays.fill(mLeft, 0.0f);
        Arrays.fill(mRight, 0.0f);
    }

    /**
     * Mixes the voice samples into the output buses, advancing the voice gain ramp by one frame.
     * @param samples for the voice, or null when the voice has no audio for this frame (underrun).  Arrays shorter
     * than the frame size are padded with silence.
     * @param gain for the voice
     * @param meter optional (nullable) meter to receive the voice's post-gain (pre-pan) level
     */
    public void mix(float[] samples, VoiceGain gain, LevelMeter meter)
    {
        for(int x = 0; x < mFrameSize; x++)
        {
            gain.step();

            float sample = (samples != null && x < samples.length) ? samples[x] : 0.0f;

            if(mMono)
            {
                float mono = sample * Math.max(gain.getLeft(), gain.getRight());
                mLeft[x] += mono;

                if(meter != null)
                {
                    meter.add(mono);
                }
            }
            else
            {
                float left = sample * gain.getLeft();
                float right = sample * gain.getRight();
                mLeft[x] += left;
                mRight[x] += right;

                if(meter != null)
                {
                    meter.add(Math.abs(left) > Math.abs(right) ? left : right);
                }
            }
        }
    }

    /**
     * Mixes unity gain samples (e.g. test audio) into one or both output buses without a gain ramp.
     * @param samples to mix, padded with silence when shorter than the frame size
     * @param left true to mix into the left bus
     * @param right true to mix into the right bus
     */
    public void mixDirect(float[] samples, boolean left, boolean right)
    {
        for(int x = 0; x < mFrameSize && x < samples.length; x++)
        {
            if(left || mMono)
            {
                mLeft[x] += samples[x];
            }

            if(right && !mMono)
            {
                mRight[x] += samples[x];
            }
        }
    }

    /**
     * Completes the frame by applying the limiter to each output bus.
     */
    public void finishFrame()
    {
        mLeftLimiter.process(mLeft);

        if(mMono)
        {
            System.arraycopy(mLeft, 0, mRight, 0, mFrameSize);
        }
        else
        {
            mRightLimiter.process(mRight);
        }
    }

    /**
     * Left output bus.  In mono mode this is the single output channel.
     */
    public float[] getLeft()
    {
        return mLeft;
    }

    /**
     * Right output bus.  In mono mode this is a copy of the left bus.
     */
    public float[] getRight()
    {
        return mRight;
    }

    /**
     * Resets the limiters to unity gain.
     */
    public void reset()
    {
        mLeftLimiter.reset();
        mRightLimiter.reset();
        startFrame();
    }
}
