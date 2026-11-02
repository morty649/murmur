# Murmur

Offline, Wispr Flow-style dictation for Android. A floating bubble sits over every app:
tap it, talk, tap again, and your words are typed into the text box you're in.

- **Fully on-device.** Speech recognition uses NVIDIA **Parakeet Unified 0.6B** through
  [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). The internet is used only to download a model once.
- **Pick your model.** Parakeet Unified (default, English), Parakeet TDT v2 (English), and
  Parakeet TDT v3 (25 European languages) download from inside the app, with SHA-256 checks.
- **Optional cleanup.** "Light" removes um/uh and tidies spacing and capitals.

## Install

1. On your phone, open the repo's **Releases → latest** and download `Murmur-*.apk`.
2. Install it (allow "install unknown apps" for your browser if asked).
3. Open Murmur, tap **Get** on Parakeet Unified (~660 MB, Wi-Fi recommended), then grant the permissions.
4. **Accessibility greyed out?** Settings → Apps → Murmur → ⋮ → *Allow restricted settings*, then enable it.
5. Turn on the bubble. Blue = ready, red = listening, amber = transcribing.

If a text box isn't focused, the text is copied to the clipboard instead.

## How it works

| Piece | File |
|---|---|
| Floating bubble, mic, model lifecycle (foreground service) | `BubbleService.kt` |
| Types text into the focused box (accessibility service) | `TypeService.kt` |
| sherpa-onnx recognizer, splits long audio at pauses | `Transcriber.kt` |
| Model list, pinned Hugging Face revisions and checksums | `ModelCatalog.kt` |
| Downloads via Android DownloadManager, verification | `ModelManager.kt` |
| Settings UI (Jetpack Compose) | `MainActivity.kt` |

## Build

CI (`.github/workflows/build.yml`) builds a signed APK on every push to `main` and publishes it as the
`latest` release. Locally: `bash scripts/fetch-sherpa.sh && gradle :app:assembleRelease`
(needs JDK 17, Android SDK 35, Gradle 8.10+).
