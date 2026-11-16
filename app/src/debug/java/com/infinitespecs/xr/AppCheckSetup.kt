package com.infinitespecs.xr

import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds attest with the App Check debug provider. On first run it logs a
 * debug token (logcat tag `DebugAppCheckProvider`) that must be registered in the
 * Firebase console under Security > App Check > Apps > Manage debug tokens;
 * until then Firebase AI Logic rejects Gemini requests from this install.
 */
fun installAppCheckProvider() {
    Firebase.appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
}
