<!-- Generated from app/src/main/assets/radios/radios.json by RadioNotesExportTest. Do not edit by hand. -->

# Ulefone Armor 3WT

*Untested · profile ulefone-armor3wt*

The Atom XL's USB radio bridge with an older DMR module, driven through the phone's radio daemon; written from a report, not yet tested.

## The radio

| | |
|---|---|
| Module | Older Agold DMR module (version ver0.5.4\_470M\_TONE in one report; chip not known) |
| Modes | FM, DMR without time slot selection |
| Bands | VHF 100–174 MHz, UHF 400–470 MHz |
| Power | Not measured yet |
| Connection | The phone's radio daemon (extmodule) over two local network sockets, commands on port 9980 and audio on 9985; the daemon drives the USB bridge and the radio's USB sound card. |

SignalDeck does not load module firmware on this phone: no module image exists (two stock ROMs checked) and the phone is not certified for loading.

## What works

| Feature | State | Note |
|---|---|---|
| FM receive | Not tested |  |
| FM transmit | Not tested | Locked until the profile is tested |
| DMR receive | Not tested | No caller ID: the module's call-info answer is not decoded yet |
| DMR transmit | Not tested | Locked until the profile is tested |

## Known quirks

### Untested profile

This profile comes from an owner's device report, the phone's intercom app and its radio daemon. It has not run on the phone yet, so SignalDeck does not take over the radio by itself and never transmits on it. Owners can help with Device, Run all checks.

### Needs the radio daemon

SignalDeck talks to the phone's radio daemon (extmodule), as the intercom app does; on a custom ROM the daemon comes from the owner's own Magisk module and must be running. SignalDeck recognises the phone by its model or its board, and only when the daemon is installed.

### No time slot

The module's digital channel has no time slot setting, so SignalDeck does not offer TS1 or TS2 on this phone: the module chooses. Simplex DMR with other radios is the safe use; repeater channels may not work.

### No signal meter

The module has no known signal-strength command, so the S-meter, band scope and fox hunt readings are off on this phone. Run all checks asks the module once to find out.

### Bands from the intercom app

The bands are what the phone's intercom app accepts, 100 to 470 MHz without the 174 to 400 MHz gap. The module's own range is not measured; Run all checks records its answer at the edges, receive only.

### Side key

The side key reaches only the kernel, not apps, on the custom ROM in the report. SignalDeck reads it with root as its default custom PTT key; it does nothing until transmit is unlocked.

[All radios](README.md)
