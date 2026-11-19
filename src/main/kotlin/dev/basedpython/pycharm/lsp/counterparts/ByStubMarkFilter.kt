package dev.basedpython.pycharm.lsp.counterparts

import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.MergeableLineMarkerInfo
import javax.swing.Icon

/**
 * One line's gutter icons without another provider's stub icon where [ByCounterpartMarkers] has put
 * one on the same name: what [dev.basedpython.pycharm.lsp.counterparts.python.ByPyiMarkerFilter] does
 * with PyCharm's, apart from the Python plugin so that it can be tested without it.
 */
internal object ByStubMarkFilter {

    /**
     * [list] without the icons of the provider class [provider] drawn with [icon] whose range meets
     * that of one of [ByCounterpartMarkers]' — the same name. Unchanged when it has none of those.
     */
    fun process(list: List<GutterMark>, provider: String, icon: Icon): List<GutterMark> {
        if (list.size < 2) return list
        val ours = list.mapNotNull { infoOf(it) as? ByCounterpartMarkerInfo }
        if (ours.isEmpty()) return list
        return list.filterNot { mark ->
            val info = infoOf(mark) ?: return@filterNot false
            isFrom(info, provider, icon) && ours.any { it.startOffset < info.endOffset && info.startOffset < it.endOffset }
        }
    }

    private fun infoOf(mark: GutterMark): LineMarkerInfo<*>? =
        (mark as? LineMarkerInfo.LineMarkerGutterIconRenderer<*>)?.lineMarkerInfo

    /**
     * Whether [info] is an icon of [provider]'s — drawn with [icon], navigating with a lambda of
     * [provider]'s own — or several of them merged into one, which the daemon does with icons of one
     * kind on one line.
     */
    fun isFrom(info: LineMarkerInfo<*>, provider: String, icon: Icon): Boolean {
        val merged = MergeableLineMarkerInfo.getMergedMarkers(info)
        return if (merged.isEmpty()) isOne(info, provider, icon) else merged.all { isOne(it, provider, icon) }
    }

    private fun isOne(info: LineMarkerInfo<*>, provider: String, icon: Icon): Boolean =
        info.icon === icon && info.navigationHandler?.javaClass?.name?.startsWith("$provider\$") == true
}
