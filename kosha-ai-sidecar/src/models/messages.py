from datetime import datetime
from uuid import UUID

from pydantic import BaseModel


class AiTaskMessage(BaseModel):
    task_id: UUID
    task_type: str  # FULL_ANALYSIS, SUMMARIZE, EXTRACT_KEYWORDS, CLASSIFY, OCR
    document_id: UUID
    version_id: UUID
    storage_key: str
    extracted_text: str | None = None
    mime_type: str
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


class ClassificationResult(BaseModel):
    document_id: UUID
    classifications: list["Classification"]


class Classification(BaseModel):
    term_id: UUID
    confidence: float


class RelationshipResult(BaseModel):
    document_id: UUID
    relationships: list["SuggestedRelationship"]


class SuggestedRelationship(BaseModel):
    target_document_id: UUID
    relationship_type: str  # RELATED_TO, SUPERSEDES, REFERENCES
    confidence: float


class FeedbackCorrection(BaseModel):
    correction_type: str  # SUMMARY, KEYWORD, CLASSIFICATION
    document_id: UUID
    version_id: UUID
    original: dict
    corrected: dict
    corrected_by: UUID
    corrected_at: datetime = datetime.now()
