"""
Eòlas preview sidecar — HTTP wrapper around headless LibreOffice.

## What it does

Accepts a POST /convert with a multipart file and returns the file
converted to PDF. Callers are the Kosha backend, never end-users directly.

## Why LibreOffice and not a library

The Python/Java ecosystem has several libraries for Office-to-PDF
conversion (docx2pdf on Windows, aspose commercial, various headless
WebKit hacks), but none are both open-source AND render well enough for
business documents. LibreOffice is the only free tool that handles the
full range of .docx quirks, embedded images, tables, headers/footers,
and CJK fonts correctly.

## Security posture

- Runs as a non-root user (set in the Dockerfile)
- No shell invocation — subprocess.run with a list argument
- Hard per-request timeout kills runaway conversions
- LibreOffice user profile pinned to /tmp so the container FS can be
  read-only (configured via docker-compose)
- No network egress expected — Kosha posts in, sidecar responds out

## Scaling notes

LibreOffice headless can't service concurrent conversions on a single
process — it serialises them at the UNO layer. Gunicorn's --workers=2
gives us two isolated LibreOffice instances, which is plenty for
interactive Kosha usage. Sites with batch conversion loads should run
multiple sidecar containers behind a load balancer.
"""

import logging
import os
import subprocess
import tempfile
import uuid
from pathlib import Path

from flask import Flask, jsonify, request, send_file

LOG_LEVEL = os.environ.get("EOLAS_PREVIEW_LOG_LEVEL", "INFO")
# Max file size we're willing to convert. 50MB is a balance between
# "real business document" (spec sheets with embedded images) and
# "someone uploaded a 2GB CAD file" (not our problem).
MAX_BYTES = int(os.environ.get("EOLAS_PREVIEW_MAX_BYTES", 50 * 1024 * 1024))
# Per-conversion hard timeout in seconds. LibreOffice sometimes hangs
# on malformed inputs; the timeout is the last line of defence.
CONVERT_TIMEOUT_S = int(os.environ.get("EOLAS_PREVIEW_TIMEOUT_S", 60))

logging.basicConfig(
    level=LOG_LEVEL,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
log = logging.getLogger("preview-sidecar")

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = MAX_BYTES


@app.get("/health")
def health():
    """Liveness probe. Compose wires this into its healthcheck."""
    return jsonify({"status": "ok"}), 200


@app.get("/version")
def version():
    """
    Returns the LibreOffice version string. Useful for debugging
    font/rendering issues that turn out to be version-specific.
    """
    try:
        result = subprocess.run(
            ["soffice", "--version"],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        return jsonify({
            "soffice": result.stdout.strip() or result.stderr.strip(),
        }), 200
    except Exception as ex:  # pragma: no cover
        return jsonify({"error": str(ex)}), 500


@app.post("/convert")
def convert():
    """
    Convert an uploaded document to PDF.

    Request: multipart/form-data with a single `file` field.
    Response: application/pdf body on success, JSON error on failure.

    The caller is responsible for passing a filename that LibreOffice
    can use to identify the input format. Content-Type is ignored —
    LibreOffice sniffs based on extension and magic bytes, not the
    client's claimed MIME.
    """
    if "file" not in request.files:
        return jsonify({"error": "missing 'file' form field"}), 400

    uploaded = request.files["file"]
    if not uploaded.filename:
        return jsonify({"error": "file has no filename"}), 400

    # Isolate each request in its own tempdir so concurrent workers
    # don't collide on output paths and LibreOffice's user profile
    # lock files behave sanely.
    with tempfile.TemporaryDirectory(prefix="eolas-preview-") as workdir:
        workpath = Path(workdir)
        # Sanitise the filename aggressively — LibreOffice is picky
        # about paths with spaces, quotes, or non-ASCII characters on
        # some locales. The original name is not preserved in the
        # output; the caller knows what it sent.
        safe_name = f"input-{uuid.uuid4().hex}{Path(uploaded.filename).suffix}"
        input_path = workpath / safe_name
        uploaded.save(input_path)

        log.info(
            "Converting %s (%d bytes, ext=%s)",
            uploaded.filename,
            input_path.stat().st_size,
            input_path.suffix,
        )

        # Run LibreOffice headless. --outdir is mandatory — without it
        # the converted file lands in $PWD which we don't control.
        # `soffice` and `libreoffice` are aliases; use `soffice` for
        # slightly better cross-distro consistency.
        try:
            result = subprocess.run(
                [
                    "soffice",
                    "--headless",
                    "--nologo",
                    "--nofirststartwizard",
                    "--convert-to",
                    "pdf",
                    "--outdir",
                    str(workpath),
                    str(input_path),
                ],
                capture_output=True,
                text=True,
                timeout=CONVERT_TIMEOUT_S,
                check=False,
            )
        except subprocess.TimeoutExpired:
            log.warning("Conversion timeout on %s", uploaded.filename)
            return jsonify({
                "error": "conversion timeout",
                "timeout_s": CONVERT_TIMEOUT_S,
            }), 504

        if result.returncode != 0:
            log.error(
                "LibreOffice exit %d on %s: stdout=%s stderr=%s",
                result.returncode,
                uploaded.filename,
                result.stdout.strip(),
                result.stderr.strip(),
            )
            return jsonify({
                "error": "conversion failed",
                "exit_code": result.returncode,
                "stderr": result.stderr.strip()[:1000],
            }), 422

        # LibreOffice writes the output as <stem>.pdf — find it rather
        # than guessing, because some locales append extra suffixes.
        pdfs = list(workpath.glob("*.pdf"))
        if not pdfs:
            log.error(
                "Conversion reported success but no PDF produced for %s",
                uploaded.filename,
            )
            return jsonify({"error": "no pdf produced"}), 500

        output_path = pdfs[0]
        log.info(
            "Converted %s → %s (%d bytes)",
            uploaded.filename,
            output_path.name,
            output_path.stat().st_size,
        )

        # send_file with as_attachment=False lets the caller inline-render.
        # Flask streams from disk so the 50MB buffer from MAX_CONTENT_LENGTH
        # doesn't compound into memory pressure on large PDFs.
        return send_file(
            output_path,
            mimetype="application/pdf",
            as_attachment=False,
            download_name=output_path.name,
        )


@app.errorhandler(413)
def too_large(_error):
    return jsonify({
        "error": "file too large",
        "max_bytes": MAX_BYTES,
    }), 413
