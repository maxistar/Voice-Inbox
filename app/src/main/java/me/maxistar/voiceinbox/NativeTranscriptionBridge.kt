package me.maxistar.voiceinbox

import org.json.JSONObject

data class RecognizedWord(
    val text: String,
    val startSeconds: Float,
    val endSeconds: Float,
)

data class NativeChunkResult(
    val text: String,
    val words: List<RecognizedWord>,
)

object NativeTranscriptionBridge {
    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("onnxruntime")
        System.loadLibrary("notes_recognition")
    }

    external fun initialize(modelDirectory: String): Boolean

    external fun reset()

    private external fun transcribeChunkJson(samples: FloatArray): String?

    fun transcribeChunk(samples: FloatArray): NativeChunkResult? {
        val root = transcribeChunkJson(samples)?.let(::JSONObject) ?: return null
        val wordsJson = root.getJSONArray("words")
        val words = buildList {
            for (index in 0 until wordsJson.length()) {
                val word = wordsJson.getJSONObject(index)
                add(
                    RecognizedWord(
                        text = word.getString("text"),
                        startSeconds = word.getDouble("start").toFloat(),
                        endSeconds = word.getDouble("end").toFloat(),
                    ),
                )
            }
        }
        return NativeChunkResult(root.getString("text"), words)
    }
}
