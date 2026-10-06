# PUBLISHING — what you must do before this app goes on Google Play

This is not boilerplate. Two items here (GPL, and the APK-vs-AAB choice) genuinely
affect whether and how you can list this app.

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

## 3. APK vs AAB

**Google Play takes an Android App Bundle (.aab)**; the APKs are for direct installs and for
stores that accept APKs (F-Droid, Amazon Appstore, Samsung Galaxy Store, a download page).

### The App Bundle (D1)

```bash
./gradlew :app:bundleRelease    # -> app/build/outputs/bundle/release/app-release.aab (signed with the upload key)
```

The build targets **API 36** (Android 16), which Play requires for new apps and updates from
31 Aug 2026; R8 is on; every native library is 16 KB-aligned (see `CLAUDE.md`, build gotchas).

**What a phone downloads from Play** (bundletool 1.18.3 `get-size total`, which estimates the
compressed download per device; measured on the D1 bundle):

| Device | With the two models (as built) | Without the two models |
|---|---|---|
| arm64-v8a (practically every phone) | **196,058,239 B** (~196 MB) | **14,364,484 B** (~14 MB) |
| x86_64 (emulators, a few Chromebooks) | 197,495,419 B | 15,801,664 B |
| armeabi-v7a (old 32-bit phones) | 195,043,077 B | 13,349,322 B |

"Without the two models" was measured on a throwaway copy of the bundle with the net and the voice
removed (never kept in the build); the difference (~181.7 MB) is the two models compressed (the
voice tar gzips to 102.6 MB, the net to 78.9 MB).

**Size limit.** Play's limit for the compressed download of the base module is 200 MB. The bundle as
built is **just under** it on every ABI (196-197.5 MB by bundletool's estimate), which is too close to
rely on: Play's own compression is not the same as bundletool's, and any growth in the code or the
libraries crosses it. The planned fix is to stop bundling the models (`docs/MODEL_DOWNLOAD_DESIGN.md`,
D-track); then the per-device download is about **14 MB**.

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
  below) becomes the **upload key**: it signs the `.aab` you upload, and Play checks that signature.
- Let Google generate the app signing key, **or** upload the existing key as the app signing key (Play
  Console offers "Export and upload a key from Java keystore" with its PEPK tool). Use the existing key
  if the APKs already handed out by direct download must keep updating in place on the same phones:
  Android only installs an update signed with the same key. If Google generates a new key, Play installs
  and sideloaded APKs are different apps to Android (a user must uninstall one to install the other).
- If the upload key is ever lost, Play support can reset it; the app signing key stays with Google.
- After the first upload, Play Console > App integrity shows the app signing certificate. That is the
  fingerprint for anything that pins the app's signature.

### Foreground-service declaration (Play Console > App content > Foreground service permissions)

The app declares `FOREGROUND_SERVICE_MEDIA_PROCESSING` (used on Android 15+) and
`FOREGROUND_SERVICE_DATA_SYNC` (used on Android 10-14, where mediaProcessing does not exist), both for one
service, `VideoExportService`. Suggested text:

> **Media processing.** When the user taps "Save video", the app renders their game review into an MP4
> video on the device (drawing the frames, synthesizing the narration with an on-device voice, encoding
> and muxing the video). This takes several minutes and must continue if the user leaves the screen or
> switches apps, so it runs in a foreground service with a visible progress notification and a Cancel
> button. It starts only from that tap, never in the background, and stops as soon as the video is saved
> or cancelled. On Android 10-14 the same service uses the dataSync type because mediaProcessing is not
> available there. Nothing is uploaded or synced: the app has no network permission.

Video for the declaration: a screen recording of "Save video" with the notification shade pulled down.

F-Droid is worth considering given the GPL requirement — it is the natural home for a
GPLv3 app and handles the source-offer obligation for you.

### APKs for direct installs

`./gradlew :app:assembleRelease` writes per-ABI APKs and a universal one to
`app/build/outputs/apk/release/` (sizes from the D1 build). Run it as its **own** Gradle invocation, not
together with `bundleRelease`: with both in one command the ABI split is switched off (AGP 8.9 fails the
bundle otherwise) and you get a single universal `app-release.apk`.

| APK | Size | For |
|---|---|---|
| `app-arm64-v8a-release.apk` | 292,485,879 B | practically every phone from the last ~8 years: **ship this one** |
| `app-armeabi-v7a-release.apk` | 282,389,054 B | old 32-bit phones |
| `app-x86_64-release.apk` | 296,743,865 B | emulators, some Chromebooks |
| `app-universal-release.apk` | 353,729,432 B | any device (all three ABIs) |

The R7 file in `dist/` (`PalayaChess-1.0-release.apk`, 364,733,235 B) is the pre-D1 universal APK
(targetSdk 34, no R8); it is kept as it was.

### APK size by design

The net and the narration voice are bundled so the app works fully offline from the first launch
(owner decision, Round 13: "a big APK is fine"). Measured on the R4a debug build:
`app-debug.apk` is **371,058,258 bytes**; the signed release APK (R7, built) is **364,733,235 bytes** (about 348 MiB, 365 MB). The biggest parts:

| Part | Size | How it is stored |
|---|---|---|
| Stockfish NNUE net (`nn-1a298aa575a0.nnue`, `:engine` asset) | 98,511,183 B | uncompressed |
| Kokoro voice, plain `.tar` (`:app` asset) | 158,269,440 B | uncompressed |
| sherpa-onnx native libraries, three ABIs | ~89 MB | uncompressed `.so` |
| Everything else (dex, resources, Stockfish `.so`, assets) | ~20 MB before D1; the dex is now 3.1 MB (R8) instead of 44.7 MB | mostly deflated |

On first run the app copies the net and unpacks the voice into private storage, about 257 MB more, so an
install needs roughly 620 MB steady and more at peak.

What this means for distribution:

- **Sideloading, F-Droid, direct download:** fine. Offer the arm64-v8a APK and state its size on the
  download page.
- **Google Play:** Play delivers per ABI from the bundle, so a phone downloads ~196 MB (table above),
  just under the 200 MB base-module limit. Moving the models out of the APK (D-track,
  `docs/MODEL_DOWNLOAD_DESIGN.md`) brings it to ~14 MB. (Before D1 this section said the bundle was "far
  over" the limit; that was the universal APK's size, not what Play delivers per device.)

---

## 4. Signing

- Release build: `./gradlew :app:assembleRelease` (needs `scripts/fetch_models.sh` first on a fresh clone) writes
  the per-ABI APKs and `app-universal-release.apk` to `app/build/outputs/apk/release/` (before D1 it wrote a single
  `app-release.apk`, which R7 copied to `dist/PalayaChess-<versionName>-release.apk`, gitignored).
  `./gradlew :app:bundleRelease` writes the signed `.aab` for Play (section 3); with Play App Signing this
  keystore is the **upload key**.
  Verify with `apksigner verify --verbose --print-certs` (build-tools 36.1.0): v2 and v3 are true; v1 is false
  by design (minSdk 26+ does not need it). The certificate fingerprint below matched on the R7 build.
- Keystore: `keystore/chessanalyzer-release.jks`, alias `chessanalyzer`, RSA 4096,
  valid until 2054-02-02.
- Credentials: `keystore.properties` (gitignored — **never commit it**).
- Certificate SHA-256 fingerprint:
  `CA:4F:7B:42:CE:83:7F:97:D4:8E:0E:80:2B:48:B1:C9:C9:C2:53:BA:4E:60:4E:56:A8:DF:F6:97:9A:89:09:47`

**Back up the keystore and the password somewhere safe and offline.** If you lose them you
can never update the app under the same listing — Play will reject a differently-signed
update. (With Play App Signing, which an App Bundle requires, a lost upload key can be reset by Play
support; see section 3. Sideloaded APKs still depend on whichever key signs them.)

---

## 5. Data safety declaration

Play requires a Data Safety form. This app's honest answers:

- **Does it collect user data?** No.
- **Does it share user data?** No.
- **Network use:** none. The app makes no network connection of its own: it opens no sockets, and
  nothing is downloaded, uploaded or checked online. The one outbound action is the About screen's links
  (stockfishchess.org and palaya.net), which hand a URL to the user's browser and need no permission.
  No analytics, no accounts, no ads.
- **Data stored on device:** imported PGNs and cached analysis, in app-private storage, plus the engine
  net and the narration voice unpacked from the APK on first run and any saved narration audio.
- **Permissions requested:** no network permissions (no `INTERNET`, no `ACCESS_NETWORK_STATE`). The manifest
  declares only `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROCESSING` (Android 15+),
  `FOREGROUND_SERVICE_DATA_SYNC` (Android 10-14) and `POST_NOTIFICATIONS`, for the video export. This is
  checked by the host `ManifestPermissionsTest` and the instrumented `NoNetworkPermissionTest`.

The listing should state the size (~196 MB download from Play while the models are bundled, plus about
257 MB unpacked on the first analysis), because users on limited storage will care.

---

## 6. Pre-launch checklist

- [ ] Publish source repo (GPLv3 obligation) and link it in About + listing
- [ ] Confirm About screen shows the Stockfish GPL notice (with the Leela data note), the CC0 opening-book credit and the voice credits (sherpa-onnx, Kokoro, espeak-ng)
- [ ] Back up `keystore/` and `keystore.properties` offline
- [ ] Screenshots: phone portrait, minimum 2, no chess.com branding visible
- [ ] Listing text avoids implying chess.com affiliation
- [ ] State the size on the download page (the arm64-v8a APK's size for direct downloads)
- [ ] Set a content rating (Everyone)
- [x] Target API 36, R8, 16 KB-aligned native libraries, typed foreground service with `onTimeout` (D1)
- [ ] For Play: upload `app-release.aab` from `:app:bundleRelease`, opt in to Play App Signing (section 3),
      fill in the foreground-service declaration (section 3). The bundle is ~196 MB per device, just under
      the 200 MB base limit; moving the models out (D-track) is planned before relying on it
- [ ] Re-run `bundletool get-size total` after any dependency change and before each release
- [ ] Re-confirm the voice licences before any commercial distribution. The Kokoro model's licence is
      verified from the `LICENSE` inside the bundled archive (Apache 2.0). The espeak-ng pronunciation data
      is credited from the espeak-ng project's README ("GPL version 3 or later"); the archive carries no
      licence file for it, so per-file terms are unverified. This is a proof of concept, so none of this is
      a hard blocker, but its status must stay recorded truthfully next to the credit.
- [ ] Confirm the **Network use: none / no network permissions** answers still hold (run
      `ManifestPermissionsTest`; look at the merged manifest) if any dependency is added

---

## 7. Narration voices — two providers, both on-device

The app narrates a game review with one of two voice providers, both fully local. Neither needs
an account, an API key, a billing relationship or a network connection, and no key of any kind ships in
the APK. The neural voice is the default; Settings > Advanced has one switch to use the phone's built-in
voice instead.

> **Removed in Round 13: the Google Cloud voice.** An opt-in Google Cloud Text-to-Speech provider
> (bring-your-own API key, with a setup wizard and encrypted key storage) existed through
> Round 12. It was deleted because Google requires a billing account with a payment method even
> for the free tier, which violates the owner's rule that everything must be free, need no credit
> card and run locally. All of it is gone: the provider, the wizard, the key storage (including the
> plaintext fallback), the settings and the strings. A one-time startup purge
> (`LegacyKeyStoragePurge`) deletes any key an older build left on a phone, and a stored `CLOUD`
> provider choice now reads as the neural voice. Do not re-add a cloud provider without revisiting
> that rule and the Data safety answers.

> **Also removed in Round 13: downloads and the Piper tier.** The voice model used to be downloaded on
> first use with a tier picker (Piper and Kokoro). It is now bundled in the APK and Piper is gone.

| Provider | Licence | In the APK? | Cost to user | Publishable? |
|---|---|---|---|---|
| **Device TTS** (Android built-in) | platform | n/a | free | yes, always |
| **On-device neural** (sherpa-onnx + Kokoro) | sherpa-onnx **Apache 2.0**; Kokoro-82M **Apache 2.0**; espeak-ng data see below | library **yes**, model **yes** | free | yes (see the espeak-ng note) |

### On-device neural voice — the default
`sherpa-onnx` is Apache 2.0, which is **compatible with this app's GPLv3**. The Kokoro voice *model* ships
inside the APK as one stored `.tar` asset, is unpacked once on the first analysis, and is SHA-256-verified
against a hash pinned in the build (`vendor/models/MODELS.lock`, fetched by `scripts/fetch_models.sh`). See
`app/src/main/kotlin/net/palaya/chessanalyzer/video/BundledVoiceInstaller.kt` and
`app/src/main/assets/NEURAL_VOICE_LICENSE.txt` (surfaced in About) for the full attribution text this
section summarises.

| Component | Licence | Verified from |
|---|---|---|
| sherpa-onnx (inference) | Apache 2.0 | its repository's licence |
| Kokoro-82M (`kokoro-int8-en-v0_19`) | **Apache License 2.0** | The full `LICENSE` file inside that exact bundled archive, matching https://huggingface.co/hexgrad/Kokoro-82M's stated licence. |
| `espeak-ng-data/` (phoneme and dictionary data, 392 files inside the same archive) | espeak-ng project: **"GPL version 3 or later"** | The espeak-ng README ("License Information") and its `COPYING` (GPLv3 text) at https://github.com/espeak-ng/espeak-ng. The archive itself contains **no** licence file for this folder, the sherpa-onnx documentation does not state one, and the repository also holds separate notices for small parts (BSD-2-Clause, Apache-2.0, Unicode). The terms of each data file were **not** checked individually: unverified. |

The espeak-ng data is credited in About as "from the espeak-ng project, GPL version 3 or later". This app is
itself GPLv3, and nothing here is a legal conclusion about compatibility or obligations.

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
