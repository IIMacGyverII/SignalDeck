<!-- Generated from app/src/main/java/com/macdmr/radio/ui/HelpView.java by HelpExportTest. Do not edit by hand. -->

# Messages

*Pages · 25 of 34*

DMR texts, M17 texts and pager threads.

## Threads

The list is one conversation per contact. A DMR text uses the radio's ID or talkgroup. An M17 text uses the sender's callsign, or the contact that carries that callsign; everything addressed to @ALL shares the M17 everyone thread. A page uses the contact that owns the RIC, otherwise the callsign at the start of a signed page, otherwise the RIC. A chip in front of the name says M17 or PAGE. A number after the name is how many are unread. Tap a row to open it. A page that arrives for a different thread while one is open is named in a line at the top.

## Monitor

The scan icon in the header turns pager monitor on. Sending is put away and the radio steps through the frequencies you set (long-press the icon; hertz, separated by commas). It waits about two and a half seconds on each frequency, long enough for the receiver to settle after the retune, stays while a preamble is being heard, and for about a second after the preamble ends so the batch can finish. It is off until you turn it on, and it stays off across a restart.

## Sending

Texts travel over the current DMR channel. Tap + and pick a contact or the channel's own call target, type, or tap the lightning button for a quick reply (the list is yours to edit), and send with the green button. On an analog channel the thread says so and nothing is sent. A pager thread sends with the page transmitter on the frequency you are on. An M17 thread sends an M17 text to that callsign, or to @ALL from the everyone thread.

## Delivery

Under each of your messages: green ✓✓ delivered means the other radio acknowledged it; amber ✓ sent means it went out without an acknowledgement, which is normal for many radios (the BTECH 6X2 among them) and the text still arrives; red ✖ means the radio could not send it.

## Receiving

Incoming texts appear in their conversation and in the intercom's activity panel. A private text goes to the sender's conversation. A talkgroup text goes to the talkgroup's conversation, with the sender's name over each message, so a group chat stays one thread; the radio does not say which talkgroup a text was sent on, so (as in the stock app) it is the talkgroup of the channel you are on.

## Names

Conversations show the contact's name; for a radio that is not in your contacts, the name and callsign from the RadioID list (Device), and for a talkgroup its contact name or TG and its number. SignalDeck Web's Messages page shows the same.

## Contact history

A text you receive or send is also written into that contact's history (Contacts → tap the contact), together with the voice calls, APRS and M17 traffic of the same person.

[All topics](README.md)
