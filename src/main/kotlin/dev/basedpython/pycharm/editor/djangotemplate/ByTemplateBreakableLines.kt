package dev.basedpython.pycharm.editor.djangotemplate

/**
 * Which lines of a django template a line breakpoint can go on: the ones a template tag or a
 * variable starts on, `{%` or `{{`.
 *
 * bpd stops on a template line when django is about to render a node that begins there, and a line
 * of nothing but markup is one django renders as part of a text node, which bpd cannot watch — a
 * breakpoint asked for there moves to the next line that has a node (bpd's
 * `docs/development/django-templates.md`). Refusing such a line in the gutter keeps the breakpoint
 * where the user put it rather than where bpd moves it. Lexical, like [dev.basedpython.pycharm.debug.ByBreakableLines]
 * and for its reason: the gutter asks synchronously, on the EDT. Which of the remaining lines bpd can
 * actually bind — an `{% endfor %}` is part of its `{% for %}`, a line under an `{% extends %}` and
 * outside every block never renders — is bpd's to say, and it says so on the breakpoint.
 */
internal object ByTemplateBreakableLines {

    fun holdsNode(line: CharSequence): Boolean = line.contains("{%") || line.contains("{{")
}
