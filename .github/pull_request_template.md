# Summary

<!-- What does this PR add? Which slice/issue and which sections? -->

Closes #

## Done checklist

Tick each item before requesting review. Your ring reviewer ticks them again on the PR.

- [ ] **Build is green.** CI compiled the document; I looked at the attached `main.pdf`.
- [ ] **I read the PDF in context**, not just the diff, and my section reads correctly in place.
- [ ] **Element and subscriber names go through their macros** in `macros.tex` (no hand-typed element names in `sections/`, except the glossary).
- [ ] **Scenario references use the IDs and labels from `scenarios.tex`** (`\scnref{...}`); I did not invent a new ID.
- [ ] **Every diagram is a PlantUML source** with a key and a caption that names the view type (no pasted images).
- [ ] **I read the prose once for tone** (first-person plural for the team) before opening this PR.
- [ ] **Every citation has a textbook page number or a deck slide number.**
- [ ] I did not change the frozen contract (`main.tex`, `macros.tex`, `scenarios.tex`, or the list of section files) on this slice branch; any such change is its own small PR.
