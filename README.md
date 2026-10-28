# 🐝 WARP IP Scanner

Finds working Cloudflare WARP endpoints on networks that fight you
(MPT in Myanmar, for example). It runs real WireGuard handshakes and
keeps only the endpoints that actually answer. The verified top list
is published as a feed that Phoenix VPN reads.

- Package: `com.phoenix.warpscanner`
- English UI, min SDK 24 (Android 7.0), target SDK 34
- [Releases](https://github.com/tugyix05/Warp-IP-Scanner/releases)

## Tabs

- **Scan.** Census of `162.159.192.0/24` on port 500. Only endpoints
  with 0% loss make the list, sorted by ms. Every candidate gets its
  full 4 verify draws before the run ends — the "Show N verified"
  number is the run's target, never a cutoff that leaves a candidate
  judged on zero draws. A row that never verifies is shown greyed
  with its draw count ("not verified this run"), not as dead: if it
  appears in a scan, it is not dead. If the endpoint already has a
  saved state record (step 1), the row shows its state instead
  ("last seen working 2d ago", "warming…").
- **Range.** Scan any CIDR you type and add what answers to Backup.
  A run is 4 passes over the same CIDRs; an endpoint is saved if it
  answers in any of the 4 (one good draw is proof — a failed draw
  says nothing about the endpoint). Switching tabs never stops a
  running scan — it keeps going and shows live progress when you
  come back.
- **Backup.** The endpoints you've kept, best ms remembered. Copy them
  out or export CSV.
- **Stable.** Re-verifies everything in Backup, up to 4 rounds with a
  fresh engine process each round. A failed draw says nothing about
  the endpoint, so it gets more draws. Ranks by history. Nothing
  auto-removes from Backup: a row that fails every round is shown
  as not verified this run, and only your explicit delete removes
  it.
- **Refresh (Stable tab, v1.20).** Warms each shown row first (one
  landed handshake, discarded), then measures until 3 handshake
  passes land (7 attempts max) and shows the median. That is the
  typical warm cost, not the first cold draw.
- **Publish.** Pushes the shown top list as `endpoints.json` to this
  repo. Needs a GitHub token in Settings (see below). Without one, the
  app hands you the JSON to push by hand.

## Engine

The engine is a fully static `aarch64` musl build of
[cf-scanner](https://github.com/QMahyar/cf-scanner) (MIT), shipped as a
native library and run as a subprocess. No root, no proot, no Termux.
A verify pass is a full handshake plus a data reply through the
tunnel, and an endpoint only counts as alive if every probe in the
pass answers.

## Read this before you scan

Scan on **mobile data with every VPN off**. A scan through a VPN or
over Wi-Fi measures the wrong network and the results are useless. On
MPT, `162.159.192.1–.10` are closed. Only `.11` and up answer.

## Settings

- **WARP .conf.** Import yours before scanning. Verification uses its
  keys, and the app will tell you if it is missing.
- **GitHub token (optional).** A fine-grained PAT, this repo only,
  Contents read/write. It is stored encrypted and never shown again
  after you save it. That is what makes one-tap Publish work.

## Building

CI builds and publishes every push to `main`
(`.github/workflows/build.yml`). The release APK lands in
[Releases](https://github.com/tugyix05/Warp-IP-Scanner/releases).

Local build (Android SDK 34 and JDK 17 required; there is no Gradle
wrapper in this repo, CI uses Gradle 8.9):

```sh
gradle assembleRelease
```

Signing uses repo secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`). Nothing signing-related lives in this
repo.
