# PUBLISHING — what you must do before this app goes on Google Play

This is not boilerplate. Several items here (GPL, the first-run download, the foreground-service and Data
safety declarations) genuinely affect whether and how you can list this app. Rewritten in D2f for the
downloading build; the owner's own to-do list is at the top of `HANDOFF.md`.

## 0. Current facts (version 1.1, versionCode 2, D2f)

| | |
|---|---|
| Package | `net.palaya.chessanalyzer` |
| Version | versionName **1.1**, versionCode **2** (1.0 / 1 is the bundled R7 build in `dist/`, which updates in place to this one: same key, proven on chess36, RUN_LOG D2f) |
| Target / min SDK | 36 / 26 |
| What Play delivers | the App Bundle (`app-release.aab`), about **14.5 MB** to a 64-bit phone (§3) |
| First run | the Setup screen; a tap on **Download** fetches the engine net (98,511,183 B) and the voice (`kokoro-int8-en-v0_19.tar.gz`, 102,543,452 B, unpacks to 158,269,440 B): **201 MB** from GitHub, about 400 MB free space at the peak, 257 MB on disk after |
| Where from | `https://github.com/palayax/Chess/releases/download/models-2026.10/<file>` (the owner's repo, created 2026-10-07; §4c) |
| Network | only after a tap: the setup download, and Settings > Check for updates (`models.json` + `.sig`, then a file only on "Download and install"). Nothing in the background, nothing uploaded |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, `POST_NOTIFICATIONS` (checked with aapt2 on the release APK, D2f) |
| Foreground services | `ModelDownloadService` (dataSync), `VideoExportService` (mediaProcessing on Android 15+, dataSync on 10-14) |
| Privacy policy | `docs/PRIVACY_POLICY.md`, live at https://palayax.github.io/Chess/privacy/ (GitHub Pages, `gh-pages` branch) |

---

## 1. Stockfish is GPLv3 — this is the big one

The app links Stockfish 19 (compiled into `libstockfish.so`). GPLv3 is viral for
distribution: **shipping this app means the whole app must be licensed GPLv3** and you
must offer the corresponding source to anyone who receives the binary.

What that means concretely:

- **You cannot publish this as closed-source proprietary software.** There is no way
  around this while Stockfish is linked in.
- **You must ship the GPLv3 licence text.** Done — `engine/src/main/assets/STOCKFISH_LICENSE.txt`
  (Stockfish's own `Copying.txt`), surfaced in the app's About screen.
- **You must offer complete corresponding source**, including the modifications and build
  scripts. In practice: publish this repo (including `scripts/fetch_stockfish.sh` and the
  CMake config) to a public GitHub repo and link it from the About screen and the Play
  Store listing description.
- **Google Play and GPLv3 coexist fine** — many GPL apps are listed. The friction is only
  if you wanted it closed-source or wanted to forbid redistribution.

If you want a proprietary app instead, the engine must be swapped for a permissively
licensed one (e.g. writing your own, or a BSD/MIT engine), which would be a substantial
rewrite and a much weaker analysis. **Recommendation: publish GPLv3 and open the repo.**

### Attribution required in the listing
- "Analysis powered by Stockfish (GPLv3)" plus a link to https://stockfishchess.org
- Opening data from lichess-org/chess-openings, CC0.
- Voice: sherpa-onnx (Apache 2.0), Kokoro-82M (Apache 2.0) and espeak-ng pronunciation data (the espeak-ng
  project's README says "GPL version 3 or later"). The Kokoro archive ships no separate licence file for the
  espeak-ng data, so its per-file terms are **unverified**; the app's About screen credits it factually.
- The Stockfish NNUE net was trained on data from the Leela Chess Zero project, made available under the
  Open Database License (per the Stockfish README, which is where About takes the wording). Whether any
  ODbL notice wording is required for the net has **not** been assessed; the licence questions are
  informational for this proof of concept.

---

## 2. Chess.com trademark and design

The brief said "use chess.com UI/UX as design reference". What was built:

- An **original** dark green design system with a chess.com-*like* feel (colours, density,
  layout, the Brilliant→Blunder vocabulary).
- **No** chess.com logos, wordmarks, icons, badge artwork, sounds, or piece sets.
- Piece artwork is the **Cburnett** set from Wikimedia Commons (**CC BY-SA 3.0**) — the de-facto
  standard open Staunton set, *not* chess.com's proprietary art. Attribution, including the
  "indicate changes" note CC BY-SA requires, is in `app/src/main/assets/PIECES_LICENSE.txt` and is
  surfaced in the in-app About screen. **Share-alike applies to the artwork**, which is compatible
  with this app being GPLv3 but means the pieces cannot be relicensed into a closed product.
- Classification badges are drawn as Compose vectors, not copies.

What you must NOT do in the store listing, or you invite a takedown:
- Do not use "Chess.com" in the app name, icon, or screenshots.
- Do not imply affiliation or endorsement.
- Describing it as "analyses your chess.com PGN files" is factual and fine. "Chess.com
  Analyzer" is not.

Move-classification *names* (Brilliant, Blunder, Inaccuracy) are ordinary chess
vocabulary and not protectable, but the specific badge *artwork* is — hence original vectors.

---

## 3. App Bundle, APKs and the Play declarations

**Google Play takes an Android App Bundle (.aab)**; the APKs are for direct installs and for stores that
accept APKs (F-Droid, Amazon Appstore, Samsung Galaxy Store, a download page).

### The App Bundle

```bash
./gradlew :app:bundleRelease    # -> app/build/outputs/bundle/release/app-release.aab (signed with the upload key)
```

Run it as its **own** Gradle invocation (not together with `assembleRelease`, see "APKs" below). The build
targets **API 36** (Android 16), which Play requires for new apps and updates from 31 Aug 2026; R8 is on; every
native library is 16 KB-aligned (`CLAUDE.md`, build gotchas).

**What a phone downloads from Play** (bundletool 1.18.3 `get-size total`, the compressed download per
device, API 36 spec, en-US, 420 dpi; D2f bundle, 68,714,862 B):

| Device | D2f (models downloaded on first run) | D1 (models inside) |
|---|---|---|
| arm64-v8a (practically every phone) | **14,473,967 B** | 196,058,239 B |
| x86_64 (emulators, a few Chromebooks) | 15,911,159 B | 197,495,419 B |
| armeabi-v7a (old 32-bit phones) | 13,458,827 B | 195,043,077 B |

Play's limit for the compressed base module is 200 MB; the D2f bundle is far below it, so no asset packs are
needed. The first-run download (201 MB) is not part of this: it comes from GitHub after a tap (§0).

Reproduce the numbers:

```bash
java -jar tools/bundletool-all-1.18.3.jar build-apks --bundle=app/build/outputs/bundle/release/app-release.aab --output=/tmp/app.apks
echo '{"supportedAbis":["arm64-v8a"],"supportedLocales":["en-US"],"screenDensity":420,"sdkVersion":36}' > /tmp/arm64.json
java -jar tools/bundletool-all-1.18.3.jar get-size total --apks=/tmp/app.apks --device-spec=/tmp/arm64.json
```

(`tools/` is gitignored: download `bundletool-all-1.18.3.jar` from
https://github.com/google/bundletool/releases; SHA-256
`a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`. Without `--ks` it signs the APK set
with the debug key, which is fine for measuring.)

### Play App Signing (do this at the first upload)

Play re-signs what it delivers with an **app signing key** that Google holds. Opt in when you create
the app (it is the default, and required for App Bundles):

- The existing keystore (`keystore/chessanalyzer-release.jks`, alias `chessanalyzer`, certificate SHA-256
  in §4) becomes the **upload key**: it signs the `.aab` you upload, and Play checks that signature.
- Let Google generate the app signing key, **or** upload the existing key as the app signing key (Play
  Console offers "Export and upload a key from Java keystore" with its PEPK tool). Use the existing key
  if the APKs already handed out by direct download (`PalayaChess-1.0-release.apk`, and the 1.1 APKs) must
  keep updating in place on the same phones: Android only installs an update signed with the same key. If
  Google generates a new key, Play installs and sideloaded APKs are different apps to Android (a user must
  uninstall one to install the other, and loses the app's data).
- If the upload key is ever lost, Play support can reset it; the app signing key stays with Google.
- After the first upload, Play Console > App integrity shows the app signing certificate.

### Foreground-service declarations (Play Console > App content > Foreground service permissions)

The app declares `FOREGROUND_SERVICE_DATA_SYNC` and `FOREGROUND_SERVICE_MEDIA_PROCESSING`, for two services.
Play asks, per type, what the task is, why it must not be interrupted, and for a short video. Suggested text:

> **Data sync: one-time setup download (`ModelDownloadService`).** On first launch the app shows a Setup
> screen that states the size (about 210 MB) and downloads the chess engine's evaluation data and the
> narration voice model only when the user taps "Download". The download takes several minutes and must
> continue if the user leaves the screen or switches apps, so it runs as a foreground service with a progress
> notification that has Pause and Cancel buttons. It never starts by itself and is never restarted by the
> system (START_NOT_STICKY); it stops when the files are downloaded and verified, or when the user pauses or
> cancels. The same type is used by the video export on Android 10-14 (below).
>
> **Media processing: video export (`VideoExportService`).** When the user taps "Save video", the app
> renders their game review into an MP4 on the device (drawing the frames, synthesizing the narration with
> an on-device voice, encoding and muxing). This takes several minutes and must continue if the user leaves
> the screen, so it runs in a foreground service with a progress notification and a Cancel button. It starts
> only from that tap and stops when the video is saved or cancelled. Nothing is uploaded. On Android 10-14
> the service uses the dataSync type because mediaProcessing does not exist there.

Videos for the declarations: (1) the Setup screen, the tap on Download, the notification shade with the
progress notification and Pause/Cancel; (2) "Save video" with the notification shade pulled down. Both
services implement `onTimeout` (Android 15's 6-hour limit): the download pauses, the export stops.

### 3b. Device and Network Abuse policy: data, not code

Play's Device and Network Abuse policy forbids an app to download executable code (dex, JAR, `.so`) from
anywhere but Google Play. Palaya Chess downloads two **data files**:

- `nn-1a298aa575a0.nnue`: the numeric weights of Stockfish's evaluation network, read by the Stockfish code
  that ships in the app (`libstockfish.so`).
- `kokoro-int8-en-v0_19.tar.gz`: an ONNX voice model, its voice embeddings, token table and espeak-ng
  pronunciation data, read by sherpa-onnx/onnxruntime, which also ship in the app.

All program code (dex, `libstockfish.so`, the sherpa-onnx and onnxruntime libraries) is in the bundle and is
updated only through Play. A new engine is an app update: the app refuses any net whose NNUE version or
architecture hash differs from the compiled engine's (`ModelCompatibility`, `NetStore.installVerified`), so a
downloaded file can never change what code runs. First-run files are pinned by SHA-256 at build time; update
files must also be listed in a manifest signed with the maintainers' P-256 key (§4b). This is the position to
state if Play asks. (F-Droid, if the app goes there, may flag the remote download as an anti-feature.)

### APKs for direct installs

`./gradlew :app:assembleRelease` writes per-ABI APKs and a universal one to `app/build/outputs/apk/release/`.
Run it as its **own** Gradle invocation, not together with `bundleRelease`: with both in one command the ABI
split is switched off (AGP 8.9 fails the bundle otherwise) and you get a single universal `app-release.apk`.

| APK (D2f, 1.1) | Size | For |
|---|---|---|
| `app-arm64-v8a-release.apk` | 35,866,501 B | practically every phone from the last ~8 years: **ship this one** |
| `app-armeabi-v7a-release.apk` | 25,769,676 B | old 32-bit phones |
| `app-x86_64-release.apk` | 40,124,486 B | emulators, some Chromebooks |
| `app-universal-release.apk` | 97,110,046 B | any device (all three ABIs) |

Copies in `dist/` (gitignored), with SHA-256s in RUN_LOG D2f: `PalayaChess-1.1-arm64-release.apk`,
`PalayaChess-1.1-universal-release.apk`, `PalayaChess-1.1-release.aab`. `PalayaChess-1.0-release.apk`
(364,733,235 B, the bundled R7 build) is kept; installing 1.1 over it keeps the user's games and the models it
had already set up (no download).

Size history: 1.0 (R7, models inside, no R8) 364.7 MB universal; D1 (R8, models inside) 292.5 MB arm64 and
~196 MB per device from Play; D2f 1.1 (models downloaded) as above.

## 4. Signing

- Release outputs: `./gradlew :app:assembleRelease` (per-ABI APKs + `app-universal-release.apk`) and, as a
  separate invocation, `./gradlew :app:bundleRelease` (the `.aab` for Play; with Play App Signing this keystore
  is the **upload key**). Neither needs the model files (the build compiles their pins from
  `vendor/models/MODELS.lock`).
- Verify with `apksigner verify --verbose --print-certs` (build-tools 36.1.0): v2 and v3 are true; v1 is false
  by design (minSdk 26+ does not need it). The certificate fingerprint below matched on R7, D1 and D2f builds.
  The `.aab` is jar-signed: `jarsigner -verify`.
- Keystore: `keystore/chessanalyzer-release.jks`, alias `chessanalyzer`, RSA 4096, valid until 2054-02-02.
- Credentials: `keystore.properties` (gitignored — **never commit it**).
- Certificate SHA-256 fingerprint:
  `CA:4F:7B:42:CE:83:7F:97:D4:8E:0E:80:2B:48:B1:C9:C9:C2:53:BA:4E:60:4E:56:A8:DF:F6:97:9A:89:09:47`

**Back up the keystore and the password somewhere safe and offline.** If you lose them you
can never update the app under the same listing — Play will reject a differently-signed
update. (With Play App Signing, which an App Bundle requires, a lost upload key can be reset by Play
support; see section 3. Sideloaded APKs still depend on whichever key signs them.)

### 4b. Model files and the signed update manifest (D2a, D2e, D2f)

The two models are not in the APK. A fresh install downloads them once from
`<MODEL_BASE_URL><release.tag>/<file>`, checked against the pins compiled from `vendor/models/MODELS.lock`; no
manifest is read on first run:

| File on the release | Size | SHA-256 (pinned) |
|---|---|---|
| `nn-1a298aa575a0.nnue` | 98,511,183 B | `1a298aa575a085434d29027978dc36867fe9c5bcea9376654b7a8eba1e52dfc2` |
| `kokoro-int8-en-v0_19.tar.gz` | 102,543,452 B | `936044f1f7e3e9822e35ac3212ed555a958983b4c255b9aac7c3b9c9069619c6` |
| (the tar inside, checked while unpacking) | 158,269,440 B | `7190c4801645bf31d10996477a04082019d9cf492ad3d5aeef7b1f7cf10a5dea` |

**Voice format (D2f).** The voice is the upstream sherpa-onnx `kokoro-int8-en-v0_19.tar.bz2`, decompressed and
re-compressed with GNU `gzip -9 -n` (reproducible: no name, no time stamp). Measured: 102.5 MB instead of the
158.3 MB plain tar (55.7 MB less for every user), and `java.util.zip.GZIPInputStream` inflates it in 0.9-1.3 s
on the chess36 emulator (the plain tar's read + hash alone: 0.4 s). The download stays resumable (HTTP Range
on the `.part`); the inflation happens locally while the voice is unpacked, and the TAR inside is checked
against `kokoro.tar.*`, so the installed voice's marker is the same as the bundled builds' (an update from 1.0
keeps its voice). bzip2 was not used: Java has no built-in decoder and a pure-Java one is tens of seconds on a
phone. `scripts/fetch_models.sh` makes and pins the `.tar.gz`; another gzip implementation can produce
different bytes, in which case take the file from the release.

Settings > **Check for updates** reads `<MODEL_BASE_URL>models/models.json` and `models.json.sig` (only when
tapped) and offers a file only if the signature verifies with the public key compiled into the app and the
entry is compatible (net: NNUE header version and architecture hash equal to the engine's; voice: layout
`kokoro-v0_19`, a sherpa-onnx range containing the app's, a `.tar` or a `.tar.gz` with `tarSha256`/`tarSize`;
`minVersionCode`/`maxVersionCode`). "Already installed" compares the tar's hash with the installed voice.
`MODEL_BASE_URL` points at the owner's repo `palayax/Chess` (created 2026-10-07, §4c).

**The manifest signing key (key custody).**

| What | Where | In git? |
|---|---|---|
| Private key, ECDSA P-256 (prime256v1), PEM | `keystore/models-signing.pem` (beside the release keystore) | **No**: `*.pem` is gitignored (`git check-ignore -v keystore/models-signing.pem` names the rule) |
| Public key, X.509 SubjectPublicKeyInfo DER, 91 bytes | `vendor/models/manifest_public_key.der` | **Yes** (negated in `.gitignore`) |
| The same public key inside the app | `GeneratedModelPins.MANIFEST_PUBLIC_KEY_DER_BASE64`, written by `:app:generateModelPins`, which fails the build unless the file is a P-256 key | generated |
| Public-key fingerprint (SHA-256 of the DER) | `912744011368613075e4fdd3604d2e6de4be46528f5e29e5dcfa4300e449f699` (also `GeneratedModelPins.MANIFEST_PUBLIC_KEY_SHA256`) | safe to publish |

The key pair was generated on 2026-10-07 (D2e) with
`openssl ecparam -genkey -name prime256v1 -noout -out keystore/models-signing.pem` and
`openssl ec -in keystore/models-signing.pem -pubout -outform DER -out vendor/models/manifest_public_key.der`.
Never print, paste, log or commit the `.pem`; the scripts only pass its path to `openssl`.

- **Back up `keystore/models-signing.pem` offline, together with the release keystore.** It is needed for
  every future model update.
- **If it is lost:** no model update can be offered to installed apps until an app update ships a new public
  key (generate a new pair, commit the new `.der`, release the app; old installs keep working, they just never
  see a newer manifest as valid). A fresh install is unaffected: first-run downloads are pinned at build time
  and do not use the manifest.
- **If it leaks:** treat it as compromised. Rotate the same way (new pair, app update). Until users update, a
  holder of the leaked key could make the app offer a file; that file must still pass the compatibility
  rules, the structural checks (NNUE header, tar layout, zip-slip guard, the size cap while inflating) and the
  trial, and it is never installed without the user's tap. It can never change program code.

**Publishing flow** (`scripts/publish_models.sh`, needs `gzip`, `openssl`, `python`, and `gh` for the upload):

1. `scripts/fetch_models.sh` (the files under `vendor/models/` must match `MODELS.lock`).
2. `scripts/publish_models.sh models-2026.10 --min-version-code 2 --dry-run`: verifies the files (the net,
   the `.tar.gz` and the tar it inflates to), writes `dist/models/models.json` (schema in
   `docs/MODEL_DOWNLOAD_DESIGN.md` §3.2 and `ModelManifest.kt`; the voice entry carries `tarSize`/`tarSha256`).
3. `scripts/publish_models.sh models-2026.10 --min-version-code 2`: the same, then signs it
   (`openssl dgst -sha256 -sign keystore/models-signing.pem` -> `models.json.sig`, a DER ECDSA signature),
   verifies the signature with the committed public key and the JSON schema (`--verify`, it stops on a
   mismatch), creates the immutable release `models-2026.10` with the two files and uploads `models.json` +
   `models.json.sig` to the rolling `models` release (`--clobber`).
4. Check what is live: download both manifest files and run `scripts/publish_models.sh --verify <dir>/models.json`,
   and download the two model files and compare their SHA-256 with the table above.

`--min-version-code 2`: versionCode 1 (the bundled 1.0) has no update check; 2 is the first build that reads
the manifest. Other modes: `--verify [manifest]` (signature + schema, no private key needed; default
`dist/models/models.json`) and `--sign <manifest>` (signs a hand-made manifest, e.g. the D2e emulator test's,
served by `scripts/model_test_server.py --manifest-dir`). **Publish the binaries and the manifest BEFORE an app
build that points at them is released**: until then a fresh install's Download ends in "The engine files
aren't on the server" (seen on the release build, D2c/D2f).

A net upgrade usually needs an app update anyway: Stockfish changes its architecture hash in most releases,
and the app refuses (never offers, never downloads) a net whose `compat.archHash`/`version` differ from the
engine's. The manifest path is for a same-architecture net and for the voice.

### 4c. The GitHub repository and the model release (owner)

The app downloads from GitHub Releases of the project's public repository, which is also where the GPLv3
source offer points. The URL is compiled in (`app/build.gradle.kts`, `defaultModelBaseUrl`), so the repo must
exist, with the files, before the app is released.

1. **Create the public repo `palayax/Chess`** on GitHub (or pick another name and tell the developers:
   it is one line in `app/build.gradle.kts` (`defaultModelBaseUrl`), the default `--repo` in
   `scripts/publish_models.sh`, the URL in `docs/PRIVACY_POLICY.md`, and this document; rebuild after the
   change). The Claude app token cannot create repos; create it in the GitHub web UI or with your own `gh`.
2. **Push the source** (GPLv3 obligation). Check first that nothing secret is staged: `keystore/`,
   `keystore.properties`, `*.pem`, `local.properties`, `vendor/models/*` (except `MODELS.lock` and
   `manifest_public_key.der`), `dist/` and `tools/` are gitignored; `git status --ignored` lists them. Then set
   the About screen's source link (`about_license_source_url` in `strings.xml`) and the listing
   already point at https://github.com/palayax/Chess (set 2026-10-07).
3. **Install and log in to `gh`** (`gh auth login`, with an account that can create releases in the repo).
4. **Publish the models**: `scripts/fetch_models.sh`, then
   `scripts/publish_models.sh models-2026.10 --min-version-code 2` (§4b). This creates the immutable
   `models-2026.10` release (the net and the voice `.tar.gz`) and the rolling `models` release with the
   signed `models.json`.
5. **Verify from outside**: on a phone or emulator with the release APK, Setup > Download must reach
   "All set", and Settings > Check for updates must say "You're up to date." (this is the last open check of
   the D-track, blocked until the repo exists).

---

## 5. Data safety declaration (Play Console > App content > Data safety)

Answers for version 1.1 (D2f; replaces the bundled builds' "no network use" answers). This is the common
reading of the form, not a legal conclusion; confirm against the form's wording on the day.

- **Does your app collect or share any of the required user data types?** **No.**
  - Collected: none. The app sends no personal data, identifiers, device IDs, app activity, location,
    contacts, files or diagnostics to the developer or anyone else. There is no account, no analytics SDK, no
    crash-reporting service and no ads.
  - Shared: none.
- **Network use, for the reviewer's understanding:** the app downloads two data files from GitHub on the
  first run (only after the user taps Download), and the update manifest, its signature and, on request, a
  model file when the user taps Check for updates in Settings. GitHub receives the device's IP address and a
  User-Agent (`PalayaChess/<version> (Android <SDK>)`) as part of serving the download, as with any web
  request; the app transmits nothing else, and the developer receives nothing (GitHub shows publishers only
  aggregate download counts). The usual reading of the form is that data which never leaves the device,
  and the ordinary connection metadata of a request the app makes to fetch content from a host, are not
  "collected"; the owner should confirm that reading against the form's help text on the day.
- **No library fetches anything on the app's behalf.** androidx.emoji2 (a Compose dependency) would ask Play
  services for the "Noto Color Emoji Compat" font on the first screen, and Play services downloads it (~3 MB)
  charged to the app's uid when it is not cached: found in D2f by `NoNetworkAfterSetupTest`'s TrafficStats
  check. Its startup initializer is removed in the manifest (pinned by `ManifestPermissionsTest` and
  `NetworkPermissionTest`). Re-check the merged manifest's `androidx.startup` initializers after any
  dependency change.
- **The diagnostic log** (`filesDir/logs/`, ~1 MB) stays on the phone. It leaves only when the user taps
  "Share diagnostic log" (Settings) or "Share details" (an error screen) and picks an app in the system share
  sheet: user-initiated sharing to a destination the user chooses, not collection by the app.
- **Android Auto Backup** is on (`allowBackup`): the user's imported games and general settings can go to
  the user's own Google account backup; models, caches, the diagnostic log and narration audio are excluded
  (`backup_rules.xml`, `data_extraction_rules.xml`). The developer never receives it.
- **Is data encrypted in transit?** Not applicable (nothing is collected). The downloads themselves are
  HTTPS only (the release build refuses plain HTTP).
- **Can users request deletion?** Not applicable (no data held by the developer); uninstalling removes
  everything the app stored.
- **Data stored on the device (not part of the form):** imported PGNs and cached analysis, the engine net and
  the voice, saved narration audio, settings, the diagnostic log, all in app-private storage; exported videos
  in `Movies/ChessAnalyzer`.
- **Permissions:** `INTERNET`, `ACCESS_NETWORK_STATE` (the downloads; the metered check before one),
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`,
  `POST_NOTIFICATIONS`. Pinned by the host `ManifestPermissionsTest` and the instrumented
  `NetworkPermissionTest`; "no request on its own" by `NoNetworkAfterSetupTest` and `UpdateCheckNetworkTest`.
- **Privacy policy URL:** required because the app requests `INTERNET`: `docs/PRIVACY_POLICY.md` is live at
  https://palayax.github.io/Chess/privacy/ (GitHub Pages from the `gh-pages` branch); enter that URL (also in the listing).

---

## 6. Release checklist

Before the first Play upload (and again for each release):

- [ ] **Publish the models with `scripts/publish_models.sh` BEFORE shipping** an app that points at them
      (§4b, §4c), then verify from outside: release APK, fresh install, Setup > Download reaches "All set";
      Settings > Check for updates says "You're up to date."
- [ ] Public source repo pushed (GPLv3), linked in About (`about_license_source_url`) and in the listing
- [x] Privacy policy hosted (https://palayax.github.io/Chess/privacy/); [ ] URL entered in Play Console and the listing
- [ ] Back up `keystore/` (`chessanalyzer-release.jks` AND `models-signing.pem`) and `keystore.properties`
      offline, in two places
- [ ] `versionCode` raised for every upload (1.1 = 2); `versionName` matches the listing's "What's new"
- [ ] `./gradlew :app:assembleRelease` and then, separately, `./gradlew :app:bundleRelease`
- [ ] `apksigner verify --verbose --print-certs` on the APKs: v2 + v3, certificate `CA:4F:7B:…:09:47`;
      `jarsigner -verify` on the `.aab`
- [ ] `zipalign -c -P 16 -v 4` on every APK (16 KB pages); `llvm-readelf -lW` Align 0x4000 on every `.so`
      after any NDK or dependency change
- [ ] `aapt2 dump permissions`: exactly the six permissions in §0; `aapt2 dump xmltree --file
      AndroidManifest.xml`: no `networkSecurityConfig`, no `usesCleartextTraffic` in the release manifest
- [ ] `unzip -l`: no `.nnue`, `.tar`, `.tar.gz` in any release APK or the `.aab`
- [ ] `bundletool get-size total` per device (§3), state the result if it changed much
- [ ] The release APK run on a device: fresh install (Setup, download from the real release), and an update
      from the previous version (no Setup, no download)
- [ ] Data safety form (§5), content rating (Everyone), foreground-service declarations with videos (§3),
      app access ("no login"), ads: No
- [ ] Screenshots: phone portrait, at least 2, no chess.com branding (`STORE_LISTING.md`)
- [ ] Listing text avoids implying chess.com affiliation; title has no "Stockfish"
- [ ] Play App Signing chosen deliberately (§3: keep the existing key if sideloaded APKs must update in place)
- [ ] Personal developer account: closed test with at least 12 testers for 14 days before production access
      (Play's rule for personal accounts created after Nov 2023; an organisation account is exempt)
- [ ] Re-confirm the voice licences before any commercial distribution (§7)

---

## 7. Narration voices — two providers, both on-device

The app narrates a game review with one of two voice providers, both running on the phone. Neither needs an
account, an API key, a billing relationship, and no key of any kind ships in the APK. The neural voice is the
default once it is downloaded; Settings > Advanced has one switch to use the phone's built-in voice instead,
and the phone's voice is used automatically until setup has downloaded the neural one.

> **Removed in Round 13: the Google Cloud voice.** An opt-in Google Cloud Text-to-Speech provider
> (bring-your-own API key, with a setup wizard and encrypted key storage) existed through
> Round 12. It was deleted because Google requires a billing account with a payment method even
> for the free tier, which violates the owner's rule that everything must be free, need no credit
> card and run locally. All of it is gone: the provider, the wizard, the key storage (including the
> plaintext fallback), the settings and the strings. A one-time startup purge
> (`LegacyKeyStoragePurge`) deletes any key an older build left on a phone, and a stored `CLOUD`
> provider choice now reads as the neural voice. Do not re-add a cloud provider without revisiting
> that rule and the Data safety answers.

> **History of the voice download.** Through Round 12 the voice was downloaded on first use with a tier picker
> (Piper and Kokoro). Round 13 (1.0) bundled Kokoro in the APK and removed Piper. D2 (1.1) moved it out of the
> APK again: one download on the Setup screen, as a `.tar.gz` since D2f.

| Provider | Licence | In the APK? | Cost to user | Publishable? |
|---|---|---|---|---|
| **Device TTS** (Android built-in) | platform | n/a | free | yes, always |
| **On-device neural** (sherpa-onnx + Kokoro) | sherpa-onnx **Apache 2.0**; Kokoro-82M **Apache 2.0**; espeak-ng data see below | library **yes**; model **no**, downloaded once on first run | free | yes (see the espeak-ng note) |

### On-device neural voice — the default
`sherpa-onnx` is Apache 2.0, which is **compatible with this app's GPLv3**. The Kokoro voice *model* is
downloaded once on the Setup screen as `kokoro-int8-en-v0_19.tar.gz` from the project's GitHub release, checked
against SHA-256 pins compiled into the build (`vendor/models/MODELS.lock`), and unpacked into private storage
(`video/VoiceStore.kt`). See `app/src/main/assets/NEURAL_VOICE_LICENSE.txt` (surfaced in About) for the full
attribution text this section summarises. Redistributing the archive on our release is allowed by its Apache
2.0 licence (its `LICENSE` file travels inside it).

| Component | Licence | Verified from |
|---|---|---|
| sherpa-onnx (inference) | Apache 2.0 | its repository's licence |
| Kokoro-82M (`kokoro-int8-en-v0_19`) | **Apache License 2.0** | The full `LICENSE` file inside that exact archive, matching https://huggingface.co/hexgrad/Kokoro-82M's stated licence. |
| `espeak-ng-data/` (phoneme and dictionary data, 392 files inside the same archive) | espeak-ng project: **"GPL version 3 or later"** | The espeak-ng README ("License Information") and its `COPYING` (GPLv3 text) at https://github.com/espeak-ng/espeak-ng. The archive itself contains **no** licence file for this folder, the sherpa-onnx documentation does not state one, and the repository also holds separate notices for small parts (BSD-2-Clause, Apache-2.0, Unicode). The terms of each data file were **not** checked individually: unverified. |

The espeak-ng data is credited in About as "from the espeak-ng project, GPL version 3 or later". This app is
itself GPLv3, and nothing here is a legal conclusion about compatibility or obligations. Because our GitHub
release redistributes the archive (and the net, trained on ODbL Leela data), the release notes should name
both and their licences; `publish_models.sh` writes the files' names and hashes there, the owner may add the
licence line.

**Piper voices NOT used, and why** (history; the Piper tier no longer ships), since this matters if anyone is
tempted to add one later:
Piper's other well-known English voices carry non-commercial/research-only training-data licences —
"lessac"/"lessac-medium" (2013 Blizzard Challenge `lessac_blizzard2013` corpus: non-commercial
research only, redistribution of derived models forbidden — confirmed by reading
https://www.cstr.ed.ac.uk/projects/blizzard/2013/lessac_blizzard2013/license.html directly) and
"amy"/"amy-low" (fine-tuned from that same Lessac voice data, so it inherits the restriction). An
unrelated "License: mit" tag shown on some HuggingFace mirror pages for these voices describes the
exported file format/tooling, not the training recordings' own terms, and is not a substitute for
checking the model card. The one Piper voice that had been used, `ljspeech-medium`, was public domain
(its model card and https://keithito.com/LJ-Speech-Dataset/).
