# Premium visual implementation — GOTV review

Continued on `codex/tv-native-parity` without resetting prior work. Read `CODEX_VISUAL_CONTINUATION.md` and inspected Home, Details, Episodes, Sources, Player and overview references. Concept images were used for composition only. iOS source remained read-only; current iOS screenshots were unavailable.

Actual physical device: GOTV GO710FR, Android 14, native 3840×2160 with its existing 1920×1080 override. Current serial was obtained from `adb devices -l`. Signing/package/version identity and stored user data were retained. No uninstall, data clear, account sign-out, release publication or push to main.

| Screen | Before | Implemented result |
|---|---|---|
| Home | Dark hero, large Continue Watching slot with external captions, next shelf below the first viewport | Brighter cinematic hero, original logo, theme primary action, image-contained resume identity/progress and compact shelf; Trending appears in the initial viewport |
| Details | Large text hero, duplicated manual/watched action row, first episode barely visible | Poster/backdrop composition, clear hierarchy, real section tabs, season toolbar and horizontal landscape episode deck with dates/runtime/progress |
| Episodes | Plain heading/manual controls and small thumbnails | Cinematic compact title header, in-place season switching, larger stills, episode identity/date/runtime/synopsis and watched/progress state in aligned rows |
| Sources | Generic Find Your Stream heading, four large actions and heavy provider boxes | Title/episode-led backdrop header, actual air date/runtime when available, compact toolbar, quiet status groups and structured provider/release rows with known metadata |
| Player | Left-aligned play/seek controls and action buttons on one toolbar | Actual shared Compose redesign: centered prominent transport, separate timeline/time labels, distinct secondary action row, contextual source control and episode chooser, real track/speed hints and original branding |

The player was rebuilt as a composition, not merely recolored. Native transport widgets are deliberately retained as real AndroidView controls for decoder integration, D-pad navigation and precise sheet focus return. Both engines use the same PlayerOverlay.

Private local evidence is in `artifacts/premium-visual/before/` and `artifacts/premium-visual/after/`; open `artifacts/premium-visual/comparison.html` for matched pairs. These PNGs were captured on GOTV, not generated from the mockups. The final player capture uses a real 720p H.264/AAC Big Buck Bunny excerpt; the earlier baseline clip was 360p. UI quality is assessed separately from fixture resolution.

`04-sources.png` is the actual owner-provider loading/empty state for the selected title. `04-sources-local-playlist.png` is explicitly a controlled local-playlist test using a real served CC BY 3.0-licensed test excerpt through the existing IPTV source path and actual TMDB title metadata. It does not certify owner-provider availability. No fictional provider was added. Unknown size/language/codec/capability fields remain absent.

## Evidence and limits

Build/lint/unit results: `artifacts/premium-visual/final-build-and-lint.log`. Physical focus/playback/provider regression: `artifacts/premium-visual/final-physical-regression.log`. Final APK/signature/checksum and final test counts are recorded in `artifacts/premium-visual/RESULTS.md` after validation completes.

Real authenticated Telegram streaming/cache eviction, all owner IPTV/server releases, online subtitle services, HDR/passthrough, long-duration A/V sync and subjective owner approval remain unverified. Low-space policy uses synthetic non-destructive tests. Process-death restoration and the broader parity differences in IOS_TV_PARITY.md are not certified by these visual captures.

## Review checklist

- Compare the real screens, especially the centered player and compact Home/Details composition.
- Check viewing-distance legibility, focus visibility and long Hebrew titles.
- Spot-check a familiar authenticated episode with preferred audio/subtitles, source switching and resume.
- Judge the visual result as the owner; screenshots and passing tests do not certify subjective perfection.

## Final validation

31 JVM tests and 44 physical GOTV tests passed; release lint has 0 errors and 40 warnings. The signed release was installed using `install -r`, retaining the original certificate. A manual D-pad poster → Details → Back check restored the exact screenshot bytes. Raw preference comparison found 0 changed original entries and 0 added entries.

Vertical focus now transfers explicitly between native controls and Compose shelves; asynchronous metadata updates retain the same hero composition. Decorative headers are excluded from focus stops. RTL rail entry and season/episode/source return paths have remote-only regression coverage. All 11 themes were captured.
