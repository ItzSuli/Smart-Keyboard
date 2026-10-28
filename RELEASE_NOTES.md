## Smart Keyboard 1.4.1 — smoother offline AI, easier punctuation

**Install:** download `SmartKeyboard-v1.4.1.apk` below, open it on your phone, then open the
Smart Keyboard app. Android 8.0+.

### Fixed
- **No more phone stutter when the offline AI finishes loading.** Starting the model on the graphics chip briefly stalled drawing for the whole phone. The offline AI now runs on the processor by default, on at most half of its cores and below the phone's own work, so nothing else slows down. Its self-test runs only on the first load after an update, and what the AI prepares on the first load is kept next to the model, so later loads are lighter. Want faster answers and don't mind the short stutter? AI assistant → Offline AI → **Use the graphics chip**. That screen also shows what the model is running on.

### Typing
- **A space comes by itself after `.` `,` `!` and `?`**, so you can keep typing. If you type the space anyway out of habit, you still get only one. Numbers like 3.5 and 1,000, links and e-mail addresses are left alone. You can turn it off in Typing & swipe.
- **Hold `.` and the comma comes first**, right under your finger. Slide left for `?` `!` `%` `-` `/` `@` `#`. The `…` is gone from that list.
- Every long-press menu now starts right under your finger, so lifting without moving always picks the first option.
