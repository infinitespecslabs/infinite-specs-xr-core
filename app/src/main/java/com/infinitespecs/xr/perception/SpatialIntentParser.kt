package com.infinitespecs.xr.perception

import android.util.Log
import androidx.xr.runtime.math.Ray
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import com.infinitespecs.xr.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Evaluates real-time gaze duration data, spatial orientation matrices, and spoken
 * audio transcript tokens into an immutable architectural intent package.
 *
 * Now powered by Google Gemini (Phase 3).
 */
class SpatialIntentParser {

    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = BuildConfig.GOOGLE_AI_API_KEY,
        generationConfig = generationConfig {
            responseMimeType = "application/json"
        },
    )

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Serializable
    data class GazeRay(
        val originX: Float,
        val originY: Float,
        val originZ: Float,
        val directionX: Float,
        val directionY: Float,
        val directionZ: Float,
    )

    @Serializable
    data class ArchitecturalIntent(
        val nodeType: String, // e.g., "KafkaConsumer", "AsynchronousEventBridge"
        val physicalAnchorId: String, // Vector tracking link to a real-world object
        val semanticConstraints: List<String>, // List of system invariants spoken aloud
        val loopEngineeringSkillTemplate: String, // Flags the target sub-agent pipeline
        val spatialContext: GazeRay? = null,
    )

    /**
     * Uses Gemini to parse natural language and spatial context into a technical schema.
     */
    suspend fun parseTokensToSchemaConstraint(
        voiceTranscript: String,
        gazeRay: Ray,
    ): ArchitecturalIntent {
        val serializableGaze = GazeRay(
            originX = gazeRay.origin.x,
            originY = gazeRay.origin.y,
            originZ = gazeRay.origin.z,
            directionX = gazeRay.direction.x,
            directionY = gazeRay.direction.y,
            directionZ = gazeRay.direction.z,
        )

        val prompt = """
            You are an expert systems architect for an XR engineering platform.
            Translate the following human spoken intent and spatial context into a technical JSON schema.
            
            Human Intent: "$voiceTranscript"
            Spatial Coordinates: (Origin: ${serializableGaze.originX}, ${serializableGaze.originY}, ${serializableGaze.originZ}) (Direction: ${serializableGaze.directionX}, ${serializableGaze.directionY}, ${serializableGaze.directionZ})
            
            The output MUST be a JSON object with the following fields:
            - nodeType: A CamelCase identifier for the component (e.g., "KafkaConsumer", "EventBridge").
            - physicalAnchorId: Should be "floating_context" unless specified otherwise.
            - semanticConstraints: A list of strict technical requirements extracted from the speech.
            - loopEngineeringSkillTemplate: Choose the most appropriate: "autonomous-service-generator-v1", "ui-agent-v1", or "infrastructure-deployer-v1".
            
            Return ONLY the raw JSON.
        """.trimIndent()

        return try {
            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: throw IllegalStateException("Empty response from Gemini")
            json.decodeFromString<ArchitecturalIntent>(responseText).copy(spatialContext = serializableGaze)
        } catch (e: Exception) {
            Log.e("SpatialIntentParser", "Gemini generation failed", e)
            Log.d("SpatialIntentParser", "API Key present: ${BuildConfig.GOOGLE_AI_API_KEY.isNotEmpty()}")
            // Fallback for demo stability if API key is missing or network fails
            ArchitecturalIntent(
                nodeType = "ErrorFallback",
                physicalAnchorId = "floating_context",
                semanticConstraints = listOf("Gemini parsing failed: ${e.message}"),
                loopEngineeringSkillTemplate = "autonomous-service-generator-v1",
                spatialContext = serializableGaze,
            )
        }
    }
}
