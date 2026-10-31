<!-- Generated from app/src/main/java/com/macdmr/radio/ui/HelpView.java by HelpExportTest. Do not edit by hand. -->

# Channels

*Pages · 23 of 34*

Your channel list, favourites and repeaters.

## The list

Each card shows the channel number, name, receive frequency, FM or DMR, and when the channel was last heard (the time if today, yesterday with the time, or the date and time). The colour code, slot, repeater shift and tones are in the channel editor, one tap away. The active channel glows and says ON AIR.

## Find and filter

The search box matches names, frequencies (type 462.6) and channel numbers. The chips show all, favourites, analog or DMR channels. The zone button at the top opens the zone list.

## Several channels at once

Long-press a channel in the list (or tap it and choose Select several) to start selecting: each tap then ticks or unticks a channel, and All ticks every channel shown (search and the filters narrow it first). The bar at the top then stars them (or unstars them when all are already favourites), adds them to a zone you pick or to a new one you name, takes them out of the zone that is open (Out of zone; the channels stay, and a zone is never left empty), or deletes them after a confirmation: they leave every zone and the channels after them move up. The cross ends selecting. SignalDeck Web has the same on its Channels page: Select, tick the rows, then the same buttons. The phone's list and SignalDeck Web follow each other: a channel or zone changed on one shows on the other within a moment, and a selection open there ends (the channel numbers may have moved).

## Zones and standard lists

The zone button lists your zones; tap one to use it. Add a zone starts with Your own: New zone from your channels names a zone and lets you tick its channels. Under it are ready-made lists, each added as its own zone, in your region's order (Device → Region): its own licence-free services first (a list only partly allowed says so), then the lists of use everywhere, then licence-free services your region does not list, marked "not legal here" (they stay because you may need them elsewhere; whether you may transmit is your responsibility); lists that exist only elsewhere (NOAA Weather where there is no weather radio, the US Business and Railroad channels) are under Show all. The lists: FRS (22 channels, no licence), GMRS (the 22 channels plus repeater channels 15R to 22R, US licence needed; pick your repeater's CTCSS tone before adding), NOAA Weather (7, receive-only), MURS (5 licence-free VHF channels), Marine VHF (10, receive-only), Ham simplex (your region's 2 m and 70 cm calling channels, its APRS and FM SSTV frequencies, and in North America the common simplex channels), DMR simplex (6 channels, colour code 1, slot 1, talkgroup 99, with a Simplex contact), Business (12 US itinerant dot and star channels, licence needed), Railroad (the 96 AAR channels, receive-only), ISS and satellites (ISS voice, APRS on 437.825, SSTV on 437.550 and the cross-band repeater, SO-50, with Doppler + and - channels and the 67 Hz access tone), UHF CB (Australia and NZ, with the repeater R channels) and PMR446 (16, Europe and UK). A channel you already have on the same frequency and tones is reused rather than added twice, and adding a list again only adds what is missing. Licence-free lists are set to low power. The ⋮ on a zone: Use, Choose its channels, Rename, Delete the zone (its channels stay), or Delete the zone and its channels (only those in no other zone). A list this radio cannot use at all (Marine VHF on a UHF-only phone) is marked Outside this radio's bands, one it can use in part says how many of its channels it can; adding a list leaves out the channels outside the radio's bands and says how many.

## Favourites

Tap a card's star to add or remove it. Starred channels form the ★ Favourites zone, so the arrows and the scanner can be limited to them.

## Use, edit, delete

Tap a card for Use (switch to it), Edit, Star or Delete. The + adds a channel.

## History

A card says when the channel was last heard. The channel editor ends with a History section, and the card's History button opens the same list: everything on that channel, newest first and grouped by day: voice (with the DMR caller), texts, tones heard, Morse repeater identifications and what they said, packet traffic, recordings, your own transmissions, and the alerts and refusals that happened on it, each with its time and signal. A DMR caller in the list is a tap target for the contact. Clear this channel's history removes the lines; captions have their own page and are kept.

## Receive only

In the channel editor (phone or SignalDeck Web), Transmit → Receive only makes a channel listen-only: Talk, the side key and every automatic transmission (beacons, replies, the voice keyer, the parrot) are refused while you are on it, and its card says RX ONLY. NOAA weather, marine VHF and railroad channels are always receive only. The setting is kept in backups (in SignalDeck's extras file, so other programs reading the backup are not affected).

## Notes

Each channel has a Notes field in the channel editor (and in SignalDeck Web's editor): who uses it, the club or owner, linking, hours, how to check in. Notes are kept in backups and can go with the channel when you share it.

## Channel names

A channel name can be up to 32 characters in the phone's editor and in SignalDeck Web's; the counter under the field shows how many are used and turns amber at the limit. Repeaters added from Nearby repeaters are named within the same limit. A longer name that came in from an import is shortened to the limit when the channel is next saved. Codeplug tools such as OpenGD77 CPS keep shorter names (16 characters), so a long name may be cut short in a backup opened there.

## Sharing channels

The share button (the paper plane at the top) → Share channels: name the set, tick the channels (or All, None, Analog, or the channels of a zone) and tap Share. Android's share sheet opens, so any app works: messages, Facebook, WhatsApp, Discord, email, Drive and the rest. The message lists every channel (frequency, offset, tone or DMR colour code, slot and talkgroup) so anyone can program it by hand, and ends with an import code (SDCP1:…:END) that SignalDeck reads back. Copy text puts the same message on the clipboard, for a post. Optional: net schedules (the nets you added to those channels; on), channel notes (off unless you turn it on: notes can be personal), channel locations (off unless you turn it on: a channel's position could be your home), a SignalDeck file with the same content, and a CHIRP file of the analog channels for other radios (Baofeng, Yaesu, Kenwood and others; DMR channels are left out of it). Encryption keys are never shared.

## Importing channels

Share a message or file to SignalDeck (it is listed as Import channels in the share sheet), open a CHIRP CSV with it, or use the share button → Import from the clipboard or Import a file. SignalDeck finds the import code anywhere in the text, even when an app has broken the long line, and also reads CHIRP CSV files from other radios' software. A preview lists every channel with a tick; channels you already have start unticked, and ones this radio cannot use (outside 136–174 and 400–480 MHz, or not FM) are listed as left out. Import adds the ticked channels after yours, by default in a new zone with the set's name; nothing you have is changed. Notes and net schedules that came with them are kept (a switch in the preview leaves them out); the nets show in Nets near you.

## Editing

Every field in the channel editor has an info button that explains what it does on this radio. Analog channels need a frequency, power, bandwidth and optionally CTCSS or DCS tones; Soft SQ and its level can be set here too. An M17 channel is wide FM with M17 on top of it: a destination (@ALL, or a callsign, including one picked from a contact on the phone) and a channel access number from 0 to 15. SignalDeck Web's channel editor has the same type, destination and access number. On the intercom, Talk sends M17 voice to that destination when the module firmware has the symbol engine, and says receive-only on the firmware the phone shipped with. The module's squelch is held open so weak signals reach the decoder, and the phone plays only the decoded voice: the radio's own FM audio stays off the speaker. The channel list marks it M17, next to FM and DMR. The destination and the access number are kept in a backup. A latitude and longitude gives the intercom the place name, distance and bearing: My position uses the phone's location; Open Maps shows the spot in your maps app, where Share → SignalDeck sends a place back; Paste reads coordinates or a maps link you copied. Sharing a place to SignalDeck while no editor is open asks which channel it belongs to. DMR channels also need a colour code, a time slot, a call target (a DMR ID for a private call or a talkgroup for a group call) and the talkgroups you want to hear on that channel, up to 32 (a group call's own talkgroup is added to that list when you save, because the radio passes only listed talkgroups). Talkgroup lists under the RX group list inserts a saved, named list in one tap or saves the current one; the DMRModHooks module's lists come over from the stock app. VHF channels are always wide band on this radio; narrow band makes its receiver deaf. On DMR channels, Call number from switches between typing the call number and picking it from your contacts: group contacts for a group call, private contacts for a private call; the channel stores the number either way.

## Nearby repeaters

The dish button lists DMR repeaters from BrandMeister and RadioID and FM repeaters from hearham around you, nearest first, within 15, 30, 60 or 120 miles (25, 50, 100 or 200 km where your region uses kilometres). The two rows under the distances show only FM or only DMR repeaters, and only VHF (136 to 174 MHz) or only UHF (400 to 480 MHz); the app remembers your choice. One tap adds a repeater with its channels, talkgroup contacts and a zone, and it can be undone.

[All topics](README.md)
