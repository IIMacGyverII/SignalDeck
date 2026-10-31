<!-- Generated from app/src/main/assets/radios/radios.json by RadioNotesExportTest. Do not edit by hand. -->

# Ulefone Armor 20WT

*Untested · profile ulefone-armor20wt*

The Armor 26 Ultra's radio family on another serial port; the profile is written from a report and not yet tested.

## The radio

| | |
|---|---|
| Module | Armor DMR module family (version not read yet) |
| Modes | FM, DMR Tier II |
| Bands | VHF 136–174 MHz, UHF 400–480 MHz |
| Power | Not measured yet |
| Connection | A serial port inside the phone at 57600 baud (a different port from the Armor 26 Ultra); audio through the phone's radio audio service. |

SignalDeck does not load module firmware on this phone: its stock app has no update path and carries no module image.

## What works

| Feature | State | Note |
|---|---|---|
| FM receive | Not tested |  |
| FM transmit | Not tested | Only in the guided Transmit test session until the profile is tested |
| DMR receive | Not tested |  |
| DMR transmit | Not tested | Only in the guided Transmit test session until the profile is tested |

## Known quirks

### Untested profile

This profile comes from an owner's device report and the phone's stock app. It has not run on the phone yet, so SignalDeck does not take over the radio by itself, and it transmits only in the guided Transmit test session (Device, Device report). Owners can help with Device, Run all checks, then that session.

### Looks like an Armor 26 Ultra

The phone has the same radio control nodes as the Armor 26 Ultra but its module is on another port. SignalDeck recognises it by its model name.

### Driven as its own app does

Its stock walkie-talkie app is an older build than the Armor 26 Ultra's, with the same commands but fewer of them. SignalDeck follows it on this phone: a shorter start-up exchange with the module, the audio opened 0.6 s before the transmitter is keyed, no new key within 2 s of the last one, and its channel files for Import from the stock app. Whether the module answers the extra commands SignalDeck uses (signal strength, caller ID, monitor) is what Run all checks finds out.

### Bands not measured

The bands shown are the Armor family's; this model's limits have not been measured yet.

[All radios](README.md)
