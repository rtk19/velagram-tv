> Historical implementation snapshot. Current work and physical GOTV evidence are documented in [PREMIUM_VISUAL_REVIEW.md](PREMIUM_VISUAL_REVIEW.md) and [IOS_TV_PARITY.md](IOS_TV_PARITY.md).

# Velagram TV 1.1 — visual and functional review

This build replaces the first port's large boxed menus and stock playback toolbar. It uses cinematic artwork, a compact navigation rail, clean poster rows and shared controls across Media3 and VLC.

The intended flow is now **catalog → title/episode → automatic source search → playback**. Source search combines connected personal libraries and enabled web providers; manual season/episode selection remains available. Cast portraits open each person's filmography. Speed is saved for the title, so changing episodes or playback engines retains it.

Actual screenshots from the installed Android TV emulator are in `artifacts/redesign/`. They contain real TMDB artwork and real playback frames. Connection screens show the actual unconnected state; there are no invented account libraries or fake successful provider results.

## What has been verified

- Signed release builds and installs; lint has zero errors.
- Thirteen JVM tests cover parsers, matching and the exact encrypted-source fixtures used by the iOS tests.
- Seven native integration tests cover Jellyfin lookup/playback negotiation, Xtream episode matching, incremental playlist search, speed persistence across episodes, secret storage/export, season rollover and saved subtitle timing.
- MP4/H.264/AAC plays in Media3. MKV/H.264/AAC and AVI/MPEG-4/MP3 play in VLC. These are three real container/codec samples, not a claim that every possible codec works on every TV.
- Home, categories, details/cast, person filmography, episodes, manual episode entry, automatic Sources, settings/themes and Telegram QR linking are exercised and captured.

## Readiness

The APK is ready for sideloading and visual review. The intended TV browsing/player flow is implemented. It is **not certified complete iOS parity**: the owner's logged-in Telegram/IPTV/server accounts and physical-TV decoder, HDR/audio and remote behavior have not been tested. Casting, portable video-byte backups and some Telegram-browser/platform-specific features remain different; see `FEATURE_STATUS.md`.

A live StreamingCommunity source was resolved during final source-screen QA after correcting the provider date field and request headers. This does not establish all providers or all titles.

The final signed artifact is `artifacts/Velagram-TV-1.1.0.apk`. Its checksum and signature report sit beside it. The iOS source was read without modification.
