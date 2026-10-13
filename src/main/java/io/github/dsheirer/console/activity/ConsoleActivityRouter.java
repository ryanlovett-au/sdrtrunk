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
package io.github.dsheirer.console.activity;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.console.ConsoleCard;
import io.github.dsheirer.console.ConsoleCardResolver;
import io.github.dsheirer.console.ConsoleLayoutEvent;
import io.github.dsheirer.console.ConsoleLayoutManager;
import io.github.dsheirer.console.audio.ConsoleAudioLevels;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.traffic.TrafficChannelManager;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes decode (call) events to console cards and maintains the per-card activity state, recent talkers and the
 * console activity log that are shown in the console window.
 *
 * Registered with the ChannelProcessingManager as a decode event listener.  For each voice call event, the call's alias
 * list (from the alias list configuration identifier) and TO identifier are resolved to a console card via the
 * ConsoleCardResolver.
 *
 * Activity logs: calls that resolve to a card are logged in the console activity log (ACTIVITY_LOG_LIMIT entries).
 * The all-traffic log (ALL_TRAFFIC_LOG_LIMIT entries) contains those calls plus the voice calls that do not resolve to a
 * card, with the status IGNORED when the traffic channel manager did not allocate a traffic channel for the call (the
 * event details carry TrafficChannelManager.NOT_ON_CONSOLE), ENCRYPTED for an encrypted call, or NOT_ON_CONSOLE
 * otherwise (decoded, e.g. with the talkgroup filter off or on a conventional channel, but not on a card).  Both logs
 * have one entry per call event, which is updated in place as the call progresses.
 *
 * Encryption learning: each call on a card is recorded once with ConsoleLayoutManager.recordCardCall() (encrypted if
 * the call was flagged as encrypted at any time) when the call completes: when the card's next call starts, or
 * CALL_COMPLETE_MS after the call's last update.  Recording is done outside of this router's lock.
 *
 * Clear audio alerts: the console mixer reports clear audio on a card that is treated as normally encrypted
 * (getClearAudioAlertListener()).  The card's current call is flagged: the card activity reports clearAlert while that
 * call is active or hanging, and the call's log entries show the CLEAR status.
 *
 * Call progress: decoders and traffic channel managers broadcast the same (mutable) call event object repeatedly
 * while the call is in progress, each time with a later end timestamp, and there is no explicit 'call ended' flag on
 * the event.  This router therefore tracks each call event by object identity and treats an event as progressing when
 * it is first seen or when its end timestamp advances.  A card is ACTIVE (or ENCRYPTED) while its calls progress and
 * for the call update timeout after the last progress update.  After that the call is considered ended and the card
 * shows HANG for the console layout's activity hang time, and then IDLE.  Re-broadcasts of an event without a later end
 * timestamp (e.g. late control channel updates after the call completed) update the talker details but do not extend
 * the call.
 *
 * Console mixer levels are a secondary activity signal: a card that has a call in the console mixer (including muted
 * calls) is ACTIVE regardless of call event updates, which extends the activity for the duration of buffered audio.
 *
 * Time is provided by an injectable clock (receipt time, not event timestamps) so that replayed recordings and unit
 * tests behave consistently.
 *
 * Thread safety: events and mixer levels are received on decoder and mixer threads and state is guarded by this
 * instance's lock.  The console window polls getSnapshot() (e.g. at 10 Hz) to obtain an immutable snapshot, so no
 * per-event UI work is scheduled.
 */
public class ConsoleActivityRouter implements Listener<IDecodeEvent>
{
    private final static Logger mLog = LoggerFactory.getLogger(ConsoleActivityRouter.class);

    /**
     * Time after the last call progress update after which the call is considered ended.
     */
    public static final long DEFAULT_CALL_UPDATE_TIMEOUT_MS = 1000;
    /**
     * Time after the last mixer level report containing the card after which the card's audio is considered ended.
     * The mixer reports levels about every 40 ms.
     */
    public static final long AUDIO_PRESENCE_TIMEOUT_MS = 250;
    /**
     * Maximum number of recent talkers retained for each card.
     */
    public static final int RECENT_TALKER_LIMIT = 5;
    /**
     * Maximum number of activity log entries retained.
     */
    public static final int ACTIVITY_LOG_LIMIT = 50;
    /**
     * Maximum number of all-traffic log entries retained.
     */
    public static final int ALL_TRAFFIC_LOG_LIMIT = 200;
    /**
     * Time after the last update of a call event after which the call is considered complete for encryption learning.
     */
    public static final long CALL_COMPLETE_MS = 5000;
    /**
     * Separator between the site name and the decoded site identifier, e.g. "North Hill · 1-3".
     */
    public static final String SITE_SEPARATOR = " · ";
    /**
     * Time after the last update after which a call event is no longer tracked.
     */
    private static final long CALL_RETENTION_MS = 30000;

    /**
     * Call event types that are routed to console cards.
     */
    private static final Set<DecodeEventType> ROUTED_EVENT_TYPES = EnumSet.of(DecodeEventType.CALL,
        DecodeEventType.CALL_ENCRYPTED, DecodeEventType.CALL_GROUP, DecodeEventType.CALL_GROUP_ENCRYPTED,
        DecodeEventType.CALL_PATCH_GROUP, DecodeEventType.CALL_PATCH_GROUP_ENCRYPTED, DecodeEventType.CALL_INTERCONNECT,
        DecodeEventType.CALL_INTERCONNECT_ENCRYPTED, DecodeEventType.CALL_UNIT_TO_UNIT,
        DecodeEventType.CALL_UNIT_TO_UNIT_ENCRYPTED, DecodeEventType.CALL_IN_PROGRESS, DecodeEventType.CALL_DETECT);

    private final ConsoleLayoutManager mConsoleLayoutManager;
    private final ConsoleCardResolver mConsoleCardResolver;
    private final AliasModel mAliasModel;
    private final LongSupplier mClock;
    private final long mCallUpdateTimeoutMs;
    private final Listener<ConsoleLayoutEvent> mLayoutListener = this::layoutChanged;
    private final Listener<ConsoleAudioLevels> mLevelListener = this::receiveLevels;
    private final Listener<String> mClearAudioAlertListener = this::receiveClearAudioAlert;
    private final Map<IDecodeEvent,CallTrack> mCalls = new IdentityHashMap<>();
    private final Map<String,CardTrack> mCards = new HashMap<>();
    private final List<ConsoleActivityLogEntry> mActivityLog = new ArrayList<>();
    private final List<ConsoleActivityLogEntry> mAllTrafficLog = new ArrayList<>();
    private List<ConsoleActivityLogEntry> mActivityLogCopy = List.of();
    private List<ConsoleActivityLogEntry> mAllTrafficLogCopy = List.of();
    private long mLogCopyVersion;
    private long mLogSequence;
    private long mLogVersion;
    private volatile long mHangTimeMs;
    private volatile IDecodedSiteResolver mDecodedSiteResolver;

    /**
     * Constructs an instance that uses the system clock and the default call update timeout, and registers for console
     * layout change notifications.
     * @param consoleLayoutManager for the console layout and activity hang time
     * @param consoleCardResolver to resolve calls to console cards
     * @param aliasModel to look up the alias list for each call
     */
    public ConsoleActivityRouter(ConsoleLayoutManager consoleLayoutManager, ConsoleCardResolver consoleCardResolver,
                                 AliasModel aliasModel)
    {
        this(consoleLayoutManager, consoleCardResolver, aliasModel, System::currentTimeMillis,
            DEFAULT_CALL_UPDATE_TIMEOUT_MS);
    }

    /**
     * Constructs an instance and registers for console layout change notifications.
     * @param consoleLayoutManager for the console layout and activity hang time
     * @param consoleCardResolver to resolve calls to console cards
     * @param aliasModel to look up the alias list for each call
     * @param clock providing the current time in milliseconds
     * @param callUpdateTimeoutMs time after the last call progress update after which the call is considered ended
     */
    public ConsoleActivityRouter(ConsoleLayoutManager consoleLayoutManager, ConsoleCardResolver consoleCardResolver,
                                 AliasModel aliasModel, LongSupplier clock, long callUpdateTimeoutMs)
    {
        mConsoleLayoutManager = consoleLayoutManager;
        mConsoleCardResolver = consoleCardResolver;
        mAliasModel = aliasModel;
        mClock = clock;
        mCallUpdateTimeoutMs = callUpdateTimeoutMs;
        mHangTimeMs = consoleLayoutManager.getActivityHangTimeMs();
        mConsoleLayoutManager.addListener(mLayoutListener);
    }

    /**
     * Unregisters from console layout change notifications.
     */
    public void dispose()
    {
        mConsoleLayoutManager.removeListener(mLayoutListener);
    }

    /**
     * Sets the resolver for the decoded site identifier of the control channel that each call is received through, to
     * enrich the card and activity log site (e.g. "North Hill · 1-3") when the call event does not carry decoded site
     * identifiers.
     * @param decodedSiteResolver to use, or null for none
     */
    public void setDecodedSiteResolver(IDecodedSiteResolver decodedSiteResolver)
    {
        mDecodedSiteResolver = decodedSiteResolver;
    }

    /**
     * Listener to register with the console audio mixer (ConsoleAudioMixer.addLevelListener()) to receive the mixer
     * level reports that are used as a secondary activity signal.
     */
    public Listener<ConsoleAudioLevels> getLevelListener()
    {
        return mLevelListener;
    }

    /**
     * Listener to register with the console audio mixer (ConsoleAudioMixer.addClearAudioAlertListener()) to receive
     * the card identifier of each clear audio (encryption slip) alert.
     */
    public Listener<String> getClearAudioAlertListener()
    {
        return mClearAudioAlertListener;
    }

    /**
     * Indicates if the event type is a call event type that is routed to console cards.
     */
    public static boolean isRoutedEventType(DecodeEventType type)
    {
        return type != null && ROUTED_EVENT_TYPES.contains(type);
    }

    /**
     * Indicates if the call is encrypted: the event type is an encrypted call type or the event has an encryption key
     * identifier that indicates encryption.
     */
    public static boolean isEncrypted(IDecodeEvent event)
    {
        if(DecodeEventType.VOICE_CALLS_ENCRYPTED.contains(event.getEventType()))
        {
            return true;
        }

        IdentifierCollection identifierCollection = event.getIdentifierCollection();

        return identifierCollection != null &&
            identifierCollection.getEncryptionIdentifier() instanceof EncryptionKeyIdentifier eki && eki.isEncrypted();
    }

    /**
     * Indicates if the call event was ignored by a traffic channel manager (no traffic channel allocated) because the
     * call is not on the console: the event details carry the TrafficChannelManager.NOT_ON_CONSOLE indicator.
     */
    public static boolean isIgnoredByTrafficChannelManager(IDecodeEvent event)
    {
        String details = event.getDetails();
        return details != null && details.contains(TrafficChannelManager.NOT_ON_CONSOLE);
    }

    /**
     * Processes a decode event.  Voice call events that resolve to a console card update the card's activity and both
     * activity logs.  Other voice call events are logged in the all-traffic log.
     */
    @Override
    public void receive(IDecodeEvent event)
    {
        try
        {
            if(event == null || !isRoutedEventType(event.getEventType()))
            {
                return;
            }

            IdentifierCollection identifierCollection = event.getIdentifierCollection();

            if(identifierCollection == null)
            {
                return;
            }

            Identifier to = identifierCollection.getToIdentifier();

            if(to == null)
            {
                return;
            }

            AliasList aliasList = mAliasModel.getAliasList(identifierCollection);
            ConsoleCard card = aliasList != null ? mConsoleCardResolver.getCard(aliasList, to) : null;
            CallDetails details = getCallDetails(event, identifierCollection, aliasList, to, card,
                mDecodedSiteResolver);
            List<CompletedCall> completed = update(event, card, details);
            recordCompletedCalls(completed);
        }
        catch(Exception e)
        {
            mLog.error("Error routing decode event to the console", e);
        }
    }

    /**
     * Records completed calls for encryption learning.  Invoked without holding this router's lock.
     */
    private void recordCompletedCalls(List<CompletedCall> completed)
    {
        for(CompletedCall call: completed)
        {
            mConsoleLayoutManager.recordCardCall(call.cardId(), call.encrypted());
        }
    }

    /**
     * Extracts the call details (talker, site, talkgroup and talkgroup display name) from the event.
     * @param aliasList for the call, or null
     * @param card for the call, or null when the call is not on the console
     */
    private static CallDetails getCallDetails(IDecodeEvent event, IdentifierCollection identifierCollection,
                                              AliasList aliasList, Identifier to, ConsoleCard card,
                                              IDecodedSiteResolver decodedSiteResolver)
    {
        Identifier from = identifierCollection.getFromIdentifier();
        String talkerId = null;
        String talkerName = null;

        if(from != null)
        {
            if(from.getForm() == Form.TALKER_ALIAS)
            {
                talkerName = from.toString();
            }
            else
            {
                talkerId = from.toString();
                talkerName = aliasList != null ? getAliasName(aliasList.getAliases(from), null) : null;
            }
        }

        if(talkerName == null)
        {
            List<Identifier> talkerAliases = identifierCollection.getIdentifiers(Form.TALKER_ALIAS);

            if(!talkerAliases.isEmpty())
            {
                talkerName = talkerAliases.getFirst().toString();
            }
        }

        String talkgroupName;

        if(card != null)
        {
            talkgroupName = aliasList != null ? getAliasName(aliasList.getAliases(to), card.getAliasUuid()) : null;

            if(talkgroupName == null && card.getSignature() != null)
            {
                talkgroupName = card.getSignature().getAliasName();
            }
        }
        else
        {
            talkgroupName = aliasList != null ? getAliasName(aliasList.getAliases(to), null) : null;

            if(talkgroupName == null)
            {
                talkgroupName = to.toString();
            }
        }

        return new CallDetails(talkerId, talkerName, getSite(identifierCollection, decodedSiteResolver), to.toString(),
            talkgroupName, isEncrypted(event), isIgnoredByTrafficChannelManager(event));
    }

    /**
     * Finds the alias name from the list of aliases.
     * @param aliases to search
     * @param aliasUuid of the preferred alias, or null for the first alias
     * @return alias name or null.
     */
    private static String getAliasName(List<Alias> aliases, String aliasUuid)
    {
        if(aliases == null)
        {
            return null;
        }

        for(Alias alias: aliases)
        {
            if(alias != null && (aliasUuid == null || aliasUuid.equals(alias.getUuid())))
            {
                return alias.getName();
            }
        }

        return null;
    }

    /**
     * Describes the site where the call is received: the site name from the channel configuration (or the system or
     * channel name when the site is not configured), followed by any decoded RFSS and site identifiers carried in the
     * identifier collection (e.g. "North Hill · 1-3").
     *
     * @param identifierCollection for the call
     * @return site description or null.
     */
    public static String getSite(IdentifierCollection identifierCollection)
    {
        return getSite(identifierCollection, null);
    }

    /**
     * Describes the site where the call is received: the site name from the channel configuration (or the system or
     * channel name when the site is not configured), followed by the decoded site identifier: from the decoded RFSS
     * and site identifiers carried in the identifier collection, otherwise from the resolver (the decoded site of the
     * control channel that granted the call).  For example "North Hill · 1-3".
     *
     * @param identifierCollection for the call
     * @param decodedSiteResolver for the decoded site of the call's control channel, or null
     * @return site description or null.
     */
    public static String getSite(IdentifierCollection identifierCollection, IDecodedSiteResolver decodedSiteResolver)
    {
        String site = null;
        String system = null;
        String channel = null;
        String rfss = null;
        String decodedSite = null;

        for(Identifier identifier: identifierCollection.getIdentifiers())
        {
            if(identifier instanceof SiteConfigurationIdentifier sci && sci.isValid())
            {
                site = sci.getValue();
            }
            else if(identifier instanceof SystemConfigurationIdentifier sci && sci.isValid())
            {
                system = sci.getValue();
            }
            else if(identifier instanceof ChannelNameConfigurationIdentifier cnci && cnci.isValid())
            {
                channel = cnci.getValue();
            }
            else if(identifier.getIdentifierClass() == IdentifierClass.NETWORK && identifier.getValue() != null)
            {
                if(identifier.getForm() == Form.RF_SUBSYSTEM)
                {
                    rfss = identifier.toString();
                }
                else if(identifier.getForm() == Form.SITE)
                {
                    decodedSite = identifier.toString();
                }
            }
        }

        String name = site != null ? site : (system != null ? system : channel);
        String decoded = decodedSite != null ? (rfss != null ? rfss + "-" + decodedSite : decodedSite) : null;

        if(decoded == null && decodedSiteResolver != null)
        {
            try
            {
                decoded = decodedSiteResolver.getDecodedSiteId(system, site, channel);
            }
            catch(Exception e)
            {
                mLog.debug("Error resolving the decoded site for a call", e);
            }
        }

        if(name != null && decoded != null)
        {
            return name + SITE_SEPARATOR + decoded;
        }

        return name != null ? name : decoded;
    }

    /**
     * Updates the call and card state and the activity logs for the call event.
     * @param event call event
     * @param card for the call, or null when the call is not on the console
     * @param details of the call
     * @return calls that completed, for encryption learning
     */
    private synchronized List<CompletedCall> update(IDecodeEvent event, ConsoleCard card, CallDetails details)
    {
        long now = mClock.getAsLong();
        List<CompletedCall> completed = new ArrayList<>();
        purgeCalls(now, completed);

        String cardId = card != null ? card.getId() : null;
        CallTrack call = mCalls.get(event);
        boolean progress;

        if(call == null || !Objects.equals(call.mCardId, cardId))
        {
            if(call != null)
            {
                completeCall(call, completed);
            }

            call = new CallTrack(cardId);
            mCalls.put(event, call);
            progress = true;
        }
        else
        {
            progress = event.getTimeEnd() > call.mLastEventEnd;
        }

        call.mLastEventEnd = Math.max(call.mLastEventEnd, event.getTimeEnd());
        call.mLastUpdate = now;
        call.mEncrypted |= details.encrypted();

        if(card != null)
        {
            updateCard(call, card, details, progress, now, completed);
        }

        updateLog(call, card, details, now);
        return completed;
    }

    /**
     * Updates the card state for the call.  Lock must be held.
     */
    private void updateCard(CallTrack call, ConsoleCard card, CallDetails details, boolean progress, long now,
                            List<CompletedCall> completed)
    {
        CardTrack cardTrack = mCards.computeIfAbsent(card.getId(), id -> new CardTrack());

        if(progress)
        {
            cardTrack.mLastCallProgress = now;

            //A new call on the card completes the card's previous call
            if(cardTrack.mCurrentCall != null && cardTrack.mCurrentCall != call)
            {
                completeCall(cardTrack.mCurrentCall, completed);
            }
        }

        //Only update the card's current call details from the card's most recently progressing call, so that a late
        //update for an ended call does not replace the details of a newer call
        if(progress || cardTrack.mCurrentCall == call)
        {
            cardTrack.mCurrentCall = call;
            cardTrack.mEncrypted = details.encrypted();
            cardTrack.mSite = details.site();
            cardTrack.mTalkgroup = details.talkgroup();

            if(details.talkerId() != null || details.talkerName() != null)
            {
                cardTrack.mTalkerId = details.talkerId();
                cardTrack.mTalkerName = details.talkerName();
            }
        }

        if(details.talkerId() != null)
        {
            cardTrack.addRecentTalker(new RecentTalker(details.talkerId(), details.talkerName(), now));
        }
    }

    /**
     * Marks the call as complete and adds it to the completed calls for encryption learning, once per call.  Calls
     * that are not on the console are not recorded.  Lock must be held.
     */
    private static void completeCall(CallTrack call, List<CompletedCall> completed)
    {
        if(!call.mCompleted)
        {
            call.mCompleted = true;

            if(call.mCardId != null)
            {
                completed.add(new CompletedCall(call.mCardId, call.mEncrypted));
            }
        }
    }

    /**
     * Log status for the call.
     */
    private static ConsoleActivityLogEntry.Status getStatus(CallTrack call, ConsoleCard card, CallDetails details)
    {
        if(card != null)
        {
            if(call.mClearAlert)
            {
                return ConsoleActivityLogEntry.Status.CLEAR;
            }

            if(details.encrypted())
            {
                return ConsoleActivityLogEntry.Status.ENCRYPTED;
            }

            return card.isMuted() ? ConsoleActivityLogEntry.Status.MUTED : ConsoleActivityLogEntry.Status.AUDIO;
        }

        if(details.encrypted())
        {
            return ConsoleActivityLogEntry.Status.ENCRYPTED;
        }

        return details.ignored() ? ConsoleActivityLogEntry.Status.IGNORED :
            ConsoleActivityLogEntry.Status.NOT_ON_CONSOLE;
    }

    /**
     * Creates or updates the activity log entries for the call: the console activity log (calls on a card) and the
     * all-traffic log (all calls).  Lock must be held.
     */
    private void updateLog(CallTrack call, ConsoleCard card, CallDetails details, long now)
    {
        ConsoleActivityLogEntry.Status status = getStatus(call, card, details);

        if(call.mLogEntry == null)
        {
            call.mLogEntry = new ConsoleActivityLogEntry(++mLogSequence, now, call.mCardId, details.talkgroupName(),
                details.talkgroup(), details.talkerName(), details.talkerId(), details.site(), status);

            if(call.mLogEntry.isOnConsole())
            {
                addLogEntry(mActivityLog, call.mLogEntry, ACTIVITY_LOG_LIMIT);
            }

            addLogEntry(mAllTrafficLog, call.mLogEntry, ALL_TRAFFIC_LOG_LIMIT);
            mLogVersion++;
            return;
        }

        ConsoleActivityLogEntry previous = call.mLogEntry;
        replaceLogEntry(call, new ConsoleActivityLogEntry(previous.id(), previous.timestamp(), previous.cardId(),
            details.talkgroupName() != null ? details.talkgroupName() : previous.talkgroupName(), details.talkgroup(),
            details.talkerName() != null ? details.talkerName() : previous.radioName(),
            details.talkerId() != null ? details.talkerId() : previous.radioId(),
            details.site() != null ? details.site() : previous.site(), status));
    }

    /**
     * Replaces the call's log entry in both activity logs when the entry changed.  Lock must be held.
     */
    private void replaceLogEntry(CallTrack call, ConsoleActivityLogEntry updated)
    {
        ConsoleActivityLogEntry previous = call.mLogEntry;

        if(previous == null || updated.equals(previous))
        {
            return;
        }

        call.mLogEntry = updated;
        boolean replaced = replaceLogEntry(mActivityLog, updated);
        replaced |= replaceLogEntry(mAllTrafficLog, updated);

        if(replaced)
        {
            mLogVersion++;
        }
    }

    /**
     * Adds the entry at the start of the log, removing the oldest entries beyond the limit.
     */
    private static void addLogEntry(List<ConsoleActivityLogEntry> log, ConsoleActivityLogEntry entry, int limit)
    {
        log.addFirst(entry);

        while(log.size() > limit)
        {
            log.removeLast();
        }
    }

    /**
     * Replaces the log entry that has the same identifier as the updated entry.
     * @return true if the entry was found and replaced.
     */
    private static boolean replaceLogEntry(List<ConsoleActivityLogEntry> log, ConsoleActivityLogEntry updated)
    {
        int index = indexOfLogEntry(log, updated.id());

        if(index >= 0)
        {
            log.set(index, updated);
            return true;
        }

        return false;
    }

    /**
     * Index of the log entry with the specified identifier, or -1.
     */
    private static int indexOfLogEntry(List<ConsoleActivityLogEntry> log, long id)
    {
        for(int x = 0; x < log.size(); x++)
        {
            if(log.get(x).id() == id)
            {
                return x;
            }
        }

        return -1;
    }

    /**
     * Completes tracked call events that have not been updated within the call complete time and removes tracked call
     * events that have not been updated within the retention period.  Lock must be held.
     * @param now current time
     * @param completed to receive the calls that completed
     */
    private void purgeCalls(long now, List<CompletedCall> completed)
    {
        Iterator<CallTrack> it = mCalls.values().iterator();

        while(it.hasNext())
        {
            CallTrack call = it.next();
            long age = now - call.mLastUpdate;

            if(age > CALL_COMPLETE_MS)
            {
                completeCall(call, completed);
            }

            if(age > CALL_RETENTION_MS)
            {
                it.remove();
            }
        }
    }

    /**
     * Processes a clear audio (encryption slip) alert from the console mixer: flags the card's current call so that the
     * card and the call's log entries show the CLEAR status.
     * @param cardId of the card that received clear audio
     */
    public void receiveClearAudioAlert(String cardId)
    {
        if(cardId == null)
        {
            return;
        }

        synchronized(this)
        {
            CardTrack cardTrack = mCards.computeIfAbsent(cardId, id -> new CardTrack());
            CallTrack call = cardTrack.mCurrentCall;
            cardTrack.mClearAlert = true;
            cardTrack.mClearAlertCall = call;

            if(call != null && !call.mClearAlert)
            {
                call.mClearAlert = true;

                if(call.mLogEntry != null)
                {
                    ConsoleActivityLogEntry entry = call.mLogEntry;
                    replaceLogEntry(call, new ConsoleActivityLogEntry(entry.id(), entry.timestamp(), entry.cardId(),
                        entry.talkgroupName(), entry.talkgroup(), entry.radioName(), entry.radioId(), entry.site(),
                        ConsoleActivityLogEntry.Status.CLEAR));
                }
            }
        }
    }

    /**
     * Processes console mixer levels: each card that has a call in the mixer is active.
     * @param levels from the console mixer
     */
    public void receiveLevels(ConsoleAudioLevels levels)
    {
        if(levels == null || levels.cardLevels().isEmpty())
        {
            return;
        }

        long now = mClock.getAsLong();

        synchronized(this)
        {
            for(ConsoleAudioLevels.CardLevel cardLevel: levels.cardLevels())
            {
                if(cardLevel.voiceCount() > 0 && cardLevel.cardId() != null)
                {
                    mCards.computeIfAbsent(cardLevel.cardId(), id -> new CardTrack()).mLastAudio = now;
                }
            }
        }
    }

    /**
     * Creates an immutable snapshot of the current console activity.  Calls that completed since the last update are
     * recorded for encryption learning (outside of this router's lock).
     */
    public ConsoleActivitySnapshot getSnapshot()
    {
        List<CompletedCall> completed = new ArrayList<>();
        ConsoleActivitySnapshot snapshot;

        synchronized(this)
        {
            long now = mClock.getAsLong();
            purgeCalls(now, completed);
            long hangTimeMs = mHangTimeMs;
            Map<String,ConsoleCardActivity> cards = new HashMap<>();

            for(Map.Entry<String,CardTrack> entry: mCards.entrySet())
            {
                CardTrack track = entry.getValue();
                CardActivityState state = track.getState(now, hangTimeMs, mCallUpdateTimeoutMs);
                boolean clearAlert = track.mClearAlert && track.mClearAlertCall == track.mCurrentCall &&
                    (state == CardActivityState.ACTIVE || state == CardActivityState.HANG);
                cards.put(entry.getKey(), new ConsoleCardActivity(entry.getKey(), state, track.mTalkerId,
                    track.mTalkerName, track.mSite, track.mTalkgroup, track.mRecentTalkers, track.getLastActivity(),
                    clearAlert));
            }

            //Immutable copies of the logs are only created when the logs change
            if(mLogCopyVersion != mLogVersion)
            {
                mActivityLogCopy = List.copyOf(mActivityLog);
                mAllTrafficLogCopy = List.copyOf(mAllTrafficLog);
                mLogCopyVersion = mLogVersion;
            }

            snapshot = new ConsoleActivitySnapshot(now, cards, mActivityLogCopy, mAllTrafficLogCopy, mLogVersion);
        }

        recordCompletedCalls(completed);
        return snapshot;
    }

    /**
     * Activity state for the card at the current time.
     * @param cardId to look up
     * @return state, or IDLE when the card has no activity.
     */
    public synchronized CardActivityState getState(String cardId)
    {
        CardTrack track = mCards.get(cardId);
        return track != null ? track.getState(mClock.getAsLong(), mHangTimeMs, mCallUpdateTimeoutMs) : CardActivityState.IDLE;
    }

    /**
     * Processes console layout changes: clears all activity when a layout is loaded (e.g. playlist switch) and clears
     * the activity of cards that are removed or re-bound to a different alias.
     */
    private void layoutChanged(ConsoleLayoutEvent event)
    {
        switch(event.getType())
        {
            case LAYOUT_LOADED ->
            {
                mHangTimeMs = mConsoleLayoutManager.getActivityHangTimeMs();
                clear();
            }
            case SETTINGS_CHANGED -> mHangTimeMs = mConsoleLayoutManager.getActivityHangTimeMs();
            case CARD_REMOVED, CARD_REBOUND ->
            {
                if(event.hasCard())
                {
                    clearCard(event.getCard().getId());
                }
            }
            default ->
            {
                //No action required
            }
        }
    }

    /**
     * Clears all activity state and the activity log.
     */
    private synchronized void clear()
    {
        mCalls.clear();
        mCards.clear();
        mActivityLog.clear();
        mAllTrafficLog.clear();
        mLogVersion++;
    }

    /**
     * Clears the activity state and tracked calls for the card.  Activity log entries are retained.
     */
    private synchronized void clearCard(String cardId)
    {
        mCards.remove(cardId);
        mCalls.values().removeIf(call -> cardId.equals(call.mCardId));
    }

    /**
     * Call details extracted from a call event.
     * @param talkgroupName card alias name for a call on the console, otherwise the talkgroup alias name or the
     * formatted TO identifier
     * @param ignored true when a traffic channel manager did not allocate a traffic channel because the call is not on
     * the console
     */
    private record CallDetails(String talkerId, String talkerName, String site, String talkgroup, String talkgroupName,
                               boolean encrypted, boolean ignored)
    {
    }

    /**
     * A completed call on a card, for encryption learning.
     */
    private record CompletedCall(String cardId, boolean encrypted)
    {
    }

    /**
     * Tracking state for a call event.
     */
    private static class CallTrack
    {
        private final String mCardId;
        private long mLastEventEnd;
        private long mLastUpdate;
        private boolean mEncrypted;
        private boolean mCompleted;
        private boolean mClearAlert;
        private ConsoleActivityLogEntry mLogEntry;

        private CallTrack(String cardId)
        {
            mCardId = cardId;
        }
    }

    /**
     * Activity state for a card.
     */
    private static class CardTrack
    {
        private long mLastCallProgress = -1;
        private long mLastAudio = -1;
        private CallTrack mCurrentCall;
        private CallTrack mClearAlertCall;
        private boolean mClearAlert;
        private boolean mEncrypted;
        private String mTalkerId;
        private String mTalkerName;
        private String mSite;
        private String mTalkgroup;
        private final LinkedList<RecentTalker> mRecentTalkers = new LinkedList<>();

        /**
         * Adds the talker as the most recent talker, removing any previous entry for the same radio.
         */
        private void addRecentTalker(RecentTalker talker)
        {
            Iterator<RecentTalker> it = mRecentTalkers.iterator();

            while(it.hasNext())
            {
                if(it.next().radioId().equals(talker.radioId()))
                {
                    it.remove();
                }
            }

            mRecentTalkers.addFirst(talker);

            while(mRecentTalkers.size() > RECENT_TALKER_LIMIT)
            {
                mRecentTalkers.removeLast();
            }
        }

        /**
         * Timestamp of the most recent call or audio activity, or 0.
         */
        private long getLastActivity()
        {
            return Math.max(0, Math.max(mLastCallProgress, mLastAudio));
        }

        /**
         * Activity state at the specified time.
         * @param now current time
         * @param hangTimeMs activity hang time
         * @param callUpdateTimeoutMs time after the last call progress update after which the call has ended
         * @return state
         */
        private CardActivityState getState(long now, long hangTimeMs, long callUpdateTimeoutMs)
        {
            long callActiveUntil = mLastCallProgress >= 0 ? mLastCallProgress + callUpdateTimeoutMs : -1;
            long audioActiveUntil = mLastAudio >= 0 ? mLastAudio + AUDIO_PRESENCE_TIMEOUT_MS : -1;
            long activeUntil = Math.max(callActiveUntil, audioActiveUntil);

            if(activeUntil < 0)
            {
                return CardActivityState.IDLE;
            }

            if(now < activeUntil)
            {
                return mEncrypted && now >= audioActiveUntil ? CardActivityState.ENCRYPTED : CardActivityState.ACTIVE;
            }

            if(now < activeUntil + hangTimeMs)
            {
                return CardActivityState.HANG;
            }

            return CardActivityState.IDLE;
        }
    }
}
