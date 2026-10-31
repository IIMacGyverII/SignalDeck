<!-- Generated from app/src/main/assets/radios/radios.json by RadioNotesExportTest. Do not edit by hand. -->

# ANYSECU W6Plus DMR

*DMR (V69) · In testing · profile anysecu-w6plus-dmr*

The W6+ body with a UHF DMR module; DMR and FM receive and transmit work.

## The radio

| | |
|---|---|
| Module | DMR006.U4A.V140 (the Atom XL's module family, on a plain serial port) |
| Modes | FM, DMR Tier II |
| Bands | UHF 400–480 MHz |
| Power | Not measured yet |
| Connection | A serial port inside the phone at 57600 baud with the Armor's command set; the receive audio reaches the app through the phone's camcorder microphone input. |
| Stock firmware | DMR006.U4A.V140 |

SignalDeck does not load module firmware on this phone.

## What works

| Feature | State | Note |
|---|---|---|
| Signal meter | Works |  |
| FM receive | Works | Tested with another radio (2026-10-29); check the channel's CTCSS/DCS setting if nothing is heard |
| FM transmit | Works | Tested on the bench (2026-10-29) |
| DMR receive | Works | Tested with another DMR radio; voice level on the phone is low (under investigation) |
| DMR transmit | Works | Tested with another DMR radio (2026-10-29) |
| M17 receive | Not tested |  |
| M17 transmit | No | The transmit audio path cuts everything below about 300 Hz, and module firmware is not loaded on this phone |
| APRS | Works | Sending decoded on the bench (HackRF) and by a second phone, through the stock app's gateway relay; receive tested from a second phone on UHF (144.390 is out of band) |
| SSTV | Works | Robot 24 colour sent through the gateway relay decoded clean by a second phone (2026-10-29); UHF only, so not 144.500 |
| FT8 over FM | Works | Sent through the gateway relay and decoded by a second phone (2026-10-29) |
| POCSAG pager | No |  |
| Module firmware loader | No | The vendor's module image stays |
| Android Auto | Not tested |  |
| SignalDeck Web | Not tested |  |

## Known quirks

### UHF only

Despite "dual band" in some listings, the radio answers only 400 to 480 MHz; VHF frequencies are never acknowledged.

### The app's own sounds go through a gateway relay

Your voice goes from the microphone straight to the radio. Sounds SignalDeck makes itself (APRS, Morse ID, tones, SSTV, FT8) go through the relay the stock app uses for its network gateway, which feeds the phone's audio to the radio's microphone input while it transmits. It is a voice path: nothing below about 300 Hz and a cut above 3 kHz, so M17 cannot be sent.

### Transmit frequency runs high

Our test unit transmitted about 1.6 kHz above the set frequency on FM and DMR alike, measured against a test receiver whose own error is part of that figure. It is inside what most DMR radios accept, but a narrow or strict receiver, or a DMR hotspot, may need its receive offset adjusted or may show a high bit error rate for this radio.

### DMR voice audio is quiet

DMR calls are detected with the caller's ID, but the decoded voice reaches the app far below FM audio. This is under investigation.

### Its transmitter desensitises nearby radios

While it keys, a radio close by on another UHF frequency hears less (its signal reading drops), though it does not open.

### Mains chargers put a buzz on the air

With the phone on a mains charger, a 120 Hz buzz got into its transmitted and received audio in a test with two of these phones; data modes that tolerate noise (RTTY, FT8, APRS) still decoded, DTMF and PSK31 did not. On battery, or on a clean charger, it is gone.

## Measured on our test unit

Measured on one test unit; your radio may differ.

| What | Value | Note |
|---|---|---|
| Carrier offset | about +1.6 kHz | \+1598, +1575 and +1604 Hz on three keys (2026-10-29). Includes the test receiver's own error: the same receiver reads an Armor 26 Ultra +0.8 to 1.0 kHz high, so this radio alone is probably 0.6 to 0.8 kHz (about 1.5 ppm) high. Measured on one test unit; your radio may differ. |
| DMR outer deviation | 1815 Hz | Nominal is 1944 Hz (2026-10-29, one test unit). |
| DMR voice audio | about 50 dB below FM | At the camcorder input (2026-10-29, one test unit). |
| FM receive audio | \-32 dBFS | A 1 kHz tone at 2.5 kHz deviation, 12 dB louder than the W6+ (2026-10-29). |
| Heat during the bench test | battery 42.1 to 42.8 °C, processor 51 to 53 °C | Including a 28 s key (2026-10-29). |
| Transmit audio response (gateway route) | 300 Hz -17 dB, 2.5 kHz +7.8 dB against 1 kHz | 500 Hz -9, 3 kHz +3, 3.5 kHz -31 dB; 100 to 250 Hz at the noise floor; the module limits near 4.3 kHz (2026-10-29, one test unit). |

[All radios](README.md)
