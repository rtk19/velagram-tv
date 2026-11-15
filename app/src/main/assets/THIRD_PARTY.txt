# Third-party notices and source references

Velagram TV retains the original Vela logo and supplied channel assets at the project owner's request. Asset inclusion does not grant rights to broadcaster content.

| Component | Version | License / upstream |
|---|---|---|
| AndroidX Fragment | 1.8.6 | Apache 2.0 — https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Media3 | 1.8.0 | Apache 2.0 — https://github.com/androidx/media |
| OkHttp | 4.12.0 | Apache 2.0 — https://github.com/square/okhttp |
| Glide | 4.16.0 | BSD and dependency notices — https://github.com/bumptech/glide |
| jsoup | 1.18.3 | MIT — https://github.com/jhy/jsoup |
| ZXing core | 3.5.3 | Apache 2.0 — https://github.com/zxing/zxing |
| TDLib Android packaging | 0.1.1 | Boost Software License — https://github.com/AkashPriyadarshii/tdlib-android |
| TDLib | Packaged by the artifact above | Boost Software License — https://github.com/tdlib/td |
| libVLC Android | 3.6.5 | LGPL 2.1 or later — https://code.videolan.org/videolan/libvlcjni and https://code.videolan.org/videolan/vlc |
| Kotlin stdlib (transitive) | 2.0.21 | Apache 2.0 — https://github.com/JetBrains/kotlin |
| JDK desugaring | 2.1.5 | GPLv2 with Classpath Exception / OpenJDK notices — https://github.com/google/desugar_jdk_libs |

libVLC and TDLib are packaged as separate shared libraries. Their native code was not modified. Rebuilding this project with a replacement compatible AAR/shared library is supported by the ordinary Gradle dependency configuration. Retain the corresponding upstream notices and source availability when redistributing APKs.
