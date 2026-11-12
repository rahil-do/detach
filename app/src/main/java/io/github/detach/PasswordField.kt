package io.github.detach

import android.content.Context
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.EditText
import java.util.concurrent.Executors

/** Shows every typed character as '*', with no "last letter visible" moment. */
object Asterisks : PasswordTransformationMethod() {
    override fun getTransformation(source: CharSequence, view: View): CharSequence = Masked(source)

    private class Masked(private val src: CharSequence) : CharSequence {
        override val length: Int get() = src.length
        override fun get(index: Int): Char = '*'
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = "*".repeat(endIndex - startIndex)
        override fun toString(): String = "*".repeat(length)
    }
}

/**
 * A password input. Order matters: making a field single-line resets its text transformation,
 * which would show the password in plain text, so the mask is applied last.
 */
fun passwordEditText(context: Context, hint: String): EditText = EditText(context).apply {
    this.hint = hint
    isSingleLine = true
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    Ui(context).style(this)
    transformationMethod = Asterisks
    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
}

/** PBKDF2 takes a moment; run it off the UI thread so the screen doesn't freeze. */
object HashWorker {
    private val pool = Executors.newSingleThreadExecutor()
    fun <T> run(view: View, work: () -> T, done: (T) -> Unit) {
        pool.execute {
            val r = work()
            view.post { done(r) }
        }
    }
}
