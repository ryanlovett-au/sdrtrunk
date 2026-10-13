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

import io.github.dsheirer.audio.AudioEvent;
import io.github.dsheirer.audio.AudioFormats;
import io.github.dsheirer.audio.playback.AudioChannel;
import io.github.dsheirer.audio.playback.AudioProvider;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Collections;
import java.util.List;

import javax.sound.sampled.AudioFormat;

/**
 * Audio provider that plugs the console audio engine into the existing AudioOutput, so that the console reuses the
 * user's selected playback device, the source data line management and the output thread.  Each getAudio() call
 * (one per 20 ms, on the audio output thread) processes one engine frame and converts it to 16-bit little endian
 * PCM: interleaved stereo for a stereo device, or a single channel (with pan ignored, see ConsoleMixer) for a mono
 * device.
 *
 * The console has no traditional audio channels, so getAudioChannels() is empty.
 */
public class ConsoleAudioProvider extends AudioProvider
{
    private final ConsoleAudioEngine mConsoleAudioEngine;
    private final boolean mMono;
    private final float[] mLeft = new float[ConsoleAudioEngine.SAMPLES_PER_FRAME];
    private final float[] mRight = new float[ConsoleAudioEngine.SAMPLES_PER_FRAME];

    /**
     * Constructs an instance
     * @param consoleAudioEngine to provide mixed audio
     * @param mono true for a single channel output device
     */
    public ConsoleAudioProvider(ConsoleAudioEngine consoleAudioEngine, boolean mono)
    {
        mConsoleAudioEngine = consoleAudioEngine;
        mMono = mono;
        mConsoleAudioEngine.setMono(mono);
    }

    @Override
    public AudioFormat getAudioFormat()
    {
        return mMono ? AudioFormats.PCM_SIGNED_8000_HZ_16_BIT_MONO : AudioFormats.PCM_SIGNED_8000_HZ_16BITS_STEREO;
    }

    @Override
    public ByteBuffer getAudio()
    {
        mConsoleAudioEngine.processFrame(System.currentTimeMillis(), mLeft, mRight);

        int channels = mMono ? 1 : 2;
        ByteBuffer buffer = ByteBuffer.allocate(AudioChannel.SAMPLES_PER_INTERVAL * 2 * channels)
            .order(ByteOrder.LITTLE_ENDIAN);
        ShortBuffer shortBuffer = buffer.asShortBuffer();

        for(int x = 0; x < AudioChannel.SAMPLES_PER_INTERVAL; x++)
        {
            shortBuffer.put(toShort(mLeft[x]));

            if(!mMono)
            {
                shortBuffer.put(toShort(mRight[x]));
            }
        }

        return buffer;
    }

    /**
     * Converts a float sample in the range [-1, 1] to a 16-bit sample.
     */
    private static short toShort(float sample)
    {
        return (short)(Math.clamp(sample, -1.0f, 1.0f) * Short.MAX_VALUE);
    }

    @Override
    public List<AudioChannel> getAudioChannels()
    {
        return Collections.emptyList();
    }

    @Override
    public void notify(AudioEvent.Type eventType)
    {
        //No audio channels to notify
    }

    @Override
    public void dispose()
    {
        //The console audio mixer manages the engine life-cycle
    }
}
