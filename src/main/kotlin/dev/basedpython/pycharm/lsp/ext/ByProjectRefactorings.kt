package dev.basedpython.pycharm.lsp.ext

import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit

// The shapes of `by`'s refactorings that edit the whole project and take a choice of the user's
// first. Their contracts are `by`'s `docs/basedpython/features/editor.md`, *refactorings*.
//
// The requests' parameters are read by `by` with unknown fields refused, so each carries exactly
// what the contract names and no `textHash`: these are answered about the documents as the server
// has them when asked.

/** A position in a document, and nothing else. */
data class ByPositionParams(val textDocument: TextDocumentIdentifier, val position: Position)

/**
 * What one of the project-wide refactorings answered: the edit and the references it leaves as they
 * were, or why nothing is changed.
 */
data class ByProjectEditAnswer(
    /** `changes` to every file the refactoring edits; absent when it is refused. */
    val edit: WorkspaceEdit? = null,
    /** Why nothing is changed; absent when it is not refused. */
    val refused: String? = null,
    /** What the edit leaves as it was that may no longer mean what it did, each with its file and line. */
    val warnings: List<String>? = null,
)

/** The definition F6 would move, from `by/prepareMoveSymbol`. */
data class ByMovableSymbol(
    val name: String? = null,
    /** `function`, `class` or `variable`. */
    val kind: String? = null,
    /** The dotted name of the module it is defined in. */
    val module: String? = null,
    /** The file that defines it, which need not be the document asked about. */
    val uri: String? = null,
    /** Its name, in [uri]. */
    val range: Range? = null,
)

data class ByMoveSymbolParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
    /** An existing `.by` or `.py` file of the project, of the same kind as the definition's. */
    val target: String,
)

/** A class of the project, from `by/prepareMoveMembers`. */
data class ByClassItem(
    val name: String? = null,
    val qualifiedName: String? = null,
    val uri: String? = null,
    /** Its name. */
    val range: Range? = null,
)

/** A member of the class `by/prepareMoveMembers` answers for. */
data class ByMemberItem(
    val name: String? = null,
    /**
     * `method`, `classmethod`, `staticmethod`, `property` or `attribute`, or `base` for a base the
     * class statement names, which only Pull Members Up moves.
     */
    val kind: String? = null,
    /** How it reads in a list: a method or property its name and parameters, `fetch(self, key: str)`. Absent from an older `by`. */
    val detail: String? = null,
    /** Its name. */
    val range: Range? = null,
) {
    /** A base of the class rather than a member of its body. */
    val isBase: Boolean get() = kind == BASE

    companion object {
        const val BASE = "base"
    }
}

/** The class at a position, its members and the project's classes above and below it. */
data class ByMovableMembers(
    /** The class itself. */
    @SerializedName("class") val owner: ByClassItem? = null,
    val members: List<ByMemberItem>? = null,
    /** Nearest first. */
    val superclasses: List<ByClassItem>? = null,
    val subclasses: List<ByClassItem>? = null,
)

data class ByPullMembersUpParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
    /** The members of the class's body to move, by name. */
    val members: List<String>,
    /**
     * The bases to move, each as listed with the kind [ByMemberItem.BASE]; sent even when empty, since
     * without it `by` reads the bases from [members] and refuses a name that is a member and a base.
     */
    val bases: List<String>,
    /** The superclass's `qualifiedName`. */
    val target: String,
)

data class ByPushMembersDownParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
    val members: List<String>,
)

data class ByExtractSuperclassParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
    val members: List<String>,
    /** The new class's name. */
    val name: String,
)

/** A function and its parameters, from `by/prepareChangeSignature`. */
data class BySignature(
    val name: String? = null,
    val uri: String? = null,
    /** Its name, in [uri]. */
    val range: Range? = null,
    val parameters: List<BySignatureParameter>? = null,
    /** Its return annotation as the source writes it; `null` for none, and from an older `by`. */
    val returns: String? = null,
)

/** One parameter as the source writes it. */
data class BySignatureParameter(
    val name: String? = null,
    /** One of [ByParameterKind]'s ids. */
    val kind: String? = null,
    val default: String? = null,
    val annotation: String? = null,
)

/**
 * The whole new parameter list `by/changeSignature` gives a function, in order, and what else changes:
 * its [name], and its return annotation, [returns]. Each of those is sent only when it changes, since
 * a `by` that predates them refuses a field it does not know.
 */
data class ByChangeSignatureParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
    val parameters: List<ByChangedParameter>,
    /** The function's new name; `null`, and so not sent, keeps it. */
    val name: String? = null,
    /** The new return annotation, or its removal; `null`, and so not sent, keeps it. */
    @JsonAdapter(ByAnnotationEdit.Adapter::class) val returns: ByAnnotationEdit? = null,
)

/**
 * An annotation changed: [Set] to a new one, written as source, or [Remove]d — sent as `null`, which
 * is not the same as not sending it, which keeps the one there is.
 */
sealed interface ByAnnotationEdit {
    data class Set(val text: String) : ByAnnotationEdit

    data object Remove : ByAnnotationEdit

    /** Writes [Remove] as an explicit `null`, which the protocol's serializer otherwise leaves out. */
    class Adapter : TypeAdapter<ByAnnotationEdit>() {
        override fun write(out: JsonWriter, value: ByAnnotationEdit?) {
            when (value) {
                is Set -> out.value(value.text)
                Remove, null -> {
                    val nulls = out.serializeNulls
                    out.serializeNulls = true
                    out.nullValue()
                    out.serializeNulls = nulls
                }
            }
        }

        override fun read(reader: JsonReader): ByAnnotationEdit? =
            if (reader.peek() == JsonToken.NULL) Remove.also { reader.nextNull() } else Set(reader.nextString())
    }

    companion object {
        /** What changed from [before] to [after], an annotation as the user typed it: `null` for nothing. */
        fun of(before: String?, after: String): ByAnnotationEdit? {
            val text = after.trim()
            return when {
                text == before.orEmpty().trim() -> null
                text.isEmpty() -> Remove
                else -> Set(text)
            }
        }
    }
}

/** One parameter of the new list. */
data class ByChangedParameter(
    val name: String,
    /** The parameter it was; `null`, and so not sent, for a new one. */
    val oldName: String? = null,
    /** One of [ByParameterKind]'s ids. */
    val kind: String,
    /** Its default, written as source; `null` for none. */
    val default: String? = null,
    /** What each call passes a new parameter, written as source; `null` leaves it to its default. */
    val value: String? = null,
    /** Its annotation changed, or removed; `null`, and so not sent, keeps it (none, for a new one). */
    @JsonAdapter(ByAnnotationEdit.Adapter::class) val annotation: ByAnnotationEdit? = null,
)

/** How a parameter is passed, by the id `by` names it with. */
enum class ByParameterKind(val id: String) {
    PositionalOnly("positionalOnly"),
    PositionalOrKeyword("positionalOrKeyword"),
    /** `*args` */
    Variadic("variadic"),
    KeywordOnly("keywordOnly"),
    /** `**kwargs` */
    VariadicKeyword("variadicKeyword"),
    ;

    companion object {
        fun of(id: String?): ByParameterKind? = entries.firstOrNull { it.id == id }
    }
}
