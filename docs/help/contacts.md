<!-- Generated from app/src/main/java/com/macdmr/radio/ui/HelpView.java by HelpExportTest. Do not edit by hand. -->

# Contacts

*Pages · 24 of 34*

People and talkgroups: DMR ID, callsign, pager RICs, notes, locations and history.

## What a contact is

A person or a talkgroup. A contact can hold a DMR ID (a person's radio) or a talkgroup number, a callsign (with an optional -SSID), one or more pager RICs, notes, a home location and a label for it. It needs at least one of a DMR ID, a callsign or a RIC, so an APRS or M17 station with no DMR radio is a contact too. These fields are SignalDeck's own: the radio module's contact table only ever gets the DMR numbers.

## The list

Avatars are colour-coded: cyan for a private ID, violet for a talkgroup, amber for all-call. Under the name: the DMR ID, the callsign in green, PAGER when the contact has RICs, and when it was last heard (the time if today, yesterday with the time, or the date and time, as on the channel cards). The chips show all, private or talkgroups; the search box matches names, callsigns, DMR IDs and RICs.

## Callsigns from RadioID

A private contact with a DMR ID and no callsign gets the callsign the RadioID list (Device) knows for that ID, once. The editor says when a callsign came from there; change or clear it and it stays as you left it.

## One tap from anything heard

Every callsign, DMR ID or pager RIC the app shows is a tap target: the caller in Last heard and in a channel's history, a station or a message on the APRS page, a callsign on the M17 page, and the pager page. A known one opens the contact; an unknown one offers Add to contacts (the editor opens with the identity, and the position and time it was heard with, already filled in) or Add to an existing contact (the callsign or RIC is added to a contact you pick). SignalDeck Web does the same from its APRS map cards.

## Two locations, kept apart

Home is the location you enter (a grid square such as EM12ab, or latitude, longitude) with a label, and it never leaves the phone unless you send it yourself. Last heard is filled by the app from APRS positions and is shown separately with its time and source; an automatic fix never overwrites what you typed. Both show their distance and direction from you when the phone has a location.

## History

Each contact keeps what was heard from them (an APRS packet with its position when it carried one, a DMR voice call, a DMR text, an M17 transmission, a page) and what you sent to them (a text, a voice call keyed to their ID or talkgroup, an APRS message, a page), newest first, up to 300 entries. Tap a contact to see it; an entry with a position opens in your maps app. Repeats from the same source within 20 seconds are folded into one entry.

## Actions

The + adds a contact. The green bubble on a card opens its messages (contacts with a DMR ID). Tap a card for the contact's sheet: identity, locations, notes, history, and Message, Edit, Delete.

## Your own identity

Your callsign, SSID and pager RICs are set once, in PKT → APRS settings: APRS and M17 send under the callsign, pages you send carry it as the sender tag, and the pager page listens for your RICs. Your DMR ID is in Device → Radio settings.

## Backups

Callsigns, RICs, notes and home locations travel in SignalDeck's extras file next to the five CSVs, matched to the contact by number, type and name on restore; contacts without a DMR ID are restored from there too. Contacts.csv keeps the plain layout other programs expect. The heard history is not part of a backup.

[All topics](README.md)
