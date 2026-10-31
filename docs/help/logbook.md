<!-- Generated from app/src/main/java/com/macdmr/radio/ui/HelpView.java by HelpExportTest. Do not edit by hand. -->

# Logbook

*Pages · 9 of 34*

Your contacts, and the stations the radio heard.

## Opening it

PKT → Logbook. Entries are newest first; contacts are green, stations only heard are grey.

## Stations heard

With Log heard on (the default), the radio logs stations by itself: DMR callers whose ID has a callsign in the RadioID list, every APRS station decoded, and callsigns in Morse identifications. One entry per station and mode per hour; the frequency is the channel it was heard on.

## Contacts

Add contact starts an entry for now on the current channel, with 59 as the reports. Tap any entry to edit the callsign, frequency, mode, reports, name and notes, to mark it a contact (QSO) or only heard, or to delete it.

## Callsign lookup and grid squares

With Look up on, callsigns heard or logged are looked up in the free HamDB directory (US and Canadian licences and some others; only the callsign is sent, and answers are kept for 30 days): the name, location and grid square fill in by themselves, and each entry shows the distance and direction from you. Look up the callsign in an entry does it on demand. The top line shows your own grid square and how many grid squares you have worked (contacts only, four-character squares, as the VUCC award counts them). Net control check-ins get their names the same way.

## ADIF

Export ADIF writes every entry to Download/DMR/Logbook as an .adi file (ADIF 3.1) and offers to share it, for logging programs and QSL services such as LoTW or QRZ. Grid squares and locations go out as GRIDSQUARE and QTH. DMR entries are mode DIGITALVOICE, submode DMR; stations only heard carry SWL, so a logging program does not count them as contacts.

[All topics](README.md)
