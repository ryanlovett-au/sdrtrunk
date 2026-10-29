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
package io.github.dsheirer.configuration.transfer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerConfiguration;
import io.github.dsheirer.source.tuner.configuration.DisabledTuner;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import io.github.dsheirer.source.tuner.configuration.TunerConfigurationState;
import io.github.dsheirer.source.tuner.recording.RecordingTunerConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for tuner settings export (snapshot without recording tuners), serialization and import (merge).
 */
public class TunerConfigurationTransferTest
{
    private static AirspyTunerConfiguration airspy(String serial, int sampleRate, double ppm)
    {
        AirspyTunerConfiguration configuration = new AirspyTunerConfiguration(serial);
        configuration.setSampleRate(sampleRate);
        configuration.setFrequencyCorrection(ppm);
        return configuration;
    }

    @Test
    public void exportExcludesRecordingTuners()
    {
        TunerConfigurationState state = new TunerConfigurationState();
        state.getTunerConfigurations().add(airspy("A1", 10_000_000, 1.0));
        RecordingTunerConfiguration recording = new RecordingTunerConfiguration("recording-1");
        recording.setPath("/Users/someone/recording.wav");
        state.getTunerConfigurations().add(recording);
        state.getDisabledTuners().add(new DisabledTuner(TunerClass.AIRSPY, "A2"));
        state.getDisabledTuners().add(new DisabledTuner(TunerClass.RECORDING_TUNER, "recording-2"));

        TunerConfigurationState copy = state.copyWithoutRecordingTuners();

        assertEquals(1, copy.getTunerConfigurations().size());
        assertEquals("A1", copy.getTunerConfigurations().getFirst().getUniqueID());
        assertEquals(List.of(new DisabledTuner(TunerClass.AIRSPY, "A2")), copy.getDisabledTuners());
        assertEquals(2, state.getTunerConfigurations().size(), "Source state is unchanged");
    }

    @Test
    public void serializationRoundTrip() throws Exception
    {
        TunerConfigurationState state = new TunerConfigurationState();
        state.getTunerConfigurations().add(airspy("A1B2", 6_000_000, -2.5));
        state.getDisabledTuners().add(new DisabledTuner(TunerClass.RTL2832, "00000001"));

        //Same mappers as the tuner configuration manager
        byte[] json = new ObjectMapper().writeValueAsBytes(state);
        TunerConfigurationState read = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .readValue(json, TunerConfigurationState.class);

        TunerConfiguration configuration = read.getTunerConfigurations().getFirst();
        AirspyTunerConfiguration airspy = assertInstanceOf(AirspyTunerConfiguration.class, configuration);
        assertEquals("A1B2", airspy.getUniqueID());
        assertEquals(6_000_000, airspy.getSampleRate());
        assertEquals(-2.5, airspy.getFrequencyCorrection());
        assertEquals(TunerType.AIRSPY_R820T, airspy.getTunerType());
        assertEquals(new DisabledTuner(TunerClass.RTL2832, "00000001"), read.getDisabledTuners().getFirst());
    }

    @Test
    public void mergeReplacesMatchingAndAddsNew()
    {
        TunerConfigurationState existing = new TunerConfigurationState();
        AirspyTunerConfiguration other = airspy("OTHER", 10_000_000, 0.0);
        existing.getTunerConfigurations().add(airspy("a1b2", 10_000_000, 0.0));
        existing.getTunerConfigurations().add(other);
        RecordingTunerConfiguration localRecording = new RecordingTunerConfiguration("recording-1");
        existing.getTunerConfigurations().add(localRecording);
        existing.getDisabledTuners().add(new DisabledTuner(TunerClass.RTL2832, "0001"));

        TunerConfigurationState imported = new TunerConfigurationState();
        AirspyTunerConfiguration replacement = airspy("A1B2", 6_000_000, 3.0);
        AirspyTunerConfiguration added = airspy("NEW", 3_000_000, 1.0);
        imported.getTunerConfigurations().add(replacement);
        imported.getTunerConfigurations().add(added);
        imported.getTunerConfigurations().add(new RecordingTunerConfiguration("recording-2"));
        imported.getDisabledTuners().add(new DisabledTuner(TunerClass.RTL2832, "0001"));
        imported.getDisabledTuners().add(new DisabledTuner(TunerClass.HACKRF, "HK1"));
        imported.getDisabledTuners().add(new DisabledTuner(TunerClass.RECORDING_TUNER, "recording-2"));

        TunerConfigurationState.MergeResult result = existing.merge(imported);

        assertEquals(new TunerConfigurationState.MergeResult(1, 1, 1), result);
        assertEquals(4, existing.getTunerConfigurations().size());
        assertSame(replacement, existing.getTunerConfigurations().get(0), "Matching serial (case-insensitive) is " +
            "replaced in place");
        assertSame(other, existing.getTunerConfigurations().get(1));
        assertSame(localRecording, existing.getTunerConfigurations().get(2), "Local recording tuners are kept");
        assertSame(added, existing.getTunerConfigurations().get(3));
        assertEquals(List.of(new DisabledTuner(TunerClass.RTL2832, "0001"), new DisabledTuner(TunerClass.HACKRF,
            "HK1")), existing.getDisabledTuners());
    }

    @Test
    public void mergeNullIsNoOp()
    {
        TunerConfigurationState existing = new TunerConfigurationState();
        assertEquals(new TunerConfigurationState.MergeResult(0, 0, 0), existing.merge(null));
        assertTrue(existing.getTunerConfigurations().isEmpty());
    }
}
