# ============================================================================
# Preview sidecar — headless LibreOffice over a thin HTTP wrapper
#
# Turns Office documents (.docx, .xlsx, .pptx, .odt, .ods, .odp, etc.) into
# PDFs so the Kosha frontend can render them via pdf.js. Runs as an opt-in
# docker-compose profile (`--profile office-preview`) because LibreOffice is
# ~500MB that sites without Office workflows don't need.
#
# Security posture:
#   - Runs as non-root user `preview` (uid 1000)
#   - No shell needed at runtime — Python is the only entry point
#   - Conversion runs with a hard per-request timeout (killed via
#     subprocess.run(..., timeout=...)) to prevent runaway inputs
#   - /tmp is the only writable path; everything else is read-only at
#     runtime (enforced by compose `read_only: true`)
#   - No network egress expected — compose network isolation
# ============================================================================

FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

# LibreOffice headless + Python runtime + fonts. The fonts-* packages
# matter — without them Asian/RTL scripts and common Windows fonts
# (Calibri substitution, etc.) render as boxes or swap to sans fallbacks,
# which ruins previews of real-world corporate documents.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libreoffice \
        libreoffice-writer \
        libreoffice-calc \
        libreoffice-impress \
        python3 \
        python3-pip \
        python3-venv \
        fonts-dejavu \
        fonts-liberation \
        fonts-noto-core \
        fonts-noto-cjk \
        curl \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* /tmp/*

# Non-root user for runtime
RUN useradd --create-home --shell /bin/bash --uid 1000 preview

WORKDIR /app
COPY --chown=preview:preview src/ /app/

# Install flask into a venv to avoid the PEP 668 system-python lockdown
RUN python3 -m venv /opt/venv && \
    /opt/venv/bin/pip install --no-cache-dir flask==3.0.* gunicorn==22.0.*
ENV PATH="/opt/venv/bin:$PATH"

USER preview

# LibreOffice writes a user profile the first time it runs. Point it at a
# writable location under /tmp so we don't need a writable /home/preview
# when the container is run read-only.
ENV HOME=/tmp
ENV UNO_PATH=/usr/lib/libreoffice/program

EXPOSE 9100

# Two workers is plenty — conversions are CPU-heavy and parallelising
# beyond the core count just causes thrashing. Sites that need more can
# scale horizontally by running multiple sidecar containers.
CMD ["gunicorn", \
     "--bind", "0.0.0.0:9100", \
     "--workers", "2", \
     "--timeout", "60", \
     "--access-logfile", "-", \
     "server:app"]
