import asyncio
import json
import logging

import nats
from nats.js.api import ConsumerConfig, DeliverPolicy

from config import settings
from handlers.task_handler import handle_ai_task
from handlers.feedback_handler import handle_feedback

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("kosha-ai")

STREAM_NAME = "KOSHA_AI"
SUBJECTS = ["ai.task.submitted", "ai.feedback.correction"]


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

    # Subscribe to AI tasks
    task_sub = await js.subscribe(
        "ai.task.submitted",
        durable="kosha-ai-task-worker",
        config=ConsumerConfig(deliver_policy=DeliverPolicy.ALL),
    )

    # Subscribe to feedback corrections
    feedback_sub = await js.subscribe(
        "ai.feedback.correction",
        durable="kosha-ai-feedback-worker",
        config=ConsumerConfig(deliver_policy=DeliverPolicy.ALL),
    )

    logger.info("Kosha AI sidecar started, listening for tasks...")

    async def process_tasks():
        async for msg in task_sub.messages:
            try:
                payload = json.loads(msg.data.decode())
                logger.info("Received AI task: %s (type=%s)", payload.get("task_id"), payload.get("task_type"))
                results = await handle_ai_task(payload, js)
                await msg.ack()
                logger.info("Task %s completed", payload.get("task_id"))
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
