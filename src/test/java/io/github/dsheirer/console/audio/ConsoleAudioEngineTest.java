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

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.console.AudioPan;
import io.github.dsheirer.console.ClearAudioAlert;
import io.github.dsheirer.console.ConsoleCard;
import io.github.dsheirer.console.ConsoleCardResolver;
import io.github.dsheirer.console.ConsoleGroup;
import io.github.dsheirer.console.ConsoleLayoutManager;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for console voices and the console audio engine, using real alias lists, identifiers, console layout and card
 * resolver.
 */
public class ConsoleAudioEngineTest
{
    private static final int FRAME = ConsoleAudioEngine.SAMPLES_PER_FRAME;
    private static final int CARD_TALKGROUP = 100;
    private static final int OTHER_TALKGROUP = 200;
    private static final float LEVEL = 0.5f;

    @TempDir
    Path mTempDir;

    private ConsoleLayoutManager mConsoleLayoutManager;
    private ConsoleCardResolver mConsoleCardResolver;
    private AliasList mAliasList;
    private ConsoleCard mCard;
    private ConsoleGroup mGroup;
    private ConsoleAudioEngine mEngine;
    private final AtomicBoolean mSuppressDuplicates = new AtomicBoolean(true);
    private final List<ConsoleAudioLevels> mLevels = new ArrayList<>();
    private final float[] mLeft = new float[FRAME];
    private final float[] mRight = new float[FRAME];
    private long mTimestamp = 1_000_000;

    @BeforeEach
    void setup()
    {
        mAliasList = new AliasList("Test Alias List");
        Alias cardAlias = new Alias("Card Talkgroup");
        cardAlias.addAliasID(new Talkgroup(Protocol.APCO25, CARD_TALKGROUP));
        mAliasList.addAlias(cardAlias);
        Alias otherAlias = new Alias("Other Talkgroup");
        otherAlias.addAliasID(new Talkgroup(Protocol.APCO25, OTHER_TALKGROUP));
        mAliasList.addAlias(otherAlias);

        mConsoleLayoutManager = new ConsoleLayoutManager(mTempDir.resolve(ConsoleLayoutManager.CONSOLE_DIRECTORY)
            .resolve(ConsoleLayoutManager.LAYOUT_FILE_NAME));
        mConsoleLayoutManager.load();
        mGroup = mConsoleLayoutManager.addGroup("Dispatch");
        mCard = mConsoleLayoutManager.addCard(mGroup.getId(), cardAlias.getUuid());
        mConsoleLayoutManager.setConsoleModeEnabled(true);
        mConsoleCardResolver = new ConsoleCardResolver(mConsoleLayoutManager);

        mEngine = new ConsoleAudioEngine(mConsoleCardResolver, mSuppressDuplicates::get);
        mEngine.setLevelListener(mLevels::add);
        //Same wiring as the console audio mixer
        mConsoleLayoutManager.addListener(event -> mEngine.layoutChanged());
        mEngine.setEnabled(true);
    }

    @AfterEach
    void teardown()
    {
        mConsoleCardResolver.dispose();
        mConsoleLayoutManager.shutdown();
    }

    /**
     * Creates a segment for the talkgroup with the consumer count held by the broadcaster for one listener.
     */
    private CountingAudioSegment segment(int talkgroup)
    {
        CountingAudioSegment segment = new CountingAudioSegment(mAliasList);
        segment.addIdentifier(APCO25Talkgroup.create(talkgroup));
        segment.incrementConsumerCount();
        return segment;
    }

    /**
     * Adds the number of 20 ms buffers of constant level audio to the segment.
     */
    private static void addAudio(AudioSegment segment, int buffers)
    {
        for(int x = 0; x < buffers; x++)
        {
            float[] audio = new float[FRAME];
            Arrays.fill(audio, LEVEL);
            segment.addAudio(audio);
        }
    }

    /**
     * Processes one engine frame.
     */
    private void frame()
    {
        mTimestamp += 20;
        mEngine.processFrame(mTimestamp, mLeft, mRight);
    }

    private static float peak(float[] samples)
    {
        float peak = 0.0f;

        for(float sample: samples)
        {
            peak = Math.max(peak, Math.abs(sample));
        }

        return peak;
    }

    private boolean isSilent()
    {
        return peak(mLeft) == 0.0f && peak(mRight) == 0.0f;
    }

    @Test
    void voicePrebuffersAndToleratesUnderrun()
    {
        CountingAudioSegment segment = segment(CARD_TALKGROUP);
        ConsoleVoice voice = new ConsoleVoice(segment, FRAME, mTimestamp);
        int prebufferFrames = ConsoleVoice.PREBUFFER_SAMPLES / FRAME;
        int rebufferFrames = ConsoleVoice.REBUFFER_SAMPLES / FRAME;

        addAudio(segment, prebufferFrames - 1);
        assertNull(voice.read(mTimestamp), "Expected no audio until the prebuffer is filled");
        assertFalse(voice.isPlaying(), "Expected buffering");

        addAudio(segment, 1);

        for(int x = 0; x < prebufferFrames; x++)
        {
            float[] audio = voice.read(mTimestamp);
            assertNotNull(audio, "Expected audio frame " + x + " once prebuffered");
            assertEquals(LEVEL, audio[0], 0.0f, "Sample value");
        }

        //Live segment has run dry: underrun outputs silence but the voice is kept
        assertNull(voice.read(mTimestamp), "Expected underrun");
        assertFalse(voice.isPlaying(), "Expected rebuffering after underrun");
        assertFalse(voice.isFinished(mTimestamp), "Expected voice to survive underrun");

        addAudio(segment, rebufferFrames - 1);
        assertNull(voice.read(mTimestamp), "Expected no audio until the rebuffer threshold is reached");
        addAudio(segment, 1);
        assertNotNull(voice.read(mTimestamp), "Expected audio to resume after rebuffering");

        for(int x = 1; x < rebufferFrames; x++)
        {
            assertNotNull(voice.read(mTimestamp), "Expected rebuffered audio");
        }

        //Completed segment plays out its remaining (short) audio without waiting for a rebuffer
        addAudio(segment, 1);
        segment.completeProperty().set(true);
        assertNotNull(voice.read(mTimestamp), "Expected final frame from completed segment");
        assertTrue(voice.isFinished(mTimestamp), "Expected voice finished after completed segment is fully read");

        voice.release();
        voice.release();
        assertEquals(1, segment.getDecrementCount(), "Expected consumer count released exactly once");
    }

    @Test
    void shortCompletedSegmentPlaysWithoutFullPrebuffer()
    {
        CountingAudioSegment segment = segment(CARD_TALKGROUP);
        addAudio(segment, 2);
        segment.completeProperty().set(true);
        ConsoleVoice voice = new ConsoleVoice(segment, FRAME, mTimestamp);
        assertNotNull(voice.read(mTimestamp), "Expected first frame");
        assertNotNull(voice.read(mTimestamp), "Expected second frame");
        assertNull(voice.read(mTimestamp), "Expected end of audio");
        assertTrue(voice.isFinished(mTimestamp), "Expected finished");
        voice.release();
        assertEquals(1, segment.getDecrementCount(), "Expected consumer count released exactly once");
    }

    @Test
    void stalledVoiceIsFinishedAfterTimeout()
    {
        CountingAudioSegment segment = segment(CARD_TALKGROUP);
        ConsoleVoice voice = new ConsoleVoice(segment, FRAME, mTimestamp);
        addAudio(segment, 1);
        voice.read(mTimestamp);
        assertFalse(voice.isFinished(mTimestamp + ConsoleVoice.STALL_TIMEOUT_MS), "Not yet stalled");
        assertTrue(voice.isFinished(mTimestamp + ConsoleVoice.STALL_TIMEOUT_MS + 1), "Expected stalled");
    }

    @Test
    void callPlaysWithCardSettingsAndReleasesOnce()
    {
        CountingAudioSegment segment = segment(CARD_TALKGROUP);
        addAudio(segment, ConsoleVoice.PREBUFFER_SAMPLES / FRAME);
        mEngine.receive(segment);

        frame();
        assertEquals(LEVEL, peak(mLeft), 1e-6f, "Expected left audio at unity gain");
        assertEquals(LEVEL, peak(mRight), 1e-6f, "Expected right audio at unity gain");

        //Live volume and pan changes apply to the playing voice
        mConsoleLayoutManager.setCardVolume(mCard.getId(), 0.5f);
        mConsoleLayoutManager.setCardPan(mCard.getId(), AudioPan.RIGHT);
        frame();
        assertEquals(0.0f, mLeft[FRAME - 1], 1e-6f, "Expected left silent after pan change ramp");
        assertEquals(LEVEL * 0.5f, mRight[FRAME - 1], 1e-6f, "Expected right at half volume after ramp");

        //Mute applies live and the call keeps being consumed
        mConsoleLayoutManager.setCardMuted(mCard.getId(), true);
        frame();
        frame();
        assertTrue(isSilent(), "Expected silence while muted");
        assertEquals(1, mEngine.getVoiceCount(), "Expected muted call to remain in the mixer");

        segment.completeProperty().set(true);

        for(int x = 0; x < 10; x++)
        {
            frame();
        }

        assertEquals(0, mEngine.getVoiceCount(), "Expected voice removed after the segment was fully played");
        assertEquals(1, segment.getDecrementCount(), "Expected consumer count released exactly once");

        mEngine.releaseAll();
        assertEquals(1, segment.getDecrementCount(), "Expected no additional release");
    }

    @Test
    void groupMuteSilencesCardAndUnmuteRestoresCardSetting()
    {
        CountingAudioSegment segment = segment(CARD_TALKGROUP);
        addAudio(segment, 40);
        mEngine.receive(segment);

        frame();
        assertEquals(LEVEL, peak(mLeft), 1e-6f, "Expected audio before the group is muted");

        //Group mute applies live (ramped) without changing the card's own setting
        mConsoleLayoutManager.setGroupMuted(mGroup.getId(), true);
        frame();
        assertEquals(0.0f, mLeft[FRAME - 1], 0.0f, "Expected silence at the end of the mute ramp");
        assertTrue(mLeft[0] > 0.0f, "Expected the mute to be ramped (not a step)");
        frame();
        assertTrue(isSilent(), "Expected silence while the group is muted");
        assertFalse(mConsoleLayoutManager.getCard(mCard.getId()).isMuted(),
            "Expected the card's own mute setting unchanged");
        assertEquals(1, mEngine.getVoiceCount(), "Expected the call to remain in the mixer");

        //Unmuting the group restores audio for an unmuted card
        mConsoleLayoutManager.setGroupMuted(mGroup.getId(), false);
        frame();
        frame();
        assertEquals(LEVEL, peak(mLeft), 1e-6f, "Expected audio after the group is unmuted");

        //A card that is muted itself stays muted when its group is unmuted
        mConsoleLayoutManager.setCardMuted(mCard.getId(), true);
        mConsoleLayoutManager.setGroupMuted(mGroup.getId(), true);
        frame();
        frame();
        assertTrue(isSilent(), "Expected silence while the card and group are muted");
        mConsoleLayoutManager.setGroupMuted(mGroup.getId(), false);
        frame();
        frame();
        assertTrue(isSilent(), "Expected the card's own mute to remain after the group is unmuted");

        mConsoleLayoutManager.setCardMuted(mCard.getId(), false);
        frame();
        frame();
        assertEquals(LEVEL, peak(mLeft), 1e-6f, "Expected audio after the card is unmuted");
        mEngine.releaseAll();
    }

    @Test
    void groupMuteDoesNotAffectCardsInOtherGroups()
    {
        ConsoleGroup other = mConsoleLayoutManager.addGroup("Other");
        mConsoleLayoutManager.setGroupMuted(other.getId(), true);
        CountingAudioSegment segment = segment(CARD_TALKGROUP);
        addAudio(segment, 40);
        mEngine.receive(segment);
        frame();
        assertEquals(LEVEL, peak(mLeft), 1e-6f, "Expected audio for a card in an unmuted group");
        mEngine.releaseAll();
    }

    @Test
    void clearAudioAlertPlaysWhenGroupMuted()
    {
        mConsoleLayoutManager.setCardClearAudioAlert(mCard.getId(), ClearAudioAlert.ALWAYS);
        mConsoleLayoutManager.setCardPan(mCard.getId(), AudioPan.LEFT);
        mConsoleLayoutManager.setGroupMuted(mGroup.getId(), true);
        List<String> alerts = playClearCall(1);
        assertEquals(List.of(mCard.getId()), alerts, "Expected an alert for a card in a muted group");
        assertEquals(1, mEngine.getAlertCount(), "Expected the alert tone to be playing");
        assertTrue(peak(mLeft) > 0.1f, "Expected the alert tone on the card's (left) side while the group is muted");
        assertEquals(0.0f, peak(mRight), 0.0f, "Expected nothing on the right side");

        int toneFrames = (AlertTone.getClearAudioAlert().length + FRAME - 1) / FRAME;

        for(int x = 0; x < toneFrames; x++)
        {
            frame();
        }

        assertEquals(0, mEngine.getAlertCount(), "Expected the alert tone to be complete");
        assertTrue(isSilent(), "Expected the call to be silent while the group is muted");
        mEngine.releaseAll();
    }

    @Test
    void segmentWithoutCardIsNotPlayed()
    {
        CountingAudioSegment segment = segment(OTHER_TALKGROUP);
        addAudio(segment, ConsoleVoice.PREBUFFER_SAMPLES / FRAME);
        segment.completeProperty().set(true);
        mEngine.receive(segment);

        for(int x = 0; x < 15; x++)
        {
            frame();
            assertTrue(isSilent(), "Expected no audio for a call without a card");
        }

        assertEquals(0, mEngine.getVoiceCount(), "Expected voice removed");
        assertEquals(1, segment.getDecrementCount(), "Expected consumer count released exactly once");
    }

    @Test
    void cardResolvedWhenTalkgroupArrivesMidCall()
    {
        CountingAudioSegment segment = segment(OTHER_TALKGROUP);
        addAudio(segment, 30);
        mEngine.receive(segment);

        frame();
        frame();
        assertTrue(isSilent(), "Expected no audio before the call resolves to a card");

        //Late TO talkgroup update from the decoder
        segment.receive(new IdentifierUpdateNotification(APCO25Talkgroup.create(CARD_TALKGROUP),
            IdentifierUpdateNotification.Operation.ADD, 0));

        frame();
        assertTrue(mLeft[0] > 0.0f && mLeft[0] < LEVEL, "Expected gain to ramp up from silence");
        assertEquals(LEVEL, mLeft[FRAME - 1], 1e-6f, "Expected full level after ramp");

        //Resolution back to a talkgroup without a card silences the call
        segment.receive(new IdentifierUpdateNotification(APCO25Talkgroup.create(OTHER_TALKGROUP),
            IdentifierUpdateNotification.Operation.ADD, 0));
        frame();
        frame();
        assertTrue(isSilent(), "Expected silence after resolving to a talkgroup without a card");

        mEngine.releaseAll();
        assertEquals(1, segment.getDecrementCount(), "Expected consumer count released exactly once");
    }

    @Test
    void linkedContinuationPlaysOnSameVoiceWithoutGap()
    {
        CountingAudioSegment first = segment(CARD_TALKGROUP);
        addAudio(first, ConsoleVoice.PREBUFFER_SAMPLES / FRAME);
        mEngine.receive(first);
        frame();
        assertFalse(isSilent(), "Expected audio");

        //Producer closes the first segment and opens a linked continuation
        CountingAudioSegment second = segment(CARD_TALKGROUP);
        second.linkTo(first);
        first.completeProperty().set(true);
        addAudio(second, ConsoleVoice.PREBUFFER_SAMPLES / FRAME);
        mEngine.receive(second);

        int totalFrames = 2 * ConsoleVoice.PREBUFFER_SAMPLES / FRAME;

        for(int x = 1; x < totalFrames; x++)
        {
            frame();
            assertEquals(LEVEL, peak(mLeft), 1e-6f, "Expected continuous audio at frame " + x);
            assertEquals(1, mEngine.getVoiceCount(), "Expected a single voice at frame " + x);
        }

        assertEquals(1, first.getDecrementCount(), "Expected first segment released once after it was played");
        assertEquals(0, second.getDecrementCount(), "Expected continuation still held");

        second.completeProperty().set(true);
        frame();
        assertEquals(0, mEngine.getVoiceCount(), "Expected voice finished");
        assertEquals(1, first.getDecrementCount(), "First segment released exactly once");
        assertEquals(1, second.getDecrementCount(), "Continuation released exactly once");
    }

    @Test
    void duplicateEncryptedAndDoNotMonitorCallsAreNotPlayed()
    {
        CountingAudioSegment duplicate = segment(CARD_TALKGROUP);
        duplicate.setDuplicate(true);
        CountingAudioSegment encrypted = segment(CARD_TALKGROUP);
        encrypted.encryptedProperty().set(true);
        CountingAudioSegment doNotMonitor = segment(CARD_TALKGROUP);
        doNotMonitor.monitorPriorityProperty().set(Priority.DO_NOT_MONITOR);

        for(CountingAudioSegment segment: List.of(duplicate, encrypted, doNotMonitor))
        {
            addAudio(segment, ConsoleVoice.PREBUFFER_SAMPLES / FRAME);
            mEngine.receive(segment);
        }

        frame();
        assertTrue(isSilent(), "Expected no audio");
        assertEquals(0, mEngine.getVoiceCount(), "Expected no voices");
        assertEquals(1, duplicate.getDecrementCount(), "Duplicate released once");
        assertEquals(1, encrypted.getDecrementCount(), "Encrypted released once");
        assertEquals(1, doNotMonitor.getDecrementCount(), "Do not monitor released once");
    }

    @Test
    void duplicatePlayedWhenSuppressionDisabled()
    {
        mSuppressDuplicates.set(false);
        CountingAudioSegment duplicate = segment(CARD_TALKGROUP);
        duplicate.setDuplicate(true);
        addAudio(duplicate, ConsoleVoice.PREBUFFER_SAMPLES / FRAME);
        mEngine.receive(duplicate);
        frame();
        assertFalse(isSilent(), "Expected duplicate to play when suppression is disabled");
        mEngine.releaseAll();
        assertEquals(1, duplicate.getDecrementCount(), "Released once");
    }

    @Test
    void callFlaggedDuplicateMidCallIsStoppedAndReleasedOnce()
    {
        CountingAudioSegment segment = segment(CARD_TALKGROUP);
        addAudio(segment, 30);
        mEngine.receive(segment);
        frame();
        assertFalse(isSilent(), "Expected audio");

        segment.setDuplicate(true);
        frame();
        assertTrue(mLeft[0] > 0.0f, "Expected ramp out rather than an abrupt stop");
        assertEquals(0.0f, mLeft[FRAME - 1], 1e-6f, "Expected silence after ramp out");
        assertEquals(0, mEngine.getVoiceCount(), "Expected voice removed");
        assertEquals(1, segment.getDecrementCount(), "Released once");

        frame();
        assertTrue(isSilent(), "Expected silence");
        assertEquals(1, segment.getDecrementCount(), "Released once");
    }

    @Test
    void disabledEngineReleasesSegmentsImmediately()
    {
        CountingAudioSegment playing = segment(CARD_TALKGROUP);
        addAudio(playing, ConsoleVoice.PREBUFFER_SAMPLES / FRAME);
        mEngine.receive(playing);
        frame();

        mEngine.setEnabled(false);
        assertEquals(1, playing.getDecrementCount(), "Expected playing segment released on disable");

        CountingAudioSegment received = segment(CARD_TALKGROUP);
        mEngine.receive(received);
        assertEquals(1, received.getDecrementCount(), "Expected segment released immediately while disabled");

        frame();
        assertTrue(isSilent(), "Expected silence while disabled");
        assertEquals(1, playing.getDecrementCount(), "Released once");
        assertEquals(1, received.getDecrementCount(), "Released once");
    }

    @Test
    void levelsReportAudibleCardsAndGoIdle()
    {
        CountingAudioSegment segment = segment(CARD_TALKGROUP);
        addAudio(segment, ConsoleVoice.PREBUFFER_SAMPLES / FRAME);
        mConsoleLayoutManager.setCardPan(mCard.getId(), AudioPan.LEFT);
        mEngine.receive(segment);

        frame();
        frame();
        assertEquals(1, mLevels.size(), "Expected one snapshot per level interval");
        ConsoleAudioLevels levels = mLevels.getLast();
        ConsoleAudioLevels.CardLevel cardLevel = levels.getCardLevel(mCard.getId());
        assertNotNull(cardLevel, "Expected card level");
        assertTrue(cardLevel.audible(), "Expected card audible");
        assertEquals(AudioPan.LEFT, cardLevel.pan(), "Pan");
        assertEquals(1, cardLevel.voiceCount(), "Voice count");
        assertEquals(LEVEL, cardLevel.peak(), 1e-6f, "Card peak");
        assertEquals(LEVEL, cardLevel.rms(), 1e-6f, "Card RMS");
        assertEquals(LEVEL, levels.leftPeak(), 1e-6f, "Left peak");
        assertEquals(0.0f, levels.rightPeak(), 0.0f, "Right peak");
        assertTrue(levels.isAnyAudible(), "Any audible");

        mConsoleLayoutManager.setCardMuted(mCard.getId(), true);
        frame();
        frame();
        frame();
        frame();
        cardLevel = mLevels.getLast().getCardLevel(mCard.getId());
        assertNotNull(cardLevel, "Expected muted card to still be reported");
        assertFalse(cardLevel.audible(), "Expected muted card not audible");
        assertEquals(0.0f, cardLevel.peak(), 0.0f, "Expected no level while muted");

        segment.completeProperty().set(true);

        for(int x = 0; x < 12; x++)
        {
            frame();
        }

        assertTrue(mLevels.getLast().cardLevels().isEmpty(), "Expected a final idle snapshot");
        int count = mLevels.size();
        frame();
        frame();
        assertEquals(count, mLevels.size(), "Expected no snapshots while idle");
    }

    /**
     * Plays a clear call on the card and returns the card identifiers of the alerts raised.
     */
    private List<String> playClearCall(int frames)
    {
        List<String> alerts = new ArrayList<>();
        mEngine.setClearAudioAlertListener(alerts::add);
        CountingAudioSegment segment = segment(CARD_TALKGROUP);
        addAudio(segment, 40);
        mEngine.receive(segment);

        for(int x = 0; x < frames; x++)
        {
            frame();
        }

        return alerts;
    }

    @Test
    void clearAudioOnEncryptedCardAlertsOnceOnCardSideEvenWhenMuted()
    {
        mConsoleLayoutManager.setCardClearAudioAlert(mCard.getId(), ClearAudioAlert.ALWAYS);
        mConsoleLayoutManager.setCardPan(mCard.getId(), AudioPan.RIGHT);
        mConsoleLayoutManager.setCardMuted(mCard.getId(), true);
        List<String> alerts = new ArrayList<>();
        mEngine.setClearAudioAlertListener(alerts::add);
        CountingAudioSegment segment = segment(CARD_TALKGROUP);
        addAudio(segment, 40);
        mEngine.receive(segment);

        frame();
        assertEquals(List.of(mCard.getId()), alerts, "Expected one alert for the card");
        assertEquals(1, mEngine.getAlertCount(), "Expected the alert tone to be playing");
        assertTrue(peak(mRight) > 0.1f, "Expected the alert tone on the card's (right) side while muted");
        assertEquals(0.0f, peak(mLeft), 0.0f, "Expected nothing on the left side");

        //The tone completes and the call (muted) continues without another alert
        int toneFrames = (AlertTone.getClearAudioAlert().length + FRAME - 1) / FRAME;

        for(int x = 1; x < toneFrames; x++)
        {
            frame();
        }

        assertEquals(0, mEngine.getAlertCount(), "Expected the alert tone to be complete");
        frame();
        assertTrue(isSilent(), "Expected silence after the tone while the card is muted");
        assertEquals(1, alerts.size(), "Expected a single alert per call");

        //A linked continuation of the same call does not alert again
        CountingAudioSegment continuation = segment(CARD_TALKGROUP);
        continuation.linkTo(segment);
        segment.completeProperty().set(true);
        addAudio(continuation, 20);
        mEngine.receive(continuation);

        for(int x = 0; x < 30; x++)
        {
            frame();
        }

        assertEquals(1, alerts.size(), "Expected no alert for the linked continuation");
        mEngine.releaseAll();
    }

    @Test
    void clearAudioAlertFollowsLearnedEncryption()
    {
        //Not learned yet: no alert
        assertTrue(playClearCall(3).isEmpty(), "Expected no alert before the talkgroup is learned as encrypted");
        mEngine.releaseAll();

        //Learned after three consecutive encrypted calls
        for(int x = 0; x < ConsoleCard.ENCRYPTED_CALLS_TO_LEARN; x++)
        {
            mConsoleLayoutManager.recordCardCall(mCard.getId(), true);
        }

        assertEquals(List.of(mCard.getId()), playClearCall(3), "Expected an alert for a learned encrypted card");
        mEngine.releaseAll();

        //The NEVER override suppresses the alert
        mConsoleLayoutManager.setCardClearAudioAlert(mCard.getId(), ClearAudioAlert.NEVER);
        assertTrue(playClearCall(3).isEmpty(), "Expected no alert with the NEVER override");
        mEngine.releaseAll();
    }

    @Test
    void clearAudioAlertDisabledGlobally()
    {
        mConsoleLayoutManager.setCardClearAudioAlert(mCard.getId(), ClearAudioAlert.ALWAYS);
        mEngine.setClearAudioAlertEnabled(false);
        List<String> alerts = playClearCall(3);
        assertTrue(alerts.isEmpty(), "Expected no alert when the clear audio alert is disabled");
        assertEquals(0, mEngine.getAlertCount(), "Expected no alert tone");
        assertEquals(LEVEL, peak(mLeft), 1e-6f, "Expected only the call audio");
        mEngine.releaseAll();
    }

    @Test
    void encryptedAudioDoesNotAlert()
    {
        mConsoleLayoutManager.setCardClearAudioAlert(mCard.getId(), ClearAudioAlert.ALWAYS);
        List<String> alerts = new ArrayList<>();
        mEngine.setClearAudioAlertListener(alerts::add);
        CountingAudioSegment encrypted = segment(CARD_TALKGROUP);
        encrypted.encryptedProperty().set(true);
        addAudio(encrypted, 20);
        mEngine.receive(encrypted);
        frame();
        frame();
        assertTrue(alerts.isEmpty(), "Expected no alert for encrypted audio");
        assertTrue(isSilent(), "Expected silence");
    }

    /**
     * Audio segment that counts consumer count decrements.
     */
    private static class CountingAudioSegment extends AudioSegment
    {
        private int mDecrementCount;

        CountingAudioSegment(AliasList aliasList)
        {
            super(aliasList, 0);
        }

        @Override
        public void decrementConsumerCount()
        {
            mDecrementCount++;
            super.decrementConsumerCount();
        }

        int getDecrementCount()
        {
            return mDecrementCount;
        }
    }
}
