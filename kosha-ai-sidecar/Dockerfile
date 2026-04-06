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
RUN pip install --no-cache-dir .

COPY src/ ./src/

ENV PYTHONPATH=/app/src
CMD ["python", "src/main.py"]
