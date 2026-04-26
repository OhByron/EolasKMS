# syntax=docker/dockerfile:1.7
FROM python:3.12-slim

# Tesseract + top-10 language data for OCR (Pass 5.1).
# ghostscript is required by ocrmypdf for PDF manipulation.
# Additional languages can be mounted at runtime via volume into
# /usr/share/tesseract-ocr/5/tessdata/
RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr \
    tesseract-ocr-eng \
    tesseract-ocr-deu \
    tesseract-ocr-fra \
    tesseract-ocr-spa \
    tesseract-ocr-ita \
    tesseract-ocr-por \
    tesseract-ocr-nld \
    tesseract-ocr-ara \
    tesseract-ocr-jpn \
    tesseract-ocr-chi-sim \
    ghostscript \
    libgl1 \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY pyproject.toml .
# Cache mount on /root/.cache/pip persists wheel downloads across
# rebuilds. Drop --no-cache-dir so pip actually populates the mounted
# cache; image size is unchanged because the cache lives on the host,
# not in the resulting layer.
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install .

# spaCy model for metadata_extractor (NER on amounts/dates/parties).
# Pinned at build time so the runtime container has no internet egress
# requirement and metadata extraction is deterministic across deploys.
RUN python -m spacy download en_core_web_sm

COPY src/ ./src/

ENV PYTHONPATH=/app/src
CMD ["python", "src/main.py"]
