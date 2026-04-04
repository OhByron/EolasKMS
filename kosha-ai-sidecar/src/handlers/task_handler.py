import json
import logging

from models.messages import AiTaskMessage

logger = logging.getLogger(__name__)


async def handle_ai_task(payload: dict, js) -> None:
    """Dispatch an AI task through the processing pipeline."""
    task = AiTaskMessage(**payload)

    if task.task_type in ("FULL_ANALYSIS", "SUMMARIZE"):
        summary_result = await _summarize(task)
        await js.publish("ai.summary.completed", json.dumps(summary_result).encode())

    if task.task_type in ("FULL_ANALYSIS", "EXTRACT_KEYWORDS"):
        keyword_result = await _extract_keywords(task)
        await js.publish("ai.keywords.extracted", json.dumps(keyword_result).encode())

    if task.task_type in ("FULL_ANALYSIS", "CLASSIFY"):
        classification_result = await _classify(task)
        await js.publish("ai.classification.completed", json.dumps(classification_result).encode())


async def _summarize(task: AiTaskMessage) -> dict:
    """Summarize document text using the configured LLM."""
    # TODO: implement with LangChain summarization chain
    logger.info("Summarizing document version %s", task.version_id)
    return {"version_id": str(task.version_id), "summary": "", "confidence": 0.0}


async def _extract_keywords(task: AiTaskMessage) -> dict:
    """Extract keywords using spaCy NER + LLM."""
    # TODO: implement with spaCy pipeline
    logger.info("Extracting keywords for version %s", task.version_id)
    return {"version_id": str(task.version_id), "keywords": []}


async def _classify(task: AiTaskMessage) -> dict:
    """Classify document against taxonomy using embeddings."""
    # TODO: implement with embedding similarity
    logger.info("Classifying document %s", task.document_id)
    return {"document_id": str(task.document_id), "classifications": []}
