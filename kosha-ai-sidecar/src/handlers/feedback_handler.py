import logging

from models.messages import FeedbackCorrection

logger = logging.getLogger(__name__)


async def handle_feedback(payload: dict) -> None:
    """Process editor corrections to improve future AI suggestions."""
    correction = FeedbackCorrection(**payload)
    logger.info(
        "Recording %s correction for document %s by user %s",
        correction.correction_type,
        correction.document_id,
        correction.corrected_by,
    )
    # TODO: store correction and update few-shot prompt examples
