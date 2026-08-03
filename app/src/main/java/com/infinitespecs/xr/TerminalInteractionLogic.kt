package com.infinitespecs.xr

/**
 * Decision logic factored out of [MainActivity] so it's unit-testable
 * without an Android runtime. `MainActivity` itself is a `ComponentActivity`
 * and can't be constructed in a plain JVM test; this object holds no
 * Android-framework dependency at all, so it can be tested directly while
 * `MainActivity` stays responsible for the actual state/log side effects.
 */
object TerminalInteractionLogic {

    /** What a selected HUD option should do, given the bridge's current pending-request state. */
    sealed interface AgentInputAction {
        data class SubmitPermission(val decision: String) : AgentInputAction
        data class SubmitQuestion(val answer: String) : AgentInputAction
        data object None : AgentInputAction
    }

    /**
     * A pending permission request takes precedence over a pending question
     * if both are somehow set — this mirrors the original inline `if`/`else if`
     * chain in `MainActivity.submitAgentInput`.
     */
    fun resolveAgentInputAction(
        selectedOption: String,
        hasPendingPermissionRequest: Boolean,
        hasPendingQuestionRequest: Boolean,
    ): AgentInputAction = when {
        hasPendingPermissionRequest -> AgentInputAction.SubmitPermission(selectedOption)
        hasPendingQuestionRequest -> AgentInputAction.SubmitQuestion(selectedOption)
        else -> AgentInputAction.None
    }

    private const val EMULATOR_STT_FALLBACK_TEXT = "Run debug build and test stage rig left"

    /**
     * The Android XR emulator doesn't support on-device speech recognition,
     * so [LocalAndroidSpeechEngine][com.infinitespecs.xr.perception.LocalAndroidSpeechEngine]
     * reports specific error text in that case. Returns the fallback prompt
     * text to inject, or null if [errorMessage] doesn't indicate that case
     * (i.e. a real STT error that should just be logged as-is).
     */
    fun resolveSttFallbackText(errorMessage: String): String? {
        val sttUnavailable = errorMessage.contains("not available", ignoreCase = true) ||
            errorMessage.contains("Client", ignoreCase = true)
        return if (sttUnavailable) EMULATOR_STT_FALLBACK_TEXT else null
    }
}
