from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    """Base model that accepts both camelCase and snake_case field names."""
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
    )


class AiTaskMessage(CamelModel):
    task_id: str
    task_type: str  # FULL_ANALYSIS, SUMMARIZE, EXTRACT_KEYWORDS, CLASSIFY
    document_id: str
    version_id: str
    storage_key: str | None = None
    extracted_text: str | None = None
    mime_type: str | None = None
    config: dict | None = None


class SummaryResult(BaseModel):
    version_id: UUID
    summary: str
    confidence: float


class KeywordResult(BaseModel):
    version_id: UUID
    keywords: list["ExtractedKeyword"]


class ExtractedKeyword(BaseModel):
    keyword: str
    frequency: int
    confidence: float


class FeedbackCorrection(BaseModel):
    correction_type: str
    document_id: UUID
    version_id: UUID
    original: dict
    corrected: dict
    corrected_by: UUID
    corrected_at: datetime = Field(default_factory=datetime.now)
