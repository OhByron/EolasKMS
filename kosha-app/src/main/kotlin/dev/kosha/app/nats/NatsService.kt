package dev.kosha.app.nats

import com.fasterxml.jackson.databind.ObjectMapper
import io.nats.client.Connection
import io.nats.client.JetStream
import io.nats.client.Nats
import io.nats.client.Options
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

data class AiTaskMessage(
    val taskId: String = UUID.randomUUID().toString(),
    val taskType: String, // FULL_ANALYSIS, SUMMARIZE, EXTRACT_KEYWORDS, CLASSIFY
    val documentId: String,
    val versionId: String,
    val storageKey: String?,
    val extractedText: String?,
    val mimeType: String?,
)

@Service
class NatsService(
    @Value("\${kosha.nats.url:nats://localhost:4222}") private val natsUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var connection: Connection? = null
    private var jetStream: JetStream? = null

    @PostConstruct
    fun connect() {
        try {
            val opts = Options.Builder()
                .server(natsUrl)
                .reconnectWait(java.time.Duration.ofSeconds(2))
                .maxReconnects(-1)
                .build()
            connection = Nats.connect(opts)
            jetStream = connection!!.jetStream()
            log.info("Connected to NATS at {}", natsUrl)
        } catch (ex: Exception) {
            log.warn("Could not connect to NATS at {}: {}. AI processing disabled.", natsUrl, ex.message)
        }
    }

    @PreDestroy
    fun disconnect() {
        connection?.close()
    }

    fun publishAiTask(task: AiTaskMessage) {
        val js = jetStream ?: run {
            log.warn("NATS JetStream not connected, skipping AI task for document {}", task.documentId)
            return
        }
        try {
            val data = objectMapper.writeValueAsBytes(task)
            val ack = js.publish("ai.task.submitted", data)
            log.info(
                "Published AI task {} to stream {} (seq={})",
                task.taskId, ack.stream, ack.seqno,
            )
        } catch (ex: Exception) {
            log.error("Failed to publish AI task: {}", ex.message)
        }
    }

    fun isConnected(): Boolean = connection?.status == Connection.Status.CONNECTED
}
