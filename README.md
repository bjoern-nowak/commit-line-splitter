# Commit Line Splitter — IntelliJ IDEA plugin

Author: Björn Nowak (github@nowakhub.de) · Source: https://github.com/bjoern-nowak/commit-line-splitter · Issues: https://github.com/bjoern-nowak/commit-line-splitter/issues

A line-level companion to IntelliJ's built-in **Extract Selected Changes to Separate Commit...**.
The built-in action moves whole files. This plugin lets you pick **individual lines of one file**.

## Usage

1. Open **Git → Log** and select a commit on your current branch.
2. In the commit's changed-files panel, right-click **one** file and choose
   **Split Lines to Separate Commit...** (it appears right below the built-in extract action;
   in IDE versions before 2025.3, which don't have that action, it is the last menu entry).
3. In the dialog:
   - Each added (`+`) or removed (`-`) line has a checkbox in the gutter. **Ticked lines move to a new commit**
     that is placed directly **after** the original commit. Unticked lines stay in the original commit.
   - The `@@ change n of m @@` header rows toggle the whole change block (tri-state checkbox).
   - Press **Space** to toggle the line at the caret, or all lines in the selection.
   - Use the toolbar to select all, select none, invert, or jump to the previous or next change.
   - The gutter shows the old and new line numbers.
   - Enter a message for the new commit. You can also edit the original commit's message.
4. Click **Split Commit**. A notification confirms the split and offers **Undo**.

## How it works

The commit is split in the same way as the built-in action, using only git plumbing commands
(`cat-file`, `ls-tree`, `hash-object`, `commit-tree`, `update-ref`):

* **Original commit:** same tree, except that the file contains only the non-extracted changes.
* **New commit:** exactly the tree of the original commit, so it contains the extracted lines.
* **Later commits up to HEAD:** have unchanged trees and are only re-parented.

Because of this, **conflicts are impossible** and the **working tree and index are never touched**. It works even
with uncommitted changes. Authors and author dates are preserved byte-for-byte. The committer becomes you, as with a rebase.

## Compatibility

| IntelliJ IDEA version | Build | Status |
|---|---|---|
| 2024.1 and older | ≤ 241 | Not supported (cannot be installed) |
| 2024.2 – 2025.2 | 242 – 252 | Supported. Verified with the Plugin Verifier; the plugin's menu entry is the last one in the menu |
| 2025.3 – 2026.3 | 253 – 263 | Supported. Verified with the Plugin Verifier; the menu entry appears right below the built-in extract action |
| 2027.1 and newer | ≥ 271 | Not supported until an update raises `untilBuild` |

The Plugin Verifier is run against the latest release of every supported version and reports no compatibility problems and
no deprecated, experimental or internal API usage. The tests run on 2024.2 (the oldest supported version, which the plugin is
compiled against) and on 2026.1.

Other JetBrains IDEs with the bundled Git plugin (PyCharm, WebStorm, GoLand, …) may work, but they are not tested.

## Limitations

* Linear history only: there must be no merge commit between the selected commit and HEAD, and the commit itself must not be a merge commit.
* Renamed or moved files and binary files are not supported.
* If the commit was already pushed, you are warned, and you will need a force push afterwards.
* Rewritten commits are not GPG-signed (the same as the built-in in-memory editing).
* Undo works only as long as the branch has not moved since the split. Otherwise use `git reflog`.
  The entries are named `split-lines: ...`.

## Build

```
./gradlew buildPlugin      # -> build/distributions/commit-line-splitter-1.0.2.zip
./gradlew test             # unit, git-integration and headless-IDE tests (on IDEA 2024.2)
./gradlew test -PplatformType=IntellijIdea -PplatformVersion=2026.1.5   # same tests on a newer IDE
./gradlew verifyPlugin     # JetBrains Plugin Verifier against IDEA 2024.2 … 2026.3
./gradlew verifyPlugin -PverifyIdes=2026.1.5.1,2026.2.4                 # only some IDE versions
./gradlew runIde           # start a sandbox IDE with the plugin
```

To install: **Settings → Plugins → ⚙ → Install Plugin from Disk...** and choose the zip.

## License

Copyright 2026 Björn Nowak

Licensed under the [Apache License, Version 2.0](LICENSE).
