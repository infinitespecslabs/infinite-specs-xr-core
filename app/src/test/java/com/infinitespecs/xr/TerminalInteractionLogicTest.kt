package com.infinitespecs.xr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalInteractionLogicTest {

    // ── resolveAgentInputAction ──────────────────────────────────────────────

    @Test
    fun `a pending permission request routes the option to SubmitPermission`() {
        val action = TerminalInteractionLogic.resolveAgentInputAction(
            selectedOption = "Yes",
            hasPendingPermissionRequest = true,
            hasPendingQuestionRequest = false,
        )
        assertEquals(TerminalInteractionLogic.AgentInputAction.SubmitPermission("Yes"), action)
    }

    @Test
    fun `a pending question request routes the option to SubmitQuestion`() {
        val action = TerminalInteractionLogic.resolveAgentInputAction(
            selectedOption = "Spaces",
            hasPendingPermissionRequest = false,
            hasPendingQuestionRequest = true,
        )
        assertEquals(TerminalInteractionLogic.AgentInputAction.SubmitQuestion("Spaces"), action)
    }

    @Test
    fun `neither pending request resolves to None`() {
        val action = TerminalInteractionLogic.resolveAgentInputAction(
            selectedOption = "Yes",
            hasPendingPermissionRequest = false,
            hasPendingQuestionRequest = false,
        )
        assertEquals(TerminalInteractionLogic.AgentInputAction.None, action)
    }

    @Test
    fun `a pending permission request takes precedence when both are somehow set`() {
        val action = TerminalInteractionLogic.resolveAgentInputAction(
            selectedOption = "Yes",
            hasPendingPermissionRequest = true,
            hasPendingQuestionRequest = true,
        )
        assertEquals(TerminalInteractionLogic.AgentInputAction.SubmitPermission("Yes"), action)
    }

    // ── resolveSttFallbackText ────────────────────────────────────────────────

    @Test
    fun `an error mentioning 'not available' triggers the emulator fallback`() {
        val fallback = TerminalInteractionLogic.resolveSttFallbackText("Speech recognition not available on this device")
        assertEquals("Run debug build and test stage rig left", fallback)
    }

    @Test
    fun `an error mentioning 'Client' triggers the emulator fallback`() {
        val fallback = TerminalInteractionLogic.resolveSttFallbackText("Client side error")
        assertEquals("Run debug build and test stage rig left", fallback)
    }

    @Test
    fun `the fallback match is case-insensitive`() {
        assertEquals("Run debug build and test stage rig left", TerminalInteractionLogic.resolveSttFallbackText("NOT AVAILABLE"))
        assertEquals("Run debug build and test stage rig left", TerminalInteractionLogic.resolveSttFallbackText("client error"))
    }

    @Test
    fun `an unrelated STT error does not trigger the fallback`() {
        assertNull(TerminalInteractionLogic.resolveSttFallbackText("No speech input detected"))
        assertNull(TerminalInteractionLogic.resolveSttFallbackText("Audio recording error"))
        assertNull(TerminalInteractionLogic.resolveSttFallbackText("Network error"))
    }

    @Test
    fun `an error matching both fallback substrings still returns the single fallback text`() {
        val fallback = TerminalInteractionLogic.resolveSttFallbackText("Client: recognizer not available")
        assertEquals("Run debug build and test stage rig left", fallback)
    }
}
