# AnkiDroid for Wear OS

A minimal watch app (built for Galaxy Watch 7) to study existing decks and add English words.

- Deck list with new / learning / review counts
- Study: tap to show the answer, then tap the **left side for Again** or the **right side for Good**
  (each side is about 30% of the screen; the middle does nothing). The sides outside the text are
  tinted red / green as a reminder; the tapped side lights up, and Good buzzes once, Again twice. Taps are
  ignored for 0.4 s after the answer appears, so a double tap on *Show* doesn't answer the card.
  Swipe right or press the back button to return to the deck list
- While studying, hold a word for half a second to add it as a new card: it's highlighted and an
  *Add* button appears at the bottom, which opens *Add word* for it. Tap anywhere else to let go
  of the word (that tap doesn't answer the card); a hold never answers it
- While studying, the word is centered and the meanings are left-aligned, in the watch's
  condensed font so more fits on screen. The top of the screen shows how many cards are left
  today, with no clock
- Back to the deck list after studying: the phone syncs with AnkiWeb, then the counts refresh
- Text only: images, audio and other media are left out
- **Add word** (top of the deck list): type, write or say an English word, check its meanings,
  pick a deck and add it. The meanings come from [anki-new-card](https://anki-new-card.vercel.app),
  so the card looks like the ones added from the website or `ank.py`

The phone does all the work. AnkiDroid on the phone owns the collection, picks the next card,
schedules the answers and syncs with AnkiWeb as usual. The watch shows the cards and sends back
your rating over Bluetooth (or Wi-Fi), using the Wear OS Data Layer. Nothing is stored on the watch,
so it needs the phone nearby.

## How it fits together

| Part | Where |
|---|---|
| Watch app (Compose for Wear OS) | `wear/` |
| Messages shared by phone and watch | `wear-protocol/` (`WearProtocol.kt`) |
| Phone-side handler: decks, next card, answers | `AnkiDroid/src/main/java/com/ichi2/anki/wear/WearRequestHandler.kt` |
| Card HTML → watch-friendly HTML | `AnkiDroid/src/main/java/com/ichi2/anki/wear/WatchCardFormatter.kt` |
| Phone-side Data Layer service (`play` flavor only) | `AnkiDroid/src/play/java/com/ichi2/anki/wear/WearBridgeService.kt` |
| Word lookup (anki-new-card) and the card's back | `AnkiDroid/src/main/java/com/ichi2/anki/wear/WordLookup.kt`, `WordCard.kt` |
| Gemini API key, stored encrypted | `AnkiDroid/src/main/java/com/ichi2/anki/wear/GeminiApiKey.kt` |

Studying on the watch behaves like studying on the phone: the watch's deck becomes the
selected deck on the phone, and answers show up in the phone's stats, undo history and next sync.

## Adding words

1. **Once, on the phone:** *AnkiDroid → Settings → Advanced → Watch → Gemini API key*, and paste
   your key from [Google AI Studio](https://aistudio.google.com/). Leave it empty to remove it.
2. **On the watch:** *Add word* at the top of the deck list. The watch's input screen opens:
   keyboard, handwriting or voice (switch the voice language to English for single words).
3. The watch shows the meanings, examples and IPA that will go on the back of the card. Tap
   *Deck* to pick another deck, then *Add*. The deck is remembered for next time; the first
   time it's the deck the phone's Add screen would pick.
4. Back on the deck list, the phone syncs with AnkiWeb.

What gets added: a **Basic** note, the word lowercase on the front and the meanings on the back,
built like the website builds them. If a Basic note already has the word on its front (any case),
the watch offers *Update* instead: the back is replaced, and the note keeps its deck and schedule
(like `ank.py --add`).

Where the Gemini API key lives:

- **Only on the phone**, typed into the setting above. Not in the source code or the APK, and the
  watch never sees it: the watch sends the word, the phone does the lookup.
- **Encrypted** with an AES key kept in the Android Keystore. AnkiDroid allows Android backups, so
  the stored value may end up in a Google Drive backup, but no other device can decrypt it. After
  restoring to a new phone, set the key again (the watch tells you to).
- **Sent** only to `https://anki-new-card.vercel.app/api/gemini`, in the body of an HTTPS request,
  as the website and `ank.py` send it.

## Requirements

The Data Layer only connects an app with its own counterpart. Phone and watch apps must have:

1. **The same application ID.** Release builds are both `com.ichi2.anki`, debug builds are both
   `com.ichi2.anki.debug`. A debug watch app doesn't talk to a release phone app.
2. **The same signing key.** Sign both with the same keystore (for release builds:
   `~/.config/android-studio/testkey.jks`, alias `key0`, the one used for the phone so far).
3. **The phone app built from this project (`play` flavor).** It contains the `WearBridgeService`.

## Build and install

### 1. Phone

Build and install the phone app as before (`play` flavor, release), so that it contains the bridge.
Either use Android Studio (*Build → Generate Signed App Bundle or APK → APK*, module `AnkiDroid`,
variant `playRelease`), or Gradle with the key in environment variables:

```sh
export KEYSTOREPATH=~/.config/android-studio/testkey.jks KEYALIAS=key0 KEYSTOREPWD=... KEYPWD=...
./gradlew :AnkiDroid:assemblePlayRelease
```

### 2. Watch

Build the watch app with **the same key**: in Android Studio pick module `wear`, variant
`release`, or run `./gradlew :wear:assembleRelease` with the same environment variables.

Connect the watch with adb over Wi-Fi (once):

1. On the watch: *Settings → About watch → Software information*, tap *Software version* 5 times.
2. *Settings → Developer options*: turn on *ADB debugging* and *Wireless debugging*.
3. In *Wireless debugging*, tap *Pair new device* and run on the computer:
   `adb pair <ip>:<pairing port>` (enter the code shown on the watch), then
   `adb connect <ip>:<port>` (the address shown on the *Wireless debugging* screen).

Install:

```sh
adb -s <ip>:<port> install -r wear/build/outputs/apk/release/wear-release.apk
# or, if built with Android Studio's "Generate Signed APK":
adb -s <ip>:<port> install -r wear/release/wear-release.apk
```

## Troubleshooting

| Message on the watch | Likely cause |
|---|---|
| *The phone isn't connected* | Bluetooth is off, or the watch isn't paired with the phone. |
| *AnkiDroid with watch support isn't on the phone* | The phone app is an older build without the bridge, or it was built with a different key or application ID (debug vs release). |
| *AnkiDroid on the phone didn't answer in time* | The phone was busy (e.g. syncing a large collection). Try again. |
| *The phone and watch apps don't match* | One side is older. Rebuild and install both. |
| *Set your Gemini API key on the phone…* | No key yet, or the phone was restored from a backup. See [Adding words](#adding-words). |
| *Couldn't reach anki-new-card…* | The phone is offline, or anki-new-card is down. |
| *API 호출 제한에 도달했습니다…* and other Gemini errors | Passed on from anki-new-card: rate limit or a wrong API key. |

Logs: `adb -s <watch> logcat -s AnkiWear` on the watch; `adb -s <phone> logcat | grep -i watch` on the phone.
