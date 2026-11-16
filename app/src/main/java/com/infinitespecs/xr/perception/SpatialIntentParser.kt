package com.infinitespecs.xr.perception

import android.util.Log
import androidx.xr.runtime.math.Ray
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.generationConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Evaluates real-time gaze duration data, spatial orientation matrices, and spoken
 * audio transcript tokens into an immutable architectural intent package.
 *
 * Powered by Gemini through Firebase AI Logic (Gemini Developer API backend).
 * The Gemini API key lives in the Firebase project, not in the app.
 */
class SpatialIntentParser(
    private val generativeModel: GenerativeModel = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
        modelName = GEMINI_MODEL,
        generationConfig = generationConfig {
            responseMimeType = "application/json"
        },
    ),
) {
    companion object {
        // gemini-2.5-flash is closed to new projects. The API points to
        // gemini-3.8-flash as its successor, but on the free tier that model
        // was rate-limited and overloaded when this was written (2026-11).
        const val GEMINI_MODEL = "gemini-3.6-flash"
    }

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
            - semanticConstraints: A list of strict technical requirements extracted from the speech. EACH requirement must be a simple flat string (e.g. "Must process incoming DMX tokens below 11ms latency"). Do NOT output objects inside this list.
            - loopEngineeringSkillTemplate: Choose the most appropriate: "autonomous-service-generator-v1", "ui-agent-v1", or "infrastructure-deployer-v1".
            
            Return ONLY the raw JSON.
        """.trimIndent()

        return try {
            val response = generativeModel.generateContent(prompt)
            val responseText = response.text ?: throw IllegalStateException("Empty response from Gemini")
            Log.d("SpatialIntentParser", "Raw Gemini JSON: $responseText")

            // Parse response as a generic JsonElement to inspect and adapt semanticConstraints robustly
            val jsonElement = json.parseToJsonElement(responseText)
            val jsonObject = jsonElement.jsonObject

            val nodeType = jsonObject["nodeType"]?.jsonPrimitive?.content ?: "UnknownNode"
            val physicalAnchorId = jsonObject["physicalAnchorId"]?.jsonPrimitive?.content ?: "floating_context"
            val loopEngineeringSkillTemplate = jsonObject["loopEngineeringSkillTemplate"]?.jsonPrimitive?.content ?: "autonomous-service-generator-v1"

            val semanticConstraints = mutableListOf<String>()
            val constraintsJson = jsonObject["semanticConstraints"]
            if (constraintsJson is kotlinx.serialization.json.JsonArray) {
                constraintsJson.forEach { element ->
                    try {
                        if (element is kotlinx.serialization.json.JsonObject) {
                            // If Gemini returned an object (e.g., { "type": "...", "value": "..." }), extract its content safely
                            val type = element["type"]?.jsonPrimitive?.content ?: ""
                            val value = element["value"]?.jsonPrimitive?.content ?: element.toString()
                            val constraintText = if (type.isNotEmpty()) "$type: $value" else value
                            semanticConstraints.add(constraintText)
                        } else {
                            semanticConstraints.add(element.jsonPrimitive.content)
                        }
                    } catch (pe: Exception) {
                        semanticConstraints.add(element.toString())
                    }
                }
            } else if (constraintsJson != null) {
                semanticConstraints.add(constraintsJson.jsonPrimitive.content)
            }

            ArchitecturalIntent(
                nodeType = nodeType,
                physicalAnchorId = physicalAnchorId,
                semanticConstraints = semanticConstraints,
                loopEngineeringSkillTemplate = loopEngineeringSkillTemplate,
                spatialContext = serializableGaze,
            )
        } catch (e: Exception) {
            Log.e("SpatialIntentParser", "Gemini generation failed", e)
            // Fallback for demo stability if the Firebase project is misconfigured or the network fails
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
