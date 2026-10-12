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
package io.github.dsheirer.console;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.UUID;

/**
 * Console card.  Each card is bound to a single alias (via the alias unique identifier) and carries the per-card audio
 * settings.
 *
 * Volume is a linear gain value in the range 0.0 (silent) to 1.0 (full/unity gain) that a mixer can apply directly
 * as a sample multiplier.  A linear value is used instead of a dB value because the audio pipeline operates on float
 * samples and because it maps directly to a slider position.  Mute is independent of volume so that un-muting
 * restores the previous volume.
 *
 * Note: this is a plain data object that is serialized as JSON.  Modifications should be made via the
 * ConsoleLayoutManager so that constraints are enforced and listeners are notified.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "aliasUuid", "volume", "muted", "pan"})
public class ConsoleCard
{
    public static final float MINIMUM_VOLUME = 0.0f;
    public static final float MAXIMUM_VOLUME = 1.0f;
    public static final float DEFAULT_VOLUME = MAXIMUM_VOLUME;

    private String mId;
    private String mAliasUuid;
    private float mVolume = DEFAULT_VOLUME;
    private boolean mMuted;
    private AudioPan mPan = AudioPan.BOTH;

    /**
     * Constructs an instance.  Used by Jackson for deserialization.
     */
    public ConsoleCard()
    {
    }

    /**
     * Constructs an instance with a new unique identifier that is bound to the specified alias.
     * @param aliasUuid unique identifier of the alias
     */
    public ConsoleCard(String aliasUuid)
    {
        mId = UUID.randomUUID().toString();
        mAliasUuid = aliasUuid;
    }

    /**
     * Creates a copy of this card, including the unique identifier.
     */
    public ConsoleCard copyOf()
    {
        ConsoleCard copy = new ConsoleCard();
        copy.setId(getId());
        copy.setAliasUuid(getAliasUuid());
        copy.setVolume(getVolume());
        copy.setMuted(isMuted());
        copy.setPan(getPan());
        return copy;
    }

    /**
     * Unique identifier for this card.
     */
    @JsonProperty("id")
    public String getId()
    {
        return mId;
    }

    public void setId(String id)
    {
        mId = id;
    }

    /**
     * Unique identifier (UUID) of the alias that is bound to this card.
     */
    @JsonProperty("aliasUuid")
    public String getAliasUuid()
    {
        return mAliasUuid;
    }

    public void setAliasUuid(String aliasUuid)
    {
        mAliasUuid = aliasUuid;
    }

    /**
     * Linear volume (gain) in the range 0.0 to 1.0
     */
    @JsonProperty("volume")
    public float getVolume()
    {
        return mVolume;
    }

    /**
     * Sets the linear volume (gain).  Values are constrained to the range 0.0 to 1.0 and a NaN value is replaced with
     * the default volume.
     * @param volume to set
     */
    public void setVolume(float volume)
    {
        mVolume = constrainVolume(volume);
    }

    /**
     * Constrains the volume argument to the valid volume range.
     * @param volume to constrain
     * @return constrained volume
     */
    public static float constrainVolume(float volume)
    {
        if(Float.isNaN(volume))
        {
            return DEFAULT_VOLUME;
        }

        return Math.clamp(volume, MINIMUM_VOLUME, MAXIMUM_VOLUME);
    }

    /**
     * Indicates if audio for this card is muted.
     */
    @JsonProperty("muted")
    public boolean isMuted()
    {
        return mMuted;
    }

    public void setMuted(boolean muted)
    {
        mMuted = muted;
    }

    /**
     * Stereo pan for audio from this card.
     */
    @JsonProperty("pan")
    public AudioPan getPan()
    {
        return mPan;
    }

    /**
     * Sets the stereo pan.  A null value is replaced with the default (BOTH).
     */
    public void setPan(AudioPan pan)
    {
        mPan = pan != null ? pan : AudioPan.BOTH;
    }

    @Override
    public String toString()
    {
        return "Console Card [" + getId() + "] alias [" + getAliasUuid() + "] volume [" + getVolume() + "] muted [" +
            isMuted() + "] pan [" + getPan() + "]";
    }
}
