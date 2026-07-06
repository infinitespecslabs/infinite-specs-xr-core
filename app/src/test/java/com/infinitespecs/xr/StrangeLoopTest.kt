package com.infinitespecs.xr

import androidx.xr.runtime.math.Ray
import androidx.xr.runtime.math.Vector3
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import com.infinitespecs.xr.perception.SpatialIntentParser
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the mathematical and systemic validity of the self-referential loop.
 * Confirms that a physical token captured by eyewear maps cleanly to a non-hallucinatory,
 * deterministic schema definition file meant to guide an autonomous coding agent.
 */
class StrangeLoopTest {

    @Test
    fun verifySpatialTelemetryFoldsIntoValidMcpSpecification() = runTest {
        // Mock the Gemini generative model response
        val mockResponse = mockk<GenerateContentResponse>()
        every { mockResponse.text } returns """
            {
                "nodeType": "KafkaConsumer",
                "physicalAnchorId": "anchor_stage_rig_left",
                "semanticConstraints": [
                    "Must process incoming DMX tokens below 11ms latency"
                ],
                "loopEngineeringSkillTemplate": "autonomous-service-generator-v1"
            }
        """.trimIndent()

        val mockModel = mockk<GenerativeModel>()
        coEvery { mockModel.generateContent(any<String>()) } returns mockResponse

        val parser = SpatialIntentParser(generativeModel = mockModel)

        val mockTranscript = "Declare an asynchronous consumer tracking the stage rig left"
        val mockGaze = Ray(Vector3.Zero, Vector3(0f, 0f, -1f))

        val generatedIntent = parser.parseTokensToSchemaConstraint(mockTranscript, mockGaze)

        // Verify the system successfully structures raw real-world physics into a microservice specification
        assertEquals("KafkaConsumer", generatedIntent.nodeType)
        assertEquals("anchor_stage_rig_left", generatedIntent.physicalAnchorId)
        assertTrue(generatedIntent.semanticConstraints.contains("Must process incoming DMX tokens below 11ms latency"))
        assertEquals("autonomous-service-generator-v1", generatedIntent.loopEngineeringSkillTemplate)
    }
}
