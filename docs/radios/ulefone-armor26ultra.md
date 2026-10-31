<!-- Generated from app/src/main/assets/radios/radios.json by RadioNotesExportTest. Do not edit by hand. -->

# Ulefone Armor 26 Ultra

*Walkie-talkie model · Tested · profile ulefone-armor26ultra*

The reference phone: a dual-band DMR and FM radio that every SignalDeck feature was built and measured on.

## The radio

| | |
|---|---|
| Module | DMR003.UV4T.V022 (HR-C7000 DMR chip with an AT1846S RF chip) |
| Modes | FM, DMR Tier II, M17 receive, M17 transmit with SignalDeck module firmware |
| Bands | VHF 136–174 MHz, UHF 400–480 MHz |
| Power | Not measured yet |
| Connection | A serial port inside the phone at 57600 baud for commands; the receive and transmit audio go through the phone's own radio audio service at 8 kHz. |
| Stock firmware | DMR003.UV4T.V022 |
| Modified firmware | SignalDeck module images from the signed firmware catalogue (Device, Radio firmware) |

A loaded image stays in the module's flash across reboots. Loading the factory image again restores the stock firmware.

## What works

| Feature | State | Note |
|---|---|---|
| Signal meter | Works | Reads in dBm, also with the squelch closed |
| FM receive | Works |  |
| FM transmit | Works |  |
| DMR receive | Works | Caller ID and talkgroup shown |
| DMR transmit | Works |  |
| DMR texts | Works | Both ways |
| M17 receive | Works | Through a calibrated equalizer |
| M17 transmit | Partly | Only with SignalDeck module firmware loaded |
| APRS | Works | Receive, send, beacon, digipeater, KISS TNC, APRS-IS |
| SSTV | Works | Receive and send |
| FT8 over FM | Works | Receive and send |
| POCSAG pager | Partly | Sending pages needs SignalDeck module firmware |
| Band scope | Works | About 0.7 s per step |
| Module firmware loader | Works | With a verified factory backup |
| Android Auto | Works |  |
| SignalDeck Web | Works |  |

## Known quirks

### The radio enforces its bands

The radio module itself only tunes 136.000 to 174.000 and 400.000 to 480.000 MHz. A frequency outside those is refused and the radio stays on the frequency it was on, so there is no 220 MHz or other out-of-band receive.

### Packet data needs a long start

The transmitter needs about 1.5 seconds after keying before data comes through cleanly. A packet sent right at key-up is lost. SignalDeck therefore leads every APRS or packet transmission with 1.6 seconds of standard AX.25 flags and sends the frame twice.

### Wait after changing channel before you talk

A channel change finishes inside the radio about 3 seconds after it is sent, and it stops a transmission that is already running. SignalDeck waits about 4 seconds after a retune before its own automatic transmissions; when you change channel by hand, wait a moment before you press Talk.

### Signal readings lag a retune

Right after a frequency change the signal reading is 15 to 20 dB too low for about 0.7 seconds. That is why the band scope takes about 0.7 seconds per step (2 m in 25 kHz steps is about 2 minutes).

### Squelch levels act as one

The radio's squelch levels 1 to 9 all behave like one threshold near -90 dBm. For anything finer, use SignalDeck's Soft SQ, which listens to the audio itself.

### Transmitting can reset the USB cable

On some 2 m frequencies at high power, the radio's own signal gets into the phone's USB cable and resets the connection (wired Android Auto drops and reconnects after you let go). Other frequencies on the same band and UHF were fine. It is RF pickup, not an app fault: a ferrite choke, a shorter shielded cable, low power on that channel or wireless Android Auto help. SignalDeck notices when it happens and suggests these.

### Use wide on VHF

Setting a VHF channel to narrow made the receiver deaf on our test unit, and only a phone reboot brought it back. SignalDeck's built-in VHF channels (APRS, SSTV, weather) use wide. On UHF both settings receive.

### Talkgroups you hear

On a DMR channel the radio only passes group calls for the talkgroups in that channel's receive list, which holds up to 32. Private calls to your own DMR ID always come through.

### Receive audio is telephone-quality

The radio delivers its audio at 8 kHz and removes almost everything below about 250 Hz. Voice is fine; digital modes that need the low tones, such as M17, are decoded through a calibrated equalizer.

### Long transmissions

SignalDeck releases the transmitter at its transmit limit (2 minutes unless changed on the Device page). A long high-power transmission warms the phone noticeably, and SignalDeck's heat guard stops long transmissions when the phone gets too hot.

## Measured on our test unit

Measured on one test unit; your radio may differ.

| What | Value | Note |
|---|---|---|
| Tuning step | 500 Hz | Requested offsets of 500 Hz to 5 kHz landed within 2 Hz of the target (2026-10-22), so Doppler correction in 500 Hz steps is real. |
| Signal reading settle time after a retune | about 0.7 s | Readings taken earlier were 15 to 20 dB low (2026-10-22). |
| Packet lead-in needed | about 1.5 s | Tone, flags and silence decoded alike at 1.6 s; shorter lost packets (2026-10-20). |
| Phone heat from a 120 s high-power key | \+11 °C processor, +1.7 °C battery | Back to normal in about 17 minutes (2026-10-25). |
| M17 receive | 300 of 300 voice frames exact | Test transmissions at the standard deviation, through the calibrated equalizer. |

## Custom ROMs

On a custom ROM or GSI the vendor radio audio service is usually missing. SignalDeck notices and takes the radio audio straight from the phone's sound card instead.

[All radios](README.md)
