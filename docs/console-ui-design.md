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
| Hang | A tint of the colour the card showed during the call, held for a configurable hang time (default 2 s) after the call ends: alias colour tint (active or muted call), encrypted purple tint with a purple border (encrypted call), alert red tint with a red border (clear audio alert) |

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
   **Paste from RadioReference:** pasting a talkgroup table copied from a RadioReference.com system page (or the
   toolbar's Paste from RadioReference… action) opens an import preview instead of pasting positionally
   (`alias.sheet.RadioReferenceTable` detects the table by its DEC/HEX/Mode/Alpha Tag/Description/Tag header, or
   without a header by the decimal + matching hex + mode code pattern; single-cell rows are category sub-headings).
   Options: alias list, protocol (APCO-25), name from description/alpha tag, alias group from tag/category/none,
   existing aliases (same list, protocol and talkgroup) skip/update, encrypted (E, TE) import/do-not-monitor/skip,
   and console placement (one group per tag, or one named group; one card per alias, aliases already on the console
   stay put). Applied as one undoable sheet operation (`RadioReferenceImport`, `AliasSheetModel.applyRadioReferenceImport`).
   The alias XML has no field for the RadioReference mode, so it is not persisted: the import result lists
   {alias UUID, mode} and the sheet model keeps it for the session (`getRadioReferenceMode`). Talkgroups listed as
   encrypted (E, TE) that end up on a console card get their clear-audio alert set to Always (unless the card already
   has an explicit Always/Never setting), so a slip alerts from the first call instead of after learning.

## Console window menus and header

The console window has its own in-window menu bar (like the main window's Swing menu bar, not the macOS system menu
bar). It is styled at runtime to look like the main window's Swing (FlatLaf) menus in the active theme:
`gui.console.ConsoleMenuTheme` reads the FlatLaf `UIManager` values on the Swing thread (MenuBar/PopupMenu/MenuItem
colours, accelerator and disabled colours, separator, check mark colour, popup corner radius and shadow, menu font and
UI scale) and generates a stylesheet scoped to `.console-menu-bar` (including the label text nodes, which the dark
JavaFX stylesheet styles directly). It is re-applied on application preference (theme) changes. The menu font is the
Swing menu font's family and size (FlatLaf fonts are already scaled by the GUI scale, so the size is not scaled again),
mapped to the JavaFX family that renders the same face (`ConsoleFonts.getMatchingFamily()`): macOS system font names
(`.AppleSystemUIFont`) map to JavaFX `System`; on macOS JavaFX resolves FlatLaf's `Helvetica Neue` to the Thin face, so
the regular face is loaded from `/System/Library/Fonts/HelveticaNeue.ttc` (falling back to `System`). Menu bar items and
menu items have the Swing row height (font height plus the 3 px FlatLaf margins, e.g. 23 px at 100%).

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

## Light and dark palettes

The console follows the sdrtrunk theme: the dark palette ("Night Ops") when the theme selected in the application
preferences is dark (`Theme.isDark()`, the flag the `ThemeManager` uses for `sdrtrunk_dark.css`), switched live on
application preference changes (the same event bus hook as the menu theme; the console root gets the `dark` style class
and the groups and cards are rebuilt). The console scene opts out of the application's dark stylesheet
(`ThemeManager.excludeFromDarkStylesheet()`), whose `.text` and button rules would override the console's colours; the
per-theme accent stylesheet still applies. Popups (menus, tooltips, card context menu) and the console's dialogs (alias
picker, confirmations, errors) are standard JavaFX windows that follow the application theme; the alias picker gets the
`dark` style class for its dimmed (already on the console) rows.

- **Stylesheet:** all colours in `console.css` are looked-up colours defined once per palette: `-console-*` on
  `.console-root` / `.console-root.dark` (window, header, switch, control strip, hint banner, groups, drop highlight,
  activity log) and `-card-*` per card state (status pills, mute/pan/volume controls, edit controls).
- **Alias colour dependent colours** (`ConsoleCardColors.Palette`): card fill, border and text (set inline, including the
  `-card-text`/`-card-sub` tokens), collapsed group summary dots and status pills and the activity log talkgroup ink. Dark: idle `#161C24` /
  `#283140` with `#E6EDF3` text; active cards keep the alias colour (lightened when needed) with near-black text; muted
  = 30% alias colour mixed into the idle surface with a dashed alias-colour border; hang = 13% tint with an alias-colour
  border (tints reduced when needed for the light text); encrypted `#B794F6`; selected pan segment inverted.
- **Contrast:** `ConsoleCardColorsTest` checks every text/background pair at 4.5:1 (3:1 for borders, dots and icons in
  the dark palette) for the 12 test alias colours plus very dark and very light colours, reading the token values from
  `console.css`, including the hang states (alias, encrypted and clear alert tints) and the collapsed group summary
  (status pill text, 3:1 dot outline and row text on the group panel). The light palette is unchanged except the unlinked status pill (`#5F6B78`, was 4.4:1), the drag handle
  on live cards (`#1F2E3F`) and the alias picker's assigned rows (a text colour instead of 50% opacity).

## Collapsible and muted groups

Each group header has a chevron (collapse/expand) at the left and a group mute button (speaker icon, 44 px touch
target) next to the active count, in normal and edit mode. Both states are persisted per group (`collapsed`, `muted` in
the layout JSON, default false) via `ConsoleLayoutManager.setGroupCollapsed/setGroupMuted/setAllGroupsCollapsed`, which
broadcast `GROUP_STATE_CHANGED` (the console window updates the group in place, without a rebuild).

- **Collapsed:** the header (chevron, name, "N ACTIVE", group mute) and, instead of the cards, a summary body styled
  like the empty group body (`console.activity.ConsoleGroupActivity`): up to 4 rows for the live cards (calls in
  progress or hanging), each with a colour dot (the colour of the call: alias colour, encrypted purple, alert red), the
  talkgroup name (ellipsis), the current talker (alias name or radio ID) and a status pill drawn like the card's status
  (RX, RX MUTED, ENCRYPTED, CLEAR or HANG; `ConsoleCardColors.getSummaryPaint()`), then "+N more active". Rows are
  ordered by the start of each card's live period (oldest first, ties in card order; `ConsoleCardActivity.callStart`)
  so rows do not jump and new calls append; a card with a clear audio alert is always shown (replacing the newest
  row). Idle collapsed groups show "202 talkgroups · no activity". The card views of a collapsed group are not created
  (they are created when the group is expanded). Cards dragged in edit mode can be dropped on a collapsed group.
- **Muted:** a card's effective audio mute is its own mute OR its group's mute (`ConsoleCardResolver.isAudioMuted()`);
  the flags are independent, so unmuting the group restores each card's own setting. The mixer ramps voices of cards in
  a muted group to silence like a card mute; clear audio alert beeps still play. Cards in a muted group show RX MUTED
  and a dashed red "Muted by group" mute button (the card's own setting is unchanged), and the activity log shows MUTED.

## Group layout and widths

The groups are laid out in masonry columns (`gui.console.ConsoleColumnPane`, placement in the toolkit independent
`ConsoleMasonry`): as many columns as fit with at least 300 px, and each group, in display order (move left/right), is
placed in the currently shortest column (ties to the leftmost), so the columns pack vertically without the gaps that a
row based layout leaves under short groups. The layout is recalculated when the window is resized and when groups or
cards are added, removed, reordered, collapsed or expanded.

A group can span several columns (`columnSpan` in the layout JSON, default 1, 0 = full width; layouts saved before
spans load with 1; `ConsoleLayoutManager.setGroupColumnSpan`, broadcast as `GROUP_STATE_CHANGED`). A group spanning S
columns is placed at the leftmost position whose S adjacent columns have the lowest maximum height. Spans are clamped
to the columns that fit (full width = all columns) without changing the saved span, so a group expands again when the
window widens. The cards of a wide group are laid out in a grid with S card columns (`ConsoleCardGrid`), in card order,
with the same card width as (and aligned with) single column groups; a collapsed wide group shows its summary across
the span. The width is set from the group header's context menu (right-click, or the keyboard context menu key on the
focused chevron or mute button; also outside edit mode): Width ▸ 1 column … N columns (only spans that fit, plus the
saved span when it is clamped, e.g. "3 columns (2 fit)") / Full width; the menu also has collapse/expand and mute/unmute.
In edit mode each group has a resize handle on its right edge: dragging snaps to whole columns with a dashed snap
preview, and the span is saved when released.

## Performance

Measured with a temporary harness (300 cards in 6 groups of 202/66/10/8/8/6, about 10 simultaneous calls with talker
updates and mixer levels, the window at 1440×900) before and after the changes below. Steady-state per-tick work was
already small (about 0.3 ms per 10 Hz refresh); the cost was in rebuilding: every structural layout event (card move,
add or remove, edit mode, group add/rename/reorder, re-link) rebuilt all card views (about 43 nodes and 7 tooltips,
each with its own popup scene, per card), 0.3–2.4 s of JavaFX thread time and about 110 MB of garbage each time for
300 cards, and collapsed groups kept all their (hidden) card nodes.

- Layout changes synchronise the existing group and card views with the layout instead of recreating them; only
  palette changes recreate the views. Card setting changes (including encryption learning after every call) and card
  re-binding update the one card in place, and only the properties that changed.
- Card views exist only for expanded groups. Per-card display values that the summaries and the activity log need
  (alias name, colour) are kept for every card. Alias lookups use a UUID index instead of a linear search per card.
- Tooltips are shared per text; edit mode controls, the encrypted indicator and the unlinked card actions are created
  when first needed. Card paints and inline card styles are cached per alias colour, state and palette; a card's style
  and style classes (which make CSS reapply to the card) are only changed when its appearance changes.
- `ConsoleActivityRouter` snapshots reuse the activity instance of each unchanged card (and the card map when nothing
  changed), so the window skips unchanged cards by reference and skips the per-card loop when no card changed.
- `ConsoleCardResolver` replaces only the changed card on `CARD_CHANGED` instead of copying the whole layout, and
  encryption learning changes are saved within 60 s (or with the next layout change, or on shutdown) instead of 2 s.

| 300 cards | Before | After |
|---|---|---|
| Rebuild on a layout event (card move, edit mode, …) | 280 ms – 2.4 s, ~110 MB garbage | 14 ms (7 ms with the 202 group collapsed) |
| Card move, FX CPU incl. CSS/layout | 259 ms | 47 ms |
| Edit mode toggle | 270 ms | 23 ms |
| Card setting change (CARD_CHANGED), above a ~11 ms measurement baseline | ~9 ms | < 1 ms |
| Window build (show) | 1.1 s | 0.72 s (0.52 s with the 202 group collapsed) |
| Scene nodes (all expanded / 202 group collapsed) | 12,969 / 12,969 | 9,865 / 3,595 |
| Live heap after show (all expanded / 202 collapsed) | 172 MB / 194 MB | 113 MB / 94 MB |
| Collapse / expand the 202 card group | 85 / 62 ms | 32 / 96 ms (cards created on expand) |

### Scrolling

Measured with a second temporary harness (the same 300 cards in groups of 10 (full width), 202, 66 (2 columns), 8, 8 (muted)
and 6, about 10 calls updating at 10 Hz including a clear audio alert, the window at 1440×900 on a 60 Hz Retina display,
JavaFX Metal pipeline), scrolling 6000 px down and up in 6 s by animating the scroll position and by synthesized scroll
events, recording the frame interval of every pulse. Scrolling redraws every visible card on every frame, and the cost
was in rendering, not in CSS or layout (about 1 ms per pulse): Prism draws region backgrounds with corner radii that are
non-uniform or larger than 0 and at most 4 px (JDK-8087965 workaround) with the software path rasterizer and a mask
texture upload on every frame, and so are dashed borders; effects (the theme's slider thumb drop shadow, the clear
audio glow) render offscreen on every frame. Each card had five such backgrounds (card 3 px, status pill 2 px, volume
track 3 px, first and last pan segments 5 0 0 5) and a shadow.

- `console.css` avoids the slow paths with the same geometry: fills under an opaque border use a larger radius that the
  border hides (card 5 px under the 2 px border, group 4.5 px), the status pill and the volume track are drawn as
  rounded borders around or instead of a square fill (borders use the fast path for any uniform radius), the pan group
  draws the segments' shared background (selected end segments use a rounded plus a square fill), and the slider thumb
  has no shadow. The group header fill is drawn by a rounded and a plain rectangle (`ConsoleGroupView.GroupHeader`). The
  clear audio glow is drawn as 1 px translucent border rings outside the card (inline style), matching the old shadow's
  falloff, and the "muted by group" dashed outline of the mute button is a dashed shape, which Prism draws from a cached
  mask. Dashed card borders (muted call, unlinked) remain, as only a few are shown at a time. Renders before and after
  differ only in corner anti-aliasing pixels, the dash phase of the group muted outline and the glow approximation.
- While the groups are scrolled (scroll position changes, any input) and until 150 ms after the last movement
  (`ScrollIdleDetector`), the 10 Hz refresh does not apply activity snapshots to the cards, group summaries and activity
  log, nor alias changes; the next refresh applies the latest snapshot (150–250 ms after scrolling stops). The router
  still takes a snapshot on every refresh; audio, routing and the log data are not affected. The clock and the control
  channel strip keep updating.

| Scrolling, light palette (dark is the same) | Frame interval median / p95 / max | Frames > 20 ms (of ~360 in 6 s) | CSS + layout per pulse p95 |
|---|---|---|---|
| Before | 173–204 / 177–359 / 367 ms (5–6 fps) | all | 1.9–3 ms |
| Rendering changes | 16.7 / 17.5–25.6 / 17.8–133 ms | 0–26 | 0.9–1.3 ms |
| Rendering changes + update pause | 16.7 / 17.4–17.6 / 17.6–33 ms | 0–1 | 0.01–0.1 ms |

Virtualising off-screen cards was not needed. The scene still has about 9,900 nodes with everything expanded.

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
