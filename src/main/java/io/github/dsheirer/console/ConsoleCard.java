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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Objects;
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
 * Clear audio (encryption slip) alert: the card records whether its recent calls were encrypted (see recordCall()) and
 * learns that its talkgroup is normally encrypted after ENCRYPTED_CALLS_TO_LEARN consecutive encrypted calls.  The
 * learned state is only cleared after CLEAR_CALLS_TO_UNLEARN consecutive clear calls, so that a clear call (a slip)
 * does not stop repeated slips from alerting.  The clear audio alert setting can override the learned state (always
 * or never treated as encrypted).
 *
 * The card also stores a fallback signature of the bound alias (alias list name, alias name and first talkgroup) so
 * that the card can be re-bound to its alias when the alias unique identifier is not found.
 *
 * Note: this is a plain data object that is serialized as JSON.  Modifications should be made via the
 * ConsoleLayoutManager so that constraints are enforced and listeners are notified.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"id", "aliasUuid", "signature", "volume", "muted", "pan", "clearAudioAlert", "encryptedLearned",
    "encryptedCallStreak", "clearCallStreak"})
public class ConsoleCard
{
    public static final float MINIMUM_VOLUME = 0.0f;
    public static final float MAXIMUM_VOLUME = 1.0f;
    public static final float DEFAULT_VOLUME = MAXIMUM_VOLUME;
    /**
     * Number of consecutive encrypted calls after which the card's talkgroup is learned to be normally encrypted.
     */
    public static final int ENCRYPTED_CALLS_TO_LEARN = 3;
    /**
     * Number of consecutive clear calls after which a learned (normally encrypted) talkgroup is no longer treated as
     * encrypted.
     */
    public static final int CLEAR_CALLS_TO_UNLEARN = 5;

    private String mId;
    private String mAliasUuid;
    private AliasSignature mSignature;
    private float mVolume = DEFAULT_VOLUME;
    private boolean mMuted;
    private AudioPan mPan = AudioPan.BOTH;
    private ClearAudioAlert mClearAudioAlert = ClearAudioAlert.AUTO;
    private boolean mEncryptedLearned;
    private int mEncryptedCallStreak;
    private int mClearCallStreak;

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
        copy.setSignature(getSignature());
        copy.setVolume(getVolume());
        copy.setMuted(isMuted());
        copy.setPan(getPan());
        copy.setClearAudioAlert(getClearAudioAlert());
        copy.setEncryptedLearned(isEncryptedLearned());
        copy.setEncryptedCallStreak(getEncryptedCallStreak());
        copy.setClearCallStreak(getClearCallStreak());
        return copy;
    }

    /**
     * Indicates if this card has the same settings and state as the other card.
     * @param other card to compare
     * @return true if all values are equal.
     */
    public boolean hasSameState(ConsoleCard other)
    {
        return other != null && Objects.equals(getId(), other.getId()) &&
            Objects.equals(getAliasUuid(), other.getAliasUuid()) &&
            Objects.equals(getSignature(), other.getSignature()) && getVolume() == other.getVolume() &&
            isMuted() == other.isMuted() && getPan() == other.getPan() &&
            getClearAudioAlert() == other.getClearAudioAlert() && isEncryptedLearned() == other.isEncryptedLearned() &&
            getEncryptedCallStreak() == other.getEncryptedCallStreak() &&
            getClearCallStreak() == other.getClearCallStreak();
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
     * Fallback signature of the alias that is bound to this card, or null if the signature has not been captured.
     */
    @JsonProperty("signature")
    public AliasSignature getSignature()
    {
        return mSignature;
    }

    /**
     * Sets the fallback signature of the alias that is bound to this card.
     */
    public void setSignature(AliasSignature signature)
    {
        mSignature = signature;
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

    /**
     * Clear audio (encryption slip) alert setting.
     */
    @JsonProperty("clearAudioAlert")
    public ClearAudioAlert getClearAudioAlert()
    {
        return mClearAudioAlert;
    }

    /**
     * Sets the clear audio alert setting.  A null value is replaced with the default (AUTO).
     */
    public void setClearAudioAlert(ClearAudioAlert clearAudioAlert)
    {
        mClearAudioAlert = clearAudioAlert != null ? clearAudioAlert : ClearAudioAlert.AUTO;
    }

    /**
     * Indicates if the console has learned that this card's talkgroup is normally encrypted.
     */
    @JsonProperty("encryptedLearned")
    public boolean isEncryptedLearned()
    {
        return mEncryptedLearned;
    }

    public void setEncryptedLearned(boolean encryptedLearned)
    {
        mEncryptedLearned = encryptedLearned;
    }

    /**
     * Number of consecutive encrypted calls most recently received on this card (capped at ENCRYPTED_CALLS_TO_LEARN).
     */
    @JsonProperty("encryptedCallStreak")
    public int getEncryptedCallStreak()
    {
        return mEncryptedCallStreak;
    }

    public void setEncryptedCallStreak(int encryptedCallStreak)
    {
        mEncryptedCallStreak = Math.clamp(encryptedCallStreak, 0, ENCRYPTED_CALLS_TO_LEARN);
    }

    /**
     * Number of consecutive clear calls most recently received on this card (capped at CLEAR_CALLS_TO_UNLEARN).
     */
    @JsonProperty("clearCallStreak")
    public int getClearCallStreak()
    {
        return mClearCallStreak;
    }

    public void setClearCallStreak(int clearCallStreak)
    {
        mClearCallStreak = Math.clamp(clearCallStreak, 0, CLEAR_CALLS_TO_UNLEARN);
    }

    /**
     * Records a completed call on this card for learning whether the card's talkgroup is normally encrypted.
     * @param encrypted true if the call was encrypted
     */
    public void recordCall(boolean encrypted)
    {
        if(encrypted)
        {
            setEncryptedCallStreak(getEncryptedCallStreak() + 1);
            setClearCallStreak(0);

            if(getEncryptedCallStreak() >= ENCRYPTED_CALLS_TO_LEARN)
            {
                setEncryptedLearned(true);
            }
        }
        else
        {
            setClearCallStreak(getClearCallStreak() + 1);
            setEncryptedCallStreak(0);

            if(getClearCallStreak() >= CLEAR_CALLS_TO_UNLEARN)
            {
                setEncryptedLearned(false);
            }
        }
    }

    /**
     * Indicates if the card's talkgroup is treated as normally encrypted, so that clear audio raises an alert: always
     * when the clear audio alert setting is ALWAYS, never when it is NEVER, otherwise when learned.
     */
    @JsonIgnore
    public boolean isTreatedAsEncrypted()
    {
        return switch(getClearAudioAlert())
        {
            case ALWAYS -> true;
            case NEVER -> false;
            default -> isEncryptedLearned();
        };
    }

    @Override
    public String toString()
    {
        return "Console Card [" + getId() + "] alias [" + getAliasUuid() + "] signature [" + getSignature() +
            "] volume [" + getVolume() + "] muted [" + isMuted() + "] pan [" + getPan() + "] clear audio alert [" +
            getClearAudioAlert() + "] encrypted learned [" + isEncryptedLearned() + "]";
    }
}
