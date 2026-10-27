# Smart Keyboard

An Android keyboard that types the way you do: relaxed, AI-assisted corrections that never
"fix" your slang, context-aware word suggestions, fast swipe typing — and it all keeps learning
from you.

**[⬇ Download the latest APK](https://github.com/ItzSuli/Smart-Keyboard/releases/latest)** — Android 8.0 or newer.

| Typing | Swipe | AI fix in one tap |
|---|---|---|
| ![Keyboard](docs/screenshots/keyboard-light.png) | ![Swipe typing](docs/screenshots/swipe.png) | ![AI fix](docs/screenshots/ai-fix.png) |
| **Themes** | **Arabic** | **Emoji** |
| ![Sunset theme](docs/screenshots/theme-sunset.png) | ![Arabic layout](docs/screenshots/arabic.png) | ![Emoji panel](docs/screenshots/emoji.png) |
| **German on the English keyboard** | **German (QWERTZ)** | **Long-press accents** |
| ![Mixed typing](docs/screenshots/mixed.png) | ![German layout](docs/screenshots/german.png) | ![Accents](docs/screenshots/accents.png) |

<p align="center">
  <img src="docs/screenshots/settings-home.png" width="200" alt="Settings home">
  <img src="docs/screenshots/settings-appearance.png" width="200" alt="Appearance settings">
  <img src="docs/screenshots/settings-typing.png" width="200" alt="Typing settings">
  <img src="docs/screenshots/settings-ai.png" width="200" alt="AI settings">
</p>

## Install

1. Download `SmartKeyboard-vX.Y.Z.apk` from the [latest release](https://github.com/ItzSuli/Smart-Keyboard/releases/latest) and open it (allow "install unknown apps" for your browser or file manager when asked).
2. Open **Smart Keyboard** and follow the two setup steps (turn it on, then switch to it).
3. Optional: open **AI assistant** and paste an API key to turn on AI proofreading. Google Gemini has a free tier.

## What it does

### Chill corrections
- **Only clear typos get fixed.** `teh → the`, `becuase → because`, `dont → don't`, `helo → hello`. Real words, slang (`idk`, `ngl`, `bruh`, `digga`, `wallah`, `يلا` …) and swearing stay exactly as typed, and there are no weird built-in shortcuts.
- **Backspace undoes any fix — and it's remembered.** Undo `teh → the` once and it will never touch `teh` again.
- Benchmarked in `engine/src/test/.../AutocorrectBenchmarkTest.kt`: fixes 77 of 78 real-world misspellings (98.7 %) and leaves all 54 slang, swear and casual words in its "leave alone" list untouched.
- Three modes: **Off**, **Chill** (default) and **Strict**.

### Mixed typing: English and German on one keyboard
Write German on the English keyboard (or any mix of Latin-script languages) without switching:
- It notices which language you're writing from the last few words ("ich", "und", "the", "and" decide it almost instantly) and leans towards what you write most. New sentences can switch language.
- Suggestions, next-word predictions, autocorrect and swipe all follow the current language. German words work without umlaut keys: `fur → für`, `mude → müde`, `strasse → Straße`, and long-press `u`, `a`, `o` or `s` gives `ü`, `ä`, `ö`, `ß` first.
- It never pushes one language onto the other: `und` stays `und` in an English sentence, `fur` stays `fur` in "soft fur", `im` stays `im` in "ich bin im Kino".
- Every word is learned into the language it belongs to, your slang is protected in both, and the AI proofreads "English and German" text as such.
- Turn it on under **Languages → Mixed typing** (tap *Deutsch* while on the English keyboard). It's on by default when English and German are both enabled. Swipe accuracy on English words barely changes with German mixed in (95.0 % vs 95.7 % top-1, no context).

### Smart, learning suggestions
- Completions and next-word predictions from a trigram language model built from everyday spoken language (subtitles) and conversational sentences.
- Personal model: every word you send teaches it your vocabulary, your phrases and your habits, and the more you type the more your own style wins. New words and slang are learned after you use them twice.
- Picking a correction teaches it that typo; long-pressing a suggestion blocks it forever.
- Emoji suggestions (`pizza` → 🍕, `liebe` → ❤️, `هههه` → 😂).

### AI assistant (optional, bring your own key)
- **Proofread the chill way.** It fixes typos and grammar slips but keeps your tone, slang, swearing, emoji and lowercase style. Your learned words are sent along so the AI never "corrects" them.
- **Auto-proofread each sentence**: Manual, Suggest (a one-tap fix appears in the bar) or Auto-fix (tap to undo).
- **Rewrite** (chill, friendlier, formal, shorter, expand, add emoji) and **translate** (English ⇄ German ⇄ Arabic and every other enabled language) with a diff preview before applying.
- **AI next-word ideas** blended into the suggestion bar.
- **Learning loop**: typo fixes you accept from the AI become instant on-device autocorrections.
- Providers: **Google Gemini**, **Anthropic Claude** (official Java SDK; Opus 5.5 for best quality, Haiku 5.5 for speed), **OpenAI**, **Groq**, **OpenRouter**, or any OpenAI-compatible server such as a local **Ollama**.

### Swipe typing
- Glide over the letters. The decoder resamples your path, keeps only words whose letters the path visits in order, then scores shape with dynamic time warping, combined with sentence context and your personal words. Your slang can be swiped too.
- Live word preview while swiping, smooth fading comet trail, alternatives in the bar, automatic spacing.
- 96 % top-1 and 99 % top-3 accuracy on 400 common words with simulated sloppy gestures, about 1.6 ms per word (`SwipeDecoderTest`).

### iPhone-style adaptive keys
The letters you're most likely to type next get a bigger *invisible* hit area. It uses the current word, the sentence context and your own vocabulary. A touch near the border between two keys goes to the likelier letter; a touch in the middle of a key always types that key. The keyboard also learns where *you* actually hit each key and shifts its targets over time.

### Everything else
- **11 languages**, each with its own layout, dictionary, slang and swear words, and its own learned data: English, German (QWERTZ, ä ö ü ß), Arabic (with Arabic-Indic digits option), French (AZERTY), Spanish, Italian, Portuguese, Turkish, Dutch, Russian and Polish. Switch with 🌐.
- **Looks**: 11 themes plus Material You and Auto (light/dark), custom accent colour, your own background picture, key shape/gap/height/letter size, number row, flat or key-background style, one-handed mode.
- **Quality of life**: long-press for accents, numbers and symbols; slide on space to move the cursor; swipe left from ⌫ to delete whole words; double-space for a full stop; smart punctuation spacing; clipboard history with pins (password clips are skipped); emoji panel with skin tones; haptics and sound with adjustable strength; incognito mode.
- **Your data**: everything learned stays on the phone. Manage your words, learned fixes and blocked words, export or import a backup, or reset.

## Privacy

Typing, suggestions, autocorrect, swipe and all learning run on the device. The internet permission is only used by the optional AI features, which talk directly to the provider you configure with your own key. Nothing is ever learned from, or sent to an AI from, password fields, incognito mode or apps that ask for no personalised learning. Learned data is excluded from Android cloud backups; use the built-in export instead.

## Project layout

```
engine/   Pure-Kotlin typing engine (no Android): dictionaries, language model, corrector,
          swipe decoder, adaptive key prediction, learning. Fast JVM tests + benchmarks.
app/      The Android app: keyboard service and views (ime/), AI providers (ai/),
          storage (data/), Compose settings app (settings/), Robolectric tests.
tools/    build_dictionaries.py — rebuilds the language packs from the open data sources.
docs/     Design notes, release/signing guide, screenshots.
```

Build locally with JDK 17+ and the Android SDK:

```bash
./gradlew :engine:test :app:testDebugUnitTest   # engine benchmarks + end-to-end keyboard tests
./gradlew :app:assembleDebug                    # debug APK
```

Every push runs the tests on GitHub Actions. Releases are published from `main`, from `v*` tags, or from any commit whose message contains `[release]`; see [docs/RELEASING.md](docs/RELEASING.md).

## Credits

- Word frequencies: [FrequencyWords](https://github.com/hermitdave/FrequencyWords) by Hermit Dave (OpenSubtitles 2018), CC BY-SA 4.0
- Context data and capitalisation: [Tatoeba](https://tatoeba.org) sentences, CC BY 2.0 FR
- Emoji data: Unicode `emoji-test.txt`, Unicode License v3
- Claude provider: [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java), MIT
