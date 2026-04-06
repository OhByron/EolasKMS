-- ============================================================================
-- V029 — OCR storage key on document versions (Pass 5.1)
--
-- When the AI sidecar detects a scanned PDF (image-based, no text layer)
-- it runs OCR via ocrmypdf/Tesseract and stores the searchable PDF as a
-- derived artifact in MinIO under `ocr/{docId}/{versionId}.pdf`. This
-- column holds that key so the preview endpoint can prefer the OCR'd
-- version for rendering while the original untouched file stays at
-- `storage_key` for integrity and download.
--
-- Also tracks whether OCR was applied and the Tesseract language used,
-- so the UI can show an "OCR processed" badge and the admin can trigger
-- a reprocess with a different language if the first pass was wrong.
-- ============================================================================

ALTER TABLE doc.document_version
    ADD COLUMN ocr_storage_key VARCHAR(1000),
    ADD COLUMN ocr_applied BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN ocr_language VARCHAR(50);

COMMENT ON COLUMN doc.document_version.ocr_storage_key
    IS 'MinIO key of the OCR-processed PDF. NULL if OCR was not needed or has not run yet.';
COMMENT ON COLUMN doc.document_version.ocr_applied
    IS 'True if OCR has been successfully applied to this version.';
COMMENT ON COLUMN doc.document_version.ocr_language
    IS 'Tesseract language code used for OCR (e.g. eng, eng+deu). NULL if OCR not applied.';
