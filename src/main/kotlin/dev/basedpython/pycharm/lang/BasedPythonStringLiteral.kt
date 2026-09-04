package dev.basedpython.pycharm.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.LiteralTextEscaper
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.impl.source.tree.LeafElement

/**
 * A string literal, and the only composite node in a `.by` tree.
 *
 * It exists for one reason: [PsiLanguageInjectionHost]. Every route the platform has for putting a
 * fragment of another language inside a file starts by asking whether the element it is looking at
 * is a host — `MultiHostRegistrar.addPlace` takes one, IntelliLang's comment injector returns
 * before anything else if it is handed something that is not one, and so does the *Inject language
 * or reference* intention. A `.by` file used to be the file node and one leaf per token, so there
 * was nothing in it that could be a host and nothing could ever be injected, however the language
 * was marked.
 *
 * The rest of the tree is unchanged, and so is this literal's leaf: the `BY_STRING` token is still
 * there, still with the same element type, one child down. Everything that reads it — the escape
 * annotator, f-string interpolation, spell checking, the TODO index, the quote handler — works off
 * the leaf or off the lexer and does not see this node at all.
 *
 * See [dev.basedpython.pycharm.lsp.inject.BasedPythonLanguageInjector] for what decides the
 * language, which is not this class's business and not the plugin's: it is `by`'s answer to
 * `by/injections`.
 */
class BasedPythonStringLiteral(node: ASTNode) : ASTWrapperPsiElement(node), PsiLanguageInjectionHost {

    /** What kind of literal this is and where its content sits, or null if it does not parse. */
    val shape: StringLiteralShape? get() = StringLiteralShape.of(text)

    /**
     * The content, quotes and prefix excluded, relative to this element.
     *
     * An empty range at offset zero for a literal that does not parse — the caller that matters
     * ([isValidHost]) has already refused such a literal, and a range is easier to hand back than
     * a null every reader would have to answer for.
     */
    val contentRange: TextRange
        get() = shape?.let { TextRange(it.contentStart, it.contentEnd) } ?: TextRange.EMPTY_RANGE

    /**
     * Whether another language can be put inside this literal.
     *
     * Three kinds are refused, and each for a reason the injected editor would otherwise run into:
     *
     * - **f-strings.** The braces hold code, not text, so the literal is not one run of anything.
     *   A fragment written across the holes has no single range to map back to.
     * - **bytes.** `b"…"` is not text; there is nothing for a text editor to edit in it.
     * - **unterminated literals.** The state of every string halfway through being typed. Its
     *   content runs to the end of the line, so injecting means re-parsing the rest of the file as
     *   the other language on every keystroke, and un-injecting the moment the closing quote lands.
     *
     * `by` never reports a fragment in one of these either — the first two are different nodes in
     * its AST, and the third does not parse — so this agrees with the server rather than
     * second-guessing it.
     */
    override fun isValidHost(): Boolean {
        val shape = shape ?: return false
        return shape.isTerminated && !shape.isFString && !shape.isBytes
    }

    /**
     * Replaces the whole literal — prefix, quotes and all — with [text].
     *
     * Called by the platform when an edit inside the injected fragment is written back out, with
     * the text this element should now have. The node holds exactly one leaf, so the edit is that
     * leaf's; going through the AST rather than through [replace] keeps this element identity,
     * which is what the platform expects back.
     */
    override fun updateText(text: String): PsiLanguageInjectionHost {
        (node.firstChildNode as? LeafElement)?.replaceWithText(text)
        return this
    }

    override fun createLiteralTextEscaper(): LiteralTextEscaper<BasedPythonStringLiteral> =
        BasedPythonStringEscaper(this)

    override fun toString(): String = "BasedPythonStringLiteral"
}
