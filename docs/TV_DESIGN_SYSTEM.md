# Velagram TV design system

Graphite identity inferred from current iOS Views.swift; actual GOTV captures are the layout evidence. Contract remains VELAGRAM_TV_CODEX_MASTER.md.

- Background #080A10, surface #141822, primary #F3F5FA, secondary #969EAE. Existing 11 theme accents stay in TvUi.
- 4dp rhythm; shelf insets 28dp inside the 78dp rail, 48dp trailing allowance; player uses 48dp side insets. Reference viewport is the device's 1920×1080 override, not a fixed canvas.
- Artwork slots are 126dp wide in 236dp poster shelves; Continue Watching uses 202dp cards in a 138dp shelf with captions and progress inside the image. Rounded artwork 6–8dp. Fixed slots include focus halo; only artwork scales 1.04 over 175ms. Captions stay still. A restrained theme outline and halo identify focus on bright/dark artwork.
- Episode row 104dp: 174dp landscape still, number/title, localized valid air date, two-line synopsis, runtime, watched check and progress. Empty dates take no fake label. Season selector sits directly above rows and focuses first new episode after selection.
- Compose heroes, source cards and Foundation LazyRow shelves/grids with stable identity keys and row focus restoration; no deprecated TV Foundation lazy lists. TV Material text, Coil asynchronous images with stable graphite background. Java interop adapter freezes JSON into immutable ArtworkItem/EpisodeItem values before composition.
- D-pad focus is the action indicator; selected destination remains distinct. Retain screen trees for Back to preserve scroll and Compose state. Sheets select current value and return focus to the opener. Asynchronous source append never explicitly requests focus.
- Player retains one CinemaControls.Playback adapter across Media3/VLC. No engine badge on video; video remains dominant. Up reveals, hidden OK toggles and reveals; hidden arrows seek saved interval; visible arrows navigate; Back hides then exits. Sheets suspend auto-hide; activity/window focus return rearms it. Seek is clamped to duration.
- Automatic MKV uses Media3, retaining decoder-error VLC fallback. AVI may prefer VLC. Container alone is not proof of unsupported codec.
- Temporary stream storage has an adaptive budget leaving a 256MB reserve and an idle 24-hour expiry. Only tracked streaming file IDs are removed through TDLib. Explicit saves, all profiles' downloads, active streams/downloads and Telegram database are protected. Existing untracked TDLib files are deliberately kept.

Dependency guidance checked against Android Developers: [TV Compose lists](https://developer.android.com/training/tv/playback/compose/lists), [Compose compiler plugin](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler). Pinned compatible stable baseline: Kotlin/compiler 2.1.20, Compose BOM 2025.04.01, TV Material 1.0.0, Activity 1.10.1. This is an incremental migration, not a claim that every screen is Compose.

## Native player parity additions

Both engines expose Sleep timer and Lock controls in Options. Timer choices are Off / 15 / 30 / 45 / 60 / 90 / 120 minutes; expiry pauses and reveals controls. It is scoped to the current player session. Locked controls ignore remote input; Back unlocks and reveals the overlay. The supported escape remains explicit and a Toast describes it. Audio/subtitle/speed sheets focus their actual current selection. PlayerOverlay.kt now supplies the actual shared Compose layout. Native transport/pill/timeline views remain the real interactive controls inside AndroidView slots, preserving remote handling and exact modal-opener restoration. There are no invisible proxy controls.

## Navigation and state

`MainActivity.deliver` defers network completions belonging to retained Back-stack screens instead of throwing them away. Restoring a screen reuses its real tree, Compose row state and scroll offset, drains pending updates, then restores focus. Episode progress/watched values refresh in place after playback/Back. Initial Home focus goes to its primary action unless the user already interacted with the remote. Navigation labels expand above browse content only while the rail is focused.

## Premium visual continuation — 2026-11-15

All five mockups in `Velagram_TV_Premium_Visual_References/` were inspected as composition references. They are concept art, not implementation evidence. Runtime artwork and metadata come from TMDB and existing providers; the original Vela logo and all eleven accent themes remain. No torrents, Shuffle, fabricated HDR/codec/availability badges or fictional services were added.

Home uses a 260dp editorial hero with a brighter right-side backdrop, controlled gradients, a single primary action and compact image-led Continue Watching cards. Details uses a 290dp poster/backdrop hero, section navigation, a compact season toolbar and a horizontal landscape episode shelf. The separate episode route uses a 162dp cinematic header and 104dp rows with fixed caption positions. Sources uses a 176dp title/episode header, compact filtering/actions, quiet provider status surfaces and 64dp release rows with provider, known metadata and selection affordance.

Player transport is centered above a separate thin timeline/time row; secondary actions sit in a distinct bottom row. Actual subtitle/audio languages and speed appear when known. Catalog playback exposes real source switching and a season episode chooser; these actions are omitted when the media has no catalog context. Video remains full-screen beneath restrained gradients. Back, seeking, auto-hide, subtitles/Studio, speed, engine fallback, skip segments, timer and lock still go through the existing controllers.

Artwork crossfades over 240ms. Poster slots reserve their focus halo; captions remain fixed. Episode/Continue Watching image zoom leaves overlay captions stationary. Bright theme accents switch primary-button text to dark graphite for contrast. Provider insertion anchors the focused row, and Back restores the provider/filter view context. Player and browse use the same original logo, graphite palette, spacing and accent rules.
