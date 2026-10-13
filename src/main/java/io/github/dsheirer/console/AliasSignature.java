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
package io.github.dsheirer.console;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import java.util.Objects;

/**
 * Fallback signature for the alias that is bound to a console card: alias list name, alias name and the alias's first
 * talkgroup (protocol and value).  The signature is used to re-bind a card to its alias when the alias unique
 * identifier is not found, for example when the playlist was saved by an older version of sdrtrunk that does not
 * persist alias unique identifiers.
 *
 * The talkgroup protocol is stored as the protocol enumeration entry name (string) so that an unrecognized protocol
 * value in the layout file does not prevent the layout from loading.
 *
 * Note: this is an immutable data object that is serialized as JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"aliasListName", "aliasName", "protocol", "talkgroup"})
public class AliasSignature
{
    private final String mAliasListName;
    private final String mAliasName;
    private final String mProtocol;
    private final Integer mTalkgroup;

    /**
     * Constructs an instance.  Used by Jackson for deserialization.
     * @param aliasListName of the alias
     * @param aliasName of the alias
     * @param protocol name for the first talkgroup, or null
     * @param talkgroup value of the first talkgroup, or null
     */
    @JsonCreator
    public AliasSignature(@JsonProperty("aliasListName") String aliasListName,
                          @JsonProperty("aliasName") String aliasName,
                          @JsonProperty("protocol") String protocol,
                          @JsonProperty("talkgroup") Integer talkgroup)
    {
        mAliasListName = aliasListName;
        mAliasName = aliasName;
        mProtocol = protocol;
        mTalkgroup = talkgroup;
    }

    /**
     * Creates a signature for the alias.
     * @param alias to describe
     * @return signature or null if the alias is null.
     */
    public static AliasSignature create(Alias alias)
    {
        if(alias == null)
        {
            return null;
        }

        Talkgroup talkgroup = getFirstTalkgroup(alias);

        return new AliasSignature(alias.getAliasListName(), alias.getName(),
            talkgroup != null && talkgroup.getProtocol() != null ? talkgroup.getProtocol().name() : null,
            talkgroup != null ? talkgroup.getValue() : null);
    }

    /**
     * Finds the first talkgroup alias identifier for the alias.
     * @param alias to inspect
     * @return first talkgroup or null.
     */
    private static Talkgroup getFirstTalkgroup(Alias alias)
    {
        for(AliasID aliasID: alias.getAliasIdentifiers())
        {
            if(aliasID instanceof Talkgroup talkgroup)
            {
                return talkgroup;
            }
        }

        return null;
    }

    /**
     * Alias list name for the bound alias.
     */
    @JsonProperty("aliasListName")
    public String getAliasListName()
    {
        return mAliasListName;
    }

    /**
     * Name of the bound alias.
     */
    @JsonProperty("aliasName")
    public String getAliasName()
    {
        return mAliasName;
    }

    /**
     * Protocol (enumeration entry name) of the bound alias's first talkgroup, or null.
     */
    @JsonProperty("protocol")
    public String getProtocol()
    {
        return mProtocol;
    }

    /**
     * Value of the bound alias's first talkgroup, or null.
     */
    @JsonProperty("talkgroup")
    public Integer getTalkgroup()
    {
        return mTalkgroup;
    }

    /**
     * Indicates if this signature has a talkgroup protocol and value.
     */
    @JsonIgnore
    public boolean hasTalkgroup()
    {
        return mProtocol != null && mTalkgroup != null;
    }

    /**
     * Indicates if the alias has the same alias list name as this signature.
     */
    public boolean matchesList(Alias alias)
    {
        return alias != null && mAliasListName != null && mAliasListName.equals(alias.getAliasListName());
    }

    /**
     * Indicates if the alias has the same alias name as this signature.
     */
    public boolean matchesName(Alias alias)
    {
        return alias != null && mAliasName != null && mAliasName.equals(alias.getName());
    }

    /**
     * Indicates if the alias has a talkgroup alias identifier with the same protocol and value as this signature.
     */
    public boolean matchesTalkgroup(Alias alias)
    {
        if(alias == null || !hasTalkgroup())
        {
            return false;
        }

        for(AliasID aliasID: alias.getAliasIdentifiers())
        {
            if(aliasID instanceof Talkgroup talkgroup && talkgroup.getProtocol() != null &&
                mProtocol.equals(talkgroup.getProtocol().name()) && mTalkgroup == talkgroup.getValue())
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean equals(Object o)
    {
        if(this == o)
        {
            return true;
        }

        if(!(o instanceof AliasSignature other))
        {
            return false;
        }

        return Objects.equals(mAliasListName, other.mAliasListName) && Objects.equals(mAliasName, other.mAliasName) &&
            Objects.equals(mProtocol, other.mProtocol) && Objects.equals(mTalkgroup, other.mTalkgroup);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(mAliasListName, mAliasName, mProtocol, mTalkgroup);
    }

    @Override
    public String toString()
    {
        return "list [" + mAliasListName + "] alias [" + mAliasName + "]" +
            (hasTalkgroup() ? " talkgroup [" + mProtocol + ":" + mTalkgroup + "]" : "");
    }
}
