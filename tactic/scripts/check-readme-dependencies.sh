#!/usr/bin/env bash
#
# check-readme-dependencies.sh -- the README's library list matches the Maven build.
#
# Collects every groupId:artifactId:version pinned in a <dependency> or <plugin> of any
# pom.xml under tactic/, and every row of the libraries table in the README's Dependencies
# section (rows whose first three cells are `group` | `artifact` | `version`). Fails, naming
# each difference, unless the two lists are equal. Our own modules (version
# ${project.version}) are not libraries and are skipped.
#
# Runs in CI after the build; works on macOS, Linux and Git Bash.
set -uo pipefail

TACTIC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
README="${TACTIC_DIR}/README.md"

from_poms() {
  find "${TACTIC_DIR}" -name pom.xml -not -path '*/target/*' -print0 \
    | xargs -0 awk '
        /<dependency>|<plugin>/  { inblock = 1; g = ""; a = ""; v = ""; next }
        inblock && /<groupId>/    { g = $0; gsub(/.*<groupId>|<\/groupId>.*/, "", g) }
        inblock && /<artifactId>/ { a = $0; gsub(/.*<artifactId>|<\/artifactId>.*/, "", a) }
        inblock && /<version>/    { v = $0; gsub(/.*<version>|<\/version>.*/, "", v) }
        /<\/dependency>|<\/plugin>/ {
          if (inblock && v != "" && v !~ /^\$\{/) print g ":" a ":" v
          inblock = 0
        }' \
    | sort -u
}

from_readme() {
  awk '
    /^## / { in_deps = ($0 ~ /^## Dependencies/) ; next }
    in_deps && /^\| *`[^`]+` *\| *`[^`]+` *\| *`[^`]+` *\|/ {
      split($0, cell, "|")
      for (i = 2; i <= 4; i++) { gsub(/[ `]/, "", cell[i]) }
      print cell[2] ":" cell[3] ":" cell[4]
    }' "${README}" | sort -u
}

poms="$(from_poms)"
readme="$(from_readme)"

if [ -z "${poms}" ]; then
  echo "FAIL: found no pinned versions in the pom files under ${TACTIC_DIR}" >&2
  exit 1
fi

missing="$(comm -23 <(printf '%s\n' "${poms}") <(printf '%s\n' "${readme}"))"
stale="$(comm -13 <(printf '%s\n' "${poms}") <(printf '%s\n' "${readme}"))"

status=0
if [ -n "${missing}" ]; then
  echo "FAIL: pinned in a pom.xml but not listed in README.md Dependencies:" >&2
  printf '    %s\n' ${missing} >&2
  status=1
fi
if [ -n "${stale}" ]; then
  echo "FAIL: listed in README.md Dependencies but not pinned in any pom.xml:" >&2
  printf '    %s\n' ${stale} >&2
  status=1
fi
if [ "${status}" -eq 0 ]; then
  echo "README dependencies match the Maven build ($(printf '%s\n' "${poms}" | grep -c .) entries)."
fi
exit "${status}"
