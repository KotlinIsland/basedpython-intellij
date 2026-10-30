package dev.basedpython.pycharm.debug

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.dap.DefaultDapVirtualFileResolver
import dev.basedpython.pycharm.run.target.ByTargetPaths

/**
 * The files of a debug session on a run target: the adapter's paths are the target's, the IDE's are
 * the project's, and [paths] translates between them — `/opt/project/pkg/main.by` on a Docker target
 * is the project's `pkg/main.by`, where a breakpoint set in it is sent.
 *
 * `by run`'s source maps name the `.by` files where `by run` ran, so a frame bpd reports is in the
 * target's paths; bpd takes a path mapping from nobody, and needs none: the translation is the
 * IDE's, which knows both ends. A path outside the project, never uploaded, has no file here; with
 * no target ([paths] null) this is the platform's own resolver.
 */
internal class ByTargetFileResolver(private val paths: () -> ByTargetPaths?) : DefaultDapVirtualFileResolver() {

    override fun resolve(path: String): VirtualFile? {
        val mapping = paths() ?: return super.resolve(path)
        val local = mapping.toLocal(path) ?: return null
        return LocalFileSystem.getInstance().findFileByNioFile(local)
    }

    override fun getAdapterPath(file: VirtualFile): String? {
        val mapping = paths() ?: return super.getAdapterPath(file)
        if (!file.isInLocalFileSystem) return null
        return mapping.toTarget(file.toNioPath())
    }
}
