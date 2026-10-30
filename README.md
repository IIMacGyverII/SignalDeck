# SignalDeck

## Mission Statement

> *This project is a showcase of what an Android ham radio can do. It is built for innovation, and built on the innovation, discovery and hard work of those who came before me. I hope it inspires others to create, and to look for the next step.*
>
> — IIMacGyverII

---

**SignalDeck** is an Android app for phones with a built-in walkie-talkie radio module (VHF/UHF, analog FM, DMR and M17; analog-only radios too). It drives the module directly, without the phone's stock walkie-talkie app, and turns the phone into a full ham radio station: voice, packet, digital modes, satellites, e-mail by radio, and the whole radio in a web browser.

> [!WARNING]
> **Public beta, for testing — bugs are expected.** SignalDeck needs a **rooted phone (Magisk)** and takes direct control of its radio module. It is provided as is, without warranty (GPL v3); the author is not responsible for any damage to your phone, its radio or your data. Transmitting legally (licence, frequencies, power) is your responsibility.

**Download:** the newest build is on the [Releases](https://github.com/IIMacGyverII/SignalDeck/releases) page (`SignalDeck-<version>.apk`). Once installed, **Device → Updates** finds later builds, with a Release path and a Beta path and the changes since your version.

**Help is built in:** every screen and feature is explained in the app (**Device → Help & how-to**, and the ⓘ buttons next to fields and settings).

## Getting started

1. Root the phone with Magisk and install the APK.
2. Open SignalDeck and grant root when Magisk asks. SignalDeck turns the stock walkie-talkie app off while it owns the radio, and imports the stock app's channels on the first start.
3. **Device → Keep access after reboot** installs a small Magisk module that makes the radio access permanent, so SignalDeck takes the radio back after every reboot.
4. More channels: open a CHIRP CSV with SignalDeck or use the share button on the Channels page, or restore a backup (the five-CSV layout of the DMRModHooks module and the OpenGD77 CPS fork, in `Download/DMR/DMR_Backups`).
5. To hand the radio back, use **Device → Exit SignalDeck**: it switches the module off, or gives the radio back to the stock app.

## Screenshots

The screenshots use demo data: made-up callsigns (unallocated `Q` prefixes, `DEMO-n`), a test location in Denver, demo channels and signals from a bench transmitter. The SSTV picture of the ISS is a real reception.

<table>
<tr><td align="center" width="25%"><img src="docs/screenshots/intercom-noaa.png" width="200" alt="Intercom: NOAA weather, S-meter"><br><sub>Intercom: NOAA weather, S-meter</sub></td><td align="center" width="25%"><img src="docs/screenshots/intercom-captions.png" width="200" alt="Live captions of a reception"><br><sub>Live captions of a reception</sub></td><td align="center" width="25%"><img src="docs/screenshots/intercom-dmr.png" width="200" alt="A DMR repeater channel"><br><sub>A DMR repeater channel</sub></td><td align="center" width="25%"><img src="docs/screenshots/intercom-classic.png" width="200" alt="The classic intercom look"><br><sub>The classic intercom look</sub></td></tr>
<tr><td align="center" width="25%"><img src="docs/screenshots/channels.png" width="200" alt="Channels and zones"><br><sub>Channels and zones</sub></td><td align="center" width="25%"><img src="docs/screenshots/channel-editor.png" width="200" alt="Channel editor"><br><sub>Channel editor</sub></td><td align="center" width="25%"><img src="docs/screenshots/pkt-sheet.png" width="200" alt="The PKT key: every mode"><br><sub>The PKT key: every mode</sub></td><td align="center" width="25%"><img src="docs/screenshots/launch.png" width="200" alt="Launch screen"><br><sub>Launch screen</sub></td></tr>
<tr><td align="center" width="25%"><img src="docs/screenshots/aprs.png" width="200" alt="APRS: packets and messages"><br><sub>APRS: packets and messages</sub></td><td align="center" width="25%"><img src="docs/screenshots/aprs-stations.png" width="200" alt="APRS stations"><br><sub>APRS stations</sub></td><td align="center" width="25%"><img src="docs/screenshots/map.png" width="200" alt="Map with APRS stations"><br><sub>Map with APRS stations</sub></td><td align="center" width="25%"><img src="docs/screenshots/ft8.png" width="200" alt="FT8 over FM"><br><sub>FT8 over FM</sub></td></tr>
<tr><td align="center" width="25%"><img src="docs/screenshots/sstv-receive.png" width="200" alt="SSTV receive"><br><sub>SSTV receive</sub></td><td align="center" width="25%"><img src="docs/screenshots/sstv-picture.png" width="200" alt="ISS SSTV picture (real reception)"><br><sub>ISS SSTV picture (real reception)</sub></td><td align="center" width="25%"><img src="docs/screenshots/band-scope.png" width="200" alt="Band scope"><br><sub>Band scope</sub></td><td align="center" width="25%"><img src="docs/screenshots/satellite-passes.png" width="200" alt="Satellite passes"><br><sub>Satellite passes</sub></td></tr>
<tr><td align="center" width="25%"><img src="docs/screenshots/conditions.png" width="200" alt="VHF/UHF conditions"><br><sub>VHF/UHF conditions</sub></td><td align="center" width="25%"><img src="docs/screenshots/help.png" width="200" alt="In-app help"><br><sub>In-app help</sub></td></tr>
</table>

**SignalDeck Web**, the radio in a browser on your Wi-Fi (desktop and phone):

<table>
<tr><td align="center" width="33%"><img src="docs/screenshots/web-intercom.png" width="300" alt="SignalDeck Web: Intercom"><br><sub>Intercom</sub></td><td align="center" width="33%"><img src="docs/screenshots/web-aprs.png" width="300" alt="SignalDeck Web: APRS"><br><sub>APRS</sub></td><td align="center" width="33%"><img src="docs/screenshots/web-map.png" width="300" alt="SignalDeck Web: Map"><br><sub>Map</sub></td></tr>
<tr><td align="center" width="33%"><img src="docs/screenshots/web-ft8.png" width="300" alt="SignalDeck Web: FT8"><br><sub>FT8</sub></td><td align="center" width="33%"><img src="docs/screenshots/web-sstv.png" width="300" alt="SignalDeck Web: SSTV gallery"><br><sub>SSTV gallery</sub></td><td align="center" width="33%"><img src="docs/screenshots/web-band-scope.png" width="300" alt="SignalDeck Web: Band scope"><br><sub>Band scope</sub></td></tr>
<tr><td align="center"><img src="docs/screenshots/web-intercom-phone.png" width="200" alt="SignalDeck Web on a phone: intercom"><br><sub>On a phone: intercom</sub></td><td align="center"><img src="docs/screenshots/web-aprs-phone.png" width="200" alt="SignalDeck Web on a phone: APRS"><br><sub>On a phone: APRS</sub></td><td></td></tr>
</table>

## What it does

### Talk and listen
- **Analog FM, DMR and M17 channels:** group and private calls, RX group lists, CTCSS and DCS, receive-only channels, high and low power, color codes and timeslots; an M17 channel has a destination and a channel access number.
- **The intercom page:** channel and frequency display, live S-meter, a real audio spectrum, the history of receptions and captions, and the talk key with the SOFT SQ / TXT / VFO / REC / MON / PKT keys around it. Two looks (new and classic). The side PTT key works, or any key you teach it.
- **Channels:** full editor, select several channels at once (delete, star, add to or take out of a zone), favourites, notes, zones, a scanner with a scan list, VFO for any frequency, Nearby Repeaters and repeaters on the map.
- **Soft SQ** (a software squelch for weak signals), **REC** (recordings per channel), **MON** (squelch held open).
- **Live captions** of received speech (Vosk on the phone, or Google Cloud), with keyword alerts; **voice announcements** of channel and caller.
- **DMR extras:** text messages with quick replies and talkgroup threads, Last heard with RadioID names and callsigns, talkgroups picked from your contacts, and **DMR roaming** (switches to the nearer repeater of your network as you travel). With SignalDeck's module firmware, MON on a DMR channel hears every call on the frequency, whatever its talkgroup or colour code, and shows the colour code and slot.
- **Contacts:** DMR IDs and talkgroups, callsigns, pager RICs, notes, a home location and the last position heard over APRS.
- **Emergency** (sends your position) and an optional roger beep.

### Digital modes and packet (the PKT key)
- **APRS:** passive decode on every analog channel, monitoring with auto-ack, messages and message groups, beacons, objects and bulletins, telemetry and weather, a fill-in digipeater, text and e-mail gateways, and APRS stations on the map.
- **APRS-IS:** receive-only or two-way iGate, internet beacon and messages.
- **KISS TNC** over TCP for APRSdroid, YAAC and other packet apps.
- **Winlink e-mail** through a radio gateway or over the internet, with the gateway list from winlink.org (nearest first), scheduled mail checks and forms by Winlink.
- **Packet terminal** (connected mode) and the **Packet finder**, which listens across packet frequencies and lists the BBSs, nodes and Winlink gateways it hears.
- **FT8 over FM:** the WSJT-X mode on an FM channel, with a waterfall and automatic contacts logged in the Logbook. It talks to other FM FT8 stations; normal FT8 on SSB cannot be received with this radio.
- **M17 digital voice:** receive on any firmware (an equaliser calibrated per phone model, which then refines itself from clean calls), with the caller and text shown; long calls stay one listing. **Transmit** (voice from the microphone) needs SignalDeck's module firmware, which sends M17 through the radio's own modulator. Checked against an MMDVM hotspot.
- **POCSAG pages:** compose and send to one or more pagers (512, 1200 or 2400 baud) with SignalDeck's module firmware.
- **SSTV:** receive (Robot, Martin, Scottie and PD, plus a continuous Robot 36 decoder that picks up pictures halfway) and send, including the long modes (Robot 36, Martin, Scottie, PD 90) with a cool-down and heat guard; a gallery with share, delete and **Combine** (several receptions of the same picture into a cleaner one).
- **Decode a recording:** finds SSTV pictures and APRS packets in any audio file.
- **RTTY and PSK31** (with SignalDeck's module firmware, RTTY, FT8 and the Morse ID go out through the module's own tone generator, without the audio path's filters), **DTMF** decode and send (with remote control by DTMF), **Morse** decode and send with a trainer, a **voice keyer**, a **simplex repeater (parrot)** and an **ID timer**.
- **Weather:** NOAA weather radio with SAME alert decoding.

### Satellites and propagation
- **Satellite passes** with Doppler tracking for the ISS (APRS and SSTV) and the FM satellites of the AMSAT list, with live AMSAT status. During a pass SignalDeck tunes and decodes by itself, pass after pass if you like.
- **VHF/UHF conditions:** tropo ducting forecast, aurora, meteor showers and the next passes on one page.
- **Band scope:** sweeps a band, or your own range, and shows which frequencies are busy.

### Operating
- **Logbook** with ADIF export, **POTA and SOTA** (spots, your activation, duplicate check), **nets near you** and **net control** (check-ins, traffic, a report).
- **Message forms:** ICS-213 and ARRL radiograms over APRS, DMR text or Winlink.
- **Scheduled operations:** at a set time, once or every week, listen to (and record) a channel, scan a zone, monitor APRS, receive SSTV, send a beacon, run the packet finder or check your Winlink mail.
- **Fox hunt** with a prediction map: compass bearings, a signal heat map and terrain (ridges the signal could not cross), each a switch, combined into one estimate. It can count only the fox's own transmissions (its CTCSS tone, or its DMR caller and colour code), take marks as you walk, and take a bearing from a spin with the phone against your chest.
- **Exam practice**, and the **map** with **coverage maps** (where your repeaters really work, measured as you move).

### Everywhere
- **SignalDeck Web:** the whole radio in a browser on your Wi-Fi, protected by a PIN (https on port 8443 for sharing and the microphone): intercom, channels, messages, APRS, SSTV, packet and Winlink, FT8, M17, satellites, band scope, map, logbook, recordings, and the phone's battery and signal. Transmitting from the web stays off until you allow it.
- **Android Auto** (the radio on the car's screen, including talk), a home-screen **widget**, and a notification with controls.
- **Region:** your country sets the APRS frequency, the channel lists offered (others are marked "not legal here"), weather radio, band edges, the station-ID interval and km or miles. The country data may be inaccurate: please report corrections with a source.
- **Backups**, channel sharing (Android share sheet, CHIRP CSV) and boot persistence.
- **Support tools:** Radio audio check, second radio test, device reports (**Run all checks**, also driven from adb; every report names the build that made it, official or a fork), a guided **transmit test session** for certifying a phone (`tx-report.json`), and a watchdog that restarts a radio module that stops taking commands.
- **Module firmware page** (Device): shows the radio module's firmware, keeps a verified backup of the factory image taken from the stock app, and loads images from the project's signed catalogue, with every safety check shown before a load and the factory image restored when anything does not match. Experimental, and only for the listed phones.

## What the hardware cannot do

These are limits of the radio module, measured on the reference phone. SignalDeck works within them:

- **Frequencies:** the module only tunes 136–174 MHz and 400–480 MHz (no 220 MHz, no out-of-band receive).
- **FM only on analog:** no SSB or CW receive, so no normal FT8 or weak-signal satellite work.
- **Transmission length:** SignalDeck ends voice transmissions at about 28 s and allows up to 118 s only for long SSTV pictures, with a cool-down between long ones (a 2-minute key warms the phone by about 11 °C).
- **M17 and POCSAG transmit need SignalDeck's module firmware:** the stock transmit audio path filters and limits the signal too much for other radios to decode. With the module firmware these modes go through the radio's own modulator instead. M17 receive works on stock firmware with the per-phone equaliser.
- **DCS codes cannot be read from the audio** (the module handles DCS squelch itself); CTCSS tones can be identified.
- **DMR:** the module admits group calls only for the 32 talkgroups in a channel's RX list.

## Phones

Specs, what works, known quirks and test-unit measurements for each phone: [docs/radios](docs/radios/README.md) (in the app: **Device → About this radio**).

| Phone | Status |
|---|---|
| **Ulefone Armor 26 Ultra** (walkie-talkie model) | Tested — the reference phone. Custom ROMs and GSIs work too: SignalDeck takes the radio audio straight from the sound card when the vendor audio service is missing. |
| **Blackview Xplore 1** | **Profile in testing** ([analysis](docs/multi-device-xplore1-findings.md)): same radio-module protocol, audio over a second serial port. A tester has receive, channel import and CTCSS working; transmit stays blocked until the profile is verified. Owners: please run **Device → Run all checks** and send the zip. |
| **Ulefone Armor 20WT** | **Profile written, untested** (from an owner's device report and its stock app): the Armor 26 Ultra's radio family on another serial port. SignalDeck does not take over or transmit by itself until it is tested. Owners: please run **Device → Run all checks** and send the zip. |
| **Unihertz Atom XL** (UHF, DMR) | **Tested** (receive and transmit): its radio sits behind a USB bridge, and SignalDeck drives it through that. FM and DMR voice, DMR texts, APRS, DTMF, SSTV, FT8 and RTTY receive, and M17 receive with its own calibration. UHF only; no computer data over the USB cable while SignalDeck has the radio; the S-meter holds still during a reception. |
| **ANYSECU W6+** (UHF analog) | **Tested** (receive, and transmit on the air): an analog-only UHF radio (400–470 MHz, no DMR) with its own module, driven by SignalDeck's second radio engine. Takes over, imports the stock app's channels, tones and squelch; receive, recording, captions, APRS, SSTV, FT8 and M17 work. No S-meter (the module gives no signal readings). DMR screens are hidden. SignalDeck's own sounds (APRS, SSTV, FT8, RTTY, DTMF, PSK31) go on the air through the stock app's relay and were decoded on a second phone. |
| **ANYSECU W6Plus DMR** (V69, UHF, DMR) | **Beta** (receive and transmit verified, 2026-10-29): DMR and analog on 400–480 MHz with the Armor's command set. DMR voice works both ways with another DMR radio; data modes decoded between two phones. |
| Other DMR phones (Oukitel WP61, UNIWA P2 Plus…) | No profile yet — the app starts in discovery mode and offers **Run all checks**. See [docs/DEVICE_SUPPORT_REQUEST.md](docs/DEVICE_SUPPORT_REQUEST.md), then [docs/DEVICE_CERTIFICATION.md](docs/DEVICE_CERTIFICATION.md) for the levels and tests. |

**Root (Magisk) is required:** Android's SELinux policy only lets the stock app open the radio's serial port. SignalDeck applies the rules it needs with Magisk (Device → *Keep access after reboot* makes them permanent as a small Magisk module). The stock walkie-talkie app is turned off while SignalDeck owns the radio, and turned back on when you hand the radio back.

## Contribute

SignalDeck is a free and open-source project. If you like it, please consider helping it grow:

- **Donate:** [Ko-fi](https://ko-fi.com/iimacgyverii) or [GitHub Sponsors](https://github.com/sponsors/IIMacGyverII). Donations buy more phones to develop for and make compatible, and keep new features coming.
- **Take part:** test for bugs, suggest features, and join the community at [signaldeckradio.com](https://signaldeckradio.com).
- **Make your own phone compatible:** fork the project and follow the [certification guide](docs/DEVICE_CERTIFICATION.md); pull requests from forks are welcome and are reviewed before merging.

**Bugs and new phones:** open an [issue](https://github.com/IIMacGyverII/SignalDeck/issues) with your phone model, the SignalDeck version, what happened, and the zip from *Run all checks* (or **Device → Device report**).

## Building

```
./gradlew assembleRelease          # needs JDK 17, the Android SDK and the NDK (Codec 2 is built from source)
./gradlew testReleaseUnitTest      # host tests (DSP, decoders, parsers)
```

Release signing is read from `local.properties` (never committed) or the environment:
`signaldeck.storeFile`, `signaldeck.storePassword`, `signaldeck.keyAlias`, `signaldeck.keyPassword`
(or `SIGNALDECK_STORE_FILE` …). Without them the build is signed with the Android debug key, which installs fine but cannot update an official build.

The device profile list in [`profiles/`](profiles/README.md) is signed separately; the app only accepts a list signed with the project's key.

## History

SignalDeck began on 2026-10-17 inside [phonedmrapp](https://github.com/IIMacGyverII/phonedmrapp), the research project and **DMRModHooks** LSPosed module for the Ulefone Armor 26 Ultra, and moved here with its full history. Beta builds 1.0.0-beta.1 to beta.11 were published there, and later betas here (beta.21 on 2026-10-30); the changes of every build are in [CHANGELOG.md](CHANGELOG.md). The research behind the radio work (the module firmware, M17, POCSAG and the bench methods) is documented in phonedmrapp.

## License

Copyright (C) 2026 IIMacGyverII. SignalDeck is free software under the **GNU General Public License v3.0 or later** ([LICENSE](LICENSE)), with **additional terms under section 7** set out in [COPYRIGHT](COPYRIGHT): keep the copyright notice and the author credit, mark modified versions as modified and don't present them as the original or as the author's work, and no rights are granted to the names "SignalDeck" / "IIMacGyverII" or the SignalDeck icon (a modified version needs its own name and icon).

[COPYRIGHT](COPYRIGHT) also lists the third-party components and data sources SignalDeck uses. Official builds are signed with the project's release key and published only on this repository's Releases page.
