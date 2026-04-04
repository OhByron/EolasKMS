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
        # Get an admin token from Keycloak
        keycloak_url = "http://localhost:8180/realms/kosha/protocol/openid-connect/token"
        async with httpx.AsyncClient() as client:
            token_res = await client.post(
                keycloak_url,
                data={
                    "client_id": "kosha-web",
                    "username": "admin@kosha.dev",
                    "password": "admin",
                    "grant_type": "password",
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

            if provider and provider != "ollama":
                settings.llm_provider = provider
                logger.info("Config: provider=%s", provider)
            if endpoint:
                settings.llm_endpoint = endpoint
            if model:
                settings.llm_model = model
            if api_key and not api_key.startswith("****"):
                settings.llm_api_key = api_key
                logger.info("Config: API key loaded from admin config")
            else:
                logger.info("Config: API key masked or not set — using env variable")

    except Exception:
        logger.exception("Failed to fetch AI config from backend")


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
        "AI config: provider=%s model=%s key=%s",
        settings.llm_provider,
        settings.llm_model,
        "set" if settings.llm_api_key else "not set",
    )

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
