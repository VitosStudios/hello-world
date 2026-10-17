# Tippli – Product & Engineering Specification (v1)

Tippli is an AI keyboard for **Android, iOS, macOS and Windows**. The user types
extremely fast and sloppily; while typing, an AI model of the user's choice
turns the raw input into correct text. There is **no dictionary, no suggestion
bar and no word prediction** – only a clean keyboard, emoji and the AI.

This document is the single source of truth for behaviour that must be
identical on every platform. Platform code lives in `android/`, `apple/` and
`windows/`; shared data lives in `shared/` and is bundled into every app
(never copied by hand – see §2).

Key words: **MUST** = required for v1, **SHOULD** = expected unless there is a
platform reason, **MAY** = optional.

---

## 1. Product goals

1. Typing speed first. A tap must never wait for the network. All AI work runs
   asynchronously and is applied without stealing keystrokes.
2. Keep the user's words. The AI fixes typos, capitalisation, umlauts,
   punctuation and obvious grammar – it does not rewrite (unless the user asks
   via the tips panel).
3. Two keyboard forms, switchable by a toolbar button: **normal** (classic
   rows) and **hex** (honeycomb, Typewise-like: two space keys in the middle,
   swipe gestures instead of extra keys).
4. Swiss focus: QWERTZ with ä ö ü, Swiss Standard German (always `ss`),
   Swiss German dialect mode, Swiss French layout – plus EN/FR/IT/ES/PT and
   Chinese via pinyin → Hanzi conversion by the AI.
5. Bring your own AI: Claude, ChatGPT, Gemini, Copilot/GitHub Models, Mistral
   or any OpenAI-compatible (local) server, with the user's own API key.
6. Privacy: text only goes from the device directly to the chosen provider;
   password/number/e-mail/URL fields never go to the AI.
7. Desktop (Mac/PC): a background app that corrects what the user types on the
   physical keyboard in every program, plus an optional on-screen keyboard
   (hex/normal) for touch screens.

---

## 2. Repository layout and shared resources

```
shared/
  SPEC.md                    this file
  layouts/layouts.json       keyboards, bottom rows, symbol pages (§3)
  layouts/accents.json       long-press alternatives (§4.4)
  languages.json             typing languages + translate targets (§5.9)
  providers.json             AI provider presets (§6)
  prompts/*.txt, prompt-parts.json   AI prompt templates (§6.4)
  emoji.json                 emoji panel content (§4.9)
  theme.json                 colours and metrics (§7.4)
  settings-defaults.json     setting keys and defaults (§8)
  strings-de.json            all UI copy (German, Swiss spelling)
  tests/engine-cases.json    test vectors for §5 (generated)
  tools/reference_engine.py  reference implementation of §5 algorithms
android/   Kotlin (Gradle). Bundles shared/ as assets via sourceSets.
apple/     Swift package TippliCore + iOS app/keyboard extension + macOS app (XcodeGen).
windows/   C# .NET 8 (WPF) tray app + on-screen keyboard.
```

Each platform MUST load the JSON/TXT files from `shared/` at build time
(Gradle `assets.srcDirs`, SwiftPM `resources: [.copy(...)]` /
XcodeGen resources, MSBuild `EmbeddedResource`/`Content` with `Link`), so a
change in `shared/` changes every app.

Each platform MUST have unit tests that load `shared/tests/engine-cases.json`
and verify its implementation of §5.2–5.7.

---

## 3. Keyboard geometry

### 3.1 Vertical structure

```
┌───────────────────────────── toolbar (theme.metrics.toolbarHeight = 40) ─┐
│ [number row]   only normal mode + setting numberRow                     │
│ letter rows    3 rows (from layouts.json keyboards.<id>.normal|hex)     │
│ bottom row     bottomRows.normal | bottomRows.hex | bottomRows.symbols  │
└──────────────────────────────────────── bottomPadding ───────────────────┘
```

* Row height `H` = `theme.metrics.rowHeight[keyHeight]` (46/52/58).
* Side padding `sp` = `theme.metrics.sidePadding`.
* Hex mode adds `H/6` padding above the first and below the last hex row
  (the pointy hexagon tips stick out of the row band).
* Total height = toolbar + rows·H (+ H/3 in hex mode) + bottomPadding.
* The emoji panel and the tips panel replace everything below the toolbar
  and keep the same total height.

### 3.2 Units

For a keyboard definition with `width` = `U` units and available width `W`:
`u = (W − 2·sp) / U`. Key `i` in a row starts at
`x = sp + (indent + Σ widths of previous keys) · u`.
The **bottom row** and the **number row** ignore `U`: their weights are scaled
so the row exactly fills `W − 2·sp`.

`$nextkb` (iOS only, when `needsInputModeSwitchKey` is true) is inserted
directly after `$123`/`$abc` with weight 1 and the space key loses 1 weight.

### 3.3 Normal keys

Rounded rectangles: the row cell `(x, y, w·u, H)` inset by `keyGap/2` on every
side, corner radius `keyCornerRadius`. Letter keys use `key` colour, special
keys use `specialKey`, the enter key uses `accent` with `accentText`.

### 3.4 Hex keys

Every key of a hex row (including `$space` and `$shift`) is a pointy-top,
vertically stretched hexagon. With centre `(cx, cy)` where
`cx = sp + (indent + i + 0.5)·u`, `cy = rowTop + H/2`, half width
`a = u/2 − hexGap/2` and full height `h = 4H/3 − hexGap`, the vertices are

```
(cx, cy − h/2)  (cx + a, cy − h/4)  (cx + a, cy + h/4)
(cx, cy + h/2)  (cx − a, cy + h/4)  (cx − a, cy − h/4)
```

Corners SHOULD be rounded by `hexCornerRadius`. Adjacent hex rows are offset
by half a key (the layouts already encode this through `indent`), so the keys
tessellate as a honeycomb. Two adjacent `$space` hexagons are drawn as two
separate space keys (both type a space; both are cursor trackpads).

### 3.5 Hit testing

* A touch in the bottom row band → the bottom-row key whose x-range contains
  the touch (clamped to first/last key).
* Normal mode letter area → row = `floor((y − top)/H)` clamped to existing
  rows, then the key whose x-range contains x, else the nearest by x.
* Hex mode letter area → the key minimising
  `dx² + (k·dy)²` with `k = (√3/2)·u/H` over **all** hex keys. (This metric
  makes the Voronoi cells identical to the drawn stretched hexagons, so there
  are no dead zones.)
* Touches in the toolbar never hit keys.

### 3.6 Labels

* Letters: the character, uppercase when shift/caps is active
  (`keyLabelSize`). In hex mode, keys whose top-row index < 10 MAY show the
  long-press digit tiny in the top-right corner when `longPressDigits` is on.
* `$shift` ⇧ (filled when one-shot shift, ⇪ / underline when caps lock),
  `$back` ⌫, `$space` the active language `short` name in `secondaryText`
  (hex: only on the first `$space`), `$enter` ⏎ or the action label from
  `strings.keys` (Senden/Suchen/Los/Weiter/Fertig), `$123` "123",
  `$sym2` "#+=", `$sym1` "123", `$abc` "ABC", `$emoji` 😊, `$nextkb` 🌐.
* Bottom-row punctuation keys use `bottomPunctuation` of the active keyboard
  if present (pinyin: `，` and `。`).

### 3.7 Symbol pages

`$123` opens `symbolPages.symbols1`, `$sym2` → `symbols2`, `$sym1` →
`symbols1`, `$abc` → letters. Symbol pages are always drawn with **normal**
(rectangular) geometry, also when hex mode is active, with
`bottomRows.symbols`. After typing a space or enter on a symbol page the
keyboard returns to letters.

---

## 4. Input behaviour

### 4.1 Pointer handling

Thresholds (dp/pt, multiply by display density):

| name | value |
|---|---|
| TAP_SLOP | 12 |
| SWIPE_UP_MIN | 28 |
| SWIPE_H_MIN | 40 |
| SWIPE_H_STEP | 40 |
| CURSOR_STEP | 10 |
| LONG_PRESS | setting `longPressMs` (default 350 ms) |
| REPEAT_DELAY | 400 ms |
| REPEAT_INTERVAL | 55 ms |
| WORD_REPEAT_AFTER | 12 deleted characters |
| WORD_REPEAT_INTERVAL | 220 ms |
| DOUBLE_TAP | 300 ms (shift) |
| DOUBLE_SPACE | 400 ms |

Every pointer is tracked independently (multi-touch). **Fast typing rule:**
when a new pointer goes down while an older pointer is still down on a
character key, has not moved beyond TAP_SLOP and has no popup open, the older
pointer is committed as a tap immediately and ignored afterwards.

Per pointer:

* **down** – hit-test the key, show pressed state, haptic/sound feedback.
  `$back`: delete one character immediately and start auto-repeat (§4.5).
  Character keys with alternatives: start the long-press timer.
* **move** – once movement exceeds TAP_SLOP cancel the long-press timer, then:
  * popup open → update the highlighted alternative (§4.4);
  * pointer started on `$space` and `|dx| > TAP_SLOP` → cursor mode: move
    the cursor one character per CURSOR_STEP of horizontal movement
    (left/right), never type a space for this pointer;
  * pointer started on any other key of the letter area or bottom row and
    `|dx| ≥ SWIPE_H_MIN` and `|dx| > 1.5·|dy|`:
    * leftwards → **delete word** (§5.6 `wordDelete`). For every further
      SWIPE_H_STEP of leftward travel in the same gesture delete one more
      word. Deleted text is pushed onto the *restore stack*.
    * rightwards → **restore** (once per gesture, §4.6).
* **up** –
  * popup open → type the highlighted alternative (nothing if cancelled);
  * swipe or cursor mode consumed the pointer → nothing more;
  * `dy ≤ −SWIPE_UP_MIN` and `|dy| > |dx|` on a letter key → type the
    **uppercase** letter (does not consume or change shift state);
  * otherwise → **tap** on the key where the pointer went down (forgiving: a
    small drift still types the original key).

### 4.2 Shift

* Tap `$shift` → one-shot shift (next letter uppercase, then off).
* Double tap within DOUBLE_TAP or long press → caps lock. Tap again → off.
* Auto-capitalisation (setting `autoCapitalize`, default on): after every
  text change and on field start, if §5.6 `autoCapitalize(textBeforeCursor)`
  is true and caps lock is off, enable one-shot shift; if false, disable a
  one-shot shift that was set automatically (never one the user tapped).
  Not in pinyin languages, not in e-mail/URL fields.

### 4.3 Space, enter, punctuation

* `$space` tap types `" "`. Double-space period (setting
  `doubleSpacePeriod`): if the previous key was also a space typed less than
  DOUBLE_SPACE ago and §5.6 `doubleSpacePeriod(textBeforeCursor)` is true,
  replace the last space with `". "`.
* `$enter`:
  * If a correction is pending or in flight and `waitOnSendMs > 0`, first
    **flush** (§5.4): run the correction immediately, wait up to
    `waitOnSendMs` for it to be applied (show a spinner on the enter key),
    then continue. A second tap on enter during the wait continues at once.
  * Then perform the editor action if the field is single-line and has one
    (send/search/go/next/done), else insert `"\n"`.
* Typing `. ! ? …` or a newline counts as a sentence end for triggers (§5.3).

### 4.4 Long-press alternatives

* Alternatives for a key = optional digit + `accents[lowercase key]`.
  * digit: if `longPressDigits` is on and the key is in the **first letter
    row** at index `i < 10`, the digit `(i+1) % 10` comes first.
  * accents: only if `longPressAccents` is on.
  * With shift/caps active every alternative is upper-cased (`ß` → `ẞ`).
* If the list is empty, a long press behaves like a slow tap.
* Popup: one row of cells above the key (cell width = `u`, min 30, height
  `0.9·H`), centred on the key and clamped to the keyboard bounds; if it does
  not fit in one row, wrap into two rows. It may overlap the toolbar.
  The first alternative is pre-selected. Moving the pointer selects the cell
  under the pointer's x (clamped to first/last; in two-row mode y picks the
  row). Moving the pointer more than `1.5·H` below the key's bottom cancels.

### 4.5 Backspace

* Tap: delete one grapheme before the cursor; if there is a selection, delete
  the selection.
* Hold: after REPEAT_DELAY delete one grapheme every REPEAT_INTERVAL; after
  WORD_REPEAT_AFTER deleted characters switch to deleting whole words
  (§5.6) every WORD_REPEAT_INTERVAL.
* Swipe left starting on `$back` behaves like swipe-left delete (§4.1).

### 4.6 Restore stack and undo

* Every swipe-left word deletion pushes the deleted text (with the cursor
  context) onto a restore stack (max 20). Typing anything else, moving the
  cursor or changing fields clears it.
* Swipe right:
  1. if the restore stack is not empty → re-insert the top entry;
  2. else if an AI correction can be undone (§5.5) → undo it;
  3. else nothing (light haptic "no-op").

### 4.7 Haptics and sound

`haptics` (default on): short tick on key down, a slightly stronger one when
a long-press popup opens, a double tick when the emotion warning appears.
`sound` (default off): platform key click.

### 4.8 Editor field rules

On every field start the platform classifies the field:

| field | AI | auto-cap | keyboard |
|---|---|---|---|
| password, PIN, one-time-code, any secure entry | **never** | off | letters |
| number, phone, date | **never** | off | symbols1 |
| e-mail, URL, username | **never** | off | letters |
| incognito / no-personalized-learning (Android flag) | off if `incognitoNoAI` | normal | letters |
| everything else | per settings | per setting | letters |

When AI is not allowed, the toolbar shows the lock state (`aiLocked`).

### 4.9 Emoji panel

`$emoji` replaces the key area with: a scrollable emoji grid (8 columns on
phones, more on wider screens), a category bar at the bottom (first "recent"
⏱, then the categories of `emoji.json` with their `icon`), plus `ABC` (back to
letters) and `⌫`. Tapping an emoji inserts it, moves it to the front of
`emojiRecents` (max 32). Emoji count as typed text for the engine.

---

## 5. Correction engine

The engine is platform-agnostic logic that talks to the text field through a
small host interface:

```
interface TextHost {
  textBeforeCursor(maxChars): String   // what we can see before the caret
  textAfterCursor(maxChars): String
  selectedText(): String
  delete(text: String)                 // delete exactly this text right before the caret
                                       // (the platform converts it to its delete unit:
                                       //  Android code points, iOS Characters,
                                       //  desktop: one Backspace per grapheme)
  insert(text: String)                 // insert at the caret
  batch(block)                         // group delete+insert to avoid flicker
}
```

### 5.1 State

* `aiEnabled` (settings) and `sessionEnabled` (toolbar dot toggles it for the
  current field; resets to true on field change).
* `fieldAllowed` (§4.8).
* Debounce timer, `inFlight` request (at most one), `rerun` flag.
* For the in-flight request: `segment`, `trailing`, `typedSince` (text the
  user appended since the request was sent), `invalid` flag.
* `history`: list of `{raw, corrected}` for the current field (max 20).
* `lastCorrection` for undo: `{raw, corrected, suffixLength}`.
* `suppressUntilBoundary` (after an undo, §5.5).
* Cache of the last 20 `segment → corrected` results.

Any non-append change (delete, cursor move, selection change not caused by
the engine, field change, swipe delete/restore, emoji panel delete) sets
`invalid = true` on the in-flight request and clears `typedSince`. Appends of
text by the keyboard add to `typedSince`.

### 5.2 Segment selection – `selectSegment(before, explicit, pinyin)`

`before` = `textBeforeCursor(1000)`; limits `maxSegment = 400`,
`maxContext = 600`. Indices are on the platform string; all cut points are
whitespace or line boundaries, so units do not matter.

1. `P` = index after the last `\n` (0 if none). `end = len(before)`,
   `trailing = ""`.
2. If **not** explicit and **not** pinyin and the last character is a letter
   or digit (a word in progress): let `k` be the index of the last whitespace;
   if `k < P` → return **null**; else `end = k+1`, `trailing = before[end:]`.
3. If `end − P > maxSegment`: `w = end − maxSegment`; `segStart` = index
   after the first whitespace at `i ≥ w` whose previous char is one of
   `. ! ? … 。 ！ ？`; else after the first whitespace at `i ≥ w`; else `w`.
   Otherwise `segStart = P`.
4. `segment = before[segStart:end]`. If it contains no letter → **null**.
5. `ctxStart = max(0, segStart − maxContext)`; if `ctxStart > 0` move it to
   after the first whitespace in `[ctxStart, segStart)`.
   `context = before[ctxStart:segStart]`.

Return `{context, segment, trailing}`. (The whole paragraph up to the cursor
is re-sent on every trigger on purpose: the prompt keeps already-correct text
unchanged, and the model gets full sentence context.)

### 5.3 Triggers

The product goal is **live conversion while typing**: the user never "writes
and then corrects" – the garbled input turns into a correct sentence word by
word, and earlier words are re-checked as more context arrives. There is no
separate conversion step afterwards.

Setting `trigger`:

* `live` (default): trigger **immediately** (no debounce) whenever a word is
  completed, i.e. a whitespace or punctuation character is typed after a
  letter or digit, and additionally on a typing pause of `pauseMs` (default
  700 ms). A sentence end (`. ! ? …`, newline) triggers immediately. Because
  only one request is in flight and `rerun` coalesces triggers, fast typing
  automatically batches several new words into the next request, and each
  request re-sends the whole paragraph so earlier words get corrected again
  with the new context.
* `pause`: every append restarts a timer of `pauseMs`; when it fires →
  correct. A sentence end triggers immediately.
* `word`: trigger when a whitespace or punctuation is typed after a letter.
* `sentence`: trigger on sentence end only.
* `manual`: only the toolbar ✨ button or flush.

Explicit triggers (✨ button, flush before send, desktop hotkey) call
`selectSegment(explicit = true)` – the word in progress is included.

Skip the request when: AI off / field not allowed / no API key / selection
non-empty / `suppressUntilBoundary` / `selectSegment` returned null / the
segment equals the last corrected output / the cache already has it (then
apply the cached result directly).

If a trigger fires while a request is in flight → set `rerun = true`; after
the in-flight result is handled, trigger again.

### 5.4 Request, cleaning and applying

1. Build the correction prompt (§6.4) and send it (timeout 10 s).
2. Clean the model output with `cleanOutput(segment, output, pinyin)`:
   1. if it contains `<segment>`, keep only the text after it; if it contains
      `</segment>`, keep only the text before it;
   2. trim; if it starts with ```` ``` ````, drop the first line and a
      trailing ```` ``` ````, trim again;
   3. if it is wrapped in one quote pair (`""`, `„“`, `“”`, `«»`, `''`,
      `‘’`) and the trimmed segment does not start with that opening quote,
      strip the pair and trim;
   4. empty → **null**; contains `<context>` or `</context>` → **null**;
   5. if not pinyin and `len(trim(segment)) ≥ 12` and the result is longer
      than `1.5·len + 10` or shorter than `0.5·len − 5` → **null** (the model
      answered instead of correcting);
   6. return `leadingWhitespace(segment) + result + trailingWhitespace(segment)`.
3. If the request is `invalid` or cleaning returned null → drop the result
   (if `rerun`, trigger again).
4. `canApply`: the current `textBeforeCursor` MUST end with
   `segment + trailing + typedSince`; otherwise drop.
5. `computeEdit(segment, corrected, suffix = trailing + typedSince)`:
   `p` = length of the common prefix of `segment` and `corrected` (moved back
   to a grapheme boundary). If `segment == corrected` → nothing to do.
   Else `delete = segment[p:] + suffix`, `insert = corrected[p:] + suffix`.
6. Apply inside `batch { delete(delete); insert(insert) }`, mark the change
   as engine-made (must not invalidate anything or clear the restore stack),
   append `{raw: segment, corrected}` to `history`, store `lastCorrection`,
   show the toolbar **Original** button.
7. Flush (before send / explicit): cancel the debounce timer, send
   immediately with `explicit = true`, await up to `waitOnSendMs`.

### 5.5 Undo of an AI correction

Possible while `lastCorrection` exists and `textBeforeCursor` ends with
`corrected + X` where X is whatever was typed since (no deletes/cursor moves).
Undo = `delete(corrected[p:] + X)`, `insert(raw[p:] + X)` (same prefix rule),
then set `suppressUntilBoundary = true` (no automatic correction until the
user types a sentence end or newline) and clear `lastCorrection`.
Triggered by the toolbar **Original** button or swipe right (§4.6).

### 5.6 Small text helpers (exact behaviour in tests/engine-cases.json)

* `wordDelete(before)` → the text a swipe-left removes: a trailing `\n` alone;
  otherwise trailing spaces plus the preceding run of word characters
  (letters, digits, `' ’ - _`), or – if the last non-space char is something
  else – the preceding run of non-word, non-space characters (punctuation,
  emoji).
* `autoCapitalize(before)` → true for an empty field, after a newline, or
  when the text ends with whitespace and the last non-whitespace character
  is a sentence end (`. ! ? … 。 ！ ？`).
* `doubleSpacePeriod(before)` → true when `before` ends with a single space
  preceded by a letter, digit or closing bracket/quote.
* `canApply`, `computeEdit`, `cleanOutput`, `selectSegment` as above.

### 5.7 Mood check (emotion warning)

If `moodWarning` and `tipsEmotion` are on and the field allows AI: after a
correction was applied whose segment contains a sentence end, and at most once
per 20 s and only when the paragraph changed by ≥ 20 characters since the last
check, send the last paragraph (max 1000 chars) with the mood prompt. Parse
`{"level", "reason"}` (§6.5). Level ≥ 2 → show the **Emotional** chip in the
toolbar (warning colour for 2, danger colour for 3) and do a double haptic
tick the first time it appears; level < 2 → hide it. Tapping the chip opens
the tips panel scrolled to the emotion section.

### 5.8 History for explanations

`history` feeds the explanations in the tips panel: lines
`raw -> corrected` (newest last, max 20) in the tips user prompt.

### 5.9 Languages

`activeLanguage` (one of `languages`) decides the keyboard layout
(`keyboard.<id>` override or `languages.json` default) and the prompt texts.
The toolbar language chip cycles through the enabled `languages`; long press
shows the full list. Pinyin languages (`pinyin: true`) skip the
word-in-progress exclusion and the length check, and disable auto-cap.

---

## 6. AI providers

### 6.1 Presets

`providers.json` lists the presets. The user picks one, enters the API key
(stored in secure storage as `apiKey.<providerId>`), picks a model from the
list, or types any model id ("Eigenes Modell"), or taps **Modelle laden**
(§6.6). The `custom` provider also has an editable base URL.

### 6.2 Anthropic (Claude) – protocol `anthropic`

```
POST {baseUrl}/messages
x-api-key: <key>
anthropic-version: 2023-06-01
content-type: application/json
{ "model": m, "max_tokens": N, "system": <system prompt>,
  "messages": [{"role": "user", "content": <user prompt>}], ...profile }
```

Model profile (prefix match on the model id):

| model | extra body | extra header |
|---|---|---|
| `claude-opus-5*`, `claude-fable-5*` | `"output_config": {"effort": E}`, `"fallbacks": "default"` | `anthropic-beta: server-side-fallback-2026-07-01` |
| `claude-sonnet-5-5*` | `"thinking": {"type": "between_tools"}`, `"output_config": {"effort": E}`, `"fallbacks": "default"` | `anthropic-beta: server-side-fallback-2026-07-01` |
| `claude-haiku-5-5*` | `"thinking": {"type": "disabled"}`, `"output_config": {"effort": E}` | – |
| anything else | – | – |

`E` = `"low"` for correction and mood, `"medium"` for tips and translation.
Never send `temperature`/`top_p` to Claude models. `max_tokens`: correction
2048, mood 1024, tips 8000, translate 4000.

Response: if `stop_reason == "refusal"` → error `refused` (correction: keep
the raw text silently). Otherwise the text is the concatenation of all
`content[]` blocks with `type == "text"` (thinking blocks are ignored).

### 6.3 OpenAI-compatible – protocol `openai`

```
POST {baseUrl}/chat/completions
Authorization: Bearer <key>          (omitted when the key is empty)
content-type: application/json
+ preset.extraHeaders
{ "model": m,
  "messages": [{"role":"system","content":S},{"role":"user","content":U}],
  <preset.maxTokensField>: N,
  "temperature": 0.2                  (only if preset.sendTemperature)
  "reasoning_effort": "low"           (only if preset.reasoningEffort is set
                                       and the model id starts with gpt-5 or o)
  "response_format": {"type":"json_object"}   (JSON tasks, only if preset.jsonMode) }
```

`N` as in §6.2 but at least 4000 for providers with `reasoningEffort`
(reasoning tokens count against it). Response text =
`choices[0].message.content`.

**Optional-parameter fallback:** if the server answers HTTP 400 or 422 and the
request contained `temperature`, `reasoning_effort` or `response_format`,
retry once without all three and remember "plain mode" for this
provider+model for the rest of the process lifetime.

### 6.4 Prompts

Placeholders `{{name}}` in `shared/prompts/*.txt` are replaced literally:

| task | system | user |
|---|---|---|
| correction | `correction-system.txt` with `language` = language.promptName, `languageRules` = language.rules, `strengthRule` = `prompt-parts.strengthRules[strength]`, `keyboardLayout` = the `name` of the active keyboard from layouts.json plus its three letter rows joined with " / " (e.g. `QWERTZ (Deutsch/Schweiz, ä ö ü): qwertzuiopü / asdfghjklöä / yxcvbnm`) | `correction-user.txt` with `context`, `segment` |
| tips | `tips-system.txt` with `uiLanguage` = `prompt-parts.uiLanguage`, `language`, `variants` = comma separated `id ("label": instruction)` for the enabled `rewriteVariants`, `disabledSections` = names from `prompt-parts.sections` of disabled sections or `none` | `tips-user.txt` with `text`, `corrections` (§5.8, or `(none)`) |
| mood | `mood-system.txt` with `uiLanguage` | the paragraph as plain text |
| translate | `translate-system.txt` with `targetLanguage` = translateTargets[i].promptName | the text |
| connection test | correction prompts with `context` = "" and `segment` = `prompt-parts.testSegment` | – |

### 6.5 JSON parsing (tips, mood)

Take the substring from the first `{` to the last `}` (after removing code
fences) and parse it. Missing fields default to empty values. Parsing errors →
`badResponse` error with a "Nochmals versuchen" button.

### 6.6 Model list

**Modelle laden**: `GET {baseUrl}/models` with the same auth headers
(Anthropic: `x-api-key` + `anthropic-version`). Show `data[].id` sorted,
Claude/GPT/Gemini chat models first. Errors are shown inline.

### 6.7 Errors and timeouts

| condition | error key (`strings.errors`) |
|---|---|
| no key and preset.keyRequired | `noKey` |
| 401/403 | `invalidKey` |
| 404 | `badModel` |
| 429 | `rateLimited` |
| ≥ 500, 529 | `server` |
| network failure | `network` |
| timeout (correction 10 s, mood 15 s, tips/translate 45 s) | `timeout` |
| refusal | `refused` |

Correction errors never interrupt typing: the toolbar dot turns red; tapping
it shows the message (toast) and offers settings. After 3 consecutive
correction errors pause automatic triggers for 30 s.

---

## 7. Toolbar, tips panel and visual design

### 7.1 Toolbar (height 40)

Left to right:

1. **AI dot** + provider short name (e.g. "Claude"): accent = ready,
   pulsing = working, red = error, grey = off, lock = field not allowed.
   Tap = toggle `sessionEnabled` (or show the error if red).
2. **Original** (↶) – only while undo is possible (§5.5).
3. **Emotional** chip – only while mood level ≥ 2 (§5.7).
4. flexible space
5. **✨** correct now (always visible in `manual` mode, otherwise MAY be hidden
   behind long-press on the AI dot).
6. **💡 Tipps** – opens the tips panel.
7. **Language chip** (`short` name) – tap cycles, long press lists.
8. **⬡ / ▭** layout toggle – switches `keyboardMode` (hex ↔ normal) and
   persists it.
9. **⚙** settings (opens the app/settings window).

### 7.2 Tips panel

Replaces the key area (same height), scrollable, with a header
`Tipps · Für die Auswahl | Für den ganzen Text` and a close button.
Text scope = selected text if any, else the whole field (as far as the
platform can read it, max 4000 chars). If the text is empty show `emptyText`.

One tips request (§6.4) fills these sections (each only if enabled in
settings):

1. **Umformulierungen** – one card per variant (label + text). Tap card or
   `Ersetzen` → replace the scope text; `Kopieren` copies.
2. **Was wurde korrigiert?** – `from → to: why` list.
3. **Schreibtipps** – bullet list.
4. **Emotions-Check** – level label (`emotionLevels`), reason, and the calmer
   version as a card (Ersetzen/Kopieren) when level > 0.
5. **Übersetzen** – chips for the `translateTargets`; tapping one runs a
   translate request and shows a card with `Ersetzen`, `Einfügen`,
   `Kopieren`.

Replace = delete the scope text and insert the new one (selection: replace the
selection; whole field: select all/delete all and insert). This is an engine
change: clear history and `lastCorrection`.

### 7.3 Settings / companion app

Sections in this order: KI-Anbieter, Korrektur, Sprachen, Tastatur, Tipps,
Datenschutz, (desktop only) Computer, Über Tippli. Mobile apps start with a
3-step onboarding (enable keyboard → connect AI → try it in a text field) and
show the gesture help (`strings.gestures`). All copy from `strings-de.json`.

### 7.4 Visual design

* Colours from `theme.json` (`light`/`dark`, chosen by `theme` setting).
* Keys: soft 1-dp bottom shadow (`keyShadow`), no borders.
* Pressed state: `keyPressed` + (normal mode, letter keys) a key preview
  bubble above the finger MAY be shown.
* Popup: `popup` background, selected cell `popupSelected` with `accentText`.
* Platform-native settings UI (Material 3 on Android, SwiftUI Form on Apple,
  WPF with a clean Fluent-like style on Windows).
* App icon: a rounded hexagon in `accent` with a white sparkle
  (shared/branding).

---

## 8. Settings

Keys and defaults: `settings-defaults.json`. All platforms use the same key
names. Secure values: `apiKey.<providerId>`. Derived keys:
`model.<providerId>` (default `defaultModel`), `keyboard.<languageId>`.

Storage:

* Android: SharedPreferences (`tippli_settings`) + API keys encrypted with an
  Android Keystore AES-GCM key.
* iOS: `UserDefaults(suiteName: "group.<bundlePrefix>.tippli")` shared between
  app and keyboard extension; API keys in the Keychain with a shared access
  group.
* macOS: `UserDefaults.standard`; API keys in the Keychain.
* Windows: `%APPDATA%\Tippli\settings.json`; API keys encrypted with DPAPI
  (`ProtectedData`, CurrentUser) in `%APPDATA%\Tippli\keys.dat`.

Changes made in the settings app MUST be picked up by the keyboard the next
time it is shown (mobile) or immediately (desktop).

---

## 9. Platform notes

### 9.1 Android (`android/`)

* `InputMethodService` with a custom `View` (Canvas) for keys, toolbar, emoji
  and tips panels (no Compose inside the IME). Settings + onboarding activity
  in Jetpack Compose (Material 3).
* `minSdk 26`, `targetSdk 35`. Text ops via `InputConnection` with
  `beginBatchEdit`/`endBatchEdit`, `deleteSurroundingTextInCodePoints`,
  `commitText`, `getTextBeforeCursor`, `getSelectedText`, `performEditorAction`.
  Engine-made changes are recognised in `onUpdateSelection` by the expected
  new cursor position.
* Field classification from `EditorInfo.inputType`/`imeOptions`
  (`IME_FLAG_NO_PERSONALIZED_LEARNING` = incognito).
* A pure-Kotlin `:core` module (engine, geometry, gestures, AI clients,
  prompts, JSON models) with JVM unit tests using engine-cases.json.

### 9.2 iOS (`apple/`)

* Container app (SwiftUI): onboarding, settings, test field.
* Keyboard extension: `UIInputViewController`, UIKit view with multi-touch
  `touchesBegan/Moved/Ended` (SwiftUI gestures lose touches during fast
  two-thumb typing). `RequestsOpenAccess = YES`; without full access the AI is
  off and the toolbar shows `fullAccessNeeded`.
* `textDocumentProxy` as `TextHost`: `deleteBackward()` per Character,
  `insertText`, `documentContextBeforeInput`, `adjustTextPosition` for the
  space-bar cursor. `needsInputModeSwitchKey` → `$nextkb` with
  `handleInputModeList(from:with:)`.
* Keep the extension light (memory limit): lazy-load emoji, no big caches.

### 9.3 macOS (`apple/`)

* Menu-bar app (`LSUIElement`), SwiftUI settings window, shared `TippliCore`.
* Live correction via `CGEventTap` (desktop engine §10); characters from
  `UCKeyTranslate` with our own dead-key state; injection with `CGEvent`
  (`keyboardSetUnicodeString`, Backspace keycode 51) tagged with
  `eventSourceUserData = 0x54495050` ("TIPP") and ignored by our tap.
* Permissions: Accessibility (`AXIsProcessTrustedWithOptions`) and Input
  Monitoring (`CGRequestListenEventAccess`), with an onboarding screen.
* On-screen keyboard: non-activating `NSPanel` (`.nonactivatingPanel`,
  floating, all spaces) drawing the same keyboard (shared renderer).
* Launch at login via `SMAppService.mainApp`.

### 9.4 Windows (`windows/`)

* .NET 8 WPF tray app (single instance), `Tippli.Core` class library
  (net8.0, no WPF) + xUnit tests.
* Live correction via `SetWindowsHookEx(WH_KEYBOARD_LL)` and
  `WH_MOUSE_LL`; characters via `ToUnicodeEx` with flag `0x4` (do not change
  keyboard state) and our own dead-key composition; injection via `SendInput`
  (`KEYEVENTF_UNICODE`, `VK_BACK`) with `dwExtraInfo = 0x54495050`; injected
  events (`LLKHF_INJECTED`) are ignored by the hook.
* Password fields: UI Automation `AutomationElement.FocusedElement` →
  `IsPassword`.
* On-screen keyboard: topmost WPF window with `WS_EX_NOACTIVATE` so the target
  app keeps focus.
* Launch at login: `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`.

---

## 10. Desktop engine (macOS + Windows)

Desktop apps cannot read the text of other programs, so the engine keeps its
own **buffer** = our best knowledge of the text before the caret in the
focused field (max 2000 chars). `TextHost.textBeforeCursor` returns the
buffer; `delete` sends one Backspace per grapheme and updates the buffer;
`insert` injects Unicode text and updates the buffer.

Buffer rules:

* Printable characters (after dead-key composition) are appended.
* Backspace removes the last grapheme (if the buffer is empty nothing is
  known any more; keep it empty).
* Reset (clear buffer, invalidate requests) on: mouse click, focus/foreground
  window change, arrow keys, Home/End/PageUp/PageDown, Escape, Tab, any
  Ctrl/Cmd/Win shortcut (Ctrl+Backspace, paste, undo, …), Enter.
* Ignore our own injected events.
* Skip everything while the foreground app's process/bundle name is in
  `desktopExcludedApps`, while the focused element is a password field, or
  while secure input is on (macOS).
* Enter/Shift+Enter: if a correction is pending/in flight and
  `waitOnSendMs > 0`, swallow the key, flush (§5.4), then re-inject the same
  key. Never hold a key longer than `waitOnSendMs`.

Hotkeys (Windows `Ctrl+Alt+…`, macOS `⌃⌥…`): **K** toggle live correction,
**H** tips for the selected text (copy via simulated Ctrl/Cmd+C with clipboard
restore; falls back to the buffer), **B** show/hide on-screen keyboard,
**J** correct now (selected text → replace via paste; else flush the buffer).

Tray / menu-bar menu: status line (provider + on/off), Live-Korrektur an/aus,
Bildschirmtastatur, Tipps für Auswahl, Einstellungen…, Beenden.

The desktop tips window shows the same sections as §7.2; "Ersetzen" pastes
over the selection (or replaces the buffer text by backspacing and typing).

The on-screen keyboard uses the same layouts, toolbar and gestures (§3, §4)
with mouse/touch/pen; keys inject into the focused app and feed the buffer as
if typed.

---

## 11. Testing and CI

* `shared/tools/reference_engine.py` regenerates `tests/engine-cases.json`.
* Android: `./gradlew :core:test :app:assembleDebug`.
* Apple: `swift test` in `apple/TippliCore`, `xcodegen` + `xcodebuild` for the
  iOS app (simulator, no signing) and the macOS app.
* Windows: `dotnet test` + `dotnet publish` (win-x64, self-contained,
  single file).
* GitHub Actions (`.github/workflows/build.yml`) runs all of the above and
  uploads the APK, the macOS app and the Windows exe as artifacts.
