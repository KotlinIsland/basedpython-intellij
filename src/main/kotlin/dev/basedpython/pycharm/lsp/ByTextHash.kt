package dev.basedpython.pycharm.lsp

/**
 * Names the text a document request to `by` is about, so that `by` answers about that text and no
 * other.
 *
 * Every answer this plugin keeps about a document — an outline, the injected fragments, where the
 * docstrings are — is kept against the `Document.modificationStamp` it was asked at, and its offsets
 * are read against that revision's text. So an answer about any other text is not a stale answer
 * but a wrong one, and it stays wrong for as long as the revision lasts. Two things used to produce
 * one, or a refusal in its place:
 *
 *  - the platform's `didOpen` for a document goes out after the events that make the plugin want
 *    to ask about it — an editor opening, a first edit to a file no editor shows, a server starting
 *    with files on screen — and `by` refused a document it had not been opened on;
 *  - a document `by` has not been opened on is, to `by`, the file on disk, which is not the text
 *    of a document edited and not yet saved.
 *
 * With the hash of the text in the request's `textHash`, `by` answers about exactly that text: at
 * once when it holds it — the open buffer, or the file on disk for a document it has not been
 * opened on — and otherwise once the `didOpen`, `didChange` or file write that brings it arrives. The
 * plugin need not know whether the platform has opened a document yet, which it has no public way
 * to know. See `ty_server`'s `document/asked_text.rs`, which defines the hash and holds the known
 * values [ByTextHashTest] checks this against.
 *
 * The hash is FNV-1a, 64 bits, over the text's UTF-16 code units, with each line ending counted as
 * a single `\n`, in sixteen lowercase hex digits. A `Document` holds `\n` only; the file on disk may
 * not, and means the same positions either way.
 */
internal object ByTextHash {

    private const val OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val PRIME = 0x100000001b3L

    /** The hash of [text], as `textHash` carries it. */
    fun of(text: CharSequence): String {
        var hash = OFFSET_BASIS
        var afterCarriageReturn = false
        for (index in 0 until text.length) {
            var unit = text[index].code
            if (unit == '\n'.code && afterCarriageReturn) {
                // the `\n` of a `\r\n`, counted already as the `\r`
                afterCarriageReturn = false
                continue
            }
            afterCarriageReturn = unit == '\r'.code
            if (afterCarriageReturn) unit = '\n'.code
            hash = (hash xor (unit and 0xFF).toLong()) * PRIME
            hash = (hash xor (unit ushr 8).toLong()) * PRIME
        }
        return java.lang.Long.toHexString(hash).padStart(16, '0')
    }
}
