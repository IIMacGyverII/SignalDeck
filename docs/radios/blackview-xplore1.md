<!-- Generated from app/src/main/assets/radios/radios.json by RadioNotesExportTest. Do not edit by hand. -->

# Blackview Xplore 1

*Walkie-talkie model · In testing · profile blackview-xplore1*

A DMR phone with the Armor's module protocol and audio over a second serial port; receive works for testers.

## The radio

| | |
|---|---|
| Module | DMR003BN.UV4P.V016 |
| Modes | FM, DMR Tier II |
| Bands | VHF 136–174 MHz, UHF 400–480 MHz |
| Power | Not measured yet |
| Connection | One serial port at 57600 baud for commands and a second one at 230400 baud carrying the audio as 8 kHz mono. |
| Stock firmware | DMR003BN.UV4P.V016 |

## What works

| Feature | State | Note |
|---|---|---|
| Signal meter | Works | Signal readings answer |
| FM receive | Works | Voice clear in testers' reports |
| FM transmit | Not tested | Only in the guided Transmit test session until the profile is tested |
| DMR receive | Not tested |  |
| DMR transmit | Not tested | Only in the guided Transmit test session until the profile is tested |
| Module firmware loader | No | Off: the stock app's updater carries no image, so this module's firmware has not been compared |

## Known quirks

### Profile in testing

Testers have receive, MON, CTCSS and channel import working. SignalDeck does not take over the radio by itself, and it transmits only in the guided Transmit test session (Device, Device report). Owners can help with Device, Run all checks, then that session.

### Keyed by its PTT line

The stock app never sends the transmit command: it keys the radio by switching its PTT line, starts the microphone when the radio reports that it is transmitting, keeps it on a fifth of a second after the release, and takes no new key within 1.5 seconds of the last release. SignalDeck does the same; if the radio does not report transmitting within 2 seconds, no audio is sent.

### DMR texts in the stock app's format

The stock app sends texts with a shorter header than the Armor and in UTF-8 rather than UTF-16. SignalDeck sends them the same way on this phone and reads received texts in either encoding. Not checked on the air yet.

### Channel changes need a pause

The stock app never writes channels less than 1 second apart; a burst of quick changes made the module refuse MON. SignalDeck keeps the same gap and only sends the newest channel.

### The module can stop taking commands

After an update the module was sometimes left on but not answering. SignalDeck checks at start-up and restarts the module when it does not answer.

### Slower reception reports

The radio reports a reception later than the Armor, so scanning waits 2.5 seconds per channel.

### Squelch after MON

On firmware V016 the squelch was seen to stay open after MON was switched off. SignalDeck now sends the channel, monitor and squelch in the stock app's order; tell us if it still happens.

## Measured on our test unit

Measured on one test unit; your radio may differ.

| What | Value | Note |
|---|---|---|
| Receive audio | 100 packets per second, 8 kHz mono | Tester's unit, 2026-10-21. |
| Signal with a nearby transmitter | \-48 to -53 dBm | A handheld keyed next to the phone in a shielded box (2026-10-22). |

[All radios](README.md)
