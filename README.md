# Commit Line Splitter — IntelliJ IDEA 2026.x plugin

Author: Björn Nowak (github@nowakhub.de)

A line-level companion to IntelliJ's built-in **Extract Selected Changes to Separate Commit...**.
The built-in action moves whole files. This plugin lets you pick **individual lines of one file**.

## Usage

1. Open **Git → Log** and select a commit on your current branch.
2. In the commit's changed-files panel, right-click **one** file and choose
   **Split Lines to Separate Commit...** (it appears right below the built-in extract action).
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
| 2025.3 and older | ≤ 253 | Not supported (cannot be installed) |
| 2026.1 | 261 | Supported. Verified on 2026.1.5 with the Plugin Verifier and the test suite |
| 2026.2 | 262 | Supported. Verified on 2026.2.4 with the Plugin Verifier |
| 2026.3 | 263 | Allowed, but not verified yet |
| 2027.1 and newer | ≥ 271 | Not supported until an update raises `untilBuild` |

Other JetBrains IDEs 2026.x with the bundled Git plugin (PyCharm, WebStorm, GoLand, …) may work, but they are not tested.

## Limitations

* Linear history only: there must be no merge commit between the selected commit and HEAD, and the commit itself must not be a merge commit.
* Renamed or moved files and binary files are not supported.
* If the commit was already pushed, you are warned, and you will need a force push afterwards.
* Rewritten commits are not GPG-signed (the same as the built-in in-memory editing).
* Undo works only as long as the branch has not moved since the split. Otherwise use `git reflog`.
  The entries are named `split-lines: ...`.

## Build

```
./gradlew buildPlugin      # -> build/distributions/commit-line-splitter-1.0.1.zip
./gradlew test             # unit, git-integration and headless-IDE tests
./gradlew verifyPlugin     # JetBrains Plugin Verifier against IDEA 2026.1.5 and 2026.2.4
./gradlew runIde           # start a sandbox IDE with the plugin
```

To install: **Settings → Plugins → ⚙ → Install Plugin from Disk...** and choose the zip.

## License

Copyright 2026 Björn Nowak

Licensed under the [Apache License, Version 2.0](LICENSE).
