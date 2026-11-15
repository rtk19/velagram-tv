# Validation — Android TV 1.1.0, 12 November 2026

## Release build

`./scripts/build.sh assembleRelease testDebugUnitTest lintRelease` passes. Thirteen JVM tests pass; release lint reports zero errors. Seven on-device integration tests also passed on the Android TV API 34 ARM64 emulator. Warnings remain for locale/resource extraction, pinned dependency upgrade suggestions, PiP recommendations and the application-context singleton; no warning-free claim is made.

The signed release is `artifacts/Velagram-TV-1.1.0.apk`, with a per-file SHA-256 and APK-signature report beside it. Package `com.velagram.tv`, version code 2, minimum Android 8/API 26. The local signing identity is unchanged, so the APK upgrades the first port in place. ARM64, ARMv7, x86 and x86_64 native libraries are packaged.

## Automated evidence

JVM tests cover M3U metadata/headers/relative URLs, SRT timing and negative clamping, WebVTT timestamp separators, XMLTV timezone/XXE handling, episode/year matching, short-title boundaries, translated aliases, metadata identity, StreamingCommunity date/media-type matching, and the exact Nekostream/Megacloud encrypted-source fixtures from the iOS tests.

On-device integration tests cover:

1. Jellyfin show/episode matching and PlaybackInfo authentication/stream negotiation.
2. Xtream series discovery and exact season/episode selection.
3. Incremental imported-playlist discovery and cancellation.
4. Per-title speed persistence across different episodes.
5. Android Keystore secret storage and credential exclusion from export.
6. Automatic next-episode rollover into the next season.
7. Saved subtitle timing, latest-version restoration and disabling automatic sync.

HTTP provider tests use controlled response fixtures on the emulator. They demonstrate integration logic, not a successful login to the owner's personal services.

## Runtime and visual evidence

Actual release screens are saved in `artifacts/redesign/`: home/posters, film details/cast, actor filmography, automatic source status, player controls, playback-speed sheet, movie collections, connections, settings, themes, series details, seasons, episodes, manual episode entry and real Telegram QR linking.

MP4/H.264/AAC was played with Media3. The same video remuxed into MKV/H.264/AAC and an AVI/MPEG-4/MP3 sample were played with VLC. Video visibly advanced before capture and player controls showed increasing position. The source material was Blender Foundation's publicly downloadable Big Buck Bunny video. The small media fixtures stay under ignored `.tools/`, outside the APK.

Native Media3/VLC use the same custom control component. Automatic engine selection chooses VLC for MKV/AVI; Media3 decoder errors can hand off to VLC. This validates the sampled container/codec pairs, not every codec, HDR profile, DRM system or audio-passthrough receiver.

TMDB images, cast portraits, seasons and episode descriptions use live service data from the copied configuration. The Telegram QR is generated locally from a real TDLib authorization-link response; no account was signed in during QA.

Earlier Kan 11 HLS playback remains documented in the retained first-port screenshots. The new navigation smoke script exercises all eight main sections and fails if the app process exits or AndroidRuntime reports a fatal exception.

## Remaining validation

The owner currently has neither laptop nor physical-TV access. No physical device was paired or installed. Real logged-in Telegram media/range seeking, personal IPTV/Xtream/Jellyfin/Emby accounts, complete live-provider contracts, background/PiP behavior, HDMI/HDR/receiver/audio capabilities and long-session performance remain unverified. The source screen reflects real availability, including unconnected/unavailable states; no fabricated account results were used for screenshots.

See `FEATURE_STATUS.md` for platform and parity differences. The release is ready for sideloading and visual review, not certified complete equivalence with iOS.

Final release navigation smoke passed all eight sections: Home, Movies, Series, Search, Telegram, Live TV, Library and Settings. No AndroidRuntime fatal exception was recorded. The final source screen resolved a real StreamingCommunity release for Inception; the provider's `last_air_date` field and desktop request headers were checked against the live response and covered by a regression test. This is source-resolution evidence, not a certification of all streams or titles.

D-pad focus was checked on the release UI: moving right from the navigation rail focused the hero's Explore title action. The focused screen is captured as `18-remote-focus.png`. Backup export tests also cover server credentials embedded in history/recent/download metadata, in addition to dedicated secure preference keys.
