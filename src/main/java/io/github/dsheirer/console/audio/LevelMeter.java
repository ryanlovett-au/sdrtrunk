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

/**
 * Accumulates peak and RMS level measurements of audio samples across one or more frames.
 *
 * Not thread safe: intended to be used only by the mixer thread.
 */
public class LevelMeter
{
    private float mPeak;
    private double mSumOfSquares;
    private int mSampleCount;

    /**
     * Adds a sample to the measurement
     * @param sample to add
     */
    public void add(float sample)
    {
        float magnitude = Math.abs(sample);

        if(magnitude > mPeak)
        {
            mPeak = magnitude;
        }

        mSumOfSquares += (double)sample * sample;
        mSampleCount++;
    }

    /**
     * Adds each of the samples to the measurement
     * @param samples to add
     */
    public void add(float[] samples)
    {
        for(float sample: samples)
        {
            add(sample);
        }
    }

    /**
     * Merges the measurements from the other meter into this meter.
     * @param other meter
     */
    public void add(LevelMeter other)
    {
        mPeak = Math.max(mPeak, other.mPeak);
        mSumOfSquares += other.mSumOfSquares;
        mSampleCount += other.mSampleCount;
    }

    /**
     * Peak absolute sample value since the last reset
     */
    public float getPeak()
    {
        return mPeak;
    }

    /**
     * Root mean square sample value since the last reset
     */
    public float getRms()
    {
        return mSampleCount > 0 ? (float)Math.sqrt(mSumOfSquares / mSampleCount) : 0.0f;
    }

    /**
     * Number of samples measured since the last reset
     */
    public int getSampleCount()
    {
        return mSampleCount;
    }

    /**
     * Resets the measurements
     */
    public void reset()
    {
        mPeak = 0.0f;
        mSumOfSquares = 0.0;
        mSampleCount = 0;
    }
}
