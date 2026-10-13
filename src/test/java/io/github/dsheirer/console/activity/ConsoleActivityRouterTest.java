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
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.console.AudioPan;
import io.github.dsheirer.console.ConsoleCard;
import io.github.dsheirer.console.ConsoleCardResolver;
import io.github.dsheirer.console.ConsoleGroup;
import io.github.dsheirer.console.ConsoleLayoutManager;
import io.github.dsheirer.console.audio.ConsoleAudioLevels;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteConfigurationIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.traffic.TrafficChannelManager;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the console activity router: routing call events to console cards and the card activity state machine.
 */
public class ConsoleActivityRouterTest
{
    private static final String ALIAS_LIST = "Test List";
    private static final String SITE = "North Hill";
    private static final int TALKGROUP_ON_CARD = 1001;
    private static final int TALKGROUP_ON_CARD_2 = 1002;
    private static final int TALKGROUP_OFF_CARD = 2001;
    private static final int RADIO_ALIASED = 1104523;
    private static final int RADIO_2 = 1104610;
    private static final int RADIO_3 = 1104288;
    private static final long CALL_TIMEOUT_MS = 1000;
    private static final long HANG_TIME_MS = 2000;

    @TempDir
    Path mTempDir;

    private final AtomicLong mClock = new AtomicLong(1_000_000);
    private AliasModel mAliasModel;
    private Alias mFireDispatch;
    private Alias mFireground;
    private ConsoleLayoutManager mManager;
    private ConsoleCardResolver mResolver;
    private ConsoleActivityRouter mRouter;
    private ConsoleCard mFireDispatchCard;
    private ConsoleCard mFiregroundCard;
    private ConsoleGroup mFireGroup;

    @BeforeEach
    void setup()
    {
        mAliasModel = new AliasModel();
        mFireDispatch = alias("Fire Dispatch", new Talkgroup(Protocol.APCO25, TALKGROUP_ON_CARD));
        mFireground = alias("Fireground 1", new Talkgroup(Protocol.APCO25, TALKGROUP_ON_CARD_2));
        alias("Police Dispatch", new Talkgroup(Protocol.APCO25, TALKGROUP_OFF_CARD));
        alias("Pumper 12", new Radio(Protocol.APCO25, RADIO_ALIASED));

        mManager = new ConsoleLayoutManager(mTempDir.resolve("playlist.console.json"));
        mManager.setAliasModel(mAliasModel);
        mManager.load();
        mManager.setActivityHangTimeMs(HANG_TIME_MS);
        mFireGroup = mManager.addGroup("Fire");
        mFireDispatchCard = mManager.addCard(mFireGroup.getId(), mFireDispatch.getUuid());
        mFiregroundCard = mManager.addCard(mFireGroup.getId(), mFireground.getUuid());
        mResolver = new ConsoleCardResolver(mManager);
        mRouter = new ConsoleActivityRouter(mManager, mResolver, mAliasModel, mClock::get, CALL_TIMEOUT_MS);
    }

    private Alias alias(String name, io.github.dsheirer.alias.id.AliasID aliasID)
    {
        Alias alias = new Alias(name);
        alias.setAliasListName(ALIAS_LIST);
        alias.addAliasID(aliasID);
        mAliasModel.addAlias(alias);
        return alias;
    }

    private static DecodeEvent call(DecodeEventType type, int talkgroup, Integer radio, long start)
    {
        List<Identifier> identifiers = new ArrayList<>();
        identifiers.add(APCO25Talkgroup.create(talkgroup));

        if(radio != null)
        {
            identifiers.add(APCO25RadioIdentifier.createFrom(radio));
        }

        identifiers.add(AliasListConfigurationIdentifier.create(ALIAS_LIST));
        identifiers.add(SiteConfigurationIdentifier.create(SITE));

        return DecodeEvent.builder(type, start).identifiers(new IdentifierCollection(identifiers))
            .protocol(Protocol.APCO25).build();
    }

    private void advance(long milliseconds)
    {
        mClock.addAndGet(milliseconds);
    }

    private CardActivityState state(ConsoleCard card)
    {
        return mRouter.getSnapshot().getCard(card.getId()).state();
    }

    @Test
    void callResolvesToCard()
    {
        DecodeEvent event = call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_ALIASED, mClock.get());
        mRouter.receive(event);

        ConsoleActivitySnapshot snapshot = mRouter.getSnapshot();
        ConsoleCardActivity activity = snapshot.getCard(mFireDispatchCard.getId());
        assertEquals(CardActivityState.ACTIVE, activity.state());
        assertEquals("Pumper 12", activity.talkerName(), "Expected talker alias name");
        assertEquals(String.valueOf(RADIO_ALIASED), activity.talkerId());
        assertEquals(SITE, activity.site());
        assertEquals(String.valueOf(TALKGROUP_ON_CARD), activity.talkgroup());
        assertEquals(CardActivityState.IDLE, snapshot.getCard(mFiregroundCard.getId()).state(),
            "Expected other card to be idle");

        assertEquals(1, snapshot.log().size());
        ConsoleActivityLogEntry entry = snapshot.log().getFirst();
        assertEquals(mFireDispatchCard.getId(), entry.cardId());
        assertEquals("Fire Dispatch", entry.talkgroupName());
        assertEquals("Pumper 12", entry.radioName());
        assertEquals(SITE, entry.site());
        assertEquals(ConsoleActivityLogEntry.Status.AUDIO, entry.status());
    }

    @Test
    void unrelatedEventsAreIgnored()
    {
        //Talkgroup that is aliased but not on a card
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_OFF_CARD, RADIO_2, mClock.get()));
        //Talkgroup that is not aliased
        mRouter.receive(call(DecodeEventType.CALL_GROUP, 9999, RADIO_2, mClock.get()));
        //Non-call event type for a talkgroup on a card
        mRouter.receive(call(DecodeEventType.AFFILIATE, TALKGROUP_ON_CARD, RADIO_2, mClock.get()));
        mRouter.receive(call(DecodeEventType.DATA_CALL, TALKGROUP_ON_CARD, RADIO_2, mClock.get()));
        //Event without an alias list
        mRouter.receive(DecodeEvent.builder(DecodeEventType.CALL_GROUP, mClock.get())
            .identifiers(new IdentifierCollection(List.of(APCO25Talkgroup.create(TALKGROUP_ON_CARD)))).build());
        mRouter.receive(null);

        ConsoleActivitySnapshot snapshot = mRouter.getSnapshot();
        assertTrue(snapshot.cards().isEmpty(), "Expected no card activity");
        assertTrue(snapshot.log().isEmpty(), "Expected no activity log entries");

        //Voice calls that are not on the console are in the all-traffic log, but not the non-call events
        assertEquals(3, snapshot.allTrafficLog().size(), "Expected the off-console voice calls in the all-traffic log");
        assertTrue(snapshot.allTrafficLog().stream().noneMatch(ConsoleActivityLogEntry::isOnConsole),
            "Expected off-console entries");
    }

    private static DecodeEvent call(DecodeEventType type, int talkgroup, Integer radio, long start, String details)
    {
        DecodeEvent event = call(type, talkgroup, radio, start);
        event.setDetails(details);
        return event;
    }

    @Test
    void allTrafficLogIncludesCallsNotOnConsole()
    {
        //Aliased talkgroup without a card, unknown talkgroup, and a call on a card
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_OFF_CARD, RADIO_ALIASED, mClock.get()));
        advance(100);
        mRouter.receive(call(DecodeEventType.CALL_GROUP, 9999, RADIO_2, mClock.get()));
        advance(100);
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_3, mClock.get()));

        ConsoleActivitySnapshot snapshot = mRouter.getSnapshot();
        assertEquals(1, snapshot.log().size(), "Expected only the console call in the console log");
        List<ConsoleActivityLogEntry> all = snapshot.allTrafficLog();
        assertEquals(3, all.size(), "Expected all calls in the all-traffic log");

        ConsoleActivityLogEntry console = all.get(0);
        assertEquals(snapshot.log().getFirst(), console, "Expected the same entry in both logs");
        assertTrue(console.isOnConsole());
        assertEquals(ConsoleActivityLogEntry.Status.AUDIO, console.status());

        ConsoleActivityLogEntry unknown = all.get(1);
        assertNull(unknown.cardId(), "Expected no card");
        assertEquals("9999", unknown.talkgroupName(), "Expected the TO identifier for an unaliased talkgroup");
        assertEquals("9999", unknown.talkgroup());
        assertEquals(String.valueOf(RADIO_2), unknown.radioId());
        assertEquals(SITE, unknown.site());
        assertEquals(ConsoleActivityLogEntry.Status.NOT_ON_CONSOLE, unknown.status());

        ConsoleActivityLogEntry aliased = all.get(2);
        assertEquals("Police Dispatch", aliased.talkgroupName(), "Expected the talkgroup alias name");
        assertEquals(String.valueOf(TALKGROUP_OFF_CARD), aliased.talkgroup());
        assertEquals("Pumper 12", aliased.radioName(), "Expected the radio alias name");
        assertEquals(ConsoleActivityLogEntry.Status.NOT_ON_CONSOLE, aliased.status());
        assertTrue(snapshot.cards().containsKey(mFireDispatchCard.getId()), "Expected only console card activity");
        assertEquals(1, snapshot.cards().size(), "Expected no card activity for off-console calls");
    }

    @Test
    void allTrafficLogStatusIgnoredAndEncrypted()
    {
        //P25/NXDN/MPT1327 prefix the details, DMR appends the indicator
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_OFF_CARD, RADIO_2, mClock.get(),
            TrafficChannelManager.NOT_ON_CONSOLE + " - PHASE 1 CHANNEL GRANT"));
        mRouter.receive(call(DecodeEventType.CALL_GROUP, 3001, RADIO_2, mClock.get(),
            "GROUP VOICE - " + TrafficChannelManager.NOT_ON_CONSOLE));
        mRouter.receive(call(DecodeEventType.CALL_GROUP_ENCRYPTED, 3002, RADIO_2, mClock.get(),
            TrafficChannelManager.NOT_ON_CONSOLE + " - PHASE 1 CHANNEL GRANT"));
        mRouter.receive(call(DecodeEventType.CALL_GROUP, 3003, RADIO_2, mClock.get(), "PHASE 1 CHANNEL GRANT"));

        List<ConsoleActivityLogEntry> all = mRouter.getSnapshot().allTrafficLog();
        assertEquals(4, all.size());
        assertEquals(ConsoleActivityLogEntry.Status.NOT_ON_CONSOLE, all.get(0).status(), "Allocated, not on console");
        assertEquals(ConsoleActivityLogEntry.Status.ENCRYPTED, all.get(1).status(), "Encrypted");
        assertEquals(ConsoleActivityLogEntry.Status.IGNORED, all.get(2).status(), "Ignored (suffix)");
        assertEquals(ConsoleActivityLogEntry.Status.IGNORED, all.get(3).status(), "Ignored (prefix)");
        assertTrue(mRouter.getSnapshot().log().isEmpty(), "Expected no console log entries");
    }

    @Test
    void allTrafficLogUpdatesCallInPlaceAndIsLimited()
    {
        long start = mClock.get();
        DecodeEvent event = call(DecodeEventType.CALL_GROUP, TALKGROUP_OFF_CARD, null, start);
        mRouter.receive(event);

        //Repeated updates of the same call event update its entry rather than adding entries
        for(int x = 1; x <= 5; x++)
        {
            advance(200);
            event.update(start + x * 200L);
            mRouter.receive(event);
        }

        event.setIdentifierCollection(new IdentifierCollection(List.of(APCO25Talkgroup.create(TALKGROUP_OFF_CARD),
            APCO25RadioIdentifier.createFrom(RADIO_ALIASED), AliasListConfigurationIdentifier.create(ALIAS_LIST))));
        mRouter.receive(event);

        List<ConsoleActivityLogEntry> all = mRouter.getSnapshot().allTrafficLog();
        assertEquals(1, all.size(), "Expected one entry for the call");
        assertEquals("Pumper 12", all.getFirst().radioName(), "Expected the late talker in the entry");
        assertEquals(SITE, all.getFirst().site(), "Expected the earlier site to be retained");

        for(int x = 0; x < ConsoleActivityRouter.ALL_TRAFFIC_LOG_LIMIT + 10; x++)
        {
            advance(100);
            mRouter.receive(call(DecodeEventType.CALL_GROUP, 5000 + x, RADIO_2, mClock.get()));
        }

        all = mRouter.getSnapshot().allTrafficLog();
        assertEquals(ConsoleActivityRouter.ALL_TRAFFIC_LOG_LIMIT, all.size(), "Expected the all-traffic log limit");
        assertEquals(String.valueOf(5000 + ConsoleActivityRouter.ALL_TRAFFIC_LOG_LIMIT + 9), all.getFirst().talkgroup(),
            "Expected most recent entry first");
    }

    @Test
    void encryptionIsLearnedFromCompletedCalls()
    {
        assertFalse(mManager.getCard(mFireDispatchCard.getId()).isEncryptedLearned());

        //Three encrypted calls: each call is recorded when the next call on the card starts
        for(int x = 0; x < ConsoleCard.ENCRYPTED_CALLS_TO_LEARN; x++)
        {
            advance(5000);
            mRouter.receive(call(DecodeEventType.CALL_GROUP_ENCRYPTED, TALKGROUP_ON_CARD, RADIO_2, mClock.get()));
        }

        assertFalse(mManager.getCard(mFireDispatchCard.getId()).isEncryptedLearned(),
            "Expected the third call to be recorded when it completes");

        //The last call completes after the call complete time
        advance(ConsoleActivityRouter.CALL_COMPLETE_MS + 1);
        mRouter.getSnapshot();
        ConsoleCard card = mManager.getCard(mFireDispatchCard.getId());
        assertTrue(card.isEncryptedLearned(), "Expected the card to be learned as encrypted");
        assertTrue(card.isTreatedAsEncrypted());

        //A clear call (a slip) does not reset the learned state
        advance(5000);
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_2, mClock.get()));
        advance(ConsoleActivityRouter.CALL_COMPLETE_MS + 1);
        mRouter.getSnapshot();
        card = mManager.getCard(mFireDispatchCard.getId());
        assertTrue(card.isEncryptedLearned(), "Expected a clear call not to reset the learned state");
        assertEquals(1, card.getClearCallStreak());
        assertEquals(0, card.getEncryptedCallStreak());

        //Calls are recorded once, however many times they are updated
        advance(5000);
        DecodeEvent event = call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_2, mClock.get());

        for(int x = 0; x < 5; x++)
        {
            event.update(mClock.get() + x * 100L);
            mRouter.receive(event);
        }

        advance(ConsoleActivityRouter.CALL_COMPLETE_MS + 1);
        mRouter.getSnapshot();
        mRouter.getSnapshot();
        assertEquals(2, mManager.getCard(mFireDispatchCard.getId()).getClearCallStreak(),
            "Expected one recorded call per call event");

        //Calls that are not on the console are not recorded on any card
        mRouter.receive(call(DecodeEventType.CALL_GROUP_ENCRYPTED, TALKGROUP_OFF_CARD, RADIO_2, mClock.get()));
        advance(ConsoleActivityRouter.CALL_COMPLETE_MS + 1);
        mRouter.getSnapshot();
        assertEquals(0, mManager.getCard(mFiregroundCard.getId()).getEncryptedCallStreak());
    }

    @Test
    void clearAudioAlertFlagsCurrentCall()
    {
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_2, mClock.get()));
        mRouter.getClearAudioAlertListener().receive(mFireDispatchCard.getId());

        ConsoleActivitySnapshot snapshot = mRouter.getSnapshot();
        assertTrue(snapshot.getCard(mFireDispatchCard.getId()).clearAlert(), "Expected the card to show CLEAR");
        assertFalse(snapshot.getCard(mFiregroundCard.getId()).clearAlert());
        assertEquals(ConsoleActivityLogEntry.Status.CLEAR, snapshot.log().getFirst().status());
        assertEquals(ConsoleActivityLogEntry.Status.CLEAR, snapshot.allTrafficLog().getFirst().status());

        //Later updates of the call keep the CLEAR status
        advance(500);
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_3, mClock.get()));
        snapshot = mRouter.getSnapshot();
        assertFalse(snapshot.getCard(mFireDispatchCard.getId()).clearAlert(), "Expected a new call to clear CLEAR");
        assertEquals(ConsoleActivityLogEntry.Status.AUDIO, snapshot.log().get(0).status(), "New call");
        assertEquals(ConsoleActivityLogEntry.Status.CLEAR, snapshot.log().get(1).status(), "Alerted call");

        //The CLEAR state ends with the activity
        mRouter.getClearAudioAlertListener().receive(mFireDispatchCard.getId());
        assertTrue(mRouter.getSnapshot().getCard(mFireDispatchCard.getId()).clearAlert());
        advance(CALL_TIMEOUT_MS + HANG_TIME_MS);
        assertFalse(mRouter.getSnapshot().getCard(mFireDispatchCard.getId()).clearAlert(), "Expected CLEAR to end");
    }

    @Test
    void activeHangIdleTiming()
    {
        long start = mClock.get();
        DecodeEvent event = call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_2, start);
        mRouter.receive(event);
        assertEquals(CardActivityState.ACTIVE, state(mFireDispatchCard));

        //Call updates with a growing end timestamp keep the call active beyond the call update timeout
        for(int x = 1; x <= 5; x++)
        {
            advance(500);
            event.update(start + x * 500L);
            mRouter.receive(event);
            assertEquals(CardActivityState.ACTIVE, state(mFireDispatchCard), "Expected active during call updates");
        }

        //Re-broadcast without a later end timestamp does not extend the call
        advance(600);
        mRouter.receive(event);
        assertEquals(CardActivityState.ACTIVE, state(mFireDispatchCard));
        advance(CALL_TIMEOUT_MS - 600);
        assertEquals(CardActivityState.HANG, state(mFireDispatchCard), "Expected hang after the call update timeout");

        advance(HANG_TIME_MS - 1);
        assertEquals(CardActivityState.HANG, state(mFireDispatchCard), "Expected hang within the hang time");

        advance(1);
        assertEquals(CardActivityState.IDLE, state(mFireDispatchCard), "Expected idle after the hang time");

        //Talker history is retained while idle
        ConsoleCardActivity activity = mRouter.getSnapshot().getCard(mFireDispatchCard.getId());
        assertEquals(String.valueOf(RADIO_2), activity.talkerId());
        assertEquals(1, activity.recentTalkers().size());
    }

    @Test
    void hangTimeFollowsLayoutSetting()
    {
        mManager.setActivityHangTimeMs(0);
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_2, mClock.get()));
        advance(CALL_TIMEOUT_MS);
        assertEquals(CardActivityState.IDLE, state(mFireDispatchCard), "Expected no hang with zero hang time");
    }

    @Test
    void encryptedCall()
    {
        DecodeEvent event = call(DecodeEventType.CALL_GROUP_ENCRYPTED, TALKGROUP_ON_CARD, RADIO_2, mClock.get());
        mRouter.receive(event);
        assertEquals(CardActivityState.ENCRYPTED, state(mFireDispatchCard));
        assertEquals(ConsoleActivityLogEntry.Status.ENCRYPTED, mRouter.getSnapshot().log().getFirst().status());

        advance(CALL_TIMEOUT_MS);
        assertEquals(CardActivityState.HANG, state(mFireDispatchCard), "Expected encrypted call to hang");

        //A following clear call is active
        advance(HANG_TIME_MS);
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_3, mClock.get()));
        assertEquals(CardActivityState.ACTIVE, state(mFireDispatchCard));
    }

    @Test
    void mutedCardIsLoggedAsMuted()
    {
        mManager.setCardMuted(mFireDispatchCard.getId(), true);
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_2, mClock.get()));
        assertEquals(CardActivityState.ACTIVE, state(mFireDispatchCard), "Expected muted card to show activity");
        assertEquals(ConsoleActivityLogEntry.Status.MUTED, mRouter.getSnapshot().log().getFirst().status());
    }

    @Test
    void cardInMutedGroupIsLoggedAsMuted()
    {
        mManager.setGroupMuted(mFireGroup.getId(), true);
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_2, mClock.get()));
        assertEquals(CardActivityState.ACTIVE, state(mFireDispatchCard),
            "Expected the card in the muted group to show activity");
        assertEquals(ConsoleActivityLogEntry.Status.MUTED, mRouter.getSnapshot().log().getFirst().status());

        //A new call after the group is unmuted plays audio (the card itself is not muted)
        mManager.setGroupMuted(mFireGroup.getId(), false);
        advance(CALL_TIMEOUT_MS + HANG_TIME_MS + 1);
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_3, mClock.get()));
        assertEquals(ConsoleActivityLogEntry.Status.AUDIO, mRouter.getSnapshot().log().getFirst().status());
    }

    @Test
    void talkgroupLabelIncludesConsoleGroupForCallsOnTheConsole()
    {
        mManager.setTalkgroupFilterEnabled(false);
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_2, mClock.get()));
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_OFF_CARD, RADIO_3, mClock.get()));
        List<ConsoleActivityLogEntry> all = mRouter.getSnapshot().allTrafficLog();
        ConsoleActivityLogEntry onConsole = all.stream().filter(ConsoleActivityLogEntry::isOnConsole).findFirst()
            .orElseThrow();
        ConsoleActivityLogEntry offConsole = all.stream().filter(entry -> !entry.isOnConsole()).findFirst()
            .orElseThrow();

        assertEquals("Fire - Fire Dispatch", onConsole.getTalkgroupLabel("Fire"), "Group and talkgroup name");
        assertEquals("Ops - Fire Dispatch", onConsole.getTalkgroupLabel("Ops"), "Expected the current group name");
        assertEquals("Fire Dispatch", onConsole.getTalkgroupLabel(null), "Expected the name when the group is unknown");
        assertEquals("Police Dispatch", offConsole.getTalkgroupLabel("Fire"),
            "Expected only the talkgroup name for calls that are not on the console");
    }

    @Test
    void recentTalkersOrderingAndDeduplication()
    {
        int[] radios = {RADIO_2, RADIO_3, RADIO_2, RADIO_ALIASED, 1, 2, 3, 4};

        for(int radio: radios)
        {
            advance(5000);
            mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, radio, mClock.get()));
        }

        List<RecentTalker> recent = mRouter.getSnapshot().getCard(mFireDispatchCard.getId()).recentTalkers();
        assertEquals(ConsoleActivityRouter.RECENT_TALKER_LIMIT, recent.size(), "Expected recent talker limit");
        assertEquals(List.of("4", "3", "2", "1", String.valueOf(RADIO_ALIASED)),
            recent.stream().map(RecentTalker::radioId).toList(), "Expected most recent first without duplicates");
        assertEquals("Pumper 12", recent.get(4).radioName());

        //Repeat talker moves to the front
        advance(5000);
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, 2, mClock.get()));
        recent = mRouter.getSnapshot().getCard(mFireDispatchCard.getId()).recentTalkers();
        assertEquals(List.of("2", "4", "3", "1", String.valueOf(RADIO_ALIASED)),
            recent.stream().map(RecentTalker::radioId).toList());
        assertEquals(mClock.get(), recent.getFirst().timestamp());
    }

    @Test
    void lateTalkerUpdateUpdatesCallAndLog()
    {
        long start = mClock.get();
        DecodeEvent event = call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, null, start);
        mRouter.receive(event);
        assertNull(mRouter.getSnapshot().getCard(mFireDispatchCard.getId()).talkerId());

        advance(200);
        event.setIdentifierCollection(new IdentifierCollection(List.of(APCO25Talkgroup.create(TALKGROUP_ON_CARD),
            APCO25RadioIdentifier.createFrom(RADIO_ALIASED), AliasListConfigurationIdentifier.create(ALIAS_LIST),
            SiteConfigurationIdentifier.create(SITE))));
        event.update(start + 200);
        mRouter.receive(event);

        ConsoleActivitySnapshot snapshot = mRouter.getSnapshot();
        assertEquals(String.valueOf(RADIO_ALIASED), snapshot.getCard(mFireDispatchCard.getId()).talkerId());
        assertEquals(1, snapshot.log().size(), "Expected the same call to update its log entry");
        assertEquals("Pumper 12", snapshot.log().getFirst().radioName());
    }

    @Test
    void activityLogIsLimited()
    {
        for(int x = 0; x < ConsoleActivityRouter.ACTIVITY_LOG_LIMIT + 10; x++)
        {
            advance(100);
            mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, x, mClock.get()));
        }

        List<ConsoleActivityLogEntry> log = mRouter.getSnapshot().log();
        assertEquals(ConsoleActivityRouter.ACTIVITY_LOG_LIMIT, log.size());
        assertEquals(String.valueOf(ConsoleActivityRouter.ACTIVITY_LOG_LIMIT + 9), log.getFirst().radioId(),
            "Expected most recent entry first");
    }

    @Test
    void mixerLevelsExtendActivity()
    {
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_2, mClock.get()));

        //Audio in the mixer keeps the card active after call event updates stop
        for(int x = 0; x < 50; x++)
        {
            advance(40);
            mRouter.getLevelListener().receive(levels(mFireDispatchCard.getId()));
        }

        assertEquals(CardActivityState.ACTIVE, state(mFireDispatchCard), "Expected audio to extend activity");

        advance(ConsoleActivityRouter.AUDIO_PRESENCE_TIMEOUT_MS);
        assertEquals(CardActivityState.HANG, state(mFireDispatchCard), "Expected hang after audio ends");
    }

    @Test
    void callStartMarksTheLivePeriod()
    {
        long start = mClock.get();
        DecodeEvent event = call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_2, start);
        mRouter.receive(event);
        assertEquals(start, activity(mFireDispatchCard).callStart(), "Expected the call start");

        //Call progress and audio do not move the start
        advance(500);
        event.update(start + 500);
        mRouter.receive(event);
        mRouter.getLevelListener().receive(levels(mFireDispatchCard.getId()));
        assertEquals(start, activity(mFireDispatchCard).callStart(), "Expected the start to be unchanged");

        //A new call while the card is hanging continues the live period
        advance(ConsoleActivityRouter.AUDIO_PRESENCE_TIMEOUT_MS + CALL_TIMEOUT_MS);
        assertEquals(CardActivityState.HANG, state(mFireDispatchCard));
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_3, mClock.get()));
        assertEquals(start, activity(mFireDispatchCard).callStart(), "Expected the live period to continue");

        //After the card was idle, a new call starts a new live period
        advance(CALL_TIMEOUT_MS + HANG_TIME_MS);
        assertEquals(CardActivityState.IDLE, state(mFireDispatchCard));
        assertEquals(0, activity(mFireDispatchCard).callStart(), "Expected no start while idle");
        long second = mClock.get();
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_2, second));
        assertEquals(second, activity(mFireDispatchCard).callStart(), "Expected a new live period");

        //Audio alone starts a live period
        advance(100);
        mRouter.getLevelListener().receive(levels(mFiregroundCard.getId()));
        assertEquals(mClock.get(), activity(mFiregroundCard).callStart(), "Expected audio to start a live period");
    }

    @Test
    void hangFollowsTheStateOfTheEndedCall()
    {
        //Encrypted call: hangs as encrypted
        mRouter.receive(call(DecodeEventType.CALL_GROUP_ENCRYPTED, TALKGROUP_ON_CARD, RADIO_2, mClock.get()));
        assertNull(activity(mFireDispatchCard).hangFrom(), "No hang state during the call");
        advance(CALL_TIMEOUT_MS);
        assertEquals(CardActivityState.HANG, state(mFireDispatchCard));
        assertEquals(CardActivityState.ENCRYPTED, activity(mFireDispatchCard).hangFrom(), "Encrypted hang");

        //Clear call: hangs as active
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD_2, RADIO_3, mClock.get()));
        advance(CALL_TIMEOUT_MS);
        assertEquals(CardActivityState.ACTIVE, activity(mFiregroundCard).hangFrom(), "Clear call hang");

        //Encrypted grant with clear audio in the mixer (shown as active): hangs as active, with the clear alert
        advance(HANG_TIME_MS);
        DecodeEvent slip = call(DecodeEventType.CALL_GROUP_ENCRYPTED, TALKGROUP_ON_CARD, RADIO_2, mClock.get());
        mRouter.receive(slip);
        mRouter.getClearAudioAlertListener().receive(mFireDispatchCard.getId());

        for(int x = 0; x < 30; x++)
        {
            advance(40);
            mRouter.getLevelListener().receive(levels(mFireDispatchCard.getId()));
        }

        assertEquals(CardActivityState.ACTIVE, state(mFireDispatchCard), "Clear audio shows as active");
        advance(ConsoleActivityRouter.AUDIO_PRESENCE_TIMEOUT_MS);
        ConsoleCardActivity hang = activity(mFireDispatchCard);
        assertEquals(CardActivityState.HANG, hang.state());
        assertEquals(CardActivityState.ACTIVE, hang.hangFrom(), "Expected the state shown at the end of the call");
        assertTrue(hang.clearAlert(), "Expected the clear alert while hanging");

        advance(HANG_TIME_MS);
        assertNull(activity(mFireDispatchCard).hangFrom(), "No hang state when idle");
    }

    @Test
    void snapshotsReuseUnchangedActivity()
    {
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_2, mClock.get()));
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD_2, RADIO_3, mClock.get()));
        ConsoleActivitySnapshot first = mRouter.getSnapshot();
        ConsoleActivitySnapshot second = mRouter.getSnapshot();
        assertSame(first.cards(), second.cards(), "Expected the card map to be reused when nothing changed");

        //Audio on one card: only that card's activity is replaced
        advance(40);
        mRouter.getLevelListener().receive(levels(mFireDispatchCard.getId()));
        ConsoleActivitySnapshot third = mRouter.getSnapshot();
        assertNotSame(second.cards(), third.cards(), "Expected a new card map");
        assertNotSame(second.getCard(mFireDispatchCard.getId()), third.getCard(mFireDispatchCard.getId()));
        assertSame(second.getCard(mFiregroundCard.getId()), third.getCard(mFiregroundCard.getId()),
            "Expected the unchanged card's activity to be reused");

        //A state change (call timeout) replaces the activity without an event
        advance(CALL_TIMEOUT_MS);
        ConsoleActivitySnapshot fourth = mRouter.getSnapshot();
        assertEquals(CardActivityState.HANG, fourth.getCard(mFiregroundCard.getId()).state());
        assertNotSame(third.getCard(mFiregroundCard.getId()), fourth.getCard(mFiregroundCard.getId()));
    }

    private ConsoleCardActivity activity(ConsoleCard card)
    {
        return mRouter.getSnapshot().getCard(card.getId());
    }

    private ConsoleAudioLevels levels(String cardId)
    {
        return new ConsoleAudioLevels(mClock.get(), 0.5f, 0.2f, 0.5f, 0.2f,
            List.of(new ConsoleAudioLevels.CardLevel(cardId, AudioPan.BOTH, false, 1, 0.0f, 0.0f)));
    }

    @Test
    void layoutLoadedClearsActivity()
    {
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_2, mClock.get()));
        assertEquals(1, mRouter.getSnapshot().log().size());
        long logVersion = mRouter.getSnapshot().logVersion();

        //Playlist switch loads another layout
        mManager.load(mTempDir.resolve("other.console.json"));

        ConsoleActivitySnapshot snapshot = mRouter.getSnapshot();
        assertTrue(snapshot.cards().isEmpty(), "Expected card activity to be cleared");
        assertTrue(snapshot.log().isEmpty(), "Expected activity log to be cleared");
        assertTrue(snapshot.logVersion() > logVersion, "Expected log version to change");

        //Calls no longer resolve to the previous layout's cards
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_2, mClock.get()));
        assertTrue(mRouter.getSnapshot().cards().isEmpty(), "Expected calls to be ignored with an empty layout");
    }

    @Test
    void removedCardActivityIsCleared()
    {
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD, RADIO_2, mClock.get()));
        mManager.removeCard(mFireDispatchCard.getId());
        assertTrue(mRouter.getSnapshot().cards().isEmpty(), "Expected removed card activity to be cleared");
    }

    @Test
    void siteDescriptionUsesConfiguredSite()
    {
        IdentifierCollection identifiers = new IdentifierCollection(List.of(SiteConfigurationIdentifier.create(SITE),
            APCO25Talkgroup.create(TALKGROUP_ON_CARD)));
        assertEquals(SITE, ConsoleActivityRouter.getSite(identifiers));
        assertNull(ConsoleActivityRouter.getSite(new IdentifierCollection(List.of(APCO25Talkgroup.create(1)))));
    }

    @Test
    void siteIsEnrichedWithDecodedSiteOfControlChannel()
    {
        List<String> lookups = new ArrayList<>();
        mRouter.setDecodedSiteResolver((system, site, channelName) -> {
            lookups.add(site + "|" + channelName);
            return SITE.equals(site) && "T-North".equals(channelName) ? "1-3" : null;
        });

        List<Identifier> identifiers = new ArrayList<>();
        identifiers.add(APCO25Talkgroup.create(TALKGROUP_ON_CARD));
        identifiers.add(APCO25RadioIdentifier.createFrom(RADIO_2));
        identifiers.add(AliasListConfigurationIdentifier.create(ALIAS_LIST));
        identifiers.add(SiteConfigurationIdentifier.create(SITE));
        identifiers.add(ChannelNameConfigurationIdentifier.create("T-North"));
        mRouter.receive(DecodeEvent.builder(DecodeEventType.CALL_GROUP, mClock.get())
            .identifiers(new IdentifierCollection(identifiers)).protocol(Protocol.APCO25).build());

        String expected = SITE + ConsoleActivityRouter.SITE_SEPARATOR + "1-3";
        assertEquals("North Hill · 1-3", expected);
        assertEquals(expected, mRouter.getSnapshot().getCard(mFireDispatchCard.getId()).site());
        assertEquals(expected, mRouter.getSnapshot().log().getFirst().site());
        assertEquals(List.of(SITE + "|T-North"), lookups);

        //Unresolved: configured site only
        mRouter.receive(call(DecodeEventType.CALL_GROUP, TALKGROUP_ON_CARD_2, RADIO_3, mClock.get()));
        assertEquals(SITE, mRouter.getSnapshot().getCard(mFiregroundCard.getId()).site());
    }
}
