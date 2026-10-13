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

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.sample.Listener;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the console card (if any) for a call, based on the call's TO identifier and the alias list of the channel
 * that carries the call.  This is the single place that decides whether a call belongs to the console, used by both
 * the talkgroup (grant) filter and the console audio mixer.
 *
 * Patch group TO identifiers resolve to a card when the patch group or any patched talkgroup has a card, because
 * AliasList.getAliases() returns the aliases for the patch group and each patched talkgroup.
 *
 * Audio mute: a card's audio is muted when the card is muted or when the card's group is muted (see isAudioMuted()).
 *
 * Thread safe: lookups use an immutable snapshot that is rebuilt whenever the console layout changes (a card settings
 * change, e.g. encryption learning after every call, only replaces that card), so lookups from decoder threads never
 * block on the layout manager.
 */
public class ConsoleCardResolver implements Listener<ConsoleLayoutEvent>
{
    private final ConsoleLayoutManager mConsoleLayoutManager;
    private volatile Map<String,ConsoleCard> mCardsByAliasUuid = Collections.emptyMap();
    private volatile Set<String> mGroupMutedCardIds = Collections.emptySet();
    private volatile boolean mConsoleModeEnabled;
    private volatile boolean mTalkgroupFilterEnabled;

    /**
     * Constructs an instance and registers for console layout change notifications.
     * @param consoleLayoutManager providing the console layout
     */
    public ConsoleCardResolver(ConsoleLayoutManager consoleLayoutManager)
    {
        mConsoleLayoutManager = consoleLayoutManager;
        mConsoleLayoutManager.addListener(this);
        refresh();
    }

    /**
     * Unregisters from console layout change notifications.
     */
    public void dispose()
    {
        mConsoleLayoutManager.removeListener(this);
    }

    @Override
    public synchronized void receive(ConsoleLayoutEvent event)
    {
        //Card setting changes (e.g. encryption learning after every call) replace the card without copying the layout
        if(event.getType() == ConsoleLayoutEvent.Type.CARD_CHANGED && event.hasCard() &&
            event.getCard().getAliasUuid() != null)
        {
            Map<String,ConsoleCard> cards = new HashMap<>(mCardsByAliasUuid);
            cards.put(event.getCard().getAliasUuid(), event.getCard());
            mCardsByAliasUuid = Collections.unmodifiableMap(cards);
            return;
        }

        refresh();
    }

    /**
     * Rebuilds the alias to card lookup snapshot from the current console layout.
     */
    private synchronized void refresh()
    {
        ConsoleLayout layout = mConsoleLayoutManager.getLayout();
        Map<String,ConsoleCard> cards = new HashMap<>();
        Set<String> groupMutedCardIds = new HashSet<>();

        for(ConsoleGroup group: layout.getGroups())
        {
            for(ConsoleCard card: group.getCards())
            {
                if(card.getAliasUuid() != null)
                {
                    cards.put(card.getAliasUuid(), card);
                }

                if(group.isMuted() && card.getId() != null)
                {
                    groupMutedCardIds.add(card.getId());
                }
            }
        }

        mCardsByAliasUuid = Collections.unmodifiableMap(cards);
        mGroupMutedCardIds = Collections.unmodifiableSet(groupMutedCardIds);
        mConsoleModeEnabled = layout.isConsoleModeEnabled();
        mTalkgroupFilterEnabled = layout.isTalkgroupFilterEnabled();
    }

    /**
     * Indicates if console mode is enabled.
     */
    public boolean isConsoleModeEnabled()
    {
        return mConsoleModeEnabled;
    }

    /**
     * Indicates if the talkgroup filter is currently active: console mode and the talkgroup filter are both enabled.
     */
    public boolean isFilterActive()
    {
        return mConsoleModeEnabled && mTalkgroupFilterEnabled;
    }

    /**
     * Indicates if the card's group is muted.
     * @param cardId of the card
     * @return true if the card is in a muted group.
     */
    public boolean isGroupMuted(String cardId)
    {
        return cardId != null && mGroupMutedCardIds.contains(cardId);
    }

    /**
     * Indicates if the card's audio is muted: the card is muted, or the card's group is muted.
     * @param card to test
     * @return true if no audio should be played for the card (false for a null card).
     */
    public boolean isAudioMuted(ConsoleCard card)
    {
        return card != null && (card.isMuted() || isGroupMuted(card.getId()));
    }

    /**
     * Finds the console card for the call described by the identifier collection.
     * @param aliasList for the channel carrying the call
     * @param identifierCollection for the call
     * @return snapshot of the matching card or null.
     */
    public ConsoleCard getCard(AliasList aliasList, IdentifierCollection identifierCollection)
    {
        if(identifierCollection == null)
        {
            return null;
        }

        return getCard(aliasList, identifierCollection.getToIdentifier());
    }

    /**
     * Finds the console card for the call with the specified TO identifier.
     * @param aliasList for the channel carrying the call
     * @param toIdentifier talkgroup, patch group or radio identifier
     * @return snapshot of the matching card or null.
     */
    public ConsoleCard getCard(AliasList aliasList, Identifier toIdentifier)
    {
        Map<String,ConsoleCard> cards = mCardsByAliasUuid;

        if(aliasList == null || toIdentifier == null || cards.isEmpty())
        {
            return null;
        }

        List<Alias> aliases = aliasList.getAliases(toIdentifier);

        for(Alias alias: aliases)
        {
            ConsoleCard card = cards.get(alias.getUuid());

            if(card != null)
            {
                return card;
            }
        }

        return null;
    }

    /**
     * Indicates if a call with the specified TO identifier should be processed.  Always true when the talkgroup
     * filter is not active, otherwise true only when the call resolves to a console card.
     * @param aliasList for the channel carrying the call
     * @param toIdentifier talkgroup, patch group or radio identifier
     * @return true if the call should be processed
     */
    public boolean isAllowed(AliasList aliasList, Identifier toIdentifier)
    {
        return !isFilterActive() || getCard(aliasList, toIdentifier) != null;
    }

    /**
     * Indicates if a call described by the identifier collection should be processed.
     * @see #isAllowed(AliasList, Identifier)
     */
    public boolean isAllowed(AliasList aliasList, IdentifierCollection identifierCollection)
    {
        return !isFilterActive() || getCard(aliasList, identifierCollection) != null;
    }
}
