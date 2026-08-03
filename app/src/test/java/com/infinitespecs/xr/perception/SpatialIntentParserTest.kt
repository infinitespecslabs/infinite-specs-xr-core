package com.infinitespecs.xr.perception

import androidx.xr.runtime.math.Ray
import androidx.xr.runtime.math.Vector3
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge-case coverage for [SpatialIntentParser.parseTokensToSchemaConstraint].
 * The happy path lives in `StrangeLoopTest`; this file exercises the defensive
 * handling around malformed/partial Gemini output, since that's the part most
 * likely to regress silently.
 */
class SpatialIntentParserTest {

    private val transcript = "Declare an asynchronous consumer tracking the stage rig left"
    private val gaze = Ray(Vector3(1f, 2f, 3f), Vector3(0f, 0f, -1f))

    // ── Shared mocking helpers ──────────────────────────────────────────────

    private fun geminiReturning(responseText: String?): GenerativeModel {
        val response = mockk<GenerateContentResponse>()
        every { response.text } returns responseText
        val model = mockk<GenerativeModel>()
        coEvery { model.generateContent(any<String>()) } returns response
        return model
    }

    private fun geminiThrowing(exception: Throwable): GenerativeModel {
        val model = mockk<GenerativeModel>()
        coEvery { model.generateContent(any<String>()) } throws exception
        return model
    }

    // ── Failure paths ────────────────────────────────────────────────────────

    @Test
    fun `null response text falls back to ErrorFallback`() = runTest {
        val parser = SpatialIntentParser(generativeModel = geminiReturning(null))

        val intent = parser.parseTokensToSchemaConstraint(transcript, gaze)

        assertEquals("ErrorFallback", intent.nodeType)
        assertEquals("floating_context", intent.physicalAnchorId)
        assertTrue(intent.semanticConstraints.single().contains("Empty response from Gemini"))
    }

    @Test
    fun `empty response text falls back to ErrorFallback`() = runTest {
        // "" is non-null, so it skips the explicit null check and fails JSON parsing instead —
        // a distinct code path from the null-text case above.
        val parser = SpatialIntentParser(generativeModel = geminiReturning(""))

        val intent = parser.parseTokensToSchemaConstraint(transcript, gaze)

        assertEquals("ErrorFallback", intent.nodeType)
    }

    @Test
    fun `malformed non-JSON response falls back to ErrorFallback`() = runTest {
        val parser = SpatialIntentParser(generativeModel = geminiReturning("not json at all"))

        val intent = parser.parseTokensToSchemaConstraint(transcript, gaze)

        assertEquals("ErrorFallback", intent.nodeType)
    }

    @Test
    fun `generateContent throwing falls back to ErrorFallback with the exception message`() = runTest {
        val parser = SpatialIntentParser(generativeModel = geminiThrowing(RuntimeException("network unreachable")))

        val intent = parser.parseTokensToSchemaConstraint(transcript, gaze)

        assertEquals("ErrorFallback", intent.nodeType)
        assertEquals("autonomous-service-generator-v1", intent.loopEngineeringSkillTemplate)
        assertTrue(intent.semanticConstraints.single().contains("network unreachable"))
    }

    @Test
    fun `gaze context is preserved even when falling back to ErrorFallback`() = runTest {
        val parser = SpatialIntentParser(generativeModel = geminiThrowing(RuntimeException("boom")))

        val intent = parser.parseTokensToSchemaConstraint(transcript, gaze)

        assertEquals(1f, intent.spatialContext?.originX)
        assertEquals(2f, intent.spatialContext?.originY)
        assertEquals(3f, intent.spatialContext?.originZ)
        assertEquals(-1f, intent.spatialContext?.directionZ)
    }

    // ── Missing-field defaults ───────────────────────────────────────────────

    @Test
    fun `missing fields fall back to documented defaults`() = runTest {
        val parser = SpatialIntentParser(generativeModel = geminiReturning("{}"))

        val intent = parser.parseTokensToSchemaConstraint(transcript, gaze)

        assertEquals("UnknownNode", intent.nodeType)
        assertEquals("floating_context", intent.physicalAnchorId)
        assertEquals("autonomous-service-generator-v1", intent.loopEngineeringSkillTemplate)
        assertTrue(intent.semanticConstraints.isEmpty())
    }

    @Test
    fun `well-formed response maps every field directly`() = runTest {
        val parser = SpatialIntentParser(
            generativeModel = geminiReturning(
                """
                {
                    "nodeType": "KafkaConsumer",
                    "physicalAnchorId": "anchor_stage_rig_left",
                    "semanticConstraints": ["Must process incoming DMX tokens below 11ms latency"],
                    "loopEngineeringSkillTemplate": "infrastructure-deployer-v1"
                }
                """.trimIndent(),
            ),
        )

        val intent = parser.parseTokensToSchemaConstraint(transcript, gaze)

        assertEquals("KafkaConsumer", intent.nodeType)
        assertEquals("anchor_stage_rig_left", intent.physicalAnchorId)
        assertEquals("infrastructure-deployer-v1", intent.loopEngineeringSkillTemplate)
        assertEquals(listOf("Must process incoming DMX tokens below 11ms latency"), intent.semanticConstraints)
        assertEquals(1f, intent.spatialContext?.originX)
    }

    // ── semanticConstraints shape handling (Gemini's least reliable field) ──

    @Test
    fun `object-shaped constraints with type and value are formatted as 'type - value'`() = runTest {
        val parser = SpatialIntentParser(
            generativeModel = geminiReturning(
                """{"semanticConstraints": [{"type": "latency", "value": "must be under 11ms"}]}""",
            ),
        )

        val intent = parser.parseTokensToSchemaConstraint(transcript, gaze)

        assertEquals(listOf("latency: must be under 11ms"), intent.semanticConstraints)
    }

    @Test
    fun `object-shaped constraints with only value omit the type prefix`() = runTest {
        val parser = SpatialIntentParser(
            generativeModel = geminiReturning(
                """{"semanticConstraints": [{"value": "must be under 11ms"}]}""",
            ),
        )

        val intent = parser.parseTokensToSchemaConstraint(transcript, gaze)

        assertEquals(listOf("must be under 11ms"), intent.semanticConstraints)
    }

    @Test
    fun `object-shaped constraints with neither type nor value stringify the raw object`() = runTest {
        val parser = SpatialIntentParser(
            generativeModel = geminiReturning(
                """{"semanticConstraints": [{"unexpected": "shape"}]}""",
            ),
        )

        val intent = parser.parseTokensToSchemaConstraint(transcript, gaze)

        assertTrue(intent.semanticConstraints.single().contains("unexpected"))
    }

    @Test
    fun `mixed plain strings and objects in the same array are both handled`() = runTest {
        val parser = SpatialIntentParser(
            generativeModel = geminiReturning(
                """{"semanticConstraints": ["Plain string constraint", {"type": "latency", "value": "under 11ms"}]}""",
            ),
        )

        val intent = parser.parseTokensToSchemaConstraint(transcript, gaze)

        assertEquals(listOf("Plain string constraint", "latency: under 11ms"), intent.semanticConstraints)
    }

    @Test
    fun `a nested array element falls back to its string form via the inner catch`() = runTest {
        val parser = SpatialIntentParser(
            generativeModel = geminiReturning(
                """{"semanticConstraints": [["nested", "array"]]}""",
            ),
        )

        val intent = parser.parseTokensToSchemaConstraint(transcript, gaze)

        assertEquals(1, intent.semanticConstraints.size)
        assertTrue(intent.semanticConstraints.single().contains("nested"))
    }

    @Test
    fun `a single non-array constraints value is wrapped into a one-element list`() = runTest {
        val parser = SpatialIntentParser(
            generativeModel = geminiReturning(
                """{"semanticConstraints": "Just one bare string constraint"}""",
            ),
        )

        val intent = parser.parseTokensToSchemaConstraint(transcript, gaze)

        assertEquals(listOf("Just one bare string constraint"), intent.semanticConstraints)
    }
}
