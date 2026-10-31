<!-- Generated from app/src/main/assets/radios/radios.json by RadioNotesExportTest. Do not edit by hand. -->

# Unihertz Atom XL

*Tested · profile unihertz-atomxl*

A compact UHF DMR and FM phone whose radio sits behind a USB bridge; tested for receive and transmit.

## The radio

| | |
|---|---|
| Module | DMR006.U4A.V067 behind an STM32 USB bridge |
| Modes | FM, DMR Tier II, M17 receive |
| Bands | UHF 400–480 MHz |
| Power | Not measured yet |
| Connection | A USB bridge chip inside the phone: commands over its serial port, audio over its own USB sound card at 32 kHz. |
| Stock firmware | DMR006.U4A.V067 |

SignalDeck cannot load module firmware on this phone; the module is behind the USB bridge.

## What works

| Feature | State | Note |
|---|---|---|
| Signal meter | Partly | Holds its last reading during a reception, by design |
| FM receive | Works |  |
| FM transmit | Works |  |
| DMR receive | Works | Group and private calls with caller ID |
| DMR transmit | Works |  |
| DMR texts | Works | Both ways |
| M17 receive | Partly | 71 to 89 of 100 frames exact on test recordings |
| M17 transmit | No | Needs module firmware this radio cannot load |
| APRS | Works | Receive and send |
| SSTV | Works | Receive tested |
| FT8 over FM | Works | Receive tested |
| POCSAG pager | No | Needs module firmware this radio cannot load |
| Band scope | Works | Inside 400 to 480 MHz |
| Module firmware loader | No | The module sits behind the USB bridge |
| Android Auto | Works | Tested on the desktop head unit |
| SignalDeck Web | Works |  |

## Known quirks

### UHF only

The radio covers 400 to 480 MHz. There is no 2 m, no NOAA weather radio and no 2 m satellites.

### No USB data while the radio is on

The radio is a USB device on the phone's only USB controller. While SignalDeck has the radio on, a computer cannot use the USB cable for data (adb, file transfer). Exit and Hand back switch the radio off and give the USB port back. Developers work over Wi-Fi ADB.

### The signal meter pauses during a reception

Any command sent across the USB bridge while audio streams can break the audio. So during a reception the S-meter shows the reading from just before it and moves again when the reception ends. Fox hunt and coverage logging still take readings, which may cost a short drop-out.

### A nearby transmitter deafens it

Another radio keying close by, even on a different UHF frequency, pushes this receiver down to the bottom of its range for the length of that key. It recovers at once when the other radio stops, and it does not fake a reception.

### The reception signal can stick

Rarely, the bridge's reception start and end signal stops for a whole session. SignalDeck notices, finds receptions from the signal reading instead and restarts the bridge once; a phone reboot always clears it.

### Short audio drop-outs

The bridge's USB audio can stop for about 2 seconds. SignalDeck restarts the bridge, which costs a few seconds without sound.

### Switch off App block

The phone's App block setting force-stops SignalDeck 5 minutes after the screen goes off. SignalDeck opens that settings page once so you can switch it off for SignalDeck.

### Charger noise

With a charger on the USB socket the idle signal reading rises by a few dB. Reception detection copes with it.

## Measured on our test unit

Measured on one test unit; your radio may differ.

| What | Value | Note |
|---|---|---|
| Idle signal reading | \-101 to -103 dBm, about -98 dBm with a charger | Test unit, 2026-10-29. |
| Audio clock | about 125 ppm fast | The bridge's sound card; the M17 calibration accounts for it. |
| Hand back | about 20 s | SignalDeck checks the bridge answers before handing the radio back. |
| Radio owned after a reboot | about 67 s | With Own the radio at boot switched on. |
| Receptions without drop-outs | 17 of 17 | RTTY and speech test transmissions with the signal meter held during receptions (2026-10-29). |

[All radios](README.md)
