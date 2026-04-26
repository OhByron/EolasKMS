import asyncio
import json
import logging

import httpx
import nats
from nats.js.api import ConsumerConfig, DeliverPolicy

from config import settings
from handlers.task_handler import handle_ai_task
from handlers.feedback_handler import handle_feedback

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("kosha-ai")

STREAM_NAME = "KOSHA_AI"


async def fetch_ai_config():
    """Fetch AI config from the backend admin API and update local settings."""
    try:
        # Service-account client_credentials grant. URL is configurable so this works
        # both on a developer laptop (localhost:8180) and inside docker-compose
        # (keycloak:8080 via service-name DNS). Avoids impersonating the seed admin
        # user, whose password is rotated by the bootstrap step on first boot.
        keycloak_url = f"{settings.keycloak_url.rstrip('/')}/realms/{settings.keycloak_realm}/protocol/openid-connect/token"
        async with httpx.AsyncClient() as client:
            token_res = await client.post(
                keycloak_url,
                data={
                    "client_id": settings.backend_client_id,
                    "client_secret": settings.backend_client_secret,
                    "grant_type": "client_credentials",
                },
            )
            if token_res.status_code != 200:
                logger.warning("Could not get admin token: %s", token_res.status_code)
                return

            token = token_res.json().get("access_token", "")

            # Fetch AI config — the GET endpoint masks the key, but the backend
            # stores the real key in memory. We need the real key.
            # For now, read from the unmasked internal store via a special header.
            config_res = await client.get(
                f"{settings.kosha_api_url}/api/v1/admin/ai/config/internal",
                headers={"Authorization": f"Bearer {token}"},
            )
            if config_res.status_code != 200:
                logger.warning("Could not fetch AI config: %s", config_res.status_code)
                return

            data = config_res.json().get("data", {})
            provider = data.get("llmProvider", "")
            endpoint = data.get("llmEndpoint", "")
            model = data.get("llmModel", "")
            api_key = data.get("llmApiKey", "")
            num_ctx = data.get("llmNumCtx")

            if provider and provider != "ollama":
                settings.llm_provider = provider
                logger.info("Config: provider=%s", provider)
            if endpoint:
                settings.llm_endpoint = endpoint
            if model:
                settings.llm_model = model
            if isinstance(num_ctx, int) and num_ctx > 0:
                settings.llm_num_ctx = num_ctx
            if api_key and not api_key.startswith("****"):
                settings.llm_api_key = api_key
                logger.info("Config: API key loaded from admin config")
            else:
                logger.info("Config: API key masked or not set — using env variable")

    except Exception:
        logger.exception("Failed to fetch AI config from backend")


async def smoke_test_llm() -> None:
    """Probe the configured LLM at startup to catch silent misconfigurations.

    Specifically catches:
    - Thinking-mode regressions: models like gemma4 emit tokens into
      `message.thinking` and leave `message.content` empty unless `think: false`
      is set, which would silently produce blank summaries and unparseable JSON.
    - Missing/unpulled Ollama models, unreachable endpoints, bad API keys.

    The sidecar continues running on failure so the operator can fix the config
    via the admin UI without restarting infrastructure.
    """
    try:
        from langchain_core.messages import HumanMessage

        from providers.llm_provider import get_llm

        llm = get_llm()
        response = await llm.ainvoke([HumanMessage(content="Reply with the single word: ok")])
        content = (getattr(response, "content", "") or "").strip()
        if not content:
            logger.error(
                "MODEL_PROBE_FAILED: %s/%s returned empty content. "
                "If this is a thinking-mode model (e.g. gemma4), confirm the provider sends 'think: false'. "
                "AI tasks will produce empty results until this is resolved.",
                settings.llm_provider, settings.llm_model,
            )
        else:
            logger.info(
                "MODEL_PROBE_OK: %s/%s replied with %d chars",
                settings.llm_provider, settings.llm_model, len(content),
            )
    except Exception as exc:
        logger.error(
            "MODEL_PROBE_FAILED: %s/%s — %s",
            settings.llm_provider, settings.llm_model, exc,
        )


async def main():
    logger.info("Connecting to NATS at %s", settings.nats_url)
    nc = await nats.connect(settings.nats_url)
    js = nc.jetstream()

    # Ensure stream exists
    try:
        await js.find_stream_name_by_subject("ai.>")
    except nats.js.errors.NotFoundError:
        await js.add_stream(name=STREAM_NAME, subjects=["ai.>"])
        logger.info("Created JetStream stream: %s", STREAM_NAME)

    # Fetch config from backend admin API
    await fetch_ai_config()
    logger.info(
        "AI config: provider=%s model=%s num_ctx=%d key=%s",
        settings.llm_provider,
        settings.llm_model,
        settings.llm_num_ctx,
        "set" if settings.llm_api_key else "not set",
    )

    # Verify the model actually responds before subscribing to tasks
    await smoke_test_llm()

    # Subscribe to AI tasks (push-based, ephemeral consumer)
    task_sub = await js.subscribe(
        "ai.task.submitted",
        stream=STREAM_NAME,
    )

    # Subscribe to feedback corrections
    feedback_sub = await js.subscribe(
        "ai.feedback.correction",
        stream=STREAM_NAME,
    )

    logger.info("Kosha AI sidecar started, listening for tasks...")

    async def process_tasks():
        async for msg in task_sub.messages:
            try:
                payload = json.loads(msg.data.decode())
                logger.info(
                    "Received AI task: %s (type=%s)", payload.get("taskId"), payload.get("taskType")
                )
                await handle_ai_task(payload, js)
                await msg.ack()
                logger.info("Task %s completed", payload.get("taskId"))
            except Exception:
                logger.exception("Error processing AI task")
                await msg.nak()

    async def process_feedback():
        async for msg in feedback_sub.messages:
            try:
                payload = json.loads(msg.data.decode())
                logger.info("Received feedback correction: %s", payload.get("correction_type"))
                await handle_feedback(payload)
                await msg.ack()
            except Exception:
                logger.exception("Error processing feedback")
                await msg.nak()

    await asyncio.gather(process_tasks(), process_feedback())


if __name__ == "__main__":
    asyncio.run(main())
