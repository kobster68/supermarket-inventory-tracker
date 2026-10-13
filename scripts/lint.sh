#!/usr/bin/env bash
# Lint the architecture-document sources.
#
#   scripts/lint.sh           hygiene + element-name + diagram-render checks
#   scripts/lint.sh --main    the above, plus: reject any TODO / \todo marker in sections/
#
# The skeleton passes the default mode (its stubs still carry \todo markers). The
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
# when it scans this file. git grep exit codes are handled explicitly -- 0 means hits
# (fail), 1 means no hits (ok), and anything >= 2 is a git error that must fail loudly
# rather than pass silently.
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
hygiene_hits=$(git grep -niIE "${grep_args[@]}" -- . 2>&1)
hygiene_rc=$?
if [ "$hygiene_rc" -ge 2 ]; then
  # git grep could not run (for example exit 128 on "dubious ownership" when the
  # checkout is owned by another uid inside a container). Fall back to a recursive
  # grep over the tree with the build artifacts and .git excluded.
  echo "    note: git grep exited $hygiene_rc; falling back to a recursive grep" >&2
  hygiene_hits=$(grep -rniIE "${grep_args[@]}" . \
    --exclude-dir=.git \
    --exclude='*.pdf' --exclude='*.svg' --exclude='*.png' --exclude='*.eps' \
    --exclude='*.aux' --exclude='*.log' --exclude='*.out' --exclude='*.toc' \
    --exclude='*.lof' --exclude='*.lot' --exclude='*.fls' \
    --exclude='*.fdb_latexmk' --exclude='*.synctex.gz' 2>&1)
  hygiene_rc=$?
fi
if [ "$hygiene_rc" -ge 2 ]; then
  echo "LINT FAIL: could not run the hygiene check (exit $hygiene_rc):" >&2
  printf '%s\n' "$hygiene_hits" >&2
  status=1
elif [ "$hygiene_rc" -eq 0 ]; then
  echo "LINT FAIL: a tracked file references private planning material:" >&2
  printf '%s\n' "$hygiene_hits" >&2
  status=1
else
  echo "    ok"
fi

# (b) Element-name check. The list is derived from macros.tex: every \newelement
# whose printed name contains a space or a hyphen (the multi-word proper names). This
# way adding an element in macros.tex updates the lint automatically. Single-word
# names (Store, DC, Subscriber, Outbox, Acknowledgement) are left to human review.
# Case-sensitive, word-bounded, sections/ only, and the glossary excluded (it is the
# one place the names are spelled out in full, to define them).
echo "==> Element names: multi-word proper names must go through macros"
element_names=()
while IFS= read -r nm; do
  [ -n "$nm" ] && element_names+=("$nm")
done < <(grep -vE '^[[:space:]]*%' macros.tex \
         | grep -oE '\\newelement\{[^}]*\}\{[^}]*\}' \
         | sed -E 's/.*\}\{([^}]*)\}$/\1/' \
         | grep -E ' |-')
section_files=()
for f in sections/*.tex; do
  [ "$(basename "$f")" = "6-2-glossary.tex" ] && continue
  section_files+=("$f")
done
element_ok=1
for n in "${element_names[@]}"; do
  hits=$(grep -nE "\b${n}\b" "${section_files[@]}" 2>/dev/null || true)
  if [ -n "$hits" ]; then
    echo "LINT FAIL: hand-typed element name '${n}' in sections/ (use its macro):" >&2
    printf '%s\n' "$hits" >&2
    element_ok=0
    status=1
  fi
done
[ "$element_ok" -eq 1 ] && echo "    ok (checked ${#element_names[@]} names)"

# (c) --main only: no TODO or \todo markers left anywhere in sections/.
if [ "$MAIN" -eq 1 ]; then
  echo "==> --main: no TODO or \\todo markers in sections/"
  todo_hits=$(grep -niE 'TODO|\\todo\{' sections/*.tex 2>/dev/null || true)
  if [ -n "$todo_hits" ]; then
    echo "LINT FAIL: TODO marker(s) remain in sections/ (not allowed on main after integration):" >&2
    printf '%s\n' "$todo_hits" >&2
    status=1
  else
    echo "    ok"
  fi
fi

# (d) Every PlantUML source renders (syntax-checked). Include-only files are skipped.
echo "==> Diagrams: every PlantUML source renders"
if ! command -v plantuml >/dev/null 2>&1; then
  echo "LINT FAIL: plantuml is not installed; cannot check diagrams." >&2
  status=1
else
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
      status=1
    fi
  done
fi

if [ "$status" -ne 0 ]; then
  echo "lint: FAILED" >&2
  exit 1
fi
echo "lint: passed"
