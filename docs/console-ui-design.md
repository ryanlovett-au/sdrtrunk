# Radio Console UI — Design

Status: agreed design, implementation in progress on branch `console-ui`.

## Goal

Add a dispatch-console style view to sdrtrunk, as an alternative to the traditional waterfall + channel list UI.
The user assigns talkgroups (via aliases) to cards, arranges cards into groups, and the console shows live
activity and plays audio according to per-card settings.

## Requirements

- **Talkgroup cards** bound to an alias. The whole card changes colour while traffic is being received.
- Each card shows: alias name, talkgroup ID, current talker, a short list of recent radio IDs, and the site the
  call is being received on.
- Per-card controls: **volume**, **mute**, **audio pan LEFT / BOTH / RIGHT**.
- Pan is always enforced: a card set to RIGHT always plays out of the right speaker. Multiple simultaneous calls
  on the same side are mixed together.
- **Muted** cards still decode and show activity visually; no audio is played.
- **Talkgroups without a card are not processed at all**: no traffic channel is allocated for them.
- Cards can be arranged into **groups**.
- A **control channel card** shows currently decoded control channels with their site names/IDs.
- The console has its **own window**. The user can switch between the console and the traditional UI. Closing
  the main window must not quit the app while the console window is open.
- A new **alias sheet editor** (spreadsheet/matrix) replaces the current alias editing experience for bulk work
  and is used to bind aliases to console cards.

## Decisions

| Topic | Decision |
|---|---|
| UI technology | JavaFX, in-process (core models already use JavaFX observable types). No headless/API refactor needed. |
| Card binding | One card ↔ one alias. An alias may appear on at most one card. Aliases get a stable UUID so cards survive renames. |
| Filter scope | A single setting ("follow only console talkgroups", default on). When on, unassigned talkgroups are skipped everywhere: traffic channel allocation, recording, streaming and the traditional view. |
| Trunked filtering | In each protocol's traffic channel manager (P25 P1/P2, DMR, NXDN, MPT1327): the grant is logged as a decode event but no traffic channel is allocated, following the existing "ignore data calls" pattern in `P25TrafficChannelManager`. |
| Conventional filtering | No grant exists, so filter at the audio segment / decode event level. |
| Patches | A patch/supergroup call matches if any member talkgroup has a card. |
| Audio | A new console mixer replaces `AudioPlaybackManager` output whenever console mode is enabled (regardless of which window is visible). Each active call is a voice with gain, mute and pan; voices are summed per side with a limiter. Duplicate-call suppression and linked-segment continuity carry over. |
| Window lifecycle | Closing the main window hides it if the console window is open; the app exits only via File → Exit, or when closing the last application window. |
| Console mode | A `consoleModeEnabled` setting (default off). Only when on does the console mixer replace traditional playback and the talkgroup filter apply, so users without a console see no change. |
| Persistence | One console layout per playlist, stored as a sidecar JSON file next to the playlist file and loaded when the playlist is loaded. Cards reference aliases by UUID (`uuid` XML attribute on `<alias>`). |
| Alias re-binding | Each card also stores a fallback signature (alias list name, alias name, protocol and first talkgroup). If a card's UUID is not found (e.g. the playlist was saved by an older sdrtrunk that dropped the attribute), the card is re-bound by signature and its UUID updated; unmatched cards are kept and shown as unlinked. |
| Visual design | Direction C (classic dense: group columns, control channel strip, activity log) with Direction A's per-card mute button and L/B/R segmented control. Mockups: https://claude.ai/artifact/RbFMWcUYnqkm8DfLkdYwRV |

## Card states

| State | Appearance |
|---|---|
| Idle | Neutral card |
| Active (receiving) | Card filled with its active colour (alias colour or a default) |
| Active + muted | Muted variant of the active fill plus a mute indicator |
| Encrypted | Distinct fill, no audio |
| Hang | Active colour held for a configurable hang time (default 2 s) after the call ends |

## Architecture

```
ChannelProcessingManager
  ├─ addDecodeEventListener ──► ConsoleActivityRouter ──► card state (active, talker, recent IDs, site)
  └─ addAudioSegmentListener ─► ConsoleAudioMixer ──────► audio output (stereo)
                                     ▲
ConsoleLayoutManager (JSON) ─────────┘  per-card volume / mute / pan

Traffic channel managers ──► ConsoleTalkgroupFilter (alias has a card?) ──► allocate or skip
Network configuration monitors ──► structured control channel snapshot ──► control channel card
```

## Phases

0. **Groundwork (done):** stable alias IDs, console layout model and persistence, window lifecycle, console mode setting.
0.5 **Layout per playlist and alias re-binding (done).**
1. **Routing and filtering:** grant filter in each trunking protocol (done: P25 P1/P2, DMR, NXDN, MPT1327; recording and
   streaming skip calls not on the console). Routing decode events to cards is part of phase 3.
2. **Console mixer:** per-call volume, mute and pan, mixing and limiter.
3. **Console window:** groups, cards, colour states, layout editing (design chosen from mockups first).
4. **Control channel card:** structured snapshot from network configuration monitors.
5. **Alias sheet editor:** ControlsFX `SpreadsheetView` (already a dependency): inline edit, paste from
   Excel/Sheets, fill-down, search/filter, CSV import/export, undo, duplicate/overlap highlighting, drag rows onto
   console groups. Independent of phases 1–3.

## Testing

Use a live tuner (P25 via Airspy Mini), and record baseband with the tuner's Record button so the same traffic can
be replayed repeatedly with Add Recording Tuner while testing filtering and mixing.
