# AGENTS.md: Murmur

Read this first. It is the hand-off for any agent (Claude Code, Codex, etc.) picking up this project.

## What this is

**Murmur** is an offline, Wispr Flow-style dictation app for **Android**. A floating bubble sits over every
app; tap it, talk, tap again, and the text is typed into whatever text box is focused.

- Started 2026-11-03. The owner (GitHub `morty649`) wanted the dictation part of
  [Muesli](https://github.com/Muesli-HQ/muesli) (a macOS app, cloned at `~/Work/muesli`) on their Android
  phone, and **nothing else**. Keep it small. Don't add meeting notes, history, sync, accounts, etc. unless asked.
- Repo: `~/Work/murmur` → private `github.com/morty649/murmur`, branch `main`.
- The name "Murmur" is the owner's choice for now. The icon is a murmuration of starlings, which is what the name refers to.

## Status (end of first session, 2026-11-03)

- Verified on the owner's phone (v0.1.2): install works, the bubble works, and text goes **directly into the
  focused box** (tested in WhatsApp).
- v0.1.3 added the murmuration icon; v0.1.4 is the same app (rebuilt when the CI workflow changed). The owner
  planned to install the latest release the next morning and hadn't given feedback on it yet.
- No open tasks were assigned. Ask the owner what they want next. Ideas are listed at the bottom.

## Owner preferences (important)

- **Short, bite-sized answers.** No essays.
- **Talk before making changes** when the change is a design or UX decision. The owner explicitly asked for this.
  Small bug fixes are fine to just do.
- **Never build or run heavy things on the owner's Mac** (it overheats, and there's no JDK installed anyway).
  All builds run on GitHub Actions.
- Show visual choices (icons, UI) as previews before committing. That's how the icon was picked.

## How to ship a change

1. Edit code in `~/Work/murmur`, commit, `git push` to `main`.
2. `.github/workflows/build.yml` builds a signed release APK on GitHub Actions (~4 min) and replaces the GitHub
   release tagged **`latest`** with `Murmur-0.1.<run_number>.apk`. Watch it with `gh run watch <id>`.
   CI is the compiler: there is no local build, so read CI errors with `gh run view <id> --log-failed`.
3. The owner downloads the APK on their phone from Releases → latest and installs it over the old one.
   Updates keep the downloaded model because the signing key is fixed.

Docs-only pushes (`*.md`) don't trigger a build.

### Signing (don't break this)

- Keystore: `~/.config/murmur/murmur.p12` (PKCS12, alias `murmur`), password in `~/.config/murmur/password.txt`.
- Stored as repo secrets `MURMUR_KEYSTORE_B64` and `MURMUR_KEYSTORE_PASSWORD`. The owner set these by hand;
  the auto-mode safety check blocks agents from uploading secrets.
- If the key changes, the owner must uninstall the app and re-download the 660 MB model. Never rotate it casually.
- Never commit the keystore (`.gitignore` covers `*.p12`).

## Architecture

Kotlin, Jetpack Compose, minSdk 26, targetSdk 35, arm64-v8a only. Speech recognition uses
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) **2.0.1** (AAR pinned by SHA-256 in
`scripts/fetch-sherpa.sh`, downloaded in CI into `app/libs/`, which is gitignored). The AAR includes the
Kotlin API classes (`com.k2fsa.sherpa.onnx.*`).

| File | Role |
|---|---|
| `BubbleService.kt` | Foreground service (type `microphone`): floating overlay bubble, recording, transcription, delivery. States: grey=loading, blue=ready, red=listening, amber=transcribing. |
| `TypeService.kt` | AccessibilityService. Inserts text at the cursor in the focused editable node (`ACTION_SET_TEXT` + selection), falls back to clipboard + `ACTION_PASTE`. Reads nothing else. |
| `Transcriber.kt` | Holds one `OfflineRecognizer` (`nemo_transducer`, greedy). Splits audio over 25 s at the quietest 100 ms window near each boundary. |
| `Recorder.kt` | `AudioRecord` 16 kHz mono, in memory only, 5-minute cap. |
| `ModelCatalog.kt` | Models, with Hugging Face repo, **pinned revision**, file sizes and SHA-256 values. |
| `ModelManager.kt` | Downloads via Android `DownloadManager` (resumable, survives app close), then verifies size and SHA-256, then writes a `.ready` marker. Models live in `getExternalFilesDir()/models/<id>/`. |
| `Cleanup.kt` | "Light" cleanup: regex filler removal (um/uh/erm/hmm), spacing, capitals. Parakeet already outputs punctuation. |
| `Prefs.kt` | SharedPreferences: selected model, cleanup mode, bubble position, download IDs. |
| `MainActivity.kt` | One-screen Compose settings UI: models, permissions, cleanup, bubble switch, test box. |

### Models (all sherpa-onnx NeMo transducers: encoder/decoder/joiner `.int8.onnx` + `tokens.txt`)

- **Parakeet Unified 0.6B (default, the owner's favourite)**:
  `csukuangfj2/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming` @ `8c3a10fb…`, about 663 MB, English.
  It's the same model Muesli uses by default on Mac (as CoreML there).
- Parakeet TDT v2 (English) and Parakeet TDT v3 (25 European languages), both from `csukuangfj/…`.
- To add a model, add an `AsrModel` entry with a pinned revision and the SHA-256 values from
  `https://huggingface.co/api/models/<repo>/tree/main` (field `lfs.oid`). A non-transducer model
  (Whisper, Moonshine, SenseVoice) also needs a model-type field and a branch in `Transcriber.load`.
- Streaming variants of Parakeet Unified exist (`…-int8-streaming-{240,560,1120}ms`) if live text is ever wanted.

## Decisions and why

- **Bubble, not a keyboard.** The owner chose a floating bubble. A voice keyboard (IME) was discussed as an
  alternative: no Accessibility or overlay permission needed, and `commitText` is perfectly reliable. It was
  parked because the bubble works. If permission pain comes back, that's the fallback design.
- **Fully offline.** The internet is used only for model downloads. No cloud ASR or LLM.
- **Model downloaded on first launch, not bundled.** That keeps the APK at about 38 MB.
- **Play Protect blocks the install.** On the owner's phone (India), Google Play Protect's fraud protection
  blocks sideloaded apps that request Accessibility. Workaround: Play Store → Play Protect → ⚙ → turn off
  "Scan apps", install, turn it back on. After install, Accessibility may be greyed out: App info → ⋮ →
  "Allow restricted settings". The app shows this hint in the UI.
- The mic works in the background because the foreground service of type `microphone` is **started from the
  activity while it's in the foreground**. Don't change it to start from boot or the background; Android 14+
  forbids that.

## Known gaps / untested

- Model load time and transcription speed on the owner's phone haven't been reported yet (phone model unknown).
- No VAD/auto-stop on silence: the user taps to stop.
- Aggressive OEM battery savers may kill the bubble service. Not handled yet.
- The bubble disappears after a reboot until the app is opened again (by design, because of the mic FGS rules above).

## Ideas for later (only if the owner asks)

1. **Smart cleanup** with a small on-device LLM (e.g. Qwen 0.6–1.7B via llama.cpp or MediaPipe), like
   Muesli's Qwen3 post-processor. Placeholder mode name: "Smart".
2. Auto-stop on silence (sherpa-onnx ships Silero VAD).
3. Custom vocabulary / hotwords (`createStream(hotwords)` is supported for transducers).
4. A voice keyboard (IME) as a second input path.
