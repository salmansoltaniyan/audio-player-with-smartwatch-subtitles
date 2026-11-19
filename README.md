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
| `phone` | Android phone        | Plays an MP3 lesson with its JSON timeline. Play / Pause / previous segment / next segment. Keeps playing with the screen off. Sends the current segment's text to the watch and shows it on the phone. |
| `wear`  | Wear OS watch        | Shows the received text in large letters and keeps the screen on while a lesson plays. Planned: Pause and "repeat segment" buttons that control the phone. |

The phone and the watch talk through the Wearable Data Layer API (`MessageClient`). For this to work, both
apps use the same `applicationId` and are signed with the same key.

## Lesson files

Each lesson is an MP3 file with a JSON timeline next to it that has the same name:

```
lesson.mp3
lesson.json
```

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
| `lang`       | `dutch` or `persian`; sets text direction and font. |
| `text`       | The text to show. |

A segment is shown from its `start` until the next segment's `start`, including the pause.

## Building

The APKs are built by GitHub Actions; Android Studio is not needed.

1. Create a signing keystore once (for example with `keytool`). Never commit it.
2. In the GitHub repository, open **Settings → Secrets and variables → Actions** and add the keystore
   (base64-encoded) and its passwords as secrets.
3. Push to the repository. The workflow builds both signed APKs.
4. Download the APKs from the run's **Artifacts** section on the **Actions** page.

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
- Remember the last playback position.
- Open the newest lesson from a fixed folder.
- Show the row's Persian under the Dutch.
