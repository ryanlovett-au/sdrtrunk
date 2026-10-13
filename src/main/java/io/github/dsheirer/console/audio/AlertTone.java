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

import java.util.Arrays;

/**
 * Synthesized console alert tones.
 *
 * Clear audio (encryption slip) alert: a rising two-tone beep, FIRST_TONE_HZ then SECOND_TONE_HZ, each TONE_MS long
 * with a short gap, at LEVEL peak amplitude.  Each tone has a raised cosine attack and release of RAMP_MS so that the
 * tone starts and stops without clicks.  The tone is mixed by the console mixer at a fixed level, summed with the
 * voices before the output limiter, so it cannot clip when it overlaps loud calls.
 */
public class AlertTone
{
    public static final float SAMPLE_RATE = 8000.0f;
    public static final float FIRST_TONE_HZ = 880.0f;
    public static final float SECOND_TONE_HZ = 1320.0f;
    public static final int TONE_MS = 120;
    public static final int GAP_MS = 30;
    public static final int RAMP_MS = 10;
    /**
     * Peak amplitude of the tone (about -12 dBFS): clearly audible over speech at typical levels without being
     * startling.
     */
    public static final float LEVEL = 0.25f;

    private static final float[] CLEAR_AUDIO_ALERT = createClearAudioAlert();

    /**
     * Prevents instantiation
     */
    private AlertTone()
    {
    }

    /**
     * Clear audio alert samples at 8 kHz.  Returns a copy.
     */
    public static float[] getClearAudioAlert()
    {
        return Arrays.copyOf(CLEAR_AUDIO_ALERT, CLEAR_AUDIO_ALERT.length);
    }

    /**
     * Shared, read-only clear audio alert samples at 8 kHz.  Callers must not modify the array.
     */
    static float[] getClearAudioAlertShared()
    {
        return CLEAR_AUDIO_ALERT;
    }

    /**
     * Creates the two-tone clear audio alert.
     */
    private static float[] createClearAudioAlert()
    {
        int toneSamples = (int)(SAMPLE_RATE * TONE_MS / 1000);
        int gapSamples = (int)(SAMPLE_RATE * GAP_MS / 1000);
        float[] samples = new float[toneSamples * 2 + gapSamples];
        addTone(samples, 0, toneSamples, FIRST_TONE_HZ);
        addTone(samples, toneSamples + gapSamples, toneSamples, SECOND_TONE_HZ);
        return samples;
    }

    /**
     * Writes a tone with a raised cosine attack and release into the samples array.
     * @param samples to write into
     * @param offset of the first tone sample
     * @param length of the tone in samples
     * @param frequency of the tone in Hertz
     */
    private static void addTone(float[] samples, int offset, int length, float frequency)
    {
        int ramp = (int)(SAMPLE_RATE * RAMP_MS / 1000);

        for(int x = 0; x < length; x++)
        {
            double envelope = 1.0;

            if(x < ramp)
            {
                envelope = 0.5 - 0.5 * Math.cos(Math.PI * x / ramp);
            }
            else if(x >= length - ramp)
            {
                envelope = 0.5 - 0.5 * Math.cos(Math.PI * (length - 1 - x) / ramp);
            }

            samples[offset + x] = (float)(LEVEL * envelope * Math.sin(2.0 * Math.PI * frequency * x / SAMPLE_RATE));
        }
    }
}
