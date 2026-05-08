import logging

from models.messages import FeedbackCorrection

logger = logging.getLogger(__name__)


async def handle_feedback(payload: dict) -> None:
    """Process editor corrections to improve future AI suggestions.

    NOT IMPLEMENTED — this is a stub that loudly refuses to ack messages
    so the JetStream subscription nak's them and they redeliver. Once
    feedback storage and few-shot prompt updates are wired up, replace
    the NotImplementedError with the real handler. Until then, messages
    accumulate in the stream rather than being silently dropped.
    """
    correction = FeedbackCorrection(**payload)
    logger.warning(
        "Feedback handling not implemented — %s correction for document %s "
        "by user %s will redeliver until the handler is wired up.",
        correction.correction_type,
        correction.document_id,
        correction.corrected_by,
    )
    raise NotImplementedError(
        "feedback_handler is a stub; remove this raise once feedback storage exists"
    )
