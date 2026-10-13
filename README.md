# Supermarket Inventory Tracking System --- Software Architecture Document

Source for the architecture document (SAD) produced by our four-person team for
**SWEN-755 Software Architecture** at the Rochester Institute of Technology. The
document is written in LaTeX, follows the SEI *Views and Beyond* template section for
section, and compiles to `main.pdf`. GitHub Actions compiles it on every push (any
branch) and every pull request and attaches the PDF so reviewers read the document,
not the diff.

Team: **Godson Umoren, Kobe Laprade, Leon Letournel, Mike Andrews.**

## What is here

```
main.tex              Document class, title block, and \input of every section in template order
macros.tex            One macro per architecture element and per protocol operation; the
                      qascenario table, the tactics-table row, the notapplicable environment, \todo
scenarios.tex         The frozen list of quality-attribute scenario IDs, with labels
references.bib        Structured source for the references (see "Bibliography" below)
sections/             One file per SEI section, numbered in template order
diagrams/             PlantUML sources: style.puml (shared style) + example-key.puml (the notation key)
scripts/              build.sh, render-diagrams.sh, lint.sh
.github/              CI workflow and the pull-request template
```

## Build it locally

### macOS

Install a TeX distribution with TeX Gyre Termes, plus PlantUML, Graphviz, and
`rsvg-convert`:

```bash
# Full TeX Live (includes latexmk and all packages used here):
brew install --cask mactex-no-gui
# ...or the smaller BasicTeX (build.sh falls back to pdflatex when latexmk is absent):
#   brew install --cask basictex

brew install plantuml graphviz librsvg
```

Then build:

```bash
scripts/build.sh
```

`build.sh` renders the diagrams, compiles `main.tex`, and prints the page count.
It uses `latexmk` when it is on the `PATH` and otherwise runs `pdflatex` a few times
so the table of contents and cross-references settle. The output is `main.pdf`.

Lint the sources with:

```bash
scripts/lint.sh          # the checks CI runs on every push and PR
scripts/lint.sh --main   # stricter: also fails if any TODO or \todo remains in sections/
```

### Fonts

The document uses **TeX Gyre Termes** (a Times-compatible font) through `pdflatex`.
No system fonts are used, so the build is identical on a laptop and in CI.

### Continuous integration

CI runs in the pinned container `texlive/texlive:TL2025-historic` and adds
`plantuml`, `graphviz`, `librsvg2-bin` (for `rsvg-convert`) and `poppler-utils` (for
`pdfinfo`) with `apt`. It then runs `scripts/build.sh` (which renders the diagrams,
compiles the document, prints the page count, and fails if `main.pdf` was not
produced) and `scripts/lint.sh`, and uploads `main.pdf` as an artifact. The
**Build SAD** check should be marked a required status check on pull requests in the
repository's branch-protection settings.

## Bibliography

The references are kept as a hand-maintained **MLA** works-cited list in
`sections/5-references.tex`, and the same entries are mirrored in `references.bib`.
We do not compile with biblatex: the MLA biblatex style (`biblatex-mla`) is not
present in the local TeX installations we build with, and depending on biblatex plus a
`biber`/`bibtex` backend would make the build differ between a laptop and the CI
container. The hand-maintained list keeps the build reproducible and never blocks it.
`references.bib` is ready for a later switch to biblatex once the pinned toolchain
provides `biblatex-mla` in both places. Cite the textbook with page numbers and the
course decks with slide numbers.

## Before submission

- [ ] Confirm the lecture **year/term** for the SWEN-755 slide decks on myCourses and
  add it to each deck entry in `sections/5-references.tex` (and `references.bib`).
- [ ] Confirm the due years, the submission format, and the dropbox on myCourses.
- [ ] Check the page count is within the 10--14 page target (CI prints it).
- [ ] Run `scripts/lint.sh --main` and resolve every remaining TODO before the final
  integration commit, then tag the submitted commit.

## Section ownership and page budget

The document is built as four slices in parallel against the shared contract. Each
slice is branched, reviewed by its ring reviewer, and squash-merged by its author once
CI is green. The review ring is Godson to Kobe to Leon to Mike and back to Godson.
**Leon** owns Slice 1 and **Mike** owns Slice 4; both confirm their Git, LaTeX and
PlantUML comfort at kickoff so a swap or pairing can be arranged if needed.

| Slice | Owner | Reviewer | Sections / files | Page budget |
|------|-------|----------|------------------|-------------|
| 1 | Leon Letournel | Mike Andrews | 1.4 stakeholders; 2.1.1 overview; 2.1.2 goals & context; 2.1.3 driving requirements (`1-4`, `2-1-1`, `2-1-2`, `2-1-3`) | §1 roadmap ~1.5; §2.1 ~2.5 |
| 2 | Godson Umoren | Kobe Laprade | 1.5 viewpoint; Section 3 intro & the C&C key view; Section 4 (`1-5`, `3-0`, `3-1-cc-view`, `4`) | C&C view packet ~4 |
| 3 | Kobe Laprade | Leon Letournel | The IEP interface specification (`3-1-iep-interface`) | interface spec ~2 |
| 4 | Mike Andrews | Godson Umoren | 2.2.1--2.2.3, 2.2.4 & 2.3 N/A; 1.7 N/A; 1.8; 6.1--6.3 (`2-2-*`, `2-3`, `1-7`, `1-8`, `6-1`, `6-2`, `6-3`) | §2.2 ~2; §4--§6 ~1.5 |

Step 0 (Godson) carries the boilerplate (1.2, 1.3, 1.6, the Section 3 intro,
references), the seeds (the revision table, the frozen scenario table, one worked
quality-attribute scenario, one worked tactics row, the glossary), and the build, lint
and CI setup. The first-draft page target is **10--14 pages**; CI prints the page count
on every build.

## Rules

- **Frozen contract.** `main.tex`, `macros.tex`, `scenarios.tex` and the list of
  section files change only through a small, separate pull request --- never on a
  slice branch. Add a new element name, operation name or scenario ID by its own PR
  before using it.
- **Branch and merge.** Branch `slice-N-<name>` from `main`. A slice merges when it has
  one approving review from its ring reviewer and green CI; the author squash-merges.
- **Names through macros.** Element names, operation names and scenario IDs are written
  only through their macros and labels, so a rename stays a one-line change and the
  glossary stays the single place the names are spelled out.
- **Diagrams.** Every diagram is a PlantUML source with a key and a caption that names
  the view type. No pasted images; diagrams are rendered by the build.
- **Citations.** Cite the textbook with page numbers and the course decks with slide
  numbers, and explain design choices in plain words.
- **Prose.** Write in the team's first-person plural and read it once for tone before
  opening the pull request.
- **Keep it clean.** Nothing in the repository refers to the team's private planning
  material; `scripts/lint.sh` enforces this on every build.
