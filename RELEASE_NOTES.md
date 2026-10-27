## Smart Keyboard 1.2.0 — fixes: Send works, smoother feel, no crashes

**Install:** download `SmartKeyboard-v1.2.0.apk` below, open it on your phone, then open the
Smart Keyboard app and follow the two setup steps. Android 8.0+.

### Fixed
- **Send works in Snapchat, WhatsApp and other chats.** Enter now does what the app asks for (Send, Search, Go, Next, Done), even in message boxes that allow several lines. Long-press enter for a new line.
- **Swipe, then tap a letter** starts a new word: swipe "hello", tap "I" → "hello I" (no more "helloi").
- **The settings app no longer closes** when you change settings.
- **Space-bar cursor** only vibrates when the cursor actually moves: no endless buzzing at the start or end of the text.
- **Held delete** now ticks once per deleted character, so the vibration speeds up together with the deleting (and stops when there's nothing left). The speed-up itself is smooth now.
- **Adaptive keys are calmer**: the middle three quarters of every key always type that key, likely letters can only reach so far, and when you delete a letter they picked (like "kis" instead of "kid" in "kidding") they back off for that pair of keys from then on. On simulated typing it changes about 3 times fewer correct taps than before.
- **Swipe** recognises words that go back and forth between neighbouring keys, like "there" (e → r → e). Accuracy on the benchmark went from 95.7 % to 96.5 % (top 3: 100 %).

### New
- **Delete a swiped word with one backspace** (Typing & swipe → Swipe typing; on by default).
- **Switch-keyboard button** on the right of the suggestion bar: tap to pick another keyboard app, hold to jump straight to the next one. Hide it under Typing & swipe → Gestures & keys.
- **Better vibration**: uses your phone's precise haptic effects (Galaxy S24 included) for short, crisp clicks. Choose **Crisp**, **Soft**, **Classic** or **System** under Typing & swipe → Feedback.

### Removed
- One-handed mode.
