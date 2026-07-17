package dev.basedpython.pycharm.docs

/**
 * Bundled descriptions for basedpython-specific keywords, modifiers and operators.
 *
 * Each entry holds a concise HTML body (used for Quick Documentation) and a
 * best-effort docs anchor (used for "External Documentation").
 */
internal data class DocEntry(
    /** Human-readable title shown as the heading. */
    val title: String,
    /** HTML body fragment (no surrounding &lt;html&gt; tags). */
    val html: String,
    /** Anchor slug appended to the docs base URL. */
    val anchor: String,
)

internal object BasedPythonDocEntries {

    const val DOCS_BASE: String = "https://basedpython.dev/docs/"

    /**
     * Keyed by the *lookup* form. Multi-word constructs are keyed both by the
     * leading keyword (e.g. `data`, `frozen`) and by their full phrase.
     */
    val ENTRIES: Map<String, DocEntry> = buildMap {
        put(
            "let", DocEntry(
                "let",
                "Declares an <b>immutable binding</b>. Once assigned, a <code>let</code> " +
                    "variable cannot be reassigned.<br/><pre>let x = 42</pre>",
                "bindings#let",
            )
        )
        put(
            "newtype", DocEntry(
                "newtype",
                "Defines a distinct <b>nominal type</b> that wraps an existing type without " +
                    "runtime overhead. Useful for type-safe identifiers." +
                    "<br/><pre>newtype UserId = int</pre>",
                "types#newtype",
            )
        )
        put(
            "protocol", DocEntry(
                "protocol",
                "Declares a <b>structural interface</b>. Any value providing the required " +
                    "members satisfies the protocol (duck typing, statically checked)." +
                    "<br/><pre>protocol Drawable:\n    def draw() -> None</pre>",
                "types#protocol",
            )
        )
        put(
            "data", DocEntry(
                "data class",
                "A <b>data class</b> auto-generates <code>__init__</code>, <code>__eq__</code>, " +
                    "<code>__repr__</code> and <code>__hash__</code> from its fields." +
                    "<br/><pre>data class Point:\n    x: int\n    y: int</pre>",
                "classes#data-class",
            )
        )
        put(
            "data class", DocEntry(
                "data class",
                "A <b>data class</b> auto-generates <code>__init__</code>, <code>__eq__</code>, " +
                    "<code>__repr__</code> and <code>__hash__</code> from its fields." +
                    "<br/><pre>data class Point:\n    x: int\n    y: int</pre>",
                "classes#data-class",
            )
        )
        put(
            "frozen", DocEntry(
                "frozen data class",
                "A <b>frozen data class</b> is an immutable data class: its fields cannot be " +
                    "reassigned after construction, making instances hashable by value." +
                    "<br/><pre>frozen data class Point:\n    x: int\n    y: int</pre>",
                "classes#frozen-data-class",
            )
        )
        put(
            "frozen data class", DocEntry(
                "frozen data class",
                "A <b>frozen data class</b> is an immutable data class: its fields cannot be " +
                    "reassigned after construction, making instances hashable by value." +
                    "<br/><pre>frozen data class Point:\n    x: int\n    y: int</pre>",
                "classes#frozen-data-class",
            )
        )
        put(
            "enum", DocEntry(
                "enum class",
                "An <b>enum class</b> defines a fixed set of named constant members." +
                    "<br/><pre>enum class Color:\n    RED\n    GREEN\n    BLUE</pre>",
                "classes#enum-class",
            )
        )
        put(
            "enum class", DocEntry(
                "enum class",
                "An <b>enum class</b> defines a fixed set of named constant members." +
                    "<br/><pre>enum class Color:\n    RED\n    GREEN\n    BLUE</pre>",
                "classes#enum-class",
            )
        )
        put(
            "class", DocEntry(
                "class def",
                "Declares a <b>class</b>. In basedpython, member modifiers such as " +
                    "<code>public</code>, <code>private</code>, <code>final</code>, " +
                    "<code>abstract</code>, <code>static</code> and <code>override</code> are " +
                    "supported.<br/><pre>class def Widget:\n    public name: str</pre>",
                "classes#class-def",
            )
        )
        put(
            "class def", DocEntry(
                "class def",
                "Declares a <b>class</b>. In basedpython, member modifiers such as " +
                    "<code>public</code>, <code>private</code>, <code>final</code>, " +
                    "<code>abstract</code>, <code>static</code> and <code>override</code> are " +
                    "supported.<br/><pre>class def Widget:\n    public name: str</pre>",
                "classes#class-def",
            )
        )
        put(
            "override", DocEntry(
                "override",
                "Marks a method that <b>overrides</b> a member of a base class or protocol. " +
                    "The compiler verifies that a matching member exists.",
                "modifiers#override",
            )
        )
        put(
            "abstract", DocEntry(
                "abstract",
                "Marks a class or method as <b>abstract</b>. Abstract members have no " +
                    "implementation and must be overridden by concrete subclasses.",
                "modifiers#abstract",
            )
        )
        put(
            "final", DocEntry(
                "final",
                "Marks a class or member as <b>final</b>: it cannot be subclassed or overridden.",
                "modifiers#final",
            )
        )
        put(
            "static", DocEntry(
                "static",
                "Marks a member as <b>static</b>: it belongs to the class itself rather than " +
                    "to instances, and takes no implicit <code>self</code>.",
                "modifiers#static",
            )
        )
        put(
            "public", DocEntry(
                "public",
                "Visibility modifier marking a member as <b>public</b> (accessible from " +
                    "anywhere). This is the default for declared members.",
                "modifiers#public",
            )
        )
        put(
            "private", DocEntry(
                "private",
                "Visibility modifier marking a member as <b>private</b>: it is accessible only " +
                    "from within the declaring class.",
                "modifiers#private",
            )
        )
        put(
            "?.", DocEntry(
                "?. (null-safe access)",
                "The <b>null-safe access</b> operator. Evaluates to <code>None</code> if the " +
                    "left operand is <code>None</code>, otherwise accesses the member." +
                    "<br/><pre>user?.name</pre>",
                "operators#null-safe-access",
            )
        )
        put(
            "??", DocEntry(
                "?? (null-coalescing)",
                "The <b>null-coalescing</b> operator. Returns the right operand when the left " +
                    "operand is <code>None</code>.<br/><pre>name ?? \"anonymous\"</pre>",
                "operators#null-coalescing",
            )
        )
    }
}
