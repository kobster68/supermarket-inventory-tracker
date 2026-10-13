#!/usr/bin/env bash
# Render every PlantUML source under diagrams/ to PDF for inclusion in the document.
#
# PlantUML emits SVG everywhere (no extra libraries), and rsvg-convert turns the SVG
# into a vector PDF. If rsvg-convert is unavailable we fall back to PlantUML's own
# PDF backend. Rendered PDFs/SVGs are build artifacts and are not committed.
#
# Include-only files (those without an @startuml line, such as diagrams/style.puml)
# are skipped on purpose: they are meant to be !included by real diagrams.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIAG_DIR="$REPO_ROOT/diagrams"

shopt -s nullglob
rendered=0
for puml in "$DIAG_DIR"/*.puml; do
  name="$(basename "$puml")"
  if ! grep -qE '^[[:space:]]*@startuml' "$puml"; then
    echo "    skip (include-only): $name"
    continue
  fi
  base="${puml%.puml}"
  echo "    render: $name"
  plantuml -tsvg "$puml"
  if [ ! -f "$base.svg" ]; then
    echo "ERROR: PlantUML produced no SVG for $name" >&2
    exit 1
  fi
  if command -v rsvg-convert >/dev/null 2>&1; then
    rsvg-convert -f pdf -o "$base.pdf" "$base.svg"
  elif plantuml -tpdf "$puml" >/dev/null 2>&1 && [ -f "$base.pdf" ]; then
    : # PlantUML produced the PDF directly
  else
    echo "ERROR: cannot produce a PDF for $name (install rsvg-convert / librsvg2-bin)" >&2
    exit 1
  fi
  if [ ! -f "$base.pdf" ]; then
    echo "ERROR: no PDF produced for $name" >&2
    exit 1
  fi
  rendered=$((rendered + 1))
done

echo "    rendered $rendered diagram(s)"
