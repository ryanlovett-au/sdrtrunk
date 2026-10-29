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

import java.util.Map;
import java.util.TreeMap;
import java.util.prefs.AbstractPreferences;

/**
 * In-memory Java Preferences implementation for tests, so that tests never read or write the real user preferences
 * store (which is not redirected by the test task's user.home setting).
 */
public class InMemoryPreferences extends AbstractPreferences
{
    private final Map<String,String> mValues = new TreeMap<>();
    private final Map<String,InMemoryPreferences> mChildren = new TreeMap<>();

    /**
     * Constructs a root node
     */
    public InMemoryPreferences()
    {
        super(null, "");
    }

    /**
     * Constructs a child node
     */
    private InMemoryPreferences(InMemoryPreferences parent, String name)
    {
        super(parent, name);
    }

    @Override
    protected void putSpi(String key, String value)
    {
        mValues.put(key, value);
    }

    @Override
    protected String getSpi(String key)
    {
        return mValues.get(key);
    }

    @Override
    protected void removeSpi(String key)
    {
        mValues.remove(key);
    }

    @Override
    protected void removeNodeSpi()
    {
        mValues.clear();

        if(parent() instanceof InMemoryPreferences parent)
        {
            parent.mChildren.remove(name());
        }
    }

    @Override
    protected String[] keysSpi()
    {
        return mValues.keySet().toArray(new String[0]);
    }

    @Override
    protected String[] childrenNamesSpi()
    {
        return mChildren.keySet().toArray(new String[0]);
    }

    @Override
    protected AbstractPreferences childSpi(String name)
    {
        return mChildren.computeIfAbsent(name, childName -> new InMemoryPreferences(this, childName));
    }

    @Override
    protected void syncSpi()
    {
    }

    @Override
    protected void flushSpi()
    {
    }
}
