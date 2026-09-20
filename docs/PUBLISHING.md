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

You asked for an **APK**, and that is what is delivered — it installs directly by
sideloading and is what you asked to be able to publish.

Note for the actual upload step: **Google Play requires an Android App Bundle (.aab)** for
new apps, not an APK. The APK is the right deliverable for testing, sideloading, and for
stores that accept APKs (Amazon Appstore, F-Droid, Samsung Galaxy Store, direct download).
When you are ready for Play specifically, run `./gradlew :app:bundleRelease` — the signing
config is already wired, so it will produce a signed `.aab` from the same source with no
further work.

F-Droid is worth considering given the GPL requirement — it is the natural home for a
GPLv3 app and handles the source-offer obligation for you.

### APK size: 108 MB, and what to do about it

Measured on the Round 5 signed build — `app-release.apk` is **108,627,896 bytes**, up from
17,023,316 before on-device neural narration was added. The Stockfish NNUE net is **not** in the
APK and never has been; it is still downloaded at runtime. The growth is entirely sherpa-onnx's
native libraries, which ship for all three ABIs and are stored uncompressed:

| Library | x86_64 | arm64-v8a | armeabi-v7a |
|---|---|---|---|
| `libonnxruntime.so` | 24.40 MB | 21.22 MB | 14.65 MB |
| `libsherpa-onnx-jni.so` | 4.96 MB | 4.55 MB | 3.27 MB |
| `libsherpa-onnx-c-api.so` | 4.70 MB | 4.26 MB | 3.05 MB |
| `libstockfish.so` | 1.55 MB | 1.51 MB | 1.22 MB |
| **Per-ABI total** | **35.6 MB** | **31.5 MB** | **22.2 MB** |

~89 MB of the 108 MB is native code for three ABIs, of which any given device uses exactly one.
Everything else — dex, resources, assets — is about 19 MB.

**This is a delivery-format problem, not a code problem.** Three options, in order of preference:

1. **Publish an `.aab`** (`./gradlew :app:bundleRelease`, already wired). Play's split delivery
   sends one ABI per device, so an arm64 phone downloads roughly **51 MB**. This is free — no code
   change — and is required for Play anyway (see above).
2. **Per-ABI APK splits** for sideloading and APK-accepting stores. Same ~51 MB result for arm64,
   at the cost of producing and tracking several APKs instead of one.
3. **Drop `x86_64`** if emulator support stops mattering. Saves 35.6 MB from the universal APK on
   its own — but it is what makes this project verifiable on this machine without physical
   hardware, so do not drop it while the emulator is the only test device.

Deliberately **not** applied for the POC: the agreed deliverable is one universal signed APK that
installs anywhere, and 108 MB is under Play's 150 MB APK ceiling. Revisit before any real
distribution — option 1 costs nothing and is needed for Play regardless.


---

## 4. Signing

- Keystore: `keystore/chessanalyzer-release.jks`, alias `chessanalyzer`, RSA 4096,
  valid until 2054-02-02.
- Credentials: `keystore.properties` (gitignored — **never commit it**).
- Certificate SHA-256 fingerprint:
  `CA:4F:7B:42:CE:83:7F:97:D4:8E:0E:80:2B:48:B1:C9:C9:C2:53:BA:4E:60:4E:56:A8:DF:F6:97:9A:89:09:47`

**Back up the keystore and the password somewhere safe and offline.** If you lose them you
can never update the app under the same listing — Play will reject a differently-signed
update. (Play App Signing can mitigate this if you opt in at first upload.)

---

## 5. Data safety declaration

Play requires a Data Safety form. This app's honest answers:

- **Does it collect user data?** No.
- **Does it share user data?** No.
- **Network use:** outbound only, to `tests.stockfishchess.org` (downloading the NNUE
  evaluation file), `api.github.com` (checking for a newer Stockfish release), and the voice-model
  host (for the on-device natural voice). All three are user-initiated downloads or explicit
  opt-ins; none is background telemetry. No analytics, no accounts, no ads.
- **Data stored on device:** imported PGNs and cached analysis, in app-private storage.
- **Permissions requested:** `INTERNET` only.

The engine-data download must be disclosed in the listing description — **79 MB over the
wire, 98.5 MB once stored on device** (the endpoint serves it compressed). Users on metered
connections will care, and Play reviewers dislike undisclosed large downloads. The app asks
before downloading and reports progress.

---

## 6. Pre-launch checklist

- [ ] Publish source repo (GPLv3 obligation) and link it in About + listing
- [ ] Confirm About screen shows the Stockfish GPL notice and the CC0 opening-book credit
- [ ] Back up `keystore/` and `keystore.properties` offline
- [ ] Screenshots: phone portrait, minimum 2, no chess.com branding visible
- [ ] Listing text avoids implying chess.com affiliation
- [ ] Disclose the one-time engine-data download (~79 MB transferred, 98.5 MB stored on device)
- [ ] Set a content rating (Everyone)
- [ ] For Play: build `.aab` rather than the APK
- [ ] Confirm the **voice model's own licence** (not just sherpa-onnx's Apache 2.0) and that it
      appears in About — see §7. This is currently a proof of concept, so a voice with a
      non-commercial/unclear licence is not a hard blocker (the model can be swapped later), but its
      status must be recorded truthfully next to it. Re-tighten this to "must verify before
      shipping" the moment commercial distribution is on the table — both models actually shipped
      today happen to already be unambiguously permissive (see §7's table), so nothing needs to
      change for that to become a hard requirement again.
- [ ] Disclose the voice-model download in the listing. **No longer conditional** — as of Round 5
      the natural voice IS the default and auto-downloads (~20 MB transferred, ~36 MB stored) the
      first time a user opens a narrated review, so this is a disclosed data cost, not an opt-in one

---

## 7. Narration voices — three providers, three licence situations

The app can narrate a game review with one of three voice providers. They differ in whether they
need a download or an account, and — the part that matters for publication — how they are
licensed. The two on-device ones are free and are the defaults; the Cloud one is an opt-in upgrade
on the **user's own** Google Cloud key, and no key of any kind ships in the APK.

| Provider | Licence | Ships in APK? | Cost to user | Publishable? |
|---|---|---|---|---|
| **Device TTS** (Android built-in) | platform | n/a | free | yes, always |
| **On-device neural** (sherpa-onnx) | **Apache 2.0** | library yes, **model downloaded** | free | **yes** |
| **Cloud voice** (Google Cloud Text-to-Speech, BYO key) | Google Cloud ToS, per user | code only, **no key** | free tier on the user's own billing project, then billed to them | yes — nothing of Google's is redistributed by the app |

### On-device neural voice — the default worth shipping
`sherpa-onnx` is Apache 2.0, which is **compatible with this app's GPLv3**. The voice *model* is
downloaded on first use (never bundled) and SHA-256-verified, the same pattern already used for the
98 MB Stockfish network — see `app/src/main/kotlin/net/palaya/chessanalyzer/video/VoiceModelProvisioner.kt`
for the pinned URL/SHA-256/size of each tier, and `app/src/main/assets/NEURAL_VOICE_LICENSE.txt`
(surfaced in About) for the full attribution text this table summarizes.

| Tier | Model | Licence | Verified from |
|---|---|---|---|
| Piper (default, fast) | `vits-piper-en_US-ljspeech-medium-int8` | Piper (rhasspy): **MIT**. Training data (LJ Speech): **public domain**. | The archive's own `MODEL_CARD` ("License: public domain") AND independently, verbatim, from https://keithito.com/LJ-Speech-Dataset/ ("This dataset is in the public domain... There are no restrictions on its use."). |
| Kokoro (best quality, opt-in download) | `kokoro-int8-en-v0_19` (Kokoro-82M) | **Apache License 2.0** | The full `LICENSE` file bundled inside that exact archive, matching https://huggingface.co/hexgrad/Kokoro-82M's stated licence. |

Both shipped tiers have an unambiguous, independently-verified permissive licence — no gate was
needed here, but the verification was still done and is recorded above because a future model swap
(a different Piper voice, a newer Kokoro release, a third tier) needs the same rigor.

**Piper voices NOT used, and why**, since this matters if anyone is tempted to add one later:
Piper's other well-known English voices carry non-commercial/research-only training-data licences —
"lessac"/"lessac-medium" (2013 Blizzard Challenge `lessac_blizzard2013` corpus: non-commercial
research only, redistribution of derived models forbidden — confirmed by reading
https://www.cstr.ed.ac.uk/projects/blizzard/2013/lessac_blizzard2013/license.html directly) and
"amy"/"amy-low" (fine-tuned from that same Lessac voice data, so it inherits the restriction). An
unrelated "License: mit" tag shown on some HuggingFace mirror pages for these voices describes the
exported file format/tooling, not the training recordings' own terms, and is not a substitute for
checking the model card.

### Cloud voice — every user brings their own Google account and key
`GoogleCloudTtsProvider` calls Google Cloud Text-to-Speech with an API key the **user** creates in
their **own** Google Cloud project (the in-app wizard walks them through it, and states up front
that Google requires billing to be enabled on that project even for the free tier). The key is
entered at runtime, validated with a real two-word request before it is kept, stored only in the
Keystore-backed `EncryptedSharedPreferences` in `NarrationSettingsRepository`, and never logged or
embedded — an APK is trivially unpacked, and a bundled key would let strangers drain the owner's
quota. There is therefore no shared quota, no owner-side cost, and no audio of Google's shipped in
the app: the synthesized narration is produced for, and by, the end user under their own agreement
with Google. Free-tier figures quoted in the wizard (4M Standard / 1M WaveNet, Neural2, Chirp 3: HD
characters per month per billing project; ~7k characters per review) are Google's published ones
at the time of writing.

**Unverified against the live API**: no Google key was available when this was built. Everything
up to the HTTP exchange is tested with stubbed responses; the socket itself is not. See RUN_LOG.md
Round 9.
