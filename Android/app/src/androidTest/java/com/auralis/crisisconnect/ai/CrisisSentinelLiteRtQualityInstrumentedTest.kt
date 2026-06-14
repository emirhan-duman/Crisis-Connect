package com.auralis.crisisconnect.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class CrisisSentinelLiteRtQualityInstrumentedTest {
    @Test
    fun installedLiteRtModelPassesMobileChatQualityCases() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Opt in with -e crisisSentinelRunLitertQuality true to run slow real-model eval.",
            args.getString("crisisSentinelRunLitertQuality") == "true"
        )

        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val runtime = CrisisSentinelLiteRtModelRuntime(appContext)
        assumeTrue("Crisis Sentinel LiteRT-LM model is not installed.", runtime.isReady)

        val cases = loadCases(appContext)
        val fullRun = args.getString("crisisSentinelEvalFull") == "true"
        val limit = args.getString("crisisSentinelEvalLimit")?.toIntOrNull()
            ?: if (fullRun) cases.size else 12
        val allowFallback = args.getString("crisisSentinelAllowFallback") == "true"
        val selectedCases = cases.take(limit.coerceAtMost(cases.size))

        runtime.use {
            val engine = CrisisSentinelOfflineEngine(
                knowledgeIndex = CrisisSentinelKnowledgeIndex.fromGuide(Locale("tr")),
                modelRuntime = runtime
            )
            selectedCases.forEach { case ->
                val response = engine.respondWithModel(
                    CrisisSentinelRequest(
                        prompt = case.prompt,
                        locale = Locale("tr")
                    )
                )
                if (!allowFallback) {
                    assertEquals(
                        "${case.id}: model output was rejected and fallback was used",
                        CrisisSentinelResponseSource.LocalModel,
                        response.source
                    )
                }
                validateCase(case, response.answer)
            }
        }
    }

    private fun loadCases(context: Context): List<QualityCase> {
        return context.assets
            .open("crisis_sentinel_mobile_chat_quality_v2.jsonl")
            .bufferedReader(Charsets.UTF_8)
            .useLines { lines ->
                lines.filter { it.isNotBlank() }
                    .map { line ->
                        val json = JSONObject(line)
                        QualityCase(
                            id = json.getString("id"),
                            prompt = json.getString("prompt"),
                            requiredTerms = json.stringArray("required_terms"),
                            forbiddenTerms = json.stringArray("forbidden_terms")
                        )
                    }
                    .toList()
            }
    }

    private fun validateCase(case: QualityCase, answer: String) {
        val normalized = answer.normalizedForEval()
        assertTrue("${case.id}: answer is too short: $answer", answer.split(Regex("\\s+")).size >= 12)
        assertFalse("${case.id}: answer looks like JSON: $answer", answer.trimStart().startsWith("{"))
        schemaLeakTerms.forEach { term ->
            assertFalse("${case.id}: schema/prompt leak term present: $term\n$answer", normalized.contains(term.normalizedForEval()))
        }
        case.requiredTerms.forEach { term ->
            assertTrue("${case.id}: missing required term '$term'\n$answer", normalized.contains(term.normalizedForEval()))
        }
        case.forbiddenTerms.forEach { term ->
            assertFalse("${case.id}: forbidden term present '$term'\n$answer", normalized.contains(term.normalizedForEval()))
        }
        assertFalse("${case.id}: dosage-like text present\n$answer", dosageRegex.containsMatchIn(answer))
    }

    private fun JSONObject.stringArray(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
    }

    private fun String.normalizedForEval(): String {
        return lowercase(Locale("tr"))
            .replace("ı", "i")
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ş", "s")
            .replace("ö", "o")
            .replace("ç", "c")
    }

    private data class QualityCase(
        val id: String,
        val prompt: String,
        val requiredTerms: List<String>,
        val forbiddenTerms: List<String>
    )

    private companion object {
        val schemaLeakTerms = listOf(
            "incidentDraft",
            "safetyNotices",
            "followUpQuestions",
            "citations",
            "articleId",
            "missingFields",
            "locationText",
            "<start_of_turn>",
            "<end_of_turn>",
            "system prompt",
            "sistem prompt"
        )
        val dosageRegex = Regex(
            """\b\d+(?:[,.]\d+)?\s*(mg|mcg|g|ml|iu|ünite|tablet|kapsül|damla|ampul)\b""",
            RegexOption.IGNORE_CASE
        )
    }
}
