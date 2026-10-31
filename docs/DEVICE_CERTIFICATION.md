# Getting your phone certified for SignalDeck

**Version 2, 2026-10-29. This is a living document:** it changes as we learn from new phones. The history is at the
bottom, and every change is in this file's git history. Questions and suggestions are welcome in GitHub Discussions
or an issue.

SignalDeck talks to the radio module inside a phone directly, through a small **device profile** for each phone model.
This page explains how a phone goes from "unknown" to "supported": the levels, the tests at each level, what to send
us, and who decides. It complements [Request support for your DMR phone](DEVICE_SUPPORT_REQUEST.md), which covers the
first step (the device report).

## 1. Safety and the law first

- **Receiving is always fine. Transmitting is your responsibility.** Only transmit where your licence allows it: a
  valid amateur licence for amateur frequencies, or a licence-free service the radio is approved for. Use your own
  callsign, at the lowest power that works, and identify as your rules require.
- **Transmit tests go into a dummy load, or at low power on a quiet simplex frequency you are allowed to use.**
  Keep every test key short (10 s or less unless a step says otherwise) and never leave a keyed radio unattended.
- **Watch the heat.** SignalDeck stops long transmissions when the phone gets hot. If the phone feels hot, stop.
- **Don't experiment with system files.** Only follow the steps here. Some phones crash or reboot if certain hidden
  system files are read; the device report knows which ones to avoid. Never load radio-module firmware unless a step
  tells you to, and only with the image SignalDeck offers for your exact module.
- **Privacy:** reports never need your callsign, DMR ID, phone number or location. Remove them if they appear in a
  screenshot or log before you share it. Use a test callsign such as N0CALL in anything you post.

## 2. The levels

Each phone model has one of these levels. It's shown in the app (Device → About this radio) and on the website.

| Level | Meaning | Transmit in the app |
|---|---|---|
| **Unknown** | No profile yet. SignalDeck won't take the radio; it offers the device report. | Off |
| **Experimental** | A draft profile from a device report and the stock app. Nothing confirmed on the air yet. | Off |
| **In progress** | Receive works on at least one tester's phone. Transmit not yet confirmed. | Off |
| **Beta** | Receive and transmit confirmed on at least one phone, measured by a trusted tester. Some features untested. | On |
| **Supported** | All core tests passed on at least two phones (or one phone with the maintainer's bench measurements), the radio notes are complete, and the help describes every known limit. | On |
| **Discontinued** | No longer supported (hardware unavailable, a blocking problem). | As last known |

Moving up a level always needs the evidence in section 3 and the maintainer's review (section 5). A level can also go
down when a new problem is found.

## 3. The tests

Run them in order. Each one says what to record. **(TX)** marks a step that transmits: do it only within section 1.

### 3.1 Identify the phone (for Experimental)
1. Install SignalDeck, allow root, and run **Device → Device report → Run all checks** (receive only). Send the report.
2. Note: phone model and Android version, the radio module's version (the report reads it), the frequency range the
   stock app allows, and whether the phone has DMR, FM only, or both.

### 3.2 Receive (for In progress)
With a second radio (any handheld) or an SDR as the signal source:
1. **FM receive:** a voice transmission on a simplex channel comes through clearly. Check with tone squelch (CTCSS)
   off first; then with a CTCSS tone that matches, and one that doesn't (the second must stay silent).
2. **Signal reading:** the S-meter moves with the signal (if the phone has one; some don't, and that's a known limit).
3. **Squelch:** with no signal the speaker is quiet; Soft SQ opens on voice and stays closed on noise.
4. **Channel changes and scanning:** change channels and zones; scan stops on an active channel.
5. **DMR receive** (DMR phones): a group call on your talkgroup and time slot shows the caller ID and plays the voice.
6. **Band limits:** try a frequency just inside and just outside the bands the stock app allows. SignalDeck must refuse
   the outside one. Note the result.
7. **Leave it running** for 30 minutes and receive a few times: no drop-outs, no app restarts. Note any.

Record: a short screen recording or screenshots (with private data removed), the app's log (Device → Diagnostics →
Share), and a one-line result per step.

### 3.3 Transmit (for Beta) (TX)
This step needs a way to check your own signal: a second radio, plus ideally an SDR (an RTL-SDR is enough for most
checks) or a service monitor. Transmit is off in the app below Beta; the maintainer sends a test build for this step,
or, in your own fork, you set `verified = true` for your profile in your build only (section 6). Run the steps with
the **transmit test session** (section 3.5): it keys, times and records each one for you.
1. **FM voice:** key for 5 s on a simplex channel; the second radio hears you clearly and at a normal level.
2. **Release:** the transmission ends when you release the key, every time. The app's 28 s limit ends a held key.
3. **Frequency:** with an SDR, note how far your carrier is from the channel frequency (a few hundred Hz is normal;
   several kHz is worth reporting).
4. **DMR** (DMR phones): a group call on your talkgroup is received by another DMR radio, with your caller ID.
5. **Heat:** note the battery temperature before and after five short keys (Device report shows it).

### 3.4 Data and extras (for Supported)
Each that the phone can do; mark the others "not supported" with the reason.
1. **APRS:** receive a packet; send a position beacon (TX) that another station or an SDR decodes.
2. **SSTV, FT8, RTTY:** receive; send (TX) one each if the phone can send app audio (some phones only transmit from the
   microphone, which is a known limit, not a failure).
3. **Messages** (DMR): send and receive a text.
4. **SignalDeck Web, Android Auto, the widget:** each opens and works.
5. **Restart and reboot:** SignalDeck takes the radio again after the app restarts and after a phone reboot (with "Own
   the radio at boot" on); handing back to the stock app works.

### 3.5 The transmit test session (TX)
**Device → Device report → Transmit test session** runs section 3.3 for you and writes the evidence we need.

- **When it opens:** only when your profile may transmit: verified, or verified in your fork's own build (the report
  then says the flag came from your build, not from the official profile list). Never while an advisory has switched
  transmit off, and never while Restricted mode keeps transmitting off.
- **Safety first:** a sheet with the rules of section 1 (licence, dummy load or low power on a simplex frequency you
  may use, short keys, attended, heat). Switch each one on, then Start. Every step has Skip and Stop, and every key has
  a **Release now** button.
- **The steps:** FM voice key (5 s) · release check (three 2 s keys, each must end with the module's SEND_STOP) · the
  28 s limit (one key SignalDeck does not release; its watchdog must end it) · frequency offset and deviation (an
  optional 10 s key while you measure, then you type the values) · DMR group call (5 s, DMR phones; did the other
  radio show your caller ID) · heat (five 3 s keys, temperatures before and after).
- **Recorded by the app, per key:** UTC time, channel number and name, receive and transmit frequency, power, mode
  (FM or DMR, with colour code, slot and talkgroup), how the radio was keyed (the PTT path), the module's status
  replies with their times, who released the key (app, watchdog, tester, module), and the battery and processor
  temperatures before and after.
- **Entered by you:** whether the receiving radio heard it (yes / no / not checked) and notes, the measured offset and
  deviation, your measuring equipment and receiving radio, and a recording: pick the file on the phone (SignalDeck
  stores its name and SHA-256, not the file) or give a link (with its SHA-256 if you have it).
- **Output:** `tx-report.json` in the device report folder (Download/DMR/DeviceReport), a "Transmit test session"
  section in report.txt and the same in 00_SUMMARY.txt. Share the report zip, or tx-report.json on its own.
- **Privacy:** the callsign and DMR ID set in the app are replaced by `<callsign>` and `<dmr id>` in everything it
  writes, and e-mail addresses are masked. Do not type them into notes anyway.

`tx-report.json`, schema version 1:

| Field | Meaning |
|---|---|
| `schema`, `type` | `1`, `"signaldeck-tx-report"`; a later schema gets a new number |
| `created`, `finished` | UTC times of the session |
| `build` | the build identity (section 3.6) |
| `phone`, `module` | manufacturer, model, device, Android version; the radio module's version string |
| `safety` | `confirmedUtc` and the rules the tester confirmed |
| `equipment`, `receiver` | the measuring equipment and the receiving radio, as typed |
| `recordings[]` | `name` or `url`, `sha256`, `bytes` (`-1` when unknown) |
| `steps[]` | `id` (`fm`, `release`, `watchdog`, `measure`, `dmr`, `heat`), `title`, `result` (`pass`, `fail`, `recorded`, `skipped`, `not run`), `auto` (what the app found), `tester` (`heard`, `callerId`, `notes`), `measured` (`offsetHz`, `deviationHz`, null when not measured), `keys[]` |
| `keys[]` | `startUtc`, `plannedMs` (0 = held for the 28 s limit), `durationMs`, `releasedBy`, `refused`, `channel` (`number`, `name`, `mode`, `rxHz`, `txHz`, `power`, `bandwidth`, `colorCode`, `slot`, `talkgroup`), `pttPath`, `statuses[]` (`tMs`, `code`, `name`), `sendStartMs`, `sendStopMs`, `batteryC` and `processorC` (`before`, `after`, null when unreadable) |

Times inside a key are milliseconds from the moment SignalDeck pressed the key; `-1` means the module never reported it.

### 3.6 Build identity
Every device report (report.txt section "SignalDeck build", `build` in report.json, the top of 00_SUMMARY.txt) and
every transmit test says exactly what was tested:

- version, git **commit** (short and full), **branch**, the **repository** it was built from (`git remote get-url
  origin`, with any user name, password or token removed), whether the working tree had **uncommitted changes**, and
  the **build time** (UTC); "unknown" when the build machine had no git;
- the SHA-256 of the **signing certificate** and whether the build is **official** (signed with the project's release
  key, whose public fingerprint is in `BuildIdentity.OFFICIAL_CERT_SHA256`) or a **fork** (any other key);
- the **profile**: id, revision, built in or from the signed list, verified, transmit switched off by an advisory, and
  "transmit forced on" when the phone's owner overrode such an advisory; and whether `verified = true` is in the
  official signed profile list or only in this build.

Build from a clean, pushed commit when you can, so the maintainer can rebuild exactly your build.

### 3.7 Timing and calibration (for Supported)
Every radio module has its own timing and scales, and SignalDeck must not assume another phone's. These are measured on
every phone before Supported, with a signal source whose level can be stepped (an SDR that transmits, such as a HackRF,
or a signal generator) and the bench commands in the maintainer's build. The phone receives only, except where marked
(TX). Each result either confirms the default or sets the phone's profile.

| # | Measure | How | Feeds |
|---|---|---|---|
| 1 | **Signal reading scale** | A carrier in 10 dB steps across the reading's range; the raw reading at each step. Linear range, steps per dB, where it saturates; the idle level | `rssiStepDb`, `rssiOffsetDb` (dB shown in the S-meter, history, fox hunt, coverage, band scope), the reception thresholds |
| 2 | **Settle after a retune** | The band scope over a carrier with the settle time stepped 0–1000 ms; the shortest that reads the carrier's true level and a clean floor next to it | band scope and scanner settle times |
| 3 | **Reading time** | Time from a signal query to its answer | band scope step time |
| 4 | **Squelch opening level** | Carriers stepped up from the floor at each squelch setting; where the radio reports a reception | the help's squelch text, Soft SQ defaults |
| 5 | **Scan rate** | A scan list of empty channels plus one with a carrier: channels per second, time to stop and open the audio, hang after the carrier ends | scanner dwell and hang times |
| 6 | **Receive audio path** | Tones from 100 Hz to 3.5 kHz at a fixed deviation; level and response; drop-outs over 15+ receptions | audio path settings, the help's limits |
| 7 | **Sub-audible tones** | CTCSS tones (low, middle, high) and DCS: detected or not | tone detection limits in the help |
| 8 | **Digital receive** | DMR caller ID and voice; M17 with the phone's equaliser or self-calibration (a fit kept only when a second station decodes as well); payload-exact frames where measurable | M17 equaliser table per phone model |
| 9 | **(TX) Transmit levels** | FM deviation and frequency offset of a 1 kHz tone and of voice; DMR framing decoded from an SDR capture; time to key and unkey | TX scale, the help's offset note |
| 10 | **(TX) Heat** | AP and battery temperature over five short keys and one long key | the heat guard |

Record the numbers in the phone's compatibility notes and in the pull request that changes its profile; a default that
was confirmed is recorded too ("settle 700 ms confirmed").

## 4. What to send

- The **device report** (3.1) and, for each level, the results above: a table with each step, pass/fail, and a note.
- Logs from Device → Diagnostics → Share for anything that failed.
- Measurements with the tool you used (for example "RTL-SDR, carrier +0.6 kHz").
- For transmit: the transmit test session's `tx-report.json` (in the report zip) and any recordings with their SHA-256.
- Where: a GitHub issue using the device template, or attached to your Discussions thread. One thread per phone model,
  so other owners of the same phone can add their results.
- **GitHub templates:** before your first key, open a **Starting transmit tests** issue ([new issue](https://github.com/IIMacGyverII/SignalDeck/issues/new/choose), [form](../.github/ISSUE_TEMPLATE/transmit-test-start.yml)) (phone model, your fork and
  commit, your equipment, the band, the licence confirmation; it has no callsign field, keep it that way). Afterwards,
  a **Transmit test results** issue ([form](../.github/ISSUE_TEMPLATE/transmit-test-results.yml)) with the report zip, a result per step and the recordings. A pull request from your
  fork gets a checklist ([`.github/pull_request_template.md`](../.github/pull_request_template.md)): device report and tx-report.json attached, the profile
  left at `verified = false`, and the tests of section 3.

## 5. Who decides, and what happens then

- The **maintainer** reviews the evidence, may ask for more, and sets the level in the phone's profile and its radio
  notes ("About this radio" in the app, and the device page on the website). Measurements from your phone are listed as
  "measured on a test unit", without your name unless you ask to be credited.
- **Transmit is enabled in the app only from Beta**, after the maintainer is satisfied the radio keys, releases and
  identifies correctly. If a problem appears later (for example a transmission that doesn't end), the level drops and
  transmit is turned off again for everyone until it's fixed.
- New levels reach users with the next SignalDeck release (or, for transmit and safety changes, sooner through the
  signed profile list).

## 6. Make your own phone compatible

If you write code: **fork** SignalDeck on GitHub, add a profile for your phone in your fork (see the existing profiles in
`DeviceProfile.java` and the device report's findings), and then open a pull request **from your fork** with your test
results from section 3. You don't need to be a collaborator for this: a pull request from a fork is GitHub's normal way
for anyone to offer changes, and nothing is merged until the maintainer has reviewed it. You can also just share a link
to your fork in your Discussions thread. To run the transmit tests (3.3) in your fork, set `verified = true` for your
profile in your own build only; every report from that build is marked as a fork build with your commit and repository,
and says the flag came from your build. Keep transmit off (`verified = false`) in the PR; the maintainer turns it on
after the Beta checks. Never add code that loads module firmware for a phone it wasn't made for.

## History

- **v3 (2026-10-30):** timing and calibration measured on every phone before Supported (3.7), after the Atom XL
  showed a module with another signal-reading scale than the Armor's.
- **v2 (2026-10-29):** the transmit test session (3.5) and `tx-report.json` schema 1; build identity in every report
  (3.6); testing transmit in a fork's own build with `verified = true`; the Starting transmit tests and Transmit test
  results issue templates and the pull request checklist (section 4).
- **v1 (2026-10-29):** first version: levels, tests per level, evidence, review.
