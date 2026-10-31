<!--
Thanks for the pull request. For a new phone or a profile change, fill in the checklist below; for anything else,
delete it and describe the change. Never put your callsign, DMR ID, phone number or location in a pull request.
Guide: docs/DEVICE_CERTIFICATION.md
-->

## What this changes

<!-- One or two sentences. -->

## Device or profile pull requests

Phone model:
Level asked for (Experimental / In progress / Beta / Supported):

**Evidence (required for a device or profile change)**

- [ ] Device report zip attached or linked (Device → Device report → Share last report); its "SignalDeck build" section shows the commit of this pull request's build
- [ ] For transmit: `tx-report.json` from Device → Device report → Transmit test session (inside the zip, or attached), and the results issue linked: #
- [ ] Measurements name the equipment and method (for example "RTL-SDR v4, carrier +0.6 kHz")
- [ ] Recordings, if any, linked with their SHA-256

**The profile**

- [ ] `verified = false` in this pull request (the maintainer turns transmit on after the Beta checks; it is fine that your own test build had it true, the report says so)
- [ ] `id` is new and stable, `revision` starts at 1 (or is raised for a change to an existing profile)
- [ ] No code that loads module firmware on a phone it was not made for
- [ ] No reads of system files the device report avoids, no new root commands without a reason in the description

**Tests from the certification guide (section 3)**

- [ ] 3.1 Identify the phone: Run all checks (receive only)
- [ ] 3.2 Receive: FM (with and without CTCSS), signal reading, squelch, channel changes and scanning, DMR receive (DMR phones), band limits, 30 minutes running
- [ ] 3.3 Transmit (TX): FM key, release, 28 s limit, frequency offset, DMR group call (DMR phones), heat; licence, dummy load or low power, short keys, attended
- [ ] 3.4 Data and extras, or "not supported" with the reason

**Housekeeping**

- [ ] In-app help (`app/src/main/assets/guide/en.json`) describes anything that behaves differently on this phone; `docs/help` regenerated
- [ ] `CHANGELOG.md` row added
- [ ] `./gradlew testReleaseUnitTest assembleRelease` passes
