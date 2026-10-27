# SignalDeck — agent notes

SignalDeck (package `com.macdmr.radio`) drives the radio module of DMR phones directly (root/Magisk). This repository
holds only the app. The hardware facts, measured limits and dead ends (MCU protocol, tuning range, 30 s TX cap,
squelch, band=1 on VHF, audio format, channel-write rules, Xplore/other phones) are in the research repository's
`CLAUDE.md`: https://github.com/IIMacGyverII/phonedmrapp (locally `D:\Documents\code\personal\phonedmrapp`). Read it
before touching radio behaviour; it wins over guesses.

## Every change
- Build + host tests: `JAVA_HOME=<JDK 17> ./gradlew assembleRelease testReleaseUnitTest`.
- Install the **release** build on the owner's phone: `adb install -r app/build/outputs/apk/release/app-release.apk`.
  Never `adb uninstall` (loses the Magisk grant and the app data).
- Update the in-app help in the same change (`ui/HelpView.java` TOPICS, `ChannelEditorView` `H_*` texts).
- Log it in `CHANGELOG.md` (newest first, dated section, `| Backlog | Change | Files | Test |` row, say whether it was
  device-tested).
- Never commit signing keys or their passwords (`local.properties` holds them), the profile-signing key
  (`~/.signaldeck/`), callsigns, APRS-IS passcodes, the web PIN or the owner's location.

## Releases (only when the owner asks)
- Asset name `SignalDeck-<version>.apk` (the updater reads the version from it). Betas `1.0.0-beta.N`: `gh release create
  <tag> <apk> --prerelease`; releases without `-` in the version. Notes start with `## What's new …` and list the APK's
  SHA-256. Builds up to beta.11 updated from phonedmrapp's releases; beta.12 (published in both) is the bridge, so from
  beta.13 on releases go to this repository only.
- Device profiles: edit `profiles/profiles.json`, bump `serial`, sign with `scripts/sign_profiles.py`; builds up to
  beta.11 read phonedmrapp's copy, so update both (advisories should reach users still on old builds).

## Mirrored agent notes
- `CLAUDE.md` and `GROK.md` are mirrors of each other: identical apart from the title. Any change to one must be
  made in the other in the same edit.

## Transmit testing
- All development testing that transmits is done inside a Faraday cage by a licensed RF expert. Never ask for, plan
  or assume an on-air test outside that setup; when a change needs a transmit test, say so and leave it to the owner.

## Restricted mode (owner rule, 2026-10-27)

Every new page, mode, tool or transmit path is wired into Restricted mode in the same change: an id in `Restriction.MODES` (or `ADMIN`), the guard at its service start (`modeAllowed` / `adminAllowed` / `txInhibited`), its hiding on the PKT sheet or the Device page, its route in `WebHost.ROUTE_MODES` / `ROUTE_ADMIN`, and a switch label in `ui/RestrictedDialog`. A feature that is not wired in is a bug, since the user who turned the mode on expects it to cover the whole app. Design: phonedmrapp `docs/plan-restricted-mode.md`; the help topic "Restricted mode" must stay true to the code.
