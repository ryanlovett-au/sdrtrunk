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
import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of console mixer playback state over a short measurement interval (a few 20 ms frames): which
 * cards currently have a call in the mixer, which of those are audible, and the peak and RMS level of each card and
 * of each output channel.  Levels are linear sample magnitudes in the range 0.0 to 1.0.
 *
 * Card levels are measured after the card's volume and mute are applied, before pan and the output limiter.  Output
 * channel levels are measured after the limiter.  A card appears in the snapshot while it has a call in the mixer,
 * including while the call is buffering, muted or at zero volume.
 *
 * @param timestamp of the end of the measurement interval, milliseconds since epoch
 * @param leftPeak left output channel peak level
 * @param leftRms left output channel RMS level
 * @param rightPeak right output channel peak level
 * @param rightRms right output channel RMS level
 * @param cardLevels for each card with a call in the mixer
 */
public record ConsoleAudioLevels(long timestamp, float leftPeak, float leftRms, float rightPeak, float rightRms,
                                 List<CardLevel> cardLevels)
{
    /**
     * Constructs an instance
     */
    public ConsoleAudioLevels
    {
        cardLevels = cardLevels != null ? Collections.unmodifiableList(cardLevels) : Collections.emptyList();
    }

    /**
     * Indicates if any card is currently audible.
     */
    public boolean isAnyAudible()
    {
        for(CardLevel cardLevel: cardLevels)
        {
            if(cardLevel.audible())
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Finds the level for the card with the specified identifier.
     * @param cardId to find
     * @return level or null when the card has no call in the mixer.
     */
    public CardLevel getCardLevel(String cardId)
    {
        for(CardLevel cardLevel: cardLevels)
        {
            if(cardLevel.cardId().equals(cardId))
            {
                return cardLevel;
            }
        }

        return null;
    }

    /**
     * Playback state and level for a single console card.  When more than one call for the card is in the mixer at
     * the same time, the levels are combined.
     *
     * @param cardId console card identifier
     * @param pan output channel routing for the card
     * @param audible true when at least one call for the card is playing (not buffering) with a non-zero gain
     * (not muted and volume above zero)
     * @param voiceCount number of calls for this card in the mixer
     * @param peak post-gain peak level
     * @param rms post-gain RMS level
     */
    public record CardLevel(String cardId, AudioPan pan, boolean audible, int voiceCount, float peak, float rms)
    {
    }
}
