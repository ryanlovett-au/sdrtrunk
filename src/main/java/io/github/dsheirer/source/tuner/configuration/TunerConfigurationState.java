/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
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

package io.github.dsheirer.source.tuner.configuration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.TunerType;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistable instance of tuner configurations and disabled tuners
 */
@JsonSerialize
public class TunerConfigurationState
{
    private List<DisabledTuner> mDisabledTuners = new ArrayList();
    private List<TunerConfiguration> mTunerConfigurations = new ArrayList<>();

    public TunerConfigurationState()
    {
    }

    /**
     * List of currently disabled tuners
     */
    public List<DisabledTuner> getDisabledTuners()
    {
        return mDisabledTuners;
    }

    /**
     * Sets the list of disabled tuners
     */
    public void setDisabledTuners(List<DisabledTuner> disabledTuners)
    {
        mDisabledTuners = disabledTuners;
    }

    /**
     * Get list of tuner configurations
     */
    public List<TunerConfiguration> getTunerConfigurations()
    {
        return mTunerConfigurations;
    }

    /**
     * Sets the list of tuner configurations
     */
    public void setTunerConfigurations(List<TunerConfiguration> tunerConfigurations)
    {
        mTunerConfigurations = tunerConfigurations;
    }

    /**
     * Creates a copy of this state (new lists that contain the same configuration instances) without the recording
     * tuner configurations and disabled recording tuners, which refer to recording files on this computer.
     * @return copy without recording tuners.
     */
    @JsonIgnore
    public TunerConfigurationState copyWithoutRecordingTuners()
    {
        TunerConfigurationState copy = new TunerConfigurationState();

        for(TunerConfiguration configuration: nonNull(mTunerConfigurations))
        {
            if(isTransferable(configuration))
            {
                copy.getTunerConfigurations().add(configuration);
            }
        }

        for(DisabledTuner disabledTuner: nonNull(mDisabledTuners))
        {
            if(isTransferable(disabledTuner))
            {
                copy.getDisabledTuners().add(disabledTuner);
            }
        }

        return copy;
    }

    /**
     * Merges the (imported) state into this state: a tuner configuration replaces the configuration that has the same
     * tuner type and unique ID (serial number), other configurations are added, and disabled tuners are added to the
     * disabled tuner list when not already present.  Recording tuner configurations and disabled recording tuners are
     * ignored, since they refer to recording files on the other computer.
     *
     * @param imported state to merge into this state.
     * @return counts of the replaced and added configurations and the added disabled tuners.
     */
    @JsonIgnore
    public MergeResult merge(TunerConfigurationState imported)
    {
        int replaced = 0;
        int added = 0;
        int disabledAdded = 0;

        if(mTunerConfigurations == null)
        {
            mTunerConfigurations = new ArrayList<>();
        }

        if(mDisabledTuners == null)
        {
            mDisabledTuners = new ArrayList<>();
        }

        if(imported != null)
        {
            for(TunerConfiguration configuration: nonNull(imported.getTunerConfigurations()))
            {
                if(!isTransferable(configuration))
                {
                    continue;
                }

                int index = indexOf(configuration.getTunerType(), configuration.getUniqueID());

                if(index >= 0)
                {
                    mTunerConfigurations.set(index, configuration);
                    replaced++;
                }
                else
                {
                    mTunerConfigurations.add(configuration);
                    added++;
                }
            }

            for(DisabledTuner disabledTuner: nonNull(imported.getDisabledTuners()))
            {
                if(isTransferable(disabledTuner) && !mDisabledTuners.contains(disabledTuner))
                {
                    mDisabledTuners.add(disabledTuner);
                    disabledAdded++;
                }
            }
        }

        return new MergeResult(replaced, added, disabledAdded);
    }

    /**
     * Index of the configuration that has the tuner type and unique ID (case-insensitive), or -1.
     */
    private int indexOf(TunerType tunerType, String uniqueID)
    {
        for(int x = 0; x < mTunerConfigurations.size(); x++)
        {
            TunerConfiguration existing = mTunerConfigurations.get(x);

            if(existing != null && existing.getTunerType() == tunerType && existing.getUniqueID() != null &&
                existing.getUniqueID().equalsIgnoreCase(uniqueID))
            {
                return x;
            }
        }

        return -1;
    }

    /**
     * Indicates if the configuration can be transferred to another computer: it identifies a (non-recording) tuner.
     */
    private static boolean isTransferable(TunerConfiguration configuration)
    {
        return configuration != null && configuration.getUniqueID() != null &&
            configuration.getTunerType() != null && configuration.getTunerType() != TunerType.RECORDING;
    }

    /**
     * Indicates if the disabled tuner can be transferred to another computer: it identifies a (non-recording) tuner.
     */
    private static boolean isTransferable(DisabledTuner disabledTuner)
    {
        return disabledTuner != null && disabledTuner.tunerClass() != null && disabledTuner.id() != null &&
            disabledTuner.tunerClass() != TunerClass.RECORDING_TUNER;
    }

    /**
     * The list argument or an empty list when the argument is null.
     */
    private static <T> List<T> nonNull(List<T> list)
    {
        return list != null ? list : List.of();
    }

    /**
     * Result of merging an imported tuner configuration state.
     * @param replaced count of tuner configurations that replaced an existing configuration
     * @param added count of tuner configurations that were added
     * @param disabledAdded count of disabled tuners that were added
     */
    public record MergeResult(int replaced, int added, int disabledAdded)
    {
    }
}
