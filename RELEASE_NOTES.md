## Smart Keyboard 1.4.0 — AI fixes that make sense, updates that keep your settings

**Install:** download `SmartKeyboard-v1.4.0.apk` below, open it on your phone, then open the
Smart Keyboard app. Android 8.0+.

### Fixed
- **No more gibberish from the offline AI.** The "Gemma 4 E2B · GPU" download was a build made for web browsers; on phones it answered in random alphabets (Chinese, Russian…) and sprinkled emoji. It's gone: there is one Gemma 4 E2B now, the official Android build (2.6 GB), which uses the graphics chip where it can and the processor elsewhere. If you had the old one, its file is deleted to free the space; download Gemma again in AI assistant → Offline AI. If you had the CPU version, nothing changes.
- **Every AI answer is checked before it may touch your text**, online or offline: an answer in an alphabet you're not writing in, with emoji your text didn't have, or that rewrites your sentence into a different one is thrown away ("nothing was changed"). On top of that the offline AI now always picks its most likely answer, and tests itself once after loading: a chip that gives garbled answers is skipped.
- **"what" no longer turns into "What".** A capital at the start of a sentence (or a whole message in caps lock) is never learned anymore. The keyboard only learns how you write a word once it's a habit, and it never forces a learned capital on an ordinary word. Your all-caps words and names (LOL, LMAO, Suli…) keep their capitals. Words that older versions learned wrong are repaired automatically.
- **Ordinary typos aren't saved anymore.** Fixing "wat" with the keyboard's own first suggestion (or accepting an AI fix the keyboard would have made anyway) no longer adds a "learned fix" or a word to your list. "Your words" now only lists your own words: slang, names, words you added or always write your way. Learned fixes can be forgotten without stopping the keyboard from fixing them, and "Forget all" clears the list.

### New
- **Update from inside the app**: Settings → **Updates & backup** checks GitHub, downloads the new version and installs it over the old one, keeping your settings, words and offline AI. The home screen tells you when there's a new version.
- **Your settings are in backups and sync now**: Save / Restore (one file with settings, learned words, fixes and shortcuts) and the encrypted sync. A fresh install that picks your sync file again gets everything back, API keys included (those only travel inside the encrypted sync file).

### Updating to 1.4.0
Older versions were signed with a new key every release, so this one more time: uninstall the old version, then install this one. Your settings from before can't come along (older versions couldn't save them); learned words can (Your words & learning → Export in the old version, then Updates & backup → Restore here). The offline model has to be downloaded again.
