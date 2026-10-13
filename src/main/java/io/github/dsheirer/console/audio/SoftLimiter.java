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

/**
 * Peak limiter for a single output channel of summed voices.
 *
 * Design: smoothed gain reduction with an instant attack and an exponential release, with no look-ahead (no added
 * latency).  For each sample, the gain required to keep the sample at or below the threshold is calculated.  When the
 * required gain is lower than the current gain, the current gain drops to it immediately (so the output never exceeds
 * the threshold), otherwise the gain recovers toward unity with a release time constant of RELEASE_MS.  Samples below
 * the threshold with the limiter at rest pass through unchanged, so a single voice at normal levels is unaffected.
 *
 * The slow release holds the gain reduction steady across the closely spaced peaks of overlapping speech, which avoids
 * the audible pumping (gain modulation at the waveform rate) of a fast release, and the instant attack avoids the
 * harmonic distortion of a static soft clipping curve.  A final hard clamp to [-1, 1] guards against non-finite
 * input values.
 *
 * Not thread safe: intended to be used only by the mixer thread.
 */
public class SoftLimiter
{
    /**
     * Default threshold, approximately -1 dBFS
     */
    public static final float DEFAULT_THRESHOLD = 0.89f;
    /**
     * Release time constant in milliseconds
     */
    public static final float RELEASE_MS = 150.0f;

    private final float mThreshold;
    private final float mReleaseCoefficient;
    private float mGain = 1.0f;

    /**
     * Constructs an instance
     * @param threshold peak output level, greater than 0.0 and not greater than 1.0
     * @param sampleRate of the audio in Hertz
     */
    public SoftLimiter(float threshold, float sampleRate)
    {
        if(threshold <= 0.0f || threshold > 1.0f)
        {
            throw new IllegalArgumentException("Threshold must be in the range (0.0, 1.0]");
        }

        mThreshold = threshold;
        mReleaseCoefficient = (float)(1.0 - Math.exp(-1.0 / (RELEASE_MS / 1000.0 * sampleRate)));
    }

    /**
     * Constructs an instance with the default threshold for 8 kHz audio.
     */
    public SoftLimiter()
    {
        this(DEFAULT_THRESHOLD, 8000.0f);
    }

    /**
     * Limits the samples in place.
     * @param samples to limit
     */
    public void process(float[] samples)
    {
        for(int x = 0; x < samples.length; x++)
        {
            float sample = samples[x];

            if(!Float.isFinite(sample))
            {
                sample = 0.0f;
            }

            float magnitude = Math.abs(sample);
            float required = magnitude > mThreshold ? mThreshold / magnitude : 1.0f;
            mGain = Math.min(required, mGain + (1.0f - mGain) * mReleaseCoefficient);
            samples[x] = Math.clamp(sample * mGain, -1.0f, 1.0f);
        }
    }

    /**
     * Current gain reduction value, 1.0 when the limiter is at rest.
     */
    public float getGain()
    {
        return mGain;
    }

    /**
     * Resets the limiter to unity gain.
     */
    public void reset()
    {
        mGain = 1.0f;
    }
}
