package dev.kosha.app.health

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Custom health indicators for every external dependency Eòlas talks
 * to. Each one tries a lightweight probe (health endpoint or simple
 * connection check) and reports UP/DOWN to the `/actuator/health`
 * composite. This gives ops a single endpoint to check whether the
 * full stack is healthy.
 *
 * All probes have a hard 5-second timeout so a slow dependency
 * doesn't hang the health endpoint for every other consumer.
 */

@Component("minio")
class MinioHealthIndicator(
    @Value("\${kosha.storage.minio.endpoint}") private val endpoint: String,
) : HealthIndicator {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun health(): Health {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$endpoint/minio/health/live"))
                .timeout(Duration.ofSeconds(5))
                .GET().build()
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200) Health.up().withDetail("endpoint", endpoint).build()
            else Health.down().withDetail("status", res.statusCode()).build()
        } catch (ex: Exception) {
            Health.down(ex).withDetail("endpoint", endpoint).build()
        }
    }
}

@Component("keycloak")
class KeycloakHealthIndicator(
    @Value("\${kosha.keycloak.server-url}") private val serverUrl: String,
    @Value("\${kosha.keycloak.realm}") private val realm: String,
) : HealthIndicator {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun health(): Health {
        return try {
            // The realm's well-known endpoint is always public
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$serverUrl/realms/$realm/.well-known/openid-configuration"))
                .timeout(Duration.ofSeconds(5))
                .GET().build()
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200) Health.up().withDetail("realm", realm).build()
            else Health.down().withDetail("status", res.statusCode()).build()
        } catch (ex: Exception) {
            Health.down(ex).withDetail("serverUrl", serverUrl).build()
        }
    }
}

@Component("nats")
class NatsHealthIndicator(
    @Value("\${kosha.nats.url}") private val natsUrl: String,
) : HealthIndicator {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun health(): Health {
        return try {
            // NATS monitoring endpoint (8222 is the default monitoring port,
            // mapped in docker-compose)
            val monitorUrl = natsUrl
                .replace("nats://", "http://")
                .replace(":4222", ":8222")
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$monitorUrl/healthz"))
                .timeout(Duration.ofSeconds(5))
                .GET().build()
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200) Health.up().build()
            else Health.down().withDetail("status", res.statusCode()).build()
        } catch (ex: Exception) {
            Health.down(ex).withDetail("natsUrl", natsUrl).build()
        }
    }
}

@Component("opensearch")
class OpenSearchHealthIndicator(
    @Value("\${kosha.opensearch.url}") private val opensearchUrl: String,
) : HealthIndicator {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun health(): Health {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$opensearchUrl/_cluster/health"))
                .timeout(Duration.ofSeconds(5))
                .GET().build()
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200) {
                val status = if (res.body().contains("\"green\"")) "green"
                else if (res.body().contains("\"yellow\"")) "yellow"
                else "red"
                if (status == "red") Health.down().withDetail("cluster", status).build()
                else Health.up().withDetail("cluster", status).build()
            } else {
                Health.down().withDetail("status", res.statusCode()).build()
            }
        } catch (ex: Exception) {
            Health.down(ex).withDetail("url", opensearchUrl).build()
        }
    }
}

@Component("mail-gateway")
class MailGatewayHealthIndicator(
    @Value("\${spring.mail.host}") private val mailHost: String,
    @Value("\${spring.mail.port}") private val mailPort: Int,
) : HealthIndicator {
    override fun health(): Health {
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(mailHost, mailPort), 5000)
            socket.close()
            Health.up().withDetail("host", "$mailHost:$mailPort").build()
        } catch (ex: Exception) {
            Health.down(ex).withDetail("host", "$mailHost:$mailPort").build()
        }
    }
}

@Component("preview-sidecar")
class PreviewSidecarHealthIndicator(
    @Value("\${kosha.preview.sidecar.url:}") private val sidecarUrl: String,
) : HealthIndicator {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun health(): Health {
        if (sidecarUrl.isBlank()) {
            return Health.up().withDetail("status", "not configured (optional)").build()
        }
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$sidecarUrl/health"))
                .timeout(Duration.ofSeconds(5))
                .GET().build()
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200) Health.up().withDetail("url", sidecarUrl).build()
            else Health.down().withDetail("status", res.statusCode()).build()
        } catch (ex: Exception) {
            Health.down(ex).withDetail("url", sidecarUrl).build()
        }
    }
}
