# Play Console answer sheet — Palaya Chess 1.1 (versionCode 2)

Fill-in sheet for the first Google Play submission (P1, 2026-10-07). Each section follows the Console's order, gives every
field and question, and the exact answer to enter. Copy the text in the boxes as it is.

**The Console changes often.** The wording below was checked against the Play Console Help pages on 2026-10-07. Where
the Help pages do not give the exact Console wording, the item is marked **(not confirmed)**: pick the option that has
the meaning given here. A list of these items is at the end (§12).

Account: the owner's **personal** developer account, with identity verification pending. Personal accounts have two
extra steps: a closed test before production (§5.2), and verifying a phone in the Play Console mobile app (§0).

Related documents: `docs/PUBLISHING.md` (the reasoning behind these answers), `docs/STORE_LISTING.md` (the listing text),
`docs/PRIVACY_POLICY.md` (the live policy). Files to upload are in `dist/play-kit/` (gitignored, on the owner's PC) and
`docs/play/screenshots/`.

---

## 0. Before you start

- [ ] **Identity verification done.** Play Console > Home shows no verification banner. A personal account verifies with a
      government ID through the payments profile, and verifies its email and phone with 6-digit codes. The developer
      email you enter is shown on Google Play.
- [ ] **Device check (new personal accounts).** Play Console > Home > "Verify that you have access to an Android mobile
      device". Install the **Play Console** app on your own Android phone (Android 10 or later, not rooted), sign in as
      the account owner and scan the QR code.
- [ ] **Android developer verification (2026).** Nothing to do. Play registers the package name `net.palaya.chessanalyzer`
      for you when you create the app. Check Settings > Developer account for any banner.
- [ ] **The two YouTube links are ready.** Upload the two demo videos as **Unlisted** (§3.12).
- [ ] **The models are live.** They are live since 2026-10-07 (`models-2026.10` release and the signed `models` manifest
      on https://github.com/palayax/Chess). Never delete or replace those assets.

Upload files (`dist/play-kit/`, rebuilt 2026-10-09 at commit `21f0d6a` (famous games, C1 commentary, Hebrew hidden, privacy link in About); AAB SHA-256 `a646f94e535b47471ad4c14d49b064e07edd0bf49f41ade56e2a9eeb27245f02`):

| File | What it is |
|---|---|
| `PalayaChess-1.1-release.aab` | **Upload this to Play.** The App Bundle, signed with the upload key. |
| `PalayaChess-1.1-arm64-release.apk`, `PalayaChess-1.1-universal-release.apk` | Do **not** upload these to Play. They are for direct installs (GitHub, other stores). |
| `play_icon_512.png` | App icon, 512 x 512, 32-bit PNG with alpha. |
| `play_feature_1024x500.png` | Feature graphic, 1024 x 500, 24-bit PNG, no alpha. |
| `fgs_data_sync_demo.mp4`, `fgs_media_processing_demo.mp4` | The foreground-service demo videos (§3.12). Upload to YouTube, unlisted. |

---

## 1. Create app

Path: Play Console > Home > **Create app**.

| Field | Enter |
|---|---|
| App name | `Palaya Chess` (12 characters; limit 30; owner's choice 2026-10-08) |
| Default language | **English (United States) – en-US** |
| App or game | **Game** (see the note below) |
| Free or paid | **Free**. You can change free to paid later. You cannot make a free app paid. |
| Contact email (if asked here) | `Chess@palaya.net` |
| Declarations | Tick all three: **Developer Program Policies**; **US export laws**; **Play App Signing Terms of Service** (exact checkbox sentences not confirmed). |

**App or game, and the category.** `STORE_LISTING.md` chose **Game > Board**. That is where players look for chess
apps, and chess tools are usually listed there. The other choice is **App > Education** (or **Tools**). The listing works
either way and you can change it later. The content rating is the same either way (§3.4).

---

## 2. Store listing (Grow users > Store presence > Main store listing)

### 2.1 App details

**App name** (12 / 30):

```
Palaya Chess
```

**Short description** (79 / 80):

```
Review your chess games on your phone: every move graded, missed tactics shown.
```

**Full description:** paste the box below as it is (plain text, 2,396 / 4,000 characters, measured with Python `len()`).
Play does not render `**bold**`, so the text has none. Each paragraph is one line; a blank line separates paragraphs.

```
Find out where your game was won and lost.

Palaya Chess reviews the games you play anywhere. Share a game from your chess app, open a PGN file or paste the moves, and get a full review on your phone. There is no account to create and nothing to upload.

Every move graded
Each move is rated from Brilliant to Blunder, with a short explanation in plain language and the better move shown on the board.

The tactics you found, and the ones you missed
The review names the ideas on both sides of the board: forks, pins, skewers, discovered attacks, deflections, back-rank mates and more. When you miss one, the walkthrough plays out how it would have gone, move by move.

Practise your own mistakes
Turn the positions you got wrong into puzzles and try them again until you find the right move.

A report that tells you something
Accuracy for both players, an estimated performance rating, a count of every kind of move, an evaluation graph and the moments the game turned on.

A narrated video of your game
Watch a spoken review in the app, or save it as an MP4 video to keep or share. The narration voice runs on your phone.

Private by design
The analysis is powered by Stockfish 19, running on your phone, not on a server. Your games stay on your device. No sign-up, no ads, no analytics, no tracking.

One-time setup download
To keep the app itself small, the chess engine's data and the narration voice (about 210 MB together) are downloaded once, the first time you open the app, and only when you tap Download. Wi-Fi is recommended; on mobile data the app asks first. After that the app works offline. It goes online again only if you tap "Check for updates" in Settings.

Getting your games in
Share a game straight from your chess app, open a .pgn file, or paste the moves. Files with several games, custom starting positions and clock times are supported.

Analysis powered by Stockfish (GPLv3), stockfishchess.org.
Opening names from the lichess-org/chess-openings project (CC0).
Narration voice: sherpa-onnx and Kokoro-82M (Apache 2.0), with espeak-ng pronunciation data (GPL v3 or later). Piece artwork: Cburnett (CC BY-SA 3.0).
Palaya Chess is free software under the GNU GPL v3. Source code: https://github.com/palayax/Chess

Palaya Chess is not affiliated with, endorsed by or connected to Chess.com or Lichess. Games exported from those sites work because PGN is an open standard.
```

### 2.2 Graphics

| Field | File | Checked |
|---|---|---|
| App icon | `dist/play-kit/play_icon_512.png` | 512 x 512, RGBA (32-bit with alpha), 93,571 B (limit 1 MB). It is the launcher icon: the adaptive icon's white background and foreground, cropped to the visible 72 dp and scaled. Play adds the rounded corners. |
| Feature graphic | `dist/play-kit/play_feature_1024x500.png` | 1024 x 500, RGB (24-bit, no alpha). Made by `docs/play/make_graphics.py` from our own launcher mark, the app's palette and the app's own board (Cburnett pieces, credited in the app). Text is kept at least 64 px from every edge. |
| Video (optional "Promo video") | leave empty | The FGS demo videos are not promo videos. Do not put them here. |

**Phone screenshots.** Upload in this order. Each is 1080 x 1920 (9:16; the long side is at most 2x the short side),
24-bit PNG with no alpha, captured from the release build on an API 36 emulator.

| # | File (`docs/play/screenshots/`) | Shows |
|---|---|---|
| 1 | `phone_01_summary.png` | Summary of the Immortal Game: accuracy 82% / 72%, estimated ratings, key moments |
| 2 | `phone_02_best_line.png` | Board, "Best line instead of 11… cxb5", stepped to 3 / 7, arrow and eval bar |
| 3 | `phone_03_practise.png` | Practise puzzle from the game, with a hint shown |
| 4 | `phone_04_walkthrough.png` | "What you missed" walkthrough, step 4 / 6 (the knight fork) |
| 5 | `phone_05_video_review.png` | Video review player on a key moment |
| 6 | `phone_06_setup.png` | First-run Setup screen (the one-time download, stated honestly) |
| 7 | `phone_07_board_blunder.png` | Board with a Blunder badge and its explanation |
| 8 | `phone_08_famous_games.png` | Famous games library (owner, 2026-10-09; replaces `phone_08_settings_voice.png`) |

The minimum is 2 and the maximum is 8. With 4 or more at 1080 px or larger, the app can be featured. **7-inch and
10-inch tablet screenshots:** optional. None were made (see RUN_LOG P1). Leave them empty.

### 2.3 Store settings (Grow users > Store presence > Store settings)

| Field | Enter |
|---|---|
| App category | **Board** (Games). Alternative if you chose App: **Education**. |
| Tags | Up to 5, chosen from Google's list ("Manage tags"). Pick the closest available to: **Chess**, **Board**, **Strategy**, **Puzzle**, **Education/Learning** (the tag names on the list are not confirmed). |
| Email address | `Chess@palaya.net` (required; shown on the listing) |
| Phone number | leave empty (optional) |
| Website | `https://palayax.github.io/Chess/` |
| External marketing | leave the default (on) unless you object to Google advertising the app outside Play |

---

## 3. Policy > App content

Path: Play Console > your app > **Policy > App content** (some Help pages say "Monitor and improve > App content"). The
Dashboard's "Set up your app" list links to the same sections. Do each section, then **Save** (and **Submit** where it
asks).

### 3.1 Privacy policy

| Field | Enter |
|---|---|
| Privacy policy URL | `https://palayax.github.io/Chess/privacy/` |

The app requests `INTERNET`, so a privacy policy is required even though it collects no data. The page is live, and
it is the same text as `docs/PRIVACY_POLICY.md`.

### 3.2 Ads

| Question | Answer |
|---|---|
| Does your app contain ads? | **No, my app does not contain ads** |

### 3.3 App access (now called "Sign-in details" on the Help pages)

| Question | Answer |
|---|---|
| Is any part of the app restricted (login, membership, location, other authentication)? | **All functionality is available without special access** (exact label not confirmed). There is no login, account, subscription or code. |

If the Console shows an **instructions** box anyway (some layouts do), paste this note for the reviewers:

```
No account or login. On first launch the app shows a Setup screen: tap "Download (about 210 MB)" once. It downloads two data files (the chess engine's evaluation network, 98.5 MB, and the on-device narration voice model, 102.5 MB; 201 MB in total) from the app's public GitHub release, verifies their SHA-256 and then works fully offline. Wi-Fi is recommended; the download takes 1-3 minutes on a typical connection. To test the main flow afterwards: on Home tap "Paste moves" and paste any PGN (for example the Immortal Game, Anderssen-Kieseritzky 1851), or share a game from a chess app; the review then opens (Summary, Board, Practise, Video review).
```

Do not choose "restricted" just to get the box. The download is a first-run step, not an access restriction.

### 3.4 Content rating

Path: App content > Content rating > **Start**.

| Field / question | Answer |
|---|---|
| Email address (for IARC) | `Chess@palaya.net` |
| Category | **"Reference, News, or Educational"** if the Console offers it. Otherwise **"All Other App Types"** (the name used in 2026). Choose **"Game"** only if the Console makes you use it for an app created as a Game. (Category names not confirmed; see the justification below.) |

**Why this category.** Palaya Chess is a study tool. It reviews games the user already played, explains the moves and
shows tactics. There is no gameplay against an opponent, no score to chase, no characters and no story. Its purpose is
learning, which is what the "Reference, News, or Educational" category is for. The puzzles are the user's own positions
to practise, like exercises in a textbook. If the Console only offers "Game" for a Game listing, choose it: every answer
below stays "No", and the rating comes out the same (IARC 3+, Everyone, PEGI 3, USK 0).

**Questionnaire.** The Help page lists the topics. The exact question sentences depend on the category, and
the ones below are paraphrased, not confirmed. Answer **No** to every question:

| Topic | Answer | Why |
|---|---|---|
| Violence (realistic, fantasy, blood, gore; violence against people or animals) | No | Chess pieces captured on a board; no depiction of violence |
| Fear / horror | No | |
| Sexuality, nudity, sexual references | No | |
| Language (profanity, crude humour) | No | All text is generated chess commentary |
| Controlled substances (drugs, alcohol, tobacco) | No | |
| Gambling, simulated gambling, real-money gaming, cash payouts | No | |
| Loot boxes / random items bought with real money | No | No purchases at all |
| Does the app let users interact or exchange content with other users (chat, voice, sharing images/audio, UGC)? | No | No in-app communication. "Share" hands a file to the Android share sheet, which the user controls; Google's help says to choose UGC/social only if that is the app's main purpose. |
| Does the app share the user's current physical location with other users? | No | No location access |
| Does the app let users buy digital goods? | No | Free, no in-app purchases |
| Is the app a web browser or search engine / does it give unrestricted internet access? | No | It downloads two fixed data files from GitHub and nothing else |
| Shares personal information with third parties? | No | Nothing collected |
| Is the app primarily news? | No | |

Expected result: **IARC 3+ / ESRB Everyone / PEGI 3 / USK 0**. Then click **Save** and **Submit**.

### 3.5 Target audience and content

| Step / question | Answer |
|---|---|
| Target age groups | Tick **13–15**, **16–17** and **18 and over**. Do not tick 5 and under, 6–8 or 9–12. Ticking an under-13 group puts the app under the Families policy (extra requirements and review). |
| Could your store listing unintentionally appeal to children? (wording not confirmed) | **No**. The listing uses ordinary chess art and plain language; it has no cartoon characters and no child-directed wording. |
| Ads | Already answered: no ads. |
| Store presence / "Appeals to children" follow-ups | **No** |

The privacy policy says the app "is suitable for all ages and collects no personal data from anyone". That is true and
does not conflict with targeting 13+. Targeting is about who the app is designed for.

### 3.6 News apps

| Question | Answer |
|---|---|
| Is your app a news app? | **No** |

### 3.7 Data safety

Path: App content > Data safety > Start. Steps: Overview > Data collection and security > Data types > Data usage and
handling > Store listing preview > Submit. The full reasoning is in `PUBLISHING.md` §5.

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| (If still shown) Is all of the user data collected by your app encrypted in transit? | Not applicable; nothing is collected. If the Console still asks, answer **Yes**: the only network traffic is HTTPS downloads, and the release build refuses plain HTTP. |
| (If still shown) Do you provide a way for users to request that their data is deleted? | Not applicable; nothing is held by the developer. If asked: **No** (no data is held off the device; uninstalling deletes everything). |
| Account creation / delete-account URL | Not applicable: the app has no accounts. If the Console asks, choose **"My app does not allow users to create an account"**. |

Then the **Store listing preview** should say **"No data collected"** and **"No data shared with third parties"**. Click **Submit**.

**Why "No" is right (for your own records, not for the form).**
- Google defines "collect" as transmitting data off the device. The app sends no personal data, identifiers, app
  activity, diagnostics or files to the developer or to anyone. There is no SDK that does it either; the emoji font fetch
  was removed in D2f.
- The downloads are anonymous HTTPS GETs of two fixed files (and, only on a tap, the update manifest). GitHub sees the
  IP address and a User-Agent like any web server. That connection metadata is used only to serve the request, which
  matches Google's "processed ephemerally" exemption, and the developer receives none of it.
- The diagnostic log leaves the phone only through the Android share sheet, when the user chooses to share it.
- Android Auto Backup goes to the user's own Google account; the developer never receives it.

### 3.8 Government apps

| Question | Answer |
|---|---|
| Is your app developed by or on behalf of a government? | **No** |

### 3.9 Financial features

| Question | Answer |
|---|---|
| Which financial features does your app provide? | Tick **"My app doesn't provide any financial features"** |

### 3.10 Health apps

| Question | Answer |
|---|---|
| Health features | Tick **"My app doesn't provide any health features"** (the Console may say "does not have any health features"; same meaning) |

### 3.11 Advertising ID

| Question | Answer |
|---|---|
| Does your app use advertising ID? | **No** |

The release manifest has no `com.google.android.gms.permission.AD_ID` (aapt2 on the P1 build lists exactly the six
permissions below, plus the library-internal `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`), and there is no ads or analytics SDK.

### 3.12 Foreground service permissions

Shown because the app targets API 36 and declares two foreground-service types. For each type the Console asks for
the use case, a description, the user impact if the task is deferred or interrupted, and a video link.

Permissions in the manifest (aapt2, P1 build): `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, `POST_NOTIFICATIONS`.

#### Data sync (`FOREGROUND_SERVICE_DATA_SYNC`)

| Field | Enter |
|---|---|
| Use case(s) | **Network transfer: Upload or download** (the first-run download). Also tick **Local processing: Import or export** if multiple choices are allowed: on Android 10–14 the video export uses dataSync, because mediaProcessing does not exist there. |
| Video link | `https://youtube.com/shorts/lkQ6lD0umSI` (unlisted, channel PalayaChess, uploaded 2026-10-09) |

Description:

```
One-time setup download (ModelDownloadService): on first launch the Setup screen states the size (about 210 MB) and, only when the user taps Download, fetches the chess engine's evaluation data and the on-device voice model. It takes minutes and must continue while the user switches apps, so it runs with a progress notification (Pause, Cancel). It never starts by itself and is not restarted (START_NOT_STICKY). On Android 10-14 the user-started video export also uses this type.
```

Impact if deferred or interrupted:

```
If the system deferred or stopped the download, the app could not be used: no game can be analysed until the engine data is installed. The user started it and is waiting for it. If it is interrupted anyway, the app keeps the bytes already downloaded and shows Paused with a Resume button; it never restarts by itself.
```

#### Media processing (`FOREGROUND_SERVICE_MEDIA_PROCESSING`)

| Field | Enter |
|---|---|
| Use case | **Media transcoding** (closest listed case: the app renders, encodes and muxes an MP4 on the device). If you can enter text instead, write: "Rendering and encoding a video file on the device". |
| Video link | `https://youtube.com/shorts/JVwfR2UHkJU` (unlisted, channel PalayaChess, uploaded 2026-10-09) |

Description:

```
Video export (VideoExportService). When the user taps "Save video", the app renders their game review into an MP4 on the device: it draws the board frames, synthesizes the narration with an on-device voice, encodes and muxes the file into Movies/ChessAnalyzer. It takes minutes and must continue while the user leaves the screen, so it runs with a progress notification and a Cancel button. It starts only from that tap and stops when the video is saved, fails or is cancelled. Nothing is uploaded.
```

Impact if deferred or interrupted:

```
The user explicitly asked for the video and is waiting for it. If the system deferred the work, no video would be produced until the user reopened the app and kept it on screen for several minutes. If it were interrupted, the partly encoded MP4 would be unusable and all the work would be lost, so the user would have to start again from the beginning.
```

The Help page gives no length limit for these boxes (not confirmed); each text above is under 500 characters to be safe. The
longer wording is in `PUBLISHING.md` §3.

**The two videos.** Each is about a minute, 1080 x 1920, filmed on the release build.
- `fgs_data_sync_demo.mp4` (57 s): the Setup screen, the tap on Download, the notification permission, the progress,
  Home, the notification shade with "Setting up Palaya Chess" and its progress bar and Pause/Cancel, back to the app,
  then "All set". The middle 58 s of the download play at 4x speed, with a caption saying so.
- `fgs_media_processing_demo.mp4` (57 s): Save video, then the progress in the app. A captioned time-lapse follows
  (1 frame per 30 s; the whole export took 36 min on the emulator). Then 13 minutes in, in real time: Home and the shade
  with "Exporting video review", its progress bar and Cancel. Another time-lapse covers the rendering. The end is in
  real time: "Video saved", Home, the shade's "Video saved" notification, and back to the app.

Upload both to YouTube as **Unlisted** (not Private: reviewers must be able to open the link). The Help page says nothing
about length or format, so these are an assumption: keep each under a few minutes, with no ads, embeddable and not
age-restricted. Paste the two URLs above.

### 3.13 Sections you should NOT see (if one appears, check why)

- **Photo and video permissions:** the app requests no `READ_MEDIA_*`. It saves videos through MediaStore without a
  permission.
- **Permissions declaration form:** for sensitive permissions such as SMS, call log, all-files access or accessibility.
  The app has none.
- **Account deletion:** the app has no accounts.

---

## 4. App signing (Test and release > Setup > App signing; also under "Protected with Play > Play app signing")

**What happens now.** New apps are enrolled in **Play App Signing** automatically. Google generates the app signing key;
the 2026 Help page calls it "quantum-ready, hybrid signing". **Your existing keystore becomes the upload key.** Play
records it from the first AAB you upload: `keystore/chessanalyzer-release.jks`, alias `chessanalyzer`, certificate
SHA-256 `CA:4F:7B:42:CE:83:7F:97:D4:8E:0E:80:2B:48:B1:C9:C9:C2:53:BA:4E:60:4E:56:A8:DF:F6:97:9A:89:09:47`.

**The one decision (`PUBLISHING.md` §3).** You can make it any time before the first open-testing or production release.
Internal and closed tests work with the default.

| Choice | Effect | When to choose it |
|---|---|---|
| **Keep Google's generated key (default)** | Play installs are signed by Google's key. APKs you hand out yourself (signed `ca4f…0947`) are a **different app** to Android, so a phone must uninstall one before installing the other. | **Recommended** if nobody important runs a sideloaded 1.0/1.1 (today: only test devices). |
| **Change the app signing key > "Provide a copy of your app signing key"** (upload `chessanalyzer-release.jks` encrypted, with Google's tool, following the Console's instructions) | Play installs and your GitHub APKs share one key, so either one updates the other in place. | Only if sideloaded users must update from Play without reinstalling. Google recommends an upload key separate from the app signing key; whether the same key may also stay the upload key was not confirmed. |

After the first upload, **App integrity** shows the app signing certificate. Keep `keystore/` (the `.jks`,
`keystore.properties` and `models-signing.pem`) backed up offline in two places (HANDOFF owner to-do 3).

---

## 5. Testing — internal first, then the required closed test

### 5.1 Internal testing (immediately; up to 100 testers; no review wait)

1. Test and release > Testing > **Internal testing** > Testers tab > **Create email list**. Name it `Palaya internal`
   and add your own Google account(s). Save, then tick the list.
2. Releases tab > **Create new release**. Upload `dist/play-kit/PalayaChess-1.1-release.aab`. Release name: it fills in
   automatically (`2 (1.1)`); keep it. Release notes: §8. **Next** > **Save and publish**.
3. Testers tab > copy the **opt-in link** ("Join on the web"). Open it on your phone while signed in with a tester
   account, tap **Become a tester**, then install from the Play link on that page.
4. Check on a real phone: Setup > Download reaches "All set"; paste a game; Summary, Board, Practise, Video, Save video.
5. Look at **Pre-launch report** (Test and release > Testing > Pre-launch report). Google's robots open the app on real
   devices and may tap Download. A warning about the 201 MB first-run download or "no further screens" is expected, not
   a failure.

### 5.2 Closed testing (required: at least 12 testers opted in for 14 days in a row)

The rule applies to personal accounts created after 13 Nov 2023. **12 testers must stay opted in for 14 consecutive
days** before you can apply for production. A tester who opts out and back in starts their 14 days again, so recruit
**15–20 people**.

1. Test and release > Testing > **Closed testing** > the default track (**Closed testing - Alpha**) > **Manage track**.
2. **Testers** tab: **Create email list** `Palaya closed test` and paste the testers' Gmail / Google account addresses
   (comma-separated or CSV). Alternatively use a Google Group (`name@googlegroups.com`) and invite people to the group.
3. **Feedback URL or email:** `Chess@palaya.net`.
4. **Countries / regions** (closed track): **Add all**, or at least every tester's country. Changes to the app's
   countries apply to all tracks.
5. **Releases**: **Create new release** > **Add from library** > the same `2 (1.1)` bundle (or a newer build with a
   higher versionCode). Use the release notes in §8. **Save** > **Send for review**. The first closed release is
   reviewed by Google, which usually takes a few days.
6. When it is approved, copy the **opt-in link** (Testers tab, "Join on the web") and send it to the testers with this
   message:

```
Thanks for testing Palaya Chess! Google needs 12 testers to stay in the test for 14 days before the app can go public.
1. Open this link on your Android phone, signed in with the Google account I invited: <OPT-IN LINK>
2. Tap "Become a tester", then "Download it on Google Play" and install the app.
3. Open it once and tap Download (about 210 MB, Wi-Fi is best). Then review one of your games: share it from your chess app or paste the moves.
4. Please stay in the test (don't leave the program) for at least 14 days, and tell me anything that's wrong or confusing: Chess@palaya.net
```

7. Track the count: the Dashboard shows the number of opted-in testers and the days elapsed. Add new testers before
   the count drops below 12.

### 5.3 Apply for production (Dashboard > "Apply for production", after day 14)

The form has three parts. Write true answers in your own words. Drafts from what the project has already done:

**About your closed test**
- How easy was it to recruit testers? Write your real experience (for example: friends and chess club members, recruited by email).
- Did testers use all the features? Answer truthfully. The features to ask testers to try: setup download, analysis,
  Board and best line, Practise, Video review, Save video, Settings (voice, pace, check for updates).
- Did the testers' use match how you expect the public to use it? Answer truthfully (mostly reviewing their own online games).
- Summarise the feedback and how you collected it. Feedback went to the track's feedback email (`Chess@palaya.net`)
  plus direct messages; list what you fixed.

**About your app**
- Intended audience: chess players aged 13 and over who play online or over the board and want to understand their games.
- Value: a full game review (move grades, tactics found and missed, best lines, practice from your own mistakes and a
  narrated video), running entirely on the phone, free, with no account, ads or tracking. The source is open (GPLv3).
- Expected installs in the first year: your own estimate.

**Production readiness**
- Changes made because of the test: list them (or "none needed; no defects reported").
- How you decided the app is ready: the closed test results, plus the release checks already done (`PUBLISHING.md` §6:
  release build verified on API 34 and 36, fresh-install download from the live GitHub release, offline use).

Google's review usually takes up to 7 days.

---

## 6. Production

1. Test and release > **Production** > **Countries / regions** > **Add countries / regions**. Suggested: **all
   countries and regions** (the app is English-only, offline and free; no country-specific rules apply). Or choose a
   smaller set if you prefer. (The exact menu path was not confirmed; it is on the Production track page.)
2. Production > **Create new release** > add the same bundle, or a newer one. A newer build needs a higher versionCode;
   every later upload must raise it (`CLAUDE.md`). Release notes as in §8. **Next** > **Save** > **Send for review**.
   A first production release cannot be a staged rollout; it goes to 100%.

---

## 7. Final checks before "Send for review"

- [ ] Dashboard: every "Set up your app" item has a green tick.
- [ ] Policy status: no warnings under App content.
- [ ] The listing has no "Chess.com" in the title or graphics; the description's disclaimer line is present.
- [ ] Privacy policy URL opens: https://palayax.github.io/Chess/privacy/
- [ ] Source URL opens: https://github.com/palayax/Chess (GPLv3 offer)

---

## 8. Release notes for 1.1 (each track's release)

Up to 500 characters per language. Paste with the tags; this text is 394 characters including the tags.

```
<en-US>
First release on Google Play.
- Every move graded from Brilliant to Blunder, with the better move on the board.
- Show the best line: play the engine's line move by move.
- See the tactics you found and the ones you missed.
- Practise the positions you got wrong.
- Narrated video review in 11 voices, saved as an MP4.
- Works offline after a one-time download of about 210 MB.
</en-US>
```

---

## 9. Facts you may be asked for

| Item | Value |
|---|---|
| Package | `net.palaya.chessanalyzer` |
| Version | versionName 1.1, versionCode 2 |
| Target / min SDK | 36 / 26 |
| Download size from Play (bundletool, P1 bundle) | arm64-v8a 14,510,518 B; x86_64 15,947,710 B; armeabi-v7a 13,495,378 B |
| First-run download | 201,054,635 B from `github.com/palayax/Chess/releases/download/models-2026.10/` (two data files, SHA-256 pinned in the app) |
| Executable code downloaded? | No. Only two data files (engine weights, voice model) read by code in the bundle (`PUBLISHING.md` §3b) |
| Developer contact | Chess@palaya.net |
| Licence | GPLv3, source at https://github.com/palayax/Chess |

---

## 10. Owner checklist (in order)

1. §0 checks; upload the two demo videos to YouTube (unlisted) and keep the URLs.
2. §1 Create app.
3. §5.1 Internal testing release (this also registers the upload key, §4). If the Console will not publish it until some
   setup tasks are done, do steps 4 and 5 first and come back (not confirmed which tasks block an internal release).
4. §3 App content: all sections, including content rating and Data safety; paste the two video URLs in §3.12.
5. §2 Store listing and store settings.
6. §5.2 Closed testing: release, send for review, invite 15–20 testers, wait 14 days with at least 12 opted in.
7. §4 Decide the app signing key before production.
8. §5.3 Apply for production; then §6 production release.

---

## 11. What the lead needs from the owner while filling the Console

- The two YouTube URLs (§3.12).
- The decision on App or Game / category (§1), and on the signing key (§4).
- The tester email list (§5.2).
- The production countries (§6).

---

## 12. Not confirmed against an official page (2026-10-07)

These come from third-party guides (2025–2026) or from memory, or the Help page does not quote the Console's exact
wording. The meaning given here is right; the label on screen may differ.

1. The exact checkbox sentences in **Create app**.
2. The exact order of the App content tasks on the Dashboard.
3. The App access / "Sign-in details" option labels, and whether an instructions box shows when nothing is restricted.
4. The **content rating category names** ("Reference, News, or Educational" / "All Other App Types" / "Game"), whether a
   Game listing may choose a non-game category, and the exact question sentences.
5. The Target audience "unintentionally appeal to children" wording.
6. Which Data safety follow-up questions are skipped after "No".
7. The Advertising ID question wording (no official page found).
8. The News apps question wording.
9. The maximum of 8 phone screenshots (the long-standing value; not on the page fetched).
10. Foreground-service video requirements (length, format, host): the Help page names none.
11. The menu path for production countries.
12. Whether, after you provide your own app signing key, the same key may also stay the upload key.
13. Whether the developer's address is shown publicly for a personal account that does not sell anything.
14. The Store settings tag names.

Sources (Play Console Help, fetched 2026-10-07): create app and listing limits
https://support.google.com/googleplay/android-developer/answer/9859152 ; graphics
https://support.google.com/googleplay/android-developer/answer/9866151 ; category and tags
https://support.google.com/googleplay/android-developer/answer/9859673 ; content rating
https://support.google.com/googleplay/android-developer/answer/9859655 and /188189 and /6159973 ; target audience
https://support.google.com/googleplay/android-developer/answer/9867159 ; Data safety
https://support.google.com/googleplay/android-developer/answer/10787469 ; foreground services
https://support.google.com/googleplay/android-developer/answer/13392821 ; financial features /13849271 ; health /14738291 ;
government /9514050 ; review preparation (Sign-in details) /9859455 ; testing requirement for new personal accounts
/14151465 ; test tracks /9845334 ; Play App Signing /9842756 ; releases /9859348 ; identity /10841920 ; device check
/14316361 ; Android developer verification https://developer.android.com/developer-verification .
