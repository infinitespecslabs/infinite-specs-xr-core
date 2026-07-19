package com.infinitespecs.xr.perception

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Interface definition for Speech to Text conversion.
 * Allows switching between local on-device engines and remote workstation APIs.
 */
interface SpeechTranscriptionEngine {
    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit)
    fun stopListening()
}

/**
 * On-device Speech transcription engine wrapping Android's built-in SpeechRecognizer.
 * Operates offline with zero network latency.
 */
class LocalAndroidSpeechEngine(private val context: Context) : SpeechTranscriptionEngine {

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)

    override fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        mainScope.launch {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    onError("Speech recognition not available on this device")
                    return@launch
                }

                // Destroy old instance if any
                speechRecognizer?.destroy()

                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer = recognizer

                val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d("LocalAndroidSpeech", "Ready for speech input")
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d("LocalAndroidSpeech", "Speech beginning")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d("LocalAndroidSpeech", "Speech end detected")
                    }

                    override fun onError(error: Int) {
                        val message = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy"
                            SpeechRecognizer.ERROR_SERVER -> "Server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
                            else -> "Unknown recognition error: $error"
                        }
                        Log.e("LocalAndroidSpeech", "Error code $error: $message")
                        onError(message)
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        Log.d("LocalAndroidSpeech", "Final transcription result: $text")
                        onResult(text)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        Log.d("LocalAndroidSpeech", "Partial transcription: $text")
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                recognizer.startListening(recognizerIntent)
            } catch (e: Exception) {
                Log.e("LocalAndroidSpeech", "Failed to start speech recognizer", e)
                onError(e.message ?: "Unknown error starting recognizer")
            }
        }
    }

    override fun stopListening() {
        mainScope.launch {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e("LocalAndroidSpeech", "Error stopping speech recognizer", e)
            }
        }
    }
}

/**
 * Stub implementation of a remote workstation-based STT engine.
 * Will capture microphone inputs and stream PCM buffers over WebSockets in a future milestone.
 */
class RemoteWorkstationSpeechEngine(private val context: Context) : SpeechTranscriptionEngine {
    override fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        Log.d("RemoteSpeechEngine", "Remote audio streaming STT is not implemented yet.")
        onError("Remote workstation speech engine is stubbed. Evaluate local STT first.")
    }

    override fun stopListening() {
        Log.d("RemoteSpeechEngine", "Stop requested.")
    }
}
