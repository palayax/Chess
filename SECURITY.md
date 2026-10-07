# Security policy

## Reporting a vulnerability

Please report security problems privately, not in a public issue:

- GitHub: **Security → Report a vulnerability** on this repository (private vulnerability reporting), or
- email **Chess@palaya.net**.

Include what you found, how to reproduce it and the app version (Settings → About). You will get an
answer within a week. Please give us a reasonable time to ship a fix before disclosing it.

## Scope

- The Palaya Chess Android app (`app`, `engine`, `core`) and the scripts in `scripts/`.
- The model files and the signed update manifest published in this repository's Releases
  (`models-*` releases and the `models` release).

## How the app protects downloads

The app downloads data files only (the Stockfish NNUE network and the Kokoro narration voice), never
program code, and only after the user taps a button:

- First-run files are checked against SHA-256 hashes, sizes and the NNUE header compiled into the app;
  a file that does not match is never used.
- "Check for updates" accepts `models.json` only with a valid ECDSA P-256 signature from the key whose
  public half is `vendor/models/manifest_public_key.der`; each offered file is then checked against the
  signed SHA-256, and a new model is tried before it replaces the old one, with rollback on failure.
- HTTPS only in release builds; cleartext is allowed only to the emulator host in debug builds.

Details: [`docs/MODEL_DOWNLOAD_DESIGN.md`](docs/MODEL_DOWNLOAD_DESIGN.md) and
[`docs/PRIVACY_POLICY.md`](docs/PRIVACY_POLICY.md).
