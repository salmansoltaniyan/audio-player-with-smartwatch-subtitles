# Audio Player with Smartwatch Subtitles

An Android audio player for language lessons that shows the current sentence on a Wear OS smartwatch.

You listen to a lesson on the phone (with earphones), and the watch shows the text that is being spoken
right now: Dutch left-to-right, Persian right-to-left, in large letters. You can see how a new word is
spelled without taking out the phone.

> Status: in development.

## How it works

The project has two apps in one Gradle project:

| Module  | Runs on              | What it does |
|---------|----------------------|--------------|
| `phone` | Android phone        | Plays an MP3 lesson with its JSON timeline. Play / Pause / previous segment / next segment. Keeps playing with the screen off. Optional: repeat the lesson when it ends, open the watch app when playback starts. Sends the current segment's text to the watch and shows it on the phone. |
| `wear`  | Wear OS watch        | Shows the received text in large letters and keeps the screen on while a lesson plays. Controls the phone (see below). |

Watch controls:

| Control                     | Action |
|-----------------------------|--------|
| ⟲ (middle button)           | Play the current segment again from its start. |
| ⏮ / ⏭                       | Play the previous / next segment. |
| Tap the text                | Play / pause. |
| Touch bezel (Galaxy Watch)  | Clockwise: next segment. Counter-clockwise: previous segment. |

To open the watch app from the watch face, add the "Lesson Subtitles" complication to a slot of the
watch face (long-press the watch face → Customize → Complications).

The phone and the watch talk through the Wearable Data Layer API (`MessageClient`, `ChannelClient`).
For this to work, both apps use the same `applicationId` and are signed with the same key.

## Lessons folder and playlists

The phone app works with one **lessons folder** that you choose once (Library tab). The folder and
all its sub folders are scanned for audio files, sorted by name (`gang9` comes before `gang11`).

- **Library tab:** all lessons, grouped by sub folder. Tap a lesson to play its sub folder from there.
  Long-press to select lessons and add them to a playlist. Pull down to scan again.
- **Playlists tab:** your own playlists. Create, rename, delete; open one to play from any lesson,
  drag ≡ to reorder, ✕ to remove a lesson.
- **Player tab:** ⏮ Lesson / Lesson ⏭ move through the playlist (or sub folder). "At the end" sets
  what happens when a lesson ends: stop, repeat the lesson, play the next lesson, or play the next
  lesson and start again after the last one. "Speed" plays slower or faster (0.5× to 2×) without
  changing the pitch; the text on the phone and the watch stays in sync.
- Every lesson remembers where you stopped and continues there next time (also after closing the
  app); a lesson that was played to the end starts again from the beginning. Drag the bar on the
  Player tab to go to any point, for example back to the start.
- When the app opens, the last lesson is loaded (paused) at its position.

Playlists are saved in the lessons folder, in `playlists/<name>.json`, so they survive a reinstall and
can be edited on a computer. Lesson paths are relative to the lessons folder:

```json
{
 "name": "New words chapter 11",
 "lessons": [
  "gang11/gang11_output9_learn_test_2026-11-19_21-03-01.mp3",
  "gang12/gang12_output1_learn_2026-11-25_20-10-44.mp3"
 ]
}
```

A lesson that was moved or deleted is shown as "Not found" in the playlist and skipped when playing.

## Lesson files

Each lesson is an audio file (usually MP3) with a JSON timeline. The app looks for the timeline in
this order:

1. **Inside the MP3:** an ID3v2.3/2.4 tag with a `TXXX` frame whose description is
   `SHADOWING_TIMELINE`; its value is the JSON on one line (UTF-8). No other file is needed.
2. A JSON file with the same name in the same folder (`lesson.mp3` + `lesson.json`).
3. A JSON file in the same folder whose `"audio"` field is the audio file's name.

A lesson without any timeline plays without subtitles. To check the tag on a computer:
`ffprobe -v error -show_entries format_tags=SHADOWING_TIMELINE -of default=nw=1:nk=1 lesson.mp3`

Example JSON:

```json
{
  "audio": "lesson.mp3",
  "duration": 467.166,
  "segments": [
    {"start": 0.0,   "speech_end": 2.544, "end": 3.816,  "row": 1, "tag": "e", "lang": "persian", "text": "سال‌های سال زنده باشد!"},
    {"start": 3.816, "speech_end": 7.055, "end": 11.913, "row": 1, "tag": "a", "lang": "dutch",   "text": "Lang zal ze leven!"}
  ]
}
```

| Field        | Meaning |
|--------------|---------|
| `start`      | Time (seconds) when the speech starts. |
| `speech_end` | Time when the speech ends. From here to `end` is a pause for the listener to repeat. |
| `end`        | End of the segment; equal to the next segment's `start`. |
| `row`        | Line number in the lesson (one exercise). |
| `tag`        | Speaker tag. |
| `lang`       | `dutch`, `persian`, `english` or `german`; Persian is shown right-to-left. |
| `text`       | The text to show. |

A segment is shown from its `start` until the next segment's `start`, including the pause.

## Building

The APKs are built by GitHub Actions; Android Studio is not needed.

1. Create a signing keystore once (for example with `keytool`). Never commit it, and keep a backup:
   a new key means the apps must be uninstalled and installed again.
2. In the GitHub repository, open **Settings → Secrets and variables → Actions** and add:

   | Secret              | Value |
   |---------------------|-------|
   | `KEYSTORE_BASE64`   | The keystore file, base64-encoded. |
   | `KEYSTORE_PASSWORD` | The keystore password. |
   | `KEY_ALIAS`         | The key alias. |
   | `KEY_PASSWORD`      | The key password. |

3. Push to `main` (or start the workflow by hand on the **Actions** page). The workflow
   [`.github/workflows/build.yml`](.github/workflows/build.yml) builds both signed APKs.
4. Every build is published as a GitHub release (tag `build-<number>`) with
   `lesson-subtitles-phone.apk` and `lesson-subtitles-watch.apk`. The APKs are also on the run's
   **Artifacts** section on the **Actions** page.

## Updating

- **Phone:** tap **Check for update** in the app. It reads the latest GitHub release, downloads the
  new APK and opens the Android installer (one tap to confirm). The first time, Android asks you to
  allow this app to install apps.
- **Watch:** in the same dialog, tap **Update watch**. The phone opens the watch app, downloads the
  watch APK and sends it to the watch, which asks you to confirm. The first time, the watch asks you to
  allow this app to install apps. Watch apps older than 0.1.2 cannot receive updates yet and must be
  installed by hand once (see below).

Both APKs must be signed with the same key, otherwise the phone and the watch cannot talk to each other.

## Installing

**Phone:** open the downloaded `phone` APK and allow installing from unknown sources.

**Watch (without a PC):**
1. On the watch, turn on Developer options (tap the software version several times in
   Settings → About watch → Software).
2. In Developer options, turn on ADB debugging and Wireless debugging.
3. On the phone, use an app such as *Bugjaeger* or *Wear Installer 2* to connect to the watch and
   install the `wear` APK.

## Ideas for later

- Setting for text size.
- Open the newest lesson from a fixed folder.
- Show the row's Persian under the Dutch.
