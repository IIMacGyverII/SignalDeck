<!-- Generated from app/src/main/assets/guide/en.json by HelpExportTest. Do not edit by hand. -->

# Transmitting legally

*Good to know · 34 of 34*

Licences, power and the time limit.

## Licences

You are responsible for the frequencies you use: amateur bands need an amateur licence, GMRS needs a licence in the US, FRS limits power, the licence-free services differ by country (Add a zone marks those your region does not list as "not legal here"; Device → Region), and NOAA weather, marine and railroad channels are receive-only (the app never transmits there). The standard lists in Add a zone say on each one whether a licence is needed.

## Time limit

Device → Longest transmission sets how long one transmission may run: 30 seconds, 1, 2 (the default), 3, 5 or 10 minutes, a custom length (10 seconds or more), or No limit. It applies to everything that transmits: the talk key and the side PTT, the car, the widget, SignalDeck Web, M17, and data such as SSTV, APRS, FT8 and pages. A voice key ends when it reaches the limit (release and key again to continue); data longer than the limit is not sent, and the history says why. The radio's own timer is set a little longer (2 seconds, at most 255 seconds; off for No limit or more than 255 seconds), so SignalDeck always ends the key first. With No limit, or a limit over 255 seconds, the radio does not stop the transmission by itself: only you (release the key) or SignalDeck (its limit or the heat guard) end it, so if SignalDeck were closed or stopped working during a key, the radio would keep transmitting until you end it, for example by restarting the phone. A few modes keep their own shorter limits (the parrot 26 s, voice keyer slots 25 s, pages 25 s, text modes about 26 s per transmission). Choosing more than 2 minutes or No limit shows a heat warning once. Whatever the limit, the heat guard stays on for every key longer than 30 seconds: a voice or M17 key ends at 30 seconds, and long data such as an SSTV picture does not start, while the phone is still cooling down after the last long one (within 1 °C of where it was, or 17 minutes when the temperature cannot be read) or while the radio area is at 60 °C or the battery at 45 °C, and any long key ends early when the radio area passes 70 °C, the battery 48 °C, or either climbs 2 °C in ten seconds. The history says which. In Restricted mode the setting is locked.

[All topics](README.md)
