#!/usr/bin/env bash
# Lint the architecture-document sources.
#
#   scripts/lint.sh           hygiene + element-name + diagram-render checks
#   scripts/lint.sh --main    the above, plus: reject any TODO marker in sections/
#
# The skeleton passes the default mode (its stubs still carry TODO markers). The
# --main mode is the stricter check for the final integration, when no TODO marker
# may remain in sections/. CI runs the default mode so main stays green while the
# four slices are in progress.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

MAIN=0
[ "${1:-}" = "--main" ] && MAIN=1

status=0

# (a) Planning-hygiene check. The forbidden strings are assembled from fragments so
# this script does not itself contain any of them: the hygiene grep stays clean even
# when it scans this file.
echo "==> Hygiene: no references to private planning material"
hygiene_patterns=(
  "decision ""0"
  "gr""ill"
  "spec-""audit"
  "gr""ill-decisions"
  "swen-755/""project"
  "plan""ning/"
)
grep_args=()
for p in "${hygiene_patterns[@]}"; do grep_args+=(-e "$p"); done
if git grep -niIE "${grep_args[@]}" -- . >/tmp/_hygiene_hits 2>/dev/null; then
  echo "LINT FAIL: a tracked file references private planning material:" >&2
  cat /tmp/_hygiene_hits >&2
  status=1
else
  echo "    ok"
fi
rm -f /tmp/_hygiene_hits

# (b) Element-name check: the multi-word proper names must be written through their
# macros, not typed by hand. Case-sensitive, word-bounded, sections/ only, and the
# glossary excluded (it defines the terms in full).
echo "==> Element names: multi-word proper names must go through macros"
element_names=(
  "Communications Front-End"
  "Central Master Inventory"
  "Store Agent"
  "DC Agent"
  "Inventory Exchange Protocol"
  "Store-Local Inventory"
  "Shipping-Order Generation"
  "Supplier Gateway"
  "POS Adapter"
)
section_files=()
for f in sections/*.tex; do
  [ "$(basename "$f")" = "6-2-glossary.tex" ] && continue
  section_files+=("$f")
done
element_fail=0
for n in "${element_names[@]}"; do
  if grep -nE "\b${n}\b" "${section_files[@]}" >/tmp/_elem_hits 2>/dev/null; then
    echo "LINT FAIL: hand-typed element name '${n}' in sections/ (use its macro):" >&2
    cat /tmp/_elem_hits >&2
    element_fail=1
    status=1
  fi
done
rm -f /tmp/_elem_hits
[ "$element_fail" -eq 0 ] && echo "    ok"

# (c) --main only: no TODO markers left in sections/.
if [ "$MAIN" -eq 1 ]; then
  echo "==> --main: no \\todo markers in sections/"
  if grep -nF '\todo{' sections/*.tex >/tmp/_todo_hits 2>/dev/null; then
    echo "LINT FAIL: \\todo marker(s) remain in sections/ (not allowed on main after integration):" >&2
    cat /tmp/_todo_hits >&2
    status=1
  else
    echo "    ok"
  fi
  rm -f /tmp/_todo_hits
fi

# (d) Every PlantUML source renders (syntax-checked). Include-only files are skipped.
echo "==> Diagrams: every PlantUML source renders"
diagram_fail=0
shopt -s nullglob
for puml in diagrams/*.puml; do
  name="$(basename "$puml")"
  if ! grep -qE '^[[:space:]]*@startuml' "$puml"; then
    echo "    skip (include-only): $name"
    continue
  fi
  if plantuml -checkonly "$puml" >/dev/null 2>&1; then
    echo "    ok: $name"
  else
    echo "LINT FAIL: PlantUML source does not render: $puml" >&2
    diagram_fail=1
    status=1
  fi
done
[ "$diagram_fail" -eq 0 ] || true

if [ "$status" -ne 0 ]; then
  echo "lint: FAILED" >&2
  exit 1
fi
echo "lint: passed"
