# Smart Keyboard — design notes

## What was asked (summary of the brief)

- Android keyboard, all code in this repository, a ready-to-install APK in Releases.
- Customisable looks, many settings, multiple languages: English, German and Arabic required, more welcome.
- Main focus: **AI-assisted correction** and **word suggestions**.
  - Automatic proofreading of a sentence or text with a *chill*, everyday tone.
  - Fast, context-aware word suggestions.
- iPhone-style enlarged hit areas for the most likely next letters.
- No "dumb shortcuts": corrections must never turn words into odd built-in expansions.
- Everything AI is tied to **learning**: corrections, suggestions, the user's own shortcuts and slang
  get better the more the keyboard is used.
- Most swear words known out of the box.
- Swipe typing, as smooth and good as possible, connected to the same learning.
- Smooth, connected, appealing overall.

## Decisions taken (and why)

| Topic | Decision |
|---|---|
| Where the "AI" runs | Two layers. An **on-device engine** (always on, instant, offline, private) does autocorrect, suggestions, swipe, adaptive keys and all learning. An **optional LLM** (user's own key) does sentence proofreading, rewrites, translation and extra next-word ideas. A keyboard cannot wait for a network round trip on every keystroke, and most people won't want every keystroke sent to a server. |
| LLM providers | Gemini (free tier, the default choice in settings), Claude via the official Anthropic Java SDK (Opus 5.5 by default at low effort; Haiku 5.5 offered for speed and cost; server-side refusal fallbacks on), OpenAI, Groq, OpenRouter and any OpenAI-compatible server (e.g. Ollama). The model field is editable and "Load models" lists what the account offers, so the app keeps working as models change. |
| "Chill" | Prompt rules: fix typos and slips only, keep slang, swearing, emoji, casing habits and tone, never formalise. Locally, autocorrect only fires on non-words (or on spelling variants like `dont → don't`), with a small error budget. Real words are never swapped for other real words in Chill mode. |
| Dictionaries | OpenSubtitles frequencies (spoken, everyday language, swear words included) + Tatoeba sentences for context (bigrams/trigrams) and capitalisation + curated slang/swear lists per language. Built by `tools/build_dictionaries.py` into compact binary packs (≈ 0.4–1.7 MB each). |
| Learning | Per-language user lexicon (SQLite): word counts (slang confirmed after 2 uses), bigrams/trigrams of the user's phrases, accepted corrections, undone corrections, blocked words, per-key touch offsets. Mixed into the language model with weights that grow with evidence in each context. Accepted AI typo fixes become local autocorrect rules; the user's slang is sent to the LLM so it is preserved. |
| Swipe | SHARK²-style decoding: candidates indexed by first/last key, in-order pass-through filter, DTW shape distance, key-fit and length terms, plus the language model (which includes learned words). Tuned on synthetic sloppy gestures. |
| Adaptive keys | Next-letter distribution from the trie (dictionary), context predictions and personal words; a tap is resolved by Gaussian touch likelihood × letter probability. Only the outer quarter of each key is shared (measured from where this person usually hits the key), a likelier neighbour's advantage is capped, and each (key under finger → picked key) pair backs off when the user deletes its picks (`HitboxLearning`). Tuned on simulated thumb typing (`AdaptiveKeysBenchmarkTest`): steals of correct taps 0.2–0.4 % at Balanced, about a third of the first version's. |
| Enter key | Same rules as the stock Android keyboard (LatinIME): perform the app's action (Send, Search, Go…) unless the app sets `IME_FLAG_NO_ENTER_ACTION` or the action is NONE; an app's custom label uses its `actionId`. Multi-line chat boxes (Snapchat, WhatsApp) therefore send. Long-press enter types a new line. |
| Haptics | Composition primitives (CLICK/TICK, scaled by the strength slider) where the vibrator supports them, the phone's predefined click effects otherwise, short amplitude pulses on old phones; held delete and cursor moves use lighter ticks, one per actual change. |
| Mixed typing | Several Latin-script languages on one keyboard. Each language keeps its own model and user lexicon; a `LanguageMix` estimates per-language weights from the last words (capped per-word evidence, decay, weaker across sentence ends, prior from how much the user writes in each language plus a nudge to the keyboard's language). `MixedSuggester` runs every language's suggester and merges by weighted score. Cross-language safety: a word valid in any mixed language is only changed by the currently dominant language; case-only fixes (German nouns) only while that language dominates; undone corrections and personal words count across all languages. Committed words are learned into the language with the highest P(word given language) × weight. |
| Arabizi | Each Latin letter/digit/pair is a set of possible Arabic letters (7 = ح, 3 = ع, sh = ش, short vowels optional, tanween "an" = ا). A dynamic-programming search over the Arabic dictionary's trie keeps only spellings that are words (one state per trie node, so it's exact and fast), scored by the language model with sentence context, a bonus for everyday dialect words (`ar.chat`, built from the curated list) and the user's own earlier picks. Tuned on a 60-word chat benchmark: 95 % top-1, 100 % top-3. Converts on space only when it's clearly Arabizi (Arabizi digits, or right after Arabic) and the word isn't a known Latin word. |
| Offline AI | LiteRT-LM (Google's on-device LLM runtime) with a model the user downloads once from the official litert-community builds through Android's DownloadManager, checked by size and SHA-256: Gemma 4 E2B (the Android build; the `-gpu` file on the same page is a web build that produced gibberish on phones and was dropped in 1.4.0), Qwen 3 1.7B, Qwen 3 0.6B. Greedy decoding. GPU first for Gemma, CPU first for Qwen; after loading, a one-line echo test runs and a backend whose answer is in the wrong alphabet or empty is marked broken and skipped. Kept loaded while used, released after 20 idle minutes or on memory pressure. Every AI answer (online or offline) passes `AiOutputCheck` before it can touch the text: no alphabet the text and keyboard don't use, no new emoji in a proofread, a proofread must keep most of the words. Gemini Nano via ML Kit's Prompt API where AICore offers it. Same prompts (and tones) as the online providers; also a fallback when an online provider can't be reached. Models aren't bundled: 0.3–2.6 GB would make the APK too large to download and install comfortably. |
| Tone per app | `EditorInfo.packageName` picks Chill / Clean / Polite prompts: chats chill, work apps clean, email polite, overridable per app. Apps are listed from where the keyboard was used plus well-known installed ones (launcher-intent package visibility only). |
| Voice typing | Android `SpeechRecognizer`, on-device recogniser first (API 31+), regular one as fallback; RECORD_AUDIO is requested by the settings app (a keyboard can't show permission dialogs). Results are inserted like typing and proofread in the app's tone, undoable from the chip. |
| Sync | The learning database exported as JSON (minus phone-specific touch data), gzip, AES-256-GCM with a PBKDF2-HMAC-SHA256 (210k rounds) key from the user's passphrase; the passphrase is stored wrapped by an Android Keystore key. One file through the Storage Access Framework (Google Drive works). Merge = max of counts, OR of flags, union of shortcuts, so syncing is idempotent and two phones converge. |
| Signing | No key is committed (the repo is public). CI signs with a private key from repository secrets, or with a one-off key until those exist (see `RELEASING.md`). |

## Architecture

```
engine/ (pure Kotlin, unit-tested on the JVM)
  BinaryDictionary  language pack reader (words, unigram/bigram/trigram log-probabilities)
  Trie              compact prefix tree (arrays) for fuzzy search and next-letter mass
  LanguageModel     stupid-backoff base model mixed with the user's model
  UserLexicon       everything learned, mirrored to storage through a listener
  SpatialModel      key geometry, touch likelihoods, learned per-key offsets (TouchModel)
  Corrector         weighted Damerau-Levenshtein trie search (keyboard-aware costs)
  Suggester         candidates + the chill autocorrect decision
  KeyPredictor      next-letter probabilities + adaptive tap resolution
  SwipeDecoder      gesture decoding
  TextDiff          word diff for AI previews and learning

app/
  ime/       SmartKeyboardService (views, layouts, panels), InputLogic (text editing, learning,
             AI hooks), KeyboardView/Overlay (drawing, touch, trail), themes, layouts, panels
  ai/        providers (Claude SDK, Gemini, OpenAI-compatible), prompts, AiAssistant
  data/      Settings, LearningStore (SQLite), LanguageManager, emoji, clipboard
  settings/  Compose settings app
```

## Testing

- `engine` — dictionary/LM/corrector/swipe unit tests, an Arabizi benchmark, a typo benchmark (fix rate ≥ 90 %, zero
  slang/swear changes), a swipe benchmark (top-1 ≥ 80 %, top-3 ≥ 93 %; currently 96.5 % / 100 %) and an
  adaptive-keys benchmark (must fix more slips than plain geometry and almost never change a correct tap).
- `app` — Robolectric tests drive the real `InputMethodService` against a real `EditText`
  (typing, autocorrect + undo, learning, punctuation, swipe and its spacing/undo, enter sending in
  multi-line chats, cursor and delete limits, changing every setting while typing, AI proofreading
  against a fake server), check each AI provider's request/response handling, and render keyboard and settings
  screenshots.

## Known limits / next steps

- Not yet tested on physical devices from this environment (no emulator available); behaviour is
  covered by the Robolectric tests above.
- Context data for Arabic, Dutch and Polish is thinner (smaller Tatoeba corpora); personal
  learning and the AI fill the gap over time.
- The offline models and Gemini Nano can't run in the JVM tests (native code); their plumbing
  is tested with a stand-in model. Real-device speed depends on the phone.
- Ideas: sticker/GIF search, a Latin-letters layout for Arabizi-only typists, handwriting.
