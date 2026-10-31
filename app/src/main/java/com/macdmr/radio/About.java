/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import com.macdmr.radio.i18n.Txt;

/**
 * The app's legal notices (GPL "Appropriate Legal Notices"): copyright, license, the section 7 terms in plain words,
 * where the source is, and the third-party credits. One text for the phone's Mission Statement sheet and SignalDeck
 * Web; the full wording is in COPYRIGHT and LICENSE at the repository root.
 */
public final class About {
    private About() { }

    public static final String SOURCE = "https://github.com/IIMacGyverII/SignalDeck";   // i18n: URL

    public static String legal() {
        return Txt.s(R.string.device_about_legal, BuildConfig.VERSION_NAME, SOURCE);
    }
}
