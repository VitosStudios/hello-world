package ch.vitosstudios.tippli.core

import ch.vitosstudios.tippli.core.ai.AiClient
import ch.vitosstudios.tippli.core.ai.AiEndpoint
import ch.vitosstudios.tippli.core.ai.AiTask
import ch.vitosstudios.tippli.core.keyboard.EditorHost
import ch.vitosstudios.tippli.core.keyboard.EnterAction
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.max
import kotlin.math.min

/** An in-memory text field with a caret and selection [cursor, selectionEnd). */
class FakeEditor(initial: String = "") : EditorHost {
    val text = StringBuilder(initial)
    var cursor = initial.length
    var selectionEnd = initial.length
    var batches = 0
    var backspaceKeys = 0
    val editorActions = mutableListOf<EnterAction>()
    val log = mutableListOf<String>()

    val value: String get() = text.toString()

    override fun textBeforeCursor(maxChars: Int): String = text.substring(max(0, cursor - maxChars), cursor)
    override fun textAfterCursor(maxChars: Int): String = text.substring(selectionEnd, min(text.length, selectionEnd + maxChars))
    override fun selectedText(): String = text.substring(cursor, selectionEnd)

    override fun delete(text: String) {
        check(textBeforeCursor(text.length) == text) { "delete('$text') but before cursor is '${textBeforeCursor(text.length)}'" }
        this.text.delete(cursor - text.length, cursor)
        cursor -= text.length
        selectionEnd = cursor
        log += "delete:$text"
    }

    override fun insert(text: String) {
        this.text.replace(cursor, selectionEnd, text)
        cursor += text.length
        selectionEnd = cursor
        log += "insert:$text"
    }

    override fun batch(block: () -> Unit) {
        batches++
        block()
    }

    override fun deleteSelection() {
        text.delete(cursor, selectionEnd)
        selectionEnd = cursor
    }

    override fun sendBackspaceKey() {
        backspaceKeys++
    }

    override fun moveCursor(delta: Int) {
        cursor = (cursor + delta).coerceIn(0, text.length)
        selectionEnd = cursor
    }

    override fun performEditorAction(action: EnterAction) {
        editorActions += action
    }

    override fun insertNewline(): Boolean {
        insert("\n")
        return true
    }

    fun select(start: Int, end: Int) {
        cursor = start
        selectionEnd = end
    }
}

/** A scripted AI client: every call waits for [Call.reply] unless [autoReply] answers it. */
class FakeAi(override val endpoint: AiEndpoint) : AiClient {
    class Call(val task: AiTask, val system: String, val user: String) {
        val reply = CompletableDeferred<String>()
        val segment: String get() = user.substringAfter("<segment>").substringBefore("</segment>")
        val context: String get() = user.substringAfter("<context>").substringBefore("</context>")
    }

    val calls = mutableListOf<Call>()
    var autoReply: ((Call) -> String)? = null

    val corrections get() = calls.filter { it.task == AiTask.CORRECTION }
    val pending get() = calls.filter { !it.reply.isCompleted }

    override suspend fun complete(task: AiTask, system: String, user: String): String {
        val call = Call(task, system, user)
        calls += call
        autoReply?.let { call.reply.complete(it(call)) }
        return call.reply.await()
    }

    override suspend fun listModels(): List<String> = emptyList()
}
