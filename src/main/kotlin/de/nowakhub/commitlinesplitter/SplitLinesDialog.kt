package de.nowakhub.commitlinesplitter

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LineNumberConverter
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import java.awt.event.KeyEvent
import java.nio.charset.Charset
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import org.jetbrains.annotations.TestOnly

/**
 * Shows the file's diff in the commit as a unified view. Every changed line has a gutter checkbox:
 * ticked lines are extracted into the new commit placed right after the original commit.
 * Each hunk has a header row with a tri-state checkbox that toggles the whole hunk.
 */
class SplitLinesDialog(
  private val project: Project,
  private val input: FileSplitInput,
  private val charset: Charset,
  private val commitShortHash: String,
  originalMessage: String,
) : DialogWrapper(project, true) {

  private val model = input.model
  private val selected = linkedSetOf<Int>()

  /** One row of the editor document: either a hunk header or a diff line. */
  private sealed class Row {
    data class Header(val hunk: Int) : Row()
    data class Line(val index: Int) : Row()
  }

  private val rows = ArrayList<Row>()
  private val rowHighlighters = HashMap<Int, RangeHighlighter>()
  internal lateinit var editor: EditorEx

  private val newMessage = JBTextArea(5, 50).apply { lineWrap = true; wrapStyleWord = true }
  private val originalMessageArea = JBTextArea(originalMessage.trimEnd(), 5, 50).apply { lineWrap = true; wrapStyleWord = true }
  private val counter = JBLabel()

  init {
    title = "Split Lines to Separate Commit"
    setOKButtonText("Split Commit")
    init()
    updateCounter()
  }

  val extractedLines: Set<Int> get() = selected.toSet()
  val newCommitMessage: String get() = newMessage.text.trim()
  val originalCommitMessage: String get() = originalMessageArea.text.trim()

  override fun getDimensionServiceKey() = "de.nowakhub.commitlinesplitter.SplitLinesDialog"
  override fun getPreferredFocusedComponent(): JComponent = editor.contentComponent

  override fun createCenterPanel(): JComponent {
    editor = createEditor()

    val left = JPanel(BorderLayout()).apply {
      val header = JPanel(BorderLayout()).apply {
        add(JBLabel("<html>Tick the lines of <b>${StringUtil.escapeXmlEntities(input.path)}</b> that should move to a <b>new commit after $commitShortHash</b>. " +
                    "Unticked changes stay in the original commit.<br>Click a checkbox, or press <b>Space</b> to toggle the lines at the caret / selection.</html>"),
            BorderLayout.NORTH)
        add(createToolbar().component, BorderLayout.WEST)
        add(counter, BorderLayout.EAST)
        border = JBUI.Borders.emptyBottom(4)
      }
      add(header, BorderLayout.NORTH)
      add(editor.component, BorderLayout.CENTER)
    }

    val right = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
      add(labeled("New commit message (extracted lines):", newMessage), BorderLayout.NORTH)
      add(labeled("Original commit message (remaining changes):", originalMessageArea), BorderLayout.CENTER)
      border = JBUI.Borders.emptyLeft(8)
    }

    return Splitter(false, 0.68f).apply {
      firstComponent = left
      secondComponent = right
      preferredSize = JBUI.size(1100, 650)
    }
  }

  private fun labeled(label: String, area: JBTextArea) = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
    add(JBLabel(label), BorderLayout.NORTH)
    add(JBScrollPane(area), BorderLayout.CENTER)
  }

  // ---------------------------------------------------------------------------------------------------------------- editor

  private fun createEditor(): EditorEx {
    val text = StringBuilder()
    var hunkIdx = 0
    for ((i, line) in model.lines.withIndex()) {
      if (hunkIdx < model.hunks.size && model.hunks[hunkIdx].first() == i) {
        rows += Row.Header(hunkIdx)
        text.append(hunkHeaderText(hunkIdx)).append('\n')
        hunkIdx++
      }
      rows += Row.Line(i)
      val prefix = when (line.kind) { LineKind.CONTEXT -> "  "; LineKind.DELETED -> "- "; LineKind.ADDED -> "+ " }
      text.append(prefix).append(model.displayText(line, charset))
      if (!line.hasEol) text.append("   \\ No newline at end of file")
      text.append('\n')
    }

    val document = EditorFactory.getInstance().createDocument(text.toString().removeSuffix("\n"))
    document.setReadOnly(true)
    val ed = EditorFactory.getInstance().createViewer(document, project) as EditorEx
    ed.settings.apply {
      isLineNumbersShown = true
      isFoldingOutlineShown = false
      isLineMarkerAreaShown = true
      isCaretRowShown = true
      additionalLinesCount = 0
      additionalColumnsCount = 0
      isRightMarginShown = false
      isUseSoftWraps = false
    }
    ed.gutterComponentEx.setLineNumberConverter(lineConverter { it.baseLine }, lineConverter { it.targetLine })

    val scheme = EditorColorsManager.getInstance().globalScheme
    val markup = ed.markupModel
    for ((r, row) in rows.withIndex()) {
      val attrs: TextAttributes? = when (row) {
        is Row.Header -> TextAttributes(scheme.defaultForeground.darker(), null, null, null, Font.ITALIC)
        is Row.Line -> when (model.lines[row.index].kind) {
          LineKind.DELETED -> scheme.getAttributes(DiffColors.DIFF_DELETED)
          LineKind.ADDED -> scheme.getAttributes(DiffColors.DIFF_INSERTED)
          LineKind.CONTEXT -> null
        }
      }
      if (attrs != null) {
        markup.addLineHighlighter(r, HighlighterLayer.SELECTION - 1, TextAttributes().apply {
          backgroundColor = attrs.backgroundColor
          foregroundColor = attrs.foregroundColor
          fontType = attrs.fontType
        })
      }
      val isToggleable = row is Row.Header || (row is Row.Line && model.lines[row.index].isChange)
      if (isToggleable) {
        val start = document.getLineStartOffset(r)
        val end = document.getLineEndOffset(r)
        rowHighlighters[r] = markup.addRangeHighlighter(start, end, HighlighterLayer.SELECTION - 1, null, HighlighterTargetArea.LINES_IN_RANGE)
      }
    }
    refreshAllRows()

    // Space toggles lines at caret / selection
    object : DumbAwareAction() {
      override fun actionPerformed(e: AnActionEvent) = toggleAtCaret()
    }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)), ed.contentComponent, disposable)

    firstHunkRow()?.let { moveCaretTo(ed, it) }
    return ed
  }

  private fun lineConverter(number: (DiffLine) -> Int) = object : LineNumberConverter {
    override fun convert(editor: Editor, line: Int): Int? {
      val row = rows.getOrNull(line - 1) as? Row.Line ?: return null
      return number(model.lines[row.index]).takeIf { it >= 0 }?.plus(1)
    }
    override fun getMaxLineNumber(editor: Editor): Int = model.lines.size + 1
  }

  private fun hunkHeaderText(h: Int): String {
    val idx = model.hunks[h]
    val dels = idx.count { model.lines[it].kind == LineKind.DELETED }
    val adds = idx.count { model.lines[it].kind == LineKind.ADDED }
    return "@@ change ${h + 1} of ${model.hunks.size}: -$dels +$adds @@"
  }

  // ------------------------------------------------------------------------------------------------------------- selection

  /** Document row index of the hunk header for hunk [h] (for tests). */
  @TestOnly internal fun headerRowOf(h: Int): Int = rows.indexOf(Row.Header(h))

  /** Document row index showing diff line [lineIndex] (for tests). */
  @TestOnly internal fun rowOfLine(lineIndex: Int): Int = rows.indexOf(Row.Line(lineIndex))

  @TestOnly internal fun iconAtRow(r: Int): Icon? = rowHighlighters[r]?.gutterIconRenderer?.icon

  private fun hunkState(h: Int): Boolean? {
    val idx = model.hunks[h]
    val n = idx.count { it in selected }
    return when (n) { 0 -> false; idx.size -> true; else -> null }
  }

  private fun setSelected(lines: Collection<Int>, value: Boolean) {
    if (value) selected.addAll(lines) else selected.removeAll(lines.toSet())
    refreshAllRows()
    updateCounter()
  }

  internal fun toggleRow(r: Int) {
    when (val row = rows[r]) {
      is Row.Header -> setSelected(model.hunks[row.hunk], hunkState(row.hunk) != true)
      is Row.Line -> if (model.lines[row.index].isChange) setSelected(listOf(row.index), row.index !in selected)
    }
  }

  internal fun toggleAtCaret() {
    val sel = editor.selectionModel
    val doc = editor.document
    val startRow: Int
    val endRow: Int
    if (sel.hasSelection()) {
      startRow = doc.getLineNumber(sel.selectionStart)
      endRow = doc.getLineNumber(maxOf(sel.selectionStart, sel.selectionEnd - 1))
    }
    else {
      startRow = editor.caretModel.logicalPosition.line
      endRow = startRow
    }
    if (startRow == endRow) { toggleRow(startRow); return }
    val lines = (startRow..endRow).flatMap { r ->
      when (val row = rows[r]) {
        is Row.Header -> model.hunks[row.hunk]
        is Row.Line -> listOf(row.index).filter { model.lines[it].isChange }
      }
    }.distinct()
    if (lines.isEmpty()) return
    setSelected(lines, !lines.all { it in selected })
  }

  private fun refreshAllRows() {
    val scheme = EditorColorsManager.getInstance().globalScheme
    for ((r, h) in rowHighlighters) {
      val (icon, tooltip) = when (val row = rows[r]) {
        is Row.Header -> when (hunkState(row.hunk)) {
          true -> AllIcons.Diff.GutterCheckBoxSelected to "Whole change moves to the new commit (click to keep it in the original)"
          false -> AllIcons.Diff.GutterCheckBox to "Change stays in the original commit (click to move all its lines)"
          null -> AllIcons.Diff.GutterCheckBoxIndeterminate to "Change partially moves to the new commit"
        }
        is Row.Line -> if (row.index in selected)
          AllIcons.Diff.GutterCheckBoxSelected to "Moves to the new commit"
        else AllIcons.Diff.GutterCheckBox to "Stays in the original commit"
      }
      h.gutterIconRenderer = ToggleRenderer(r, icon, tooltip)
      if (rows[r] is Row.Line) {
        val moved = (rows[r] as Row.Line).index in selected
        (h as RangeHighlighterEx).setTextAttributes(if (moved) TextAttributes().apply {
          fontType = Font.BOLD
          effectType = com.intellij.openapi.editor.markup.EffectType.BOXED
          effectColor = scheme.defaultForeground
        } else null)
      }
    }
  }

  private inner class ToggleRenderer(private val row: Int, private val icon: Icon, private val tip: String) : GutterIconRenderer() {
    override fun getIcon(): Icon = icon
    override fun getTooltipText(): String = tip
    override fun isNavigateAction(): Boolean = true
    override fun getAlignment(): Alignment = Alignment.LEFT
    override fun getClickAction(): AnAction = object : DumbAwareAction() {
      override fun actionPerformed(e: AnActionEvent) = toggleRow(row)
    }
    override fun equals(other: Any?): Boolean = other is ToggleRenderer && other.row == row && other.icon == icon
    override fun hashCode(): Int = row * 31 + icon.hashCode()
  }

  private fun updateCounter() {
    val total = model.changedLineIndices().size
    counter.text = "${selected.size} of $total changed lines move to the new commit"
  }

  // --------------------------------------------------------------------------------------------------------------- toolbar

  private fun createToolbar(): ActionToolbar {
    val group = DefaultActionGroup(
      action("Select All Changes", AllIcons.Actions.Selectall) { setSelected(model.changedLineIndices(), true) },
      action("Unselect All Changes", AllIcons.Actions.Unselectall) { setSelected(model.changedLineIndices(), false) },
      action("Invert Selection", AllIcons.Actions.SwapPanels) {
        val all = model.changedLineIndices()
        val inverted = all.filter { it !in selected }
        selected.clear(); selected.addAll(inverted)
        refreshAllRows(); updateCounter()
      },
      action("Previous Change", AllIcons.Actions.PreviousOccurence) { jumpHunk(-1) },
      action("Next Change", AllIcons.Actions.NextOccurence) { jumpHunk(+1) },
    )
    return ActionManager.getInstance().createActionToolbar("SplitLinesDialog", group, true).apply {
      targetComponent = editor.contentComponent
    }
  }

  private fun action(text: String, icon: Icon, block: () -> Unit) = object : DumbAwareAction(text, null, icon) {
    override fun actionPerformed(e: AnActionEvent) = block()
  }

  private fun headerRows() = rows.indices.filter { rows[it] is Row.Header }
  private fun firstHunkRow() = headerRows().firstOrNull()

  private fun jumpHunk(direction: Int) {
    val caret = editor.caretModel.logicalPosition.line
    val headers = headerRows()
    val target = if (direction > 0) headers.firstOrNull { it > caret } else headers.lastOrNull { it < caret }
    target?.let { moveCaretTo(editor, it) }
  }

  private fun moveCaretTo(ed: EditorEx, row: Int) {
    ed.caretModel.moveToOffset(ed.document.getLineStartOffset(row))
    ed.scrollingModel.scrollToCaret(ScrollType.CENTER)
  }

  // ------------------------------------------------------------------------------------------------------------ validation

  override fun doValidate(): ValidationInfo? = when {
    selected.isEmpty() -> ValidationInfo("Select at least one changed line to move to the new commit")
    newCommitMessage.isBlank() -> ValidationInfo("Enter a message for the new commit", newMessage)
    originalCommitMessage.isBlank() -> ValidationInfo("The original commit message must not be empty", originalMessageArea)
    else -> null
  }

  @TestOnly internal fun validateForTest(): ValidationInfo? = doValidate()

  override fun dispose() {
    if (::editor.isInitialized) EditorFactory.getInstance().releaseEditor(editor)
    super.dispose()
  }
}
