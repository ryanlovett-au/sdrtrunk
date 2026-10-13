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
| Clear audio alert | Learned per card (3 consecutive encrypted calls) with an Auto / Always / Never override; triggered by clear audio in the console mixer, once per call; beep on the card's side even when muted. |
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
2. **Console mixer (done):** per-call volume, mute and pan, per-side mixing with a peak limiter; replaces traditional
   playback while console mode is on; per-card level API for the UI.
3. **Console window (implemented, pending visual verification):** groups, cards, colour states, layout editing
   (`gui.console`), decode event routing to cards with ACTIVE/ENCRYPTED/HANG/IDLE state, recent talkers and an
   activity log (`console.activity.ConsoleActivityRouter`), and a control channel strip from channel configuration and
   state (`console.control`, with `IControlChannelDetailProvider` as the phase 4 seam).
4. **Control channel card (done):** each network configuration monitor (P25 P1/P2, DMR, NXDN) publishes an immutable
   `NetworkSiteInfo` snapshot (`module.decode.network`: system/WACN, RFSS, site, NAC / color code / RAN, LRA, announced
   control channel frequency, neighbor count) and the decoder states implement `INetworkSiteInfoProvider` (snapshot
   plus decode quality). `console.control.NetworkSiteDetailProvider` finds them among the decoder states of the
   channel's processing chain and fills the control strip chips (site, RFSS-site e.g. "1-3", NAC, frequency, quality or
   state, details in the tooltip). Cards show the decoded site of the control channel that granted the call
   (e.g. "North Hill · 1-3"), resolved by `ControlChannelMonitor` from the call's configured site/channel name.
5. **Alias sheet editor (done):** View → Alias Sheet. Inline edit, paste from
   Excel/Sheets, fill-down, search/filter, CSV import/export, undo, duplicate/overlap highlighting, console group
   column (adds/moves/removes the alias's card). Built on a cell-selection `TableView` rather than ControlsFX
   `SpreadsheetView`: the SpreadsheetView skin needs a JavaFX internal package export
   (`com.sun.javafx.scene.control.behavior`) that the launchers don't provide, and its static grid model would have
   to be rebuilt on every alias change, sort and filter. Editing logic lives in `io.github.dsheirer.alias.sheet`
   (testable without JavaFX controls). Independent of phases 1–3. Selected rows can be dragged (press on an already
   selected cell and drag) onto a console group in the console window: one card per alias, cards in other groups are
   moved (`console.ConsoleAliasDrop`, custom data format of alias UUIDs).

## Console window menus and header

The console window has its own in-window menu bar (like the main window's Swing menu bar, not the macOS system menu
bar). It is styled at runtime to look like the main window's Swing (FlatLaf) menus in the active theme:
`gui.console.ConsoleMenuTheme` reads the FlatLaf `UIManager` values on the Swing thread (MenuBar/PopupMenu/MenuItem
colours, accelerator and disabled colours, separator, check mark colour, popup corner radius and shadow, menu font and
UI scale) and generates a stylesheet scoped to `.console-menu-bar` (including the label text nodes, which the dark
JavaFX stylesheet styles directly). It is re-applied on application preference (theme) changes.

- **File:** Traditional view (shows the main window), Exit.
- **View:** the main window's View menu items, labels, order and FontAwesome icons: Playlist Editor, Alias Sheet,
  separator, Application Log Files, Audio Recordings, Channel Event Log Files, Icon Manager, Message Recording Viewer
  (.bits), Screen Captures, User Preferences (folders opened via `gui.DirectoryOpener`). Radio Console (this window),
  Tuners and the main window display options are not repeated. Icons are `jiconfont` `IconNode` glyphs coloured like
  the item text (including hover/selection).
- **Console:** Edit layout (Ctrl/Cmd+E, in sync with the header button), Add group…, Collapse all groups, Expand all
  groups, Console mode, Follow only console talkgroups, Activity hang time, Show all traffic in activity log, Alert on
  clear audio from encrypted talkgroups.

The header keeps the title, the Edit layout button, the Console mode switch and the clock (wrapping at narrow widths).

## Collapsible and muted groups

Each group header has a chevron (collapse/expand) at the left and a group mute button (speaker icon, 44 px touch
target) next to the active count, in normal and edit mode. Both states are persisted per group (`collapsed`, `muted` in
the layout JSON, default false) via `ConsoleLayoutManager.setGroupCollapsed/setGroupMuted/setAllGroupsCollapsed`, which
broadcast `GROUP_STATE_CHANGED` (the console window updates the group in place, without a rebuild).

- **Collapsed:** only the header is shown. A second header row summarises the cards' activity
  (`console.activity.ConsoleGroupActivity`): a badge in the featured card's fill colour (red for a clear audio alert)
  with the card name and talker, e.g. "● 23 NR Emergency · 5710363", plus "+N more" for other live cards (RX, RX
  MUTED, ENCRYPTED, CLEAR or HANG), and the existing "N ACTIVE" count. The featured card is a clear audio alert first,
  then a call in progress, then a hanging call, then the most recent activity. Idle collapsed groups show
  "N talkgroups · no activity". Cards dragged in edit mode can be dropped on a collapsed group.
- **Muted:** a card's effective audio mute is its own mute OR its group's mute (`ConsoleCardResolver.isAudioMuted()`);
  the flags are independent, so unmuting the group restores each card's own setting. The mixer ramps voices of cards in
  a muted group to silence like a card mute; clear audio alert beeps still play. Cards in a muted group show RX MUTED
  and a dashed red "Muted by group" mute button (the card's own setting is unchanged), and the activity log shows MUTED.

## Activity log: console only / all traffic

The activity log title bar has a segmented "Console only | All traffic" toggle, persisted as `activityLogShowAll` in
the console layout (default console only; also Console → Show all traffic in activity log). Calls on a console card
show the card's current group with the talkgroup name in the Talkgroup column, e.g. "Ops - TG 23 Primary"
(`ConsoleActivityLogEntry.getTalkgroupLabel()`), so renaming a group or moving the card updates the rows. `ConsoleActivityRouter`
keeps the console log (50 calls routed to cards) and an all-traffic log (200 entries) that adds the voice calls that do
not resolve to a card. Both have one entry per call event, updated in place as the call progresses. Calls that are not
on the console show the talkgroup alias name (or the TO identifier, including patch notation), are drawn in grey, and
have the status:

- **ENCRYPTED** — the call is encrypted.
- **IGNORED** (amber) — a traffic channel manager did not allocate a traffic channel because of the console talkgroup
  filter (the event details carry `TrafficChannelManager.NOT_ON_CONSOLE`).
- **NOT ON CONSOLE** — decoded but not on a card (filter off, or a conventional channel with no grant).

## Clear audio (encryption slip) alert

Plays a short beep when a talkgroup that is normally encrypted carries clear audio.

- **Normally encrypted:** each card records its completed calls (`ConsoleCard.recordCall()`, persisted as
  `encryptedLearned`, `encryptedCallStreak`, `clearCallStreak`): a card is learned as encrypted after 3 consecutive
  encrypted calls, and only unlearned after 5 consecutive clear calls, so repeated slips keep alerting. A call is recorded
  once, when the card's next call starts or 5 s after its last update. The per-card right-click menu sets
  "Clear-audio alert: Auto / Always / Never" (`clearAudioAlert`). Cards treated as encrypted show a lock outline.
- **Trigger:** clear audio actually reaching the console mixer: a voice (call, including linked continuation segments)
  that starts playing non-encrypted audio for a card treated as encrypted raises one alert per call. A grant without the
  encryption flag does not alert on its own.
- **Alert:** a two-tone beep (880 Hz then 1320 Hz, 120 ms each with smooth envelopes, about -12 dBFS) mixed on the
  card's pan side(s) through the output limiter, even when the card or its group is muted. The card shows a red CLEAR
  status pill and red border while the call is active or hanging, and the call's activity log entries show **CLEAR ⚠**.
- **Setting:** Console → Alert on clear audio from encrypted talkgroups (`clearAudioAlertEnabled`, default on).

## Decode quality

The control strip shows a 0–100% decode quality per control channel (`module.decode.network.DecodeQualityTracker`):
the share of the received bit stream (airtime) that decoded into valid (CRC/FEC passing) messages over a 10 second
sliding window, i.e. valid bits / (valid + invalid + sync loss bits). The message framers already account for the whole
stream: framed messages are delivered valid or not, and stretches without a sync are reported as `SyncLossMessage`s
carrying the bits processed, so missed messages count against the quality (a simple valid/total message ratio would
read 100% on a fading channel). Airtime per message: P25 phase 1 from the data unit ID's elapsed length (TSBK frame
360 bits; each extra block of a multi-block frame 216/144 bits; assembled AMBTC/SNDCP messages are not counted);
P25 phase 2 720 bits per I-ISCH (2 per 4-timeslot superframe fragment); DMR 288 bits per burst (honoring the ignore
CRC setting); NXDN 384 bits per frame (a frame counts as valid when any of its messages is valid). Shown as "—" when
unknown (no messages yet, or none for 3 seconds).

## Testing

Use a live tuner (P25 via Airspy Mini), and record baseband with the tuner's Record button so the same traffic can
be replayed repeatedly with Add Recording Tuner while testing filtering and mixing.
