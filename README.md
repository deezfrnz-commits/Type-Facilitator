# TypeFacilitator

A small, offline English keyboard for Android. Every key is a hexagon, laid out as a honeycomb.
No internet permission, no third-party libraries, no AndroidX: the APK holds only the keyboard's
own code, an English word list and the emoji list.

## Features

| Area | Details |
|---|---|
| Keys | Honeycomb QWERTY, optional number row, two symbol pages, number pad for number/phone fields, long-press alternatives (accents, digits, extra symbols), shift / caps lock (double tap) |
| Emoji and symbols | Every fully-qualified Unicode emoji by category, skin tones on long press, recently used; a symbol catalog (punctuation, currency, maths, arrows, shapes, Greek, fractions, accented letters, marks, box lines) |
| Swipe typing | Slide across the letters of a word; other readings of the swipe are offered above the keys |
| Words | Completions, auto-correct (backspace undoes it), next-word guesses, learning of your words and word pairs, misspelled words underlined, hold a suggestion to remove it |
| Clipboard | Keeps the last 100 copied texts in any language; tap one to type it, pin or delete it |
| Modes | One-handed (left or right), floating (drag to move, corner to resize), landscape |
| Editing | Cursor arrows, select, copy, cut, paste, undo, redo; slide on the space bar to move the cursor |
| Look | Black / silver / white by default, more themes, every colour adjustable, key height, letter size, outlines |
| Privacy | Works without a network; learned words, clipboard and caches can be viewed and deleted in the settings; nothing is learned in password fields |

Android 5.0 (API 21) and newer.

## Build

Requires JDK 17 and the Android SDK (platform 35).

```
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew :core:test               # typing engine tests
```

Without the Android SDK, `TF_CORE_ONLY=1 ./gradlew :core:test :localcheck:test` tests the typing
engine and compiles the Android sources against the framework classes from Maven Central.

`.github/workflows/typefacilitator.yml` builds the APKs, then runs `scripts/emulator_test.py` on
Android emulators (API 34 and API 23). That script enables the keyboard and uses it like a person:
typing, swiping, emoji, clipboard, one-handed and floating mode, settings.

## Layout

* `core/` - typing engine in plain Kotlin: dictionary, completion and correction, next-word
  prediction, learning, swipe decoding.
* `app/` - the Android keyboard: `KeyboardView` (hexagon keys, touch), `TfService` (input logic),
  `GridPanel` (emoji and symbols), `ClipPanel`/`ClipStore` (clipboard), `SettingsActivity`.
* `data/`, `scripts/gen_assets.py` - sources and generator for the word list and emoji data.

## Data sources

* English word list: Android Open Source Project, LatinIME `en_US_wordlist.combined` (Apache License 2.0).
* Emoji list: Unicode `emoji-test.txt` (Unicode License).
