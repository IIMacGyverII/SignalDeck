<!-- Generated from app/src/main/java/com/macdmr/radio/ui/HelpView.java by HelpExportTest. Do not edit by hand. -->

# Winlink e-mail

*Pages · 18 of 34*

E-mail by radio through a Winlink gateway, or over the internet.

## What it is

Winlink is the amateur radio e-mail system used by emergency services and travellers: your callsign is your address (CALLSIGN@winlink.org), and you can write to other callsigns or to any internet e-mail address. PKT → Winlink opens your mailbox: Inbox, Outbox (waiting for the next connection) and Sent. The same mailbox, with writing, connecting, settings, the session log and attachments, is on SignalDeck Web’s Messages page. You need an amateur licence and your callsign set in PKT → Settings.

## Please report

Winlink in SignalDeck is new and has not yet been tested with the real Winlink system: it was tested against a test gateway over the radio and a test server over the internet, which follow the same protocol. If you use it, please tell us whether it worked (or what went wrong, with the session log): the notice at the top of the Winlink screen opens SignalDeck's issue page on GitHub.

## Detailed log

Winlink → Settings → Detailed diagnostic log (also offered at your first connection) records, for each session, the time of every step, the protocol bytes both ways and every radio frame. Your password is never in it; the messages' data only if you tick the second box. After a session, Session log → Share log (SignalDeck Web: Download log) sends it: please do, whether it worked or not, it is how Winlink in SignalDeck gets fixed.

## Trying a real gateway

To help confirm it works, try one session with a real gateway or over the internet and report: your phone, whether it was by radio (gateway callsign, frequency, how far) or internet; whether it connected and logged in; whether your message arrived (send one to your own e-mail address) and whether mail came back; how long it took. If anything failed, open Session log and copy it into the report (the login answer is already hidden). Start with one short message to yourself.

## Writing

New message: To (callsigns or e-mail addresses, separated by commas), an optional Cc, a subject and plain text. Put in the outbox stores it; nothing is sent until you connect. Messages are compressed for the radio; keep them short, a page of text takes minutes on 1200-baud packet. Reply and Forward quote the original; Delete removes a message (from the outbox it will then not be sent).

## Connect: radio

Tune the channel of a Winlink packet gateway (RMS; listed at winlink.org under RMS channels, usually 1200 baud on 2 m or 70 cm), then Connect: radio and enter its callsign (e.g. N0ABC-10), and digipeaters if you need them. SignalDeck calls it in AX.25 connected mode, sends the outbox, collects your mail and hangs up. Expect a few minutes: every transmission starts with a 1.6-second lead-in and frames lost on the air are sent again; the Packet terminal cannot be used at the same time.

## Gateways near you

Winlink → Gateways → Open winlink.org opens Winlink's gateway list (RMS Channels) in your browser: tap RMS List, choose Packet, then CSV File (if the buttons are cut off on the phone, turn on the browser's Desktop site). Back in SignalDeck, Import the CSV opens Android's file picker, where the file you just downloaded is at the top of Recent (or in Downloads). SignalDeck Web's Messages page imports the same file from a computer. Repeat it now and then: gateways come and go. SignalDeck keeps the 1200-baud packet gateways on frequencies this radio tunes and lists them nearest first, with distance and direction from the phone's last known position (worked out on the phone, never sent). Tap one to tune its frequency and connect, to tune and listen first, or to make it your gateway. Packet finder: listen on the nearest 10 sets the packet finder to their frequencies, to check which ones you can actually hear.

## Connect: internet

Connect: internet does the same exchange with the Winlink system (the CMS) over the phone's internet connection: handy to test your setup, or when there is no gateway in range but there is a signal.

## Account and password

The first connection with a new callsign creates its Winlink account. Set a password for it at winlink.org, then enter it under Winlink → Settings: Winlink asks for it at every login (secure login; only a one-time answer computed from it is sent). The password is kept only in SignalDeck's private settings: never shown, logged, put in a device report, a backup or Android's cloud backup. The optional grid square is sent to the gateway when you log in.

## Session log and attachments

Session log shows the last connection line by line (the login answer is hidden). Received attachments are listed under the message: tap one to open or share it. When new mail arrives you get a notification, and PKT shows how many are new.

[All topics](README.md)
