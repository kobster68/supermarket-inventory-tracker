#!/usr/bin/env bash
# Build the Software Architecture Document to main.pdf.
#
# Steps: render the PlantUML diagrams, compile main.tex, and print the page count.
# latexmk is used when present (the CI image has it); otherwise we run pdflatex three
# times so the table of contents and cross-references settle. A clean checkout needs
# only a TeX installation with TeX Gyre Termes plus PlantUML, Graphviz and
# rsvg-convert on the PATH.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "==> Rendering diagrams"
"$REPO_ROOT/scripts/render-diagrams.sh"

echo "==> Compiling main.tex"
if command -v latexmk >/dev/null 2>&1; then
  latexmk -pdf -interaction=nonstopmode -halt-on-error main.tex
else
  echo "    latexmk not found; running pdflatex three times"
  # Pass 1 runs visibly so a LaTeX error is seen immediately; every pass dumps the
  # log tail and stops on failure, so set -e never hides an error behind /dev/null.
  pdflatex -interaction=nonstopmode -halt-on-error main.tex \
    || { echo "ERROR: pdflatex failed; last 40 lines of main.log:" >&2; tail -40 main.log >&2; exit 1; }
  for _ in 2 3; do
    pdflatex -interaction=nonstopmode -halt-on-error main.tex >/dev/null \
      || { echo "ERROR: pdflatex failed; last 40 lines of main.log:" >&2; tail -40 main.log >&2; exit 1; }
  done
fi

if [ ! -f main.pdf ]; then
  echo "ERROR: main.pdf was not produced" >&2
  exit 1
fi

echo "==> Page count"
if command -v pdfinfo >/dev/null 2>&1; then
  pdfinfo main.pdf | awk '/^Pages:/ {print "    main.pdf pages:", $2}'
elif command -v qpdf >/dev/null 2>&1; then
  echo "    main.pdf pages: $(qpdf --show-npages main.pdf)"
else
  echo "    main.pdf pages: (install poppler-utils or qpdf to count)"
fi

echo "==> Build complete: $REPO_ROOT/main.pdf"
