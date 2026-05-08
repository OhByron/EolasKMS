#!/usr/bin/env bash
# Render every *.puml under docs/diagrams to SVG.
# Usage:  docs/diagrams/render.sh
# Requires: java on PATH. Downloads plantuml.jar to ~/.plantuml on first run.
set -euo pipefail

PLANTUML_VERSION="1.2024.7"
JAR_DIR="${HOME}/.plantuml"
JAR_PATH="${JAR_DIR}/plantuml.jar"
JAR_URL="https://github.com/plantuml/plantuml/releases/download/v${PLANTUML_VERSION}/plantuml-${PLANTUML_VERSION}.jar"

if [[ ! -f "${JAR_PATH}" ]]; then
  echo "Downloading PlantUML ${PLANTUML_VERSION} → ${JAR_PATH}"
  mkdir -p "${JAR_DIR}"
  curl -sSLf -o "${JAR_PATH}" "${JAR_URL}"
fi

DIAGRAMS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${DIAGRAMS_DIR}"

echo "Rendering $(ls -1 *.puml | wc -l) diagram(s) to SVG…"
java -jar "${JAR_PATH}" -tsvg -nbthread auto *.puml
echo "Done. SVGs are in ${DIAGRAMS_DIR}"
