<!-- Generated from app/src/main/java/com/macdmr/radio/ui/HelpView.java by HelpExportTest. Do not edit by hand. -->

# Scanning and zones

*On the intercom · 3 of 34*

SCAN searches a zone; ZONE picks one.

## Scan

SCAN steps through the channels of the current zone, about one and a half seconds each (two and a half on phones whose radio reports a reception later, such as the Blackview Xplore 1), and stops on a channel with activity. It moves on about two and a half seconds after the activity ends. Tap SCAN again to stop.

## Skip a channel

Long-press SCAN while it is holding on a channel to leave that channel out for the rest of this scan.

## Priority channel

In Channels, tap a channel and Make scan priority. While scanning, that channel (even one outside the zone) is checked again after every three other channels and the scan stops there when it is busy, so you do not miss it. While the scan is held on another busy channel, it still checks the priority channel every 10 seconds (a short gap in the audio) and moves to it if it is busy. Its card shows PRIORITY; the same button clears it.

## Soft SQ while scanning

While scanning, Soft SQ is used on every analog channel, so weak stations are caught and static is not. Each channel keeps its own Soft SQ level, set with the slider or in Channels → Edit; a channel that was never set uses 5, the middle of 1 (lenient) to 9 (strict). The level is applied on every step of the scan, not fixed when the scan starts. A channel with a receive tone (CTCSS) opens on its tone instead. Moving the slider while the scan is held on a channel changes that channel's level. A channel that only had Soft SQ for the scan returns to its own squelch when the scan stops. Turn this off in Device → Radio settings to scan with the radio's own squelch.

## Zones

ZONE opens the zone list: tap a zone to make the arrows, the scanner and the car's channel list use only its channels, or ALL channels for every one. Each zone remembers the channel you last used in it, and choosing the zone tunes that channel (the first time, the radio stays where it is if the channel is in the zone, else it takes the zone's first channel); while you talk, scan or use the VFO the channel is left alone. Starred channels form the ★ Favourites zone; nearby-repeater imports create a zone of their own. Add a zone adds a standard channel list or a zone of your own; the ⋮ on a zone chooses its channels, renames it or deletes it (see Channels → Zones and standard lists).

[All topics](README.md)
