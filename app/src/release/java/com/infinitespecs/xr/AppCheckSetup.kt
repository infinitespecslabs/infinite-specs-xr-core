package com.infinitespecs.xr

/**
 * Release builds have no App Check provider yet, so Firebase AI Logic rejects
 * their Gemini requests while enforcement is on. A production provider (Play
 * Integrity) has to be installed here before distribution (ROADMAP Phase 6).
 */
fun installAppCheckProvider() {
    // Intentionally empty until a production attestation provider is chosen.
}
