package dev.kosha.workflow.service

import io.github.jamsesso.jsonlogic.JsonLogic
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Evaluates JSON Logic expressions against a document context.
 *
 * Thin wrapper around `io.github.jamsesso.jsonlogic.JsonLogic` that
 * adds logging, null-safety, and the truthiness convention:
 *
 * - **null or blank conditionJson** → always true (step fires)
 * - **valid expression returning truthy** → step fires
 * - **valid expression returning falsy** → step is SKIPPED
 * - **invalid expression (parse error, eval error)** → step fires
 *   (fail-open). We don't want a broken condition to silently skip
 *   a step that was supposed to run — that's worse than a false
 *   positive. The parse error is logged loudly so the admin can fix
 *   the condition.
 *
 * ## What counts as truthy
 *
 * JSON Logic's native truthiness: `false`, `null`, `0`, `""`, `[]`
 * are falsy; everything else is truthy. This matches JavaScript
 * semantics and is what the JSON Logic spec defines. We don't layer
 * additional rules on top.
 */
@Component
class ConditionEvaluator {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jsonLogic = JsonLogic()

    /**
     * Evaluate a condition expression against a data context.
     *
     * @param conditionJson the JSON Logic expression, or null/blank
     * @param context the data map (see [WorkflowConditionContext])
     * @return true if the step should fire, false if it should be skipped
     */
    fun shouldFire(conditionJson: String?, context: Map<String, Any?>): Boolean {
        if (conditionJson.isNullOrBlank()) return true

        return try {
            val result = jsonLogic.apply(conditionJson, context)
            val truthy = isTruthy(result)
            log.debug(
                "Condition evaluated: expression={} result={} truthy={}",
                conditionJson.take(80), result, truthy,
            )
            truthy
        } catch (ex: Exception) {
            // Fail-open: a broken condition should not silently skip a step.
            log.error(
                "JSON Logic evaluation failed (fail-open, step WILL fire): expression={} error={}",
                conditionJson.take(80), ex.message,
            )
            true
        }
    }

    /**
     * JSON Logic truthiness: false, null, 0, "", empty list are falsy.
     */
    private fun isTruthy(value: Any?): Boolean {
        return when (value) {
            null -> false
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.isNotEmpty()
            is Collection<*> -> value.isNotEmpty()
            else -> true
        }
    }
}
