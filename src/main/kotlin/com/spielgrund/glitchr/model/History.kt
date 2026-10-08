package com.spielgrund.glitchr.model

/**
 * The whole document at one point in time: canvas size, name and layers (bottom first).
 * Equality ignores the selection, so selecting another layer doesn't create an undo
 * step. Pictures are compared by identity, which is enough because pixels are never modified.
 */
data class DocState(
    val width: Int,
    val height: Int,
    val name: String,
    val layers: List<LayerMemento>,
    /** The animation's length and frame rate; null without animation. */
    val timeline: Timeline? = null,
) {
    var selectedId: Int? = null
}

/**
 * Undo/redo as a list of document states. Callers [commit] after an edit is finished
 * (or has been idle for a moment); states equal to the current one are ignored, so
 * committing too often is harmless.
 */
class History(private val limit: Int = 60) {
    private val states = mutableListOf<DocState>()
    private var index = -1

    val canUndo get() = index > 0
    val canRedo get() = index < states.size - 1

    /** Records [state] as the newest step, dropping any redo steps. Returns whether it was new. */
    fun commit(state: DocState): Boolean {
        val current = states.getOrNull(index)
        if (current == state) {
            current.selectedId = state.selectedId
            return false
        }
        while (states.size > index + 1) states.removeAt(states.size - 1)
        states.add(state)
        while (states.size > limit) states.removeAt(0)
        index = states.size - 1
        return true
    }

    fun undo(): DocState? = if (canUndo) states[--index] else null

    fun redo(): DocState? = if (canRedo) states[++index] else null

    fun clear() {
        states.clear()
        index = -1
    }
}
