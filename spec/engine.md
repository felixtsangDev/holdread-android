# Hold-to-read engine spec

The reading engine is the part that has to behave the same on Android and iOS. This file states
its rules as exact numbers; `spec/cases/*.json` are finger traces with expected results that both
apps replay in their unit tests. The rules are ported 1:1 from the web prototype.

Android implementation: `engine/src/main/kotlin/app/holdread/engine/`.

## Words

- Text is split with ICU word breaking (Android `android.icu.text.BreakIterator`; iOS should use
  ICU-compatible segmentation, e.g. `NLTokenizer(.word)`, and check the CJK unit counts).
- A segment is a word if it contains a letter/digit or a CJK character
  (U+3400–9FFF, U+F900–FAFF, U+3040–30FF, U+AC00–D7AF).
- Punctuation joins a neighbour so it never wraps alone. Opening marks `“ ‘ ( [ { 「 『 《 〈 " '`
  go to the next word; everything else to the previous word. A straight quote directly after a word
  closes it. Punctuation with no neighbour (a dash between spaces) stays in the text but is not a word.
- **Units**: a CJK word counts one unit per character; any other word counts 1.
- **Pause**: 2 if the trailing punctuation has `. ! ? 。 ！ ？ …`, 1 if it has `, ; : ， ； ： 、 —`.
- The last word of each paragraph/heading is a paragraph end.

## Speed cap (minimum dwell per word)

`base = 60000 / wpm` ms, wpm 100–900, default 350.

| | dwell |
|---|---|
| CJK word | `base × units` |
| other word | `base × min(1.6, 0.6 + 0.05 × length)` |
| + sentence end | `+ 0.5 × base` |
| + clause break | `+ 0.25 × base` |
| + paragraph end | `+ 0.4 × base` |

The cursor advances at most one word per frame, and only when the dwell since the last advance
(or since the finger went down) has passed. A faster finger does not make the cursor faster.

## Finger → word

The track mirrors the line being read: a word's x-span on the track equals its x-span on screen.

- **Position cursor** (default): the finger is on the last word of the line whose middle it has
  passed (`line.first − 1` when none).
- **Word cursor**: the nearest word on the line within 40 px, else none (−1).

## Each frame while the finger is down

With `j` = finger word, `i` = next word to read:

1. Waiting for a lift (chapter just ended): do nothing.
2. Rewinding (line just changed): once `j ≤ i`, rewinding ends. Status `rewind`.
3. `j > i + 3`: status `ahead`; remember when this started. Nothing advances.
4. Otherwise:
   - if we were ahead for **more than 600 ms**, count one **skip**;
   - going back: with `b` = word at finger x + 8 px (position cursor) or `j` (word cursor), if
     `b < i − 1` and `b + 1 ≥ line.first`, move back to `max(line.first, b + 1)` and un-count those
     words. Going back never leaves the line;
   - else if `j ≥ i` and the dwell has passed: advance. If the next word is on a new line, start
     rewinding. If it was the chapter's last word, the chapter ends and the finger must lift;
   - status: `fast` if `j > i + 1`, `go` if `j ≥ i − 1` (and on a word), else `idle`.
5. Reading time grows by the frame's duration (capped at 250 ms) while the cursor advanced in the
   last 2500 ms.

Finger down resets rewinding and lift-waiting. Finger up sets status `idle`.

## Sessions

Goals: 5, 10, 20, 30, 45 minutes of reading time, or "finish this chapter". Leaving the app counts
a leave. Giving up needs a 3 s hold and resets the streak. A streak counts consecutive days with a
completed session.

## Test case format (`spec/cases/*.json`)

```json
{
  "name": "…",
  "settings": { "wpm": 600, "cursor": "position" | "word" },
  "blocks": [["line one of paragraph one", "line two"], ["paragraph two"]],
  "charWidth": 10, "spaceWidth": 10, "frameMs": 16,
  "script": [
    { "at": 0, "down": 0 },
    { "at": 16, "move": { "word": 2, "f": 0.6 } },
    { "at": 0, "slide": { "to": 175, "until": 1000 } },
    { "at": 500, "up": true },
    { "at": 400, "expect": { "index": 3, "status": "go", "units": 3, "skips": 0,
                             "readingMs": 2496, "rewinding": false, "needLift": false, "chapterEnded": false } }
  ]
}
```

- Layout is monospace: every character `charWidth` (10) wide, words separated by `spaceWidth` (10),
  each line starting at x = 0. Each string in a block is one line.
- An x is a number, or `{ "word": i, "f": 0.6 }` = `f` of the way across word `i` (default 0.6).
- The clock ticks every `frameMs` (16) from 0. On each tick: apply steps whose `at` has been
  reached (in order), move the finger along any active slide, run one engine frame if the finger is
  down, then check expects. A slide moves linearly from where the finger is to `to` by `until`.
