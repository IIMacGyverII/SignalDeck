/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import android.app.Application;
import android.content.res.Configuration;

import com.macdmr.radio.i18n.Txt;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class RadioApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // The vendor audio classes (android.os.PrizeTinyService / ITinyRecvCallback) are hidden API;
        // exempt everything before any class that references them is loaded.
        try { HiddenApiBypass.addHiddenApiExemptions(""); } catch (Throwable ignored) { }
        Txt.init(this);   // user-facing text in the app's language, for every class (services and the car included)
    }

    /** The phone's language changed: rebuild the localised text context (the override, if set, still wins). */
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        Txt.init(this);
    }
}
