<!--
Published 2026-10-07 at https://palayax.github.io/Chess/privacy/ (the gh-pages branch, privacy/index.html, generated from this
file; GitHub Pages, owner's choice: GitHub only). Republish it whenever this file changes. Keep the two in step, and keep it in step with docs/PUBLISHING.md §5 (Data safety answers). This comment
is not part of the page.
-->

# Palaya Chess — Privacy Policy

**Effective date:** 7 October 2026 (version 1.1 of the app)

Palaya Chess is an Android app for reviewing your chess games. It is made by Dor Amit, Palaya Cyber
Security LTD ("we"). You can reach us at **Chess@palaya.net**.

## The short version

- Palaya Chess works on your phone. Your games, analyses, videos and settings stay on your phone.
- We do not collect personal data. There are no accounts, no ads, no analytics and no tracking.
- The app uses the internet only when you ask it to: once, to download the chess engine's data and the
  narration voice, and whenever you tap **Check for updates**. Those files come from GitHub, which sees
  your device's IP address when it sends them, as with any download.
- The app's diagnostic log stays on your phone unless you choose to share it.

## What the app does on your phone

When you share or open a chess game (a PGN file) with Palaya Chess, the app analyses it with the Stockfish
chess engine running on your phone. It grades the moves, finds tactics and can make a narrated video
review. All of this happens on the device:

- The games you import, their analysis, your settings (such as your name as it appears in a game, the
  language and the analysis strength), the narration audio it prepares and the diagnostic log are kept in
  the app's private storage. Other apps cannot read them.
- If you have turned on Android's own backup to your Google account, Android includes your imported games
  and general settings in that backup, so they come back on a new phone. That backup belongs to your
  account and is handled by Google; we cannot see it. The engine data, the voice, the analysis caches,
  the narration audio and the diagnostic log are left out of it.
- A video is saved to your phone's Movies folder (Movies/ChessAnalyzer) only when you tap **Save video**.
  It is shared only if you tap **Share** and choose where it goes.
- The narration voice runs on your phone. If you switch to the phone's built-in voice in Settings, the
  text is spoken by the text-to-speech engine installed on your phone, which is provided under its own
  terms by its maker.
- If you turn on **Natural wording** (offered once at setup, and in Settings), the app can download a
  language model (about 1.2 GB, from GitHub, after you tap Download) and uses it on your phone to reword its
  commentary; every fact is checked against the app's own text. Nothing you do is sent anywhere: the model
  runs entirely on your phone, and the reworded text stays in the app's private storage.

Nothing you import, analyse or create is sent to us or to anyone else by the app (apart from Android's
own backup described above, if you use it).

## When the app uses the internet

Palaya Chess makes a network request only after you tap a button that says it will. It never checks for
anything in the background and never uploads anything.

1. **First-run download (once).** The app needs two files before its first review: the chess engine's data
   (about 100 MB) and the narration voice (about 110 MB). The Setup screen says this and downloads them only
   when you tap **Download**. If you are on mobile data it asks you first. If the download is interrupted,
   it continues only when you tap **Resume**.
2. **The optional wording model (only if you ask for it).** If you tick **Also download the wording model**
   on the Setup screen, or tap **Download** under Natural wording in Settings, the app downloads one more file
   (about 1.2 GB), with the same mobile-data question and the same Resume rule. It is never downloaded
   otherwise.
3. **Check for updates (only when you tap it).** In Settings, **Check for updates** downloads a small list
   of the latest engine data and voice files and its digital signature. If a newer file is available, it is
   downloaded only if you tap **Download and install**.

These files are published on GitHub (github.com/palayax/Chess), a code-hosting service run by
GitHub, Inc. The app checks every file against fingerprints built into the app (or, for updates, against
the signed list) before it uses it. The files are data for the engine, the voice and the wording model; no
program code is ever downloaded.

The app also has links in its About screen (for example to stockfishchess.org and palaya.net). Tapping one
opens your web browser; the app itself sends nothing.

## What GitHub sees

To send you a file, GitHub receives what any web server receives with a download request: your device's IP
address, the time, the file asked for, and a short "user agent" text that says the app's name and version
and your Android version (for example "PalayaChess/1.1 (Android 16)"). The app sends no account, no
identifier, no game data and nothing else. GitHub handles this information under its own privacy
statement: https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement

We do not receive your IP address or any information about you from these downloads. Like anyone who
publishes files on GitHub, we can see only how many times each file has been downloaded in total.

## The diagnostic log

To help fix problems, the app keeps a small log on your phone (about 1 MB at most; older entries are
replaced). It records things like the app and Android version, the phone model, the analysis settings,
the moves and the PGN header details (such as player names and the event) of games you analyse, how long
each step took, download progress, and error details. It does not record your location, contacts or any
account.

The log never leaves your phone by itself. It is shared only if you tap **Share diagnostic log** in
Settings or **Share details** on an error screen and then choose an app (for example, your email app to
send it to us). What happens to it then depends on where you send it. If you email it to us, we use it
only to fix the problem you report, and we delete it when we no longer need it for that.

## What the app does not do

- No accounts or sign-in.
- No advertising and no advertising identifiers.
- No analytics, crash reporting services or tracking of any kind, and no third-party code that collects
  data.
- No location, contacts, camera, microphone or file-storage permissions.
- No sale or sharing of personal data, because the app collects none.

## Permissions the app asks for

- **Internet** and **network state**: for the downloads described above, and to check whether you are on
  Wi-Fi or mobile data before a download starts.
- **Notifications**: to show the progress of the first-run download and of a video export. You can say no;
  the app still works.
- **Foreground service** (data sync and media processing): so the first-run download and a video export can
  keep running, with a visible notification, while you use another app.

## Children

Palaya Chess is suitable for all ages and collects no personal data from anyone, including children.

## Deleting your data

Everything the app stores is on your phone. Uninstalling the app, or clearing its storage in Android's
settings, deletes all of it. Videos you saved to the Movies folder stay there until you delete them.

## Changes to this policy

If the app's handling of data changes, we will update this page and its effective date before releasing
that version of the app.

## Contact

Questions about privacy: **Chess@palaya.net**
