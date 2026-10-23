package com.notifyforward.app

import android.content.Context
import com.notifyforward.app.engine.LearningStore
import com.notifyforward.app.engine.SemanticEngine
import org.json.JSONObject

/** Diagnostic evaluation only. Never reads or writes the user's labels or sends notifications. */
object SemanticBenchmark {
    fun run(ctx: Context, testContext: Context, asset: String = "diagnostic-v1.json", emit: (String) -> Unit) {
        val data = JSONObject(testContext.assets.open(asset).bufferedReader().use { it.readText() })
        val tests = data.getJSONArray("tests")
        val support = data.getJSONArray("support")
        fun sample(row: JSONObject) = LearningStore.Sample(row.getString("package"), false, row.getString("title"), row.getString("text"), row.getInt("label"), 0L)
        val references = (0 until support.length()).map { sample(support.getJSONObject(it)) }
        require((0 until tests.length()).none { i -> references.any { it.key == sample(tests.getJSONObject(i)).key } })
        try {
            listOf("cold", "personal16").forEach { mode ->
                SemanticEngine.release()
                for (i in 0 until tests.length()) {
                    val row = tests.getJSONObject(i)
                    val started = android.os.SystemClock.elapsedRealtime()
                    val prediction = SemanticEngine.judge(ctx, sample(row).copy(label = 0), if (mode == "cold") emptyList() else references)
                    emit("EVAL " + JSONObject().apply {
                        put("mode", mode); put("id", row.getString("id")); put("expected", row.getInt("label"))
                        put("predicted", prediction.label); put("reason", prediction.reason)
                        put("personalSamples", prediction.personalSamples)
                        put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
                        put("model", SemanticEngine.MODEL_ID); put("classifier", SemanticEngine.CLASSIFIER_ID)
                    }.toString())
                }
            }
        } finally { SemanticEngine.release() }
    }
}
