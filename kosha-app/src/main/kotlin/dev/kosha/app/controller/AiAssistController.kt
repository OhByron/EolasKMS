package dev.kosha.app.controller

import com.fasterxml.jackson.databind.ObjectMapper
import dev.kosha.common.api.ApiResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class DefineTermRequest(
    val label: String,
    val parentLabel: String? = null,
    val parentDescription: String? = null,
)

data class SuggestChildrenRequest(
    val parentLabel: String,
    val parentDescription: String? = null,
    val existingChildren: List<String> = emptyList(),
)

data class SuggestedTerm(
    val label: String,
    val description: String,
)

/**
 * AI-assisted taxonomy helpers. Used by the taxonomy management UI when
 * an admin is authoring or browsing terms — the LLM generates a draft
 * definition or suggests child subtypes. Available to admins with taxonomy
 * authority (global or department admins).
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
@PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')")
class AiAssistController(
    private val aiConfigRepo: AiConfigRepository,
    private val objectMapper: ObjectMapper,
) {

    @PostMapping("/define-term")
    fun defineTerm(@RequestBody request: DefineTermRequest): ApiResponse<Map<String, String>> {
        val config = aiConfigRepo.findById("default").orElse(null)
        val apiKey = config?.llmApiKey ?: ""
        val model = config?.llmModel ?: "claude-sonnet-4-20250514"

        if (apiKey.isBlank()) {
            return ApiResponse(data = mapOf("definition" to "AI not configured. Please set an API key in AI Config."))
        }

        val parentContext = if (request.parentLabel != null) {
            val parentDesc = request.parentDescription?.let { " ($it)" } ?: ""
            " This term is a child/subtype of '${request.parentLabel}'$parentDesc. Frame the definition in relation to the parent term."
        } else ""

        val prompt = "Define the term '${request.label}' in 1-2 sentences suitable for a taxonomy/glossary entry in an enterprise knowledge management system. For acronyms, expand them first.$parentContext Return ONLY the definition text, no preamble."

        val definition = callAnthropic(apiKey, model, prompt, 200)
        return ApiResponse(data = mapOf("definition" to definition))
    }

    @PostMapping("/suggest-children")
    fun suggestChildren(@RequestBody request: SuggestChildrenRequest): ApiResponse<List<SuggestedTerm>> {
        val config = aiConfigRepo.findById("default").orElse(null)
        val apiKey = config?.llmApiKey ?: ""
        val model = config?.llmModel ?: "claude-sonnet-4-20250514"

        if (apiKey.isBlank()) {
            return ApiResponse(data = emptyList())
        }

        val parts = mutableListOf<String>()
        parts.add("Suggest 5 child/subtype taxonomy terms for the parent term '${request.parentLabel}'.")
        request.parentDescription?.let { parts.add("Parent description: $it") }
        if (request.existingChildren.isNotEmpty()) {
            parts.add("Already existing children (do NOT suggest these): ${request.existingChildren.joinToString(", ")}")
        }
        parts.add("Each child should be a meaningful subcategory, subtype, component, or specialization of the parent.")
        parts.add("Return valid JSON: an array of objects with \"label\" and \"description\" fields.")
        parts.add("Each description should be 1-2 sentences defining the term in relation to its parent.")
        parts.add("For acronyms, expand them. Return ONLY the JSON array.")

        val prompt = parts.joinToString("\n")
        val responseText = callAnthropic(apiKey, model, prompt, 800)

        return try {
            var json = responseText.trim()
            if (json.startsWith("```")) {
                json = json.substringAfter("\n").substringBeforeLast("```").trim()
            }

            val suggestions = objectMapper.readValue(json, Array<SuggestedTerm>::class.java).toList()
            ApiResponse(data = suggestions)
        } catch (ex: Exception) {
            ApiResponse(data = emptyList())
        }
    }

    private fun callAnthropic(apiKey: String, model: String, prompt: String, maxTokens: Int): String {
        val client = java.net.http.HttpClient.newHttpClient()

        // Use Jackson for proper JSON serialization — no manual escaping
        val requestBody = objectMapper.writeValueAsString(
            mapOf(
                "model" to model,
                "max_tokens" to maxTokens,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to prompt)
                )
            )
        )

        val httpRequest = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("https://api.anthropic.com/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        return try {
            val response = client.send(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString())
            val responseBody = response.body()

            // Parse the response properly with Jackson
            val responseJson = objectMapper.readTree(responseBody)
            val content = responseJson.path("content")
            if (content.isArray && content.size() > 0) {
                content[0].path("text").asText("Could not generate response.")
            } else {
                val errorMsg = responseJson.path("error").path("message").asText("")
                if (errorMsg.isNotBlank()) "Error: $errorMsg"
                else "Could not generate response."
            }
        } catch (ex: Exception) {
            "Error: ${ex.message}"
        }
    }
}
