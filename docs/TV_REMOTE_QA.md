# Physical GOTV remote QA — 2026-11-15

Device discovered by `adb devices -l`: GOTV GO710FR, Android 14, serial `adb-GTVB000142-jV8vsv._adb-tls-connect._tcp`. Native panel 3840×2160; existing override 1920×1080. No display/account settings changed.

## Observed validation

| Check | Result / evidence |
|---|---|
| Home primary focus; poster shelf edges | Passed physical UI regression and screenshot review; halo remains inside reserved shelf padding |
| Detail → Back | Retained root/scroll assertion and matched focused-poster screenshots pass |
| Pending network completion while away | Native regression verifies delivery after returning to the retained screen |
| Season switch and valid inline dates | Native navigation and real TMDB GOTV captures pass; dates are omitted when invalid/missing |
| Watched/progress state, profiles, resume, rollover | Store/episode native contracts pass; physical watched/RTL and Continue Watching captures reviewed |
| Sources loading / errors / provider results | Real provider-state capture reviewed; controlled existing-IPTV source path produces a real local media candidate; authenticated owner releases remain unverified |
| Provider insertion / focus | Append-only result views retain focus; viewport anchor compensates when earlier groups add rows |
| Player hidden/visible keys | Native physical tests pass: hidden OK toggle, Up reveal, hidden Right seek, visible Right navigation, Back hide then exit |
| Panels | Current speed/track selection focus and exact opener restoration pass on physical Media3/VLC tests |
| MP4/MKV speed/seek | Media3 MP4 + MKV and VLC MKV at 1×/1.25×/1.5×/2× pass actual decoder/position tests |
| Skip intro/recap/preview/outro | Both engines' actual playback skip tests pass |
| Sleep timer / lock | Both-engine tests pass pause-on-expiry, accidental-key suppression and Back unlock |
| Eleven accents | All eleven focused Home states captured on GOTV; original owner theme restored; Silver/Gold bright-accent contrast reviewed |
| Hebrew / RTL | Actual physical RTL screen captured. Review found a date/runtime bidi defect; isolated text runs and a targeted physical rerun corrected it |
| Missing artwork/date | Stable empty artwork and no invented date are visible in the physical fixture capture |
| Telegram | Unconnected screen captured when account was unconnected; no sign-out was used to manufacture QR state |
| Storage pressure | Synthetic non-destructive budget/eviction tests pass; no disk-filling test. Authenticated TDLib eviction remains unverified |
| Build / tests / lint | 31 JVM tests; 44 physical regressions, including remote-only hero/shelf, RTL rail and season/source roundtrips. Release lint: 0 errors, 40 warnings |

## Evidence

Private local before/after PNGs and comparison: `artifacts/premium-visual/`. Final comprehensive run: `final-physical-regression.log` (`OK (44 tests)`). Final RTL check: `rtl-final-check.log`. Build/lint: `final-build-and-lint.log`. User-preference comparison: `data-preservation.json` (0 original values changed, 0 added entries after cleanup).

Initial attempted baseline launches in the earlier engineering pass failed with the pre-existing AppUpdater RejectedExecutionException; those old launcher/settings pictures are not Velagram baseline evidence. The premium continuation has a new, valid five-screen baseline in `artifacts/premium-visual/before/`.

Signing SHA-256 matches the installed identity: `821b4bec4bda1143ae6c4409330e9ab806dcbf10ca81ab2f1094d2c20e1cb0e6`. Install with `adb install -r`; no uninstall/data clear, credential deletion or schema reset occurred. Original APK is retained privately for rollback.

## Unverified boundaries

Owner-authenticated IPTV/server/Telegram releases, online subtitle-provider accounts, long playback A/V sync judgment, all codec/HDR/audio-passthrough combinations, real low-storage TDLib cleanup, process-death route restoration and final subjective visual approval are not certified by the passing tests. Broader iOS differences remain documented in IOS_TV_PARITY.md.

Final signed-release D-pad smoke: hero → Continue Watching → Trending → second poster → Details → Back. Restored poster/focus/scroll screenshot is byte-for-byte identical. See `release-navigation.txt` and `release-poster-*.png` in the private evidence folder.
