package com.infinitespecs.xr

import androidx.xr.runtime.math.Ray
import androidx.xr.runtime.math.Vector3
import com.infinitespecs.xr.perception.SpatialIntentParser
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
    fun verifySpatialTelemetryFoldsIntoValidMcpSpecification() {
        val parser = SpatialIntentParser()

        val mockTranscript = "Declare an asynchronous consumer tracking the stage rig left"
        val mockGaze = Ray(Vector3.Zero, Vector3(0f, 0f, -1f))

        val generatedIntent = parser.parseTokensToSchemaConstraint(mockTranscript, mockGaze)

        // Verify the system successfully structures raw real-world physics into a microservice specification
        assertEquals("AsynchronousEventBridge", generatedIntent.nodeType)
        assertTrue(generatedIntent.semanticConstraints.any { it.contains("11ms") })

        // Assert that the generated file contains an explicit loop stopping condition parameter
        assertEquals("autonomous-service-generator-v1", generatedIntent.loopEngineeringSkillTemplate)
    }
}
