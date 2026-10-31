<!-- Generated from app/src/main/java/com/macdmr/radio/ui/HelpView.java by HelpExportTest. Do not edit by hand. -->

# POTA and SOTA

*Pages · 10 of 34*

Parks and summits on the air: activate, or work the activators.

## Open it

PKT → Logbook → POTA / SOTA (it says Activating … while you are), or SignalDeck Web’s Logbook page.

## Activate

In a park or on a summit: Activate a park (type its reference, e.g. US-1234, or pick one of the parks within 60 km of you; POTA is asked for an area rounded to half a degree) or Activate a summit (e.g. W0C/FR-001). SignalDeck checks the reference with POTA or SOTA and shows the name. Then Log a contact for each station: callsign, reports (59 by default), their park or summit for a park-to-park contact, notes; the frequency is the one the radio is on. A station already logged on that band in the same UTC day under your activation is flagged as a duplicate. The card counts today’s contacts against what counts as an activation (POTA 10 in a UTC day, SOTA 4).

## Upload the log

Export ADIF writes Download/DMR/Logbook/\<your call\>@\<reference\>-\<UTC date\>.adi (the name POTA asks for) with MY\_SIG / MY\_SIG\_INFO and MY\_POTA\_REF or MY\_SOTA\_REF, the other station’s POTA\_REF or SOTA\_REF, and STATION\_CALLSIGN (your callsign from the APRS settings), then offers to send it; upload it at pota.app or sotadata.org.uk. End the activation when you leave; its contacts stay in the logbook.

## Hunt

On the air now lists the activators spotted on POTA and SOTA, the ones this radio can work first (FM on 2 m and 70 cm), then the rest (HF, CW, digital: shown dimmed). Tap one to tune the VFO to it or to log it with its park or summit. Most activations are on HF; VHF/UHF FM ones are local, so check the list now and then.

[All topics](README.md)
