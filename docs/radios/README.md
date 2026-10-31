# Radio notes

<!-- Generated from app/src/main/assets/radios/radios.json by RadioNotesExportTest. Do not edit by hand. -->

Specs, what works, known quirks and what we measured on our test unit, for each phone SignalDeck has a profile for. The app shows the same notes for the phone in use under Device → About this radio. Measurements come from one test unit; yours may differ.

| Radio | Status | Summary |
|---|---|---|
| [Ulefone Armor 26 Ultra](ulefone-armor26ultra.md) | Tested | The reference phone: a dual-band DMR and FM radio that every SignalDeck feature was built and measured on. |
| [Unihertz Atom XL](unihertz-atomxl.md) | Tested | A compact UHF DMR and FM phone whose radio sits behind a USB bridge; tested for receive and transmit. |
| [ANYSECU W6+](anysecu-w6plus.md) | Tested | An analog-only UHF FM phone (400 to 470 MHz) with its own simpler radio module; tested for receive and transmit. |
| [ANYSECU W6Plus DMR](anysecu-w6plus-dmr.md) | In testing | The W6+ body with a UHF DMR module; DMR and FM receive and transmit work. |
| [Ulefone Armor 20WT](ulefone-armor20wt.md) | Untested | The Armor 26 Ultra's radio family on another serial port; the profile is written from a report and not yet tested. |
| [Ulefone Armor 3WT](ulefone-armor3wt.md) | Untested | The Atom XL's USB radio bridge with an older DMR module, driven through the phone's radio daemon; written from a report, not yet tested. |
| [Blackview Xplore 1](blackview-xplore1.md) | In testing | A DMR phone with the Armor's module protocol and audio over a second serial port; receive works for testers. |

Notes as of 2026-10-29. radios.json holds the same data for the website.
