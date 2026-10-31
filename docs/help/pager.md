<!-- Generated from app/src/main/java/com/macdmr/radio/ui/HelpView.java by HelpExportTest. Do not edit by hand. -->

# Pager

*Pages · 15 of 34*

Send a POCSAG page to one RIC or several, in one transmission.

## Open it

PKT → POCSAG page, or SignalDeck Web → Pager. The frequency starts as the channel you are on. 1200 baud is the usual speed; 512 and 2400 are there too. The function code is 3: alphanumeric text, which alphanumeric pagers display. A numeric-only pager (function 0) is not sent yet.

## Who it goes to

Type RIC numbers, or the name or callsign of a contact that has pager RICs. Several fit in one transmission: separate them with spaces or commas. A contact with more than one RIC is paged on all of them. Your own RICs are set in PKT → APRS settings.

## Sending

Send keys the radio at low power on that frequency. A page is about two seconds on the air: the radio fills its buffer before it keys, then the page, then a short tail. If you are not already on a low-power wide channel there, the radio tunes a low-power VFO, waits for the retune to settle, sends the page, and comes back to the channel you were on. Signing, which is on unless you tap it off, puts your callsign and a colon at the start of the text. A SignalDeck that hears it treats that as the sender and can reply; an ordinary alphanumeric pager shows it as the start of the message. The page goes through the module's symbol engine, so it sends only when the module reports bench firmware from FW21 on. On the firmware the phone shipped with, Send says so and does not key.

## Receiving

PKT → Hear pages listens on the analog channel you are on, below the squelch, and files each page under the contact that owns its RIC. A signed page from an unknown RIC (your callsign and a colon at the start) files under that callsign. A page with neither files under the RIC number: tap it to open the thread, then Save as contact or Mute. On bench firmware the voice filter is held open. At 512 baud on this phone the audio is restored first, undoing a high-pass of about 7.5 ms that sags a long run of the same bit. The offset taken off is the average over the preamble, and during a run of the same bit the previous bit is kept until the level is a quarter of the eye past the threshold. Padding after the text is not shown. The held-path equalizer is not used at that speed. At 1200 and 2400 baud the page uses this phone's held-path equalizer and is not restored. On the firmware the phone shipped with, Hear pages says it needs the bench firmware and does not start: that receiver's audio is not something a plain slicer can read. An address word that does not check, or that needs more than one bit repaired, does not open a page, and a page is listed only when a preamble came just before it and a message word checks. A damaged address is not shown as some other number. Messages → the scan icon is monitor mode: receive only, stepping through the pager frequencies (long-press the icon to edit them) and waiting about two and a half seconds on each. It stays while a preamble is coming in, and for about a second after that so the batch can finish. A page for one of your RICs raises a notification.

[All topics](README.md)
