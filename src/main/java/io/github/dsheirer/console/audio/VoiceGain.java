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

import io.github.dsheirer.console.AudioPan;
import io.github.dsheirer.console.ConsoleCard;

/**
 * Left and right channel gain for a single mixer voice, derived from a console card's volume, mute and pan settings.
 *
 * Pan is strictly enforced: LEFT routes the voice to the left channel only, RIGHT to the right channel only and BOTH
 * to both channels at full card volume (no pan law attenuation), so a card always plays from its assigned side(s).
 *
 * Gain changes are ramped linearly, per sample, by at most 1 / RAMP_SAMPLES per sample so that live volume, mute and
 * pan changes do not produce audible clicks.  A full scale change (e.g. mute or unmute at full volume) completes in
 * RAMP_SAMPLES samples (5 ms at 8 kHz).  The first target assigned to a new voice is applied immediately.
 *
 * Not thread safe: intended to be used only by the mixer thread.
 */
public class VoiceGain
{
    /**
     * Ramp length in samples for a full scale (0.0 to 1.0) gain change: 5 milliseconds at 8 kHz.
     */
    public static final int RAMP_SAMPLES = 40;
    private static final float RAMP_STEP = 1.0f / RAMP_SAMPLES;

    private float mTargetLeft;
    private float mTargetRight;
    private float mLeft;
    private float mRight;
    private boolean mInitialized;

    /**
     * Constructs an instance with a silent (zero) gain.
     */
    public VoiceGain()
    {
    }

    /**
     * Sets the target gain from the card audio settings.
     * @param volume linear gain 0.0 to 1.0
     * @param muted true to silence the voice
     * @param pan output channel routing
     */
    public void setTarget(float volume, boolean muted, AudioPan pan)
    {
        float gain = muted ? 0.0f : ConsoleCard.constrainVolume(volume);
        AudioPan routing = pan != null ? pan : AudioPan.BOTH;

        mTargetLeft = routing == AudioPan.RIGHT ? 0.0f : gain;
        mTargetRight = routing == AudioPan.LEFT ? 0.0f : gain;

        if(!mInitialized)
        {
            mLeft = mTargetLeft;
            mRight = mTargetRight;
            mInitialized = true;
        }
    }

    /**
     * Sets the target gain from the card, or silences the voice when the card is null.
     * @param card with audio settings, or null
     */
    public void setTarget(ConsoleCard card)
    {
        if(card != null)
        {
            setTarget(card.getVolume(), card.isMuted(), card.getPan());
        }
        else
        {
            setSilent();
        }
    }

    /**
     * Sets the target gain to silence (ramped).
     */
    public void setSilent()
    {
        setTarget(0.0f, true, AudioPan.BOTH);
    }

    /**
     * Advances the gain ramp by one sample.  Invoke once per sample before reading the left and right gain values.
     */
    public void step()
    {
        mLeft = approach(mLeft, mTargetLeft);
        mRight = approach(mRight, mTargetRight);
    }

    /**
     * Moves the current value toward the target value by no more than the ramp step.
     */
    private static float approach(float current, float target)
    {
        if(current < target)
        {
            return Math.min(current + RAMP_STEP, target);
        }
        else if(current > target)
        {
            return Math.max(current - RAMP_STEP, target);
        }

        return current;
    }

    /**
     * Current (ramped) left channel gain
     */
    public float getLeft()
    {
        return mLeft;
    }

    /**
     * Current (ramped) right channel gain
     */
    public float getRight()
    {
        return mRight;
    }

    /**
     * Target left channel gain
     */
    public float getTargetLeft()
    {
        return mTargetLeft;
    }

    /**
     * Target right channel gain
     */
    public float getTargetRight()
    {
        return mTargetRight;
    }

    /**
     * Indicates if the target gain is non-zero on at least one channel, meaning that the voice will be heard.
     */
    public boolean isAudible()
    {
        return mTargetLeft > 0.0f || mTargetRight > 0.0f;
    }
}
