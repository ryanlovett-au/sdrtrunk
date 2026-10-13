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

import io.github.dsheirer.console.AudioPan;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the console mixing core: voice gain, pan routing, summing and limiting.
 */
public class ConsoleMixerTest
{
    private static final int FRAME_SIZE = 160;
    private static final float TOLERANCE = 1e-6f;

    /**
     * Creates a frame filled with the value.
     */
    private static float[] constant(float value)
    {
        float[] samples = new float[FRAME_SIZE];
        Arrays.fill(samples, value);
        return samples;
    }

    /**
     * Creates a voice gain with the settings applied immediately (no ramp).
     */
    private static VoiceGain gain(float volume, boolean muted, AudioPan pan)
    {
        VoiceGain gain = new VoiceGain();
        gain.setTarget(volume, muted, pan);
        return gain;
    }

    private static void assertAll(float[] samples, float expected, String message)
    {
        for(int x = 0; x < samples.length; x++)
        {
            assertEquals(expected, samples[x], TOLERANCE, message + " at sample " + x);
        }
    }

    /**
     * Mixes a single voice for one frame.
     */
    private static ConsoleMixer mixOne(float[] samples, VoiceGain gain)
    {
        ConsoleMixer mixer = new ConsoleMixer(FRAME_SIZE);
        mixer.startFrame();
        mixer.mix(samples, gain, null);
        mixer.finishFrame();
        return mixer;
    }

    @Test
    void panLeftRoutesToLeftOnly()
    {
        ConsoleMixer mixer = mixOne(constant(0.5f), gain(1.0f, false, AudioPan.LEFT));
        assertAll(mixer.getLeft(), 0.5f, "Left");
        assertAll(mixer.getRight(), 0.0f, "Right");
    }

    @Test
    void panRightRoutesToRightOnly()
    {
        ConsoleMixer mixer = mixOne(constant(0.5f), gain(1.0f, false, AudioPan.RIGHT));
        assertAll(mixer.getLeft(), 0.0f, "Left");
        assertAll(mixer.getRight(), 0.5f, "Right");
    }

    @Test
    void panBothRoutesToBothAtFullLevel()
    {
        ConsoleMixer mixer = mixOne(constant(0.5f), gain(1.0f, false, AudioPan.BOTH));
        assertAll(mixer.getLeft(), 0.5f, "Left");
        assertAll(mixer.getRight(), 0.5f, "Right");
    }

    @Test
    void twoVoicesOnSameSideAreSummed()
    {
        ConsoleMixer mixer = new ConsoleMixer(FRAME_SIZE);
        mixer.startFrame();
        mixer.mix(constant(0.25f), gain(1.0f, false, AudioPan.LEFT), null);
        mixer.mix(constant(0.125f), gain(1.0f, false, AudioPan.LEFT), null);
        mixer.mix(constant(0.3f), gain(1.0f, false, AudioPan.RIGHT), null);
        mixer.finishFrame();

        assertAll(mixer.getLeft(), 0.375f, "Left sum");
        assertAll(mixer.getRight(), 0.3f, "Right");
    }

    @Test
    void mutedVoiceIsSilent()
    {
        ConsoleMixer mixer = mixOne(constant(0.8f), gain(1.0f, true, AudioPan.BOTH));
        assertAll(mixer.getLeft(), 0.0f, "Left");
        assertAll(mixer.getRight(), 0.0f, "Right");
    }

    @Test
    void zeroVolumeIsSilent()
    {
        ConsoleMixer mixer = mixOne(constant(0.8f), gain(0.0f, false, AudioPan.BOTH));
        assertAll(mixer.getLeft(), 0.0f, "Left");
        assertAll(mixer.getRight(), 0.0f, "Right");
    }

    @Test
    void volumeIsAppliedAsLinearGain()
    {
        ConsoleMixer mixer = mixOne(constant(0.8f), gain(0.25f, false, AudioPan.BOTH));
        assertAll(mixer.getLeft(), 0.2f, "Left");
        assertAll(mixer.getRight(), 0.2f, "Right");
    }

    @Test
    void underrunVoiceContributesSilence()
    {
        ConsoleMixer mixer = new ConsoleMixer(FRAME_SIZE);
        mixer.startFrame();
        mixer.mix(null, gain(1.0f, false, AudioPan.BOTH), null);
        mixer.mix(constant(0.1f), gain(1.0f, false, AudioPan.BOTH), null);
        mixer.finishFrame();
        assertAll(mixer.getLeft(), 0.1f, "Left");
    }

    @Test
    void gainChangesAreRamped()
    {
        VoiceGain gain = gain(1.0f, false, AudioPan.BOTH);
        ConsoleMixer mixer = mixOne(constant(0.5f), gain);
        assertAll(mixer.getLeft(), 0.5f, "Initial target applied immediately");

        //Mute while playing: the gain must ramp down without a step larger than the ramp step
        gain.setTarget(1.0f, true, AudioPan.BOTH);
        mixer.startFrame();
        mixer.mix(constant(0.5f), gain, null);
        mixer.finishFrame();

        float[] left = mixer.getLeft();
        float previous = 0.5f;
        float maximumStep = 0.5f / VoiceGain.RAMP_SAMPLES + TOLERANCE;

        for(int x = 0; x < FRAME_SIZE; x++)
        {
            assertTrue(previous - left[x] <= maximumStep, "Step too large at sample " + x);
            assertTrue(left[x] <= previous + TOLERANCE, "Expected non-increasing level at sample " + x);
            previous = left[x];
        }

        assertTrue(left[0] > 0.0f, "Expected first sample after mute to still be ramping");
        assertEquals(0.0f, left[VoiceGain.RAMP_SAMPLES - 1], TOLERANCE, "Expected ramp complete");
        assertEquals(0.0f, left[FRAME_SIZE - 1], TOLERANCE, "Expected silence after ramp");
    }

    @Test
    void panChangeCrossFadesToNewSide()
    {
        VoiceGain gain = gain(1.0f, false, AudioPan.LEFT);
        ConsoleMixer mixer = mixOne(constant(0.5f), gain);

        gain.setTarget(1.0f, false, AudioPan.RIGHT);
        mixer.startFrame();
        mixer.mix(constant(0.5f), gain, null);
        mixer.finishFrame();

        assertTrue(mixer.getLeft()[0] > 0.0f, "Expected left to ramp down");
        assertTrue(mixer.getRight()[0] < 0.5f, "Expected right to ramp up");
        assertEquals(0.0f, mixer.getLeft()[FRAME_SIZE - 1], TOLERANCE, "Expected left silent after ramp");
        assertEquals(0.5f, mixer.getRight()[FRAME_SIZE - 1], TOLERANCE, "Expected right full after ramp");
    }

    @Test
    void limiterKeepsOverlappingFullScaleVoicesInRange()
    {
        ConsoleMixer mixer = new ConsoleMixer(FRAME_SIZE);
        Random random = new Random(1234);
        float[] voice1 = new float[FRAME_SIZE];
        float[] voice2 = new float[FRAME_SIZE];
        float[] voice3 = new float[FRAME_SIZE];
        VoiceGain gain1 = gain(1.0f, false, AudioPan.BOTH);
        VoiceGain gain2 = gain(1.0f, false, AudioPan.LEFT);
        VoiceGain gain3 = gain(1.0f, false, AudioPan.BOTH);
        float maximum = 0.0f;

        for(int frame = 0; frame < 200; frame++)
        {
            for(int x = 0; x < FRAME_SIZE; x++)
            {
                //Full scale square wave and full scale noise
                voice1[x] = ((frame * FRAME_SIZE + x) / 20) % 2 == 0 ? 1.0f : -1.0f;
                voice2[x] = voice1[x];
                voice3[x] = random.nextFloat() * 2.0f - 1.0f;
            }

            mixer.startFrame();
            mixer.mix(voice1, gain1, null);
            mixer.mix(voice2, gain2, null);
            mixer.mix(voice3, gain3, null);
            mixer.finishFrame();

            for(int x = 0; x < FRAME_SIZE; x++)
            {
                maximum = Math.max(maximum, Math.abs(mixer.getLeft()[x]));
                maximum = Math.max(maximum, Math.abs(mixer.getRight()[x]));
            }
        }

        assertTrue(maximum <= 1.0f, "Expected output within [-1, 1] but peak was " + maximum);
        assertTrue(maximum <= SoftLimiter.DEFAULT_THRESHOLD + TOLERANCE, "Expected output at or below threshold");
        assertTrue(maximum > 0.5f, "Expected limited output to remain loud");
    }

    @Test
    void limiterPassesNormalLevelsUnchanged()
    {
        SoftLimiter limiter = new SoftLimiter();
        float[] samples = constant(0.5f);
        limiter.process(samples);
        assertAll(samples, 0.5f, "Expected no gain reduction below threshold");
        assertEquals(1.0f, limiter.getGain(), TOLERANCE, "Gain");
    }

    @Test
    void limiterReleasesSmoothlyAfterPeak()
    {
        SoftLimiter limiter = new SoftLimiter();
        limiter.process(constant(2.0f));
        float reduced = limiter.getGain();
        assertTrue(reduced < 0.5f, "Expected gain reduction for 2.0 input");

        //One 20 ms frame later the gain has recovered only partially (no pumping back to unity)
        limiter.process(constant(0.1f));
        assertTrue(limiter.getGain() > reduced && limiter.getGain() < 1.0f, "Expected partial release");
    }

    @Test
    void limiterReplacesNonFiniteSamples()
    {
        SoftLimiter limiter = new SoftLimiter();
        float[] samples = {Float.NaN, Float.POSITIVE_INFINITY, 0.5f};
        limiter.process(samples);

        for(float sample: samples)
        {
            assertTrue(Float.isFinite(sample) && Math.abs(sample) <= 1.0f, "Expected finite sample in range");
        }
    }

    @Test
    void monoModeIgnoresPan()
    {
        ConsoleMixer mixer = new ConsoleMixer(FRAME_SIZE);
        mixer.setMono(true);
        mixer.startFrame();
        mixer.mix(constant(0.25f), gain(1.0f, false, AudioPan.LEFT), null);
        mixer.mix(constant(0.25f), gain(1.0f, false, AudioPan.RIGHT), null);
        mixer.finishFrame();
        assertAll(mixer.getLeft(), 0.5f, "Mono");
        assertAll(mixer.getRight(), 0.5f, "Mono copy");
    }

    @Test
    void levelMeterMeasuresPostGainLevel()
    {
        LevelMeter meter = new LevelMeter();
        ConsoleMixer mixer = new ConsoleMixer(FRAME_SIZE);
        mixer.startFrame();
        mixer.mix(constant(-0.8f), gain(0.5f, false, AudioPan.RIGHT), meter);
        mixer.finishFrame();
        assertEquals(0.4f, meter.getPeak(), TOLERANCE, "Peak");
        assertEquals(0.4f, meter.getRms(), TOLERANCE, "RMS");
        assertEquals(FRAME_SIZE, meter.getSampleCount(), "Sample count");
    }
}
