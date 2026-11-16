// Paints the project frame and every popup window over it into one 2x PNG, without screen recording.
// OUT is replaced by the caller.
import com.intellij.openapi.application.EDT
import com.intellij.openapi.wm.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

val out = File("OUT")
withContext(Dispatchers.EDT) {
    val frame = WindowManager.getInstance().getFrame(project)!!
    val root = frame.rootPane
    val scale = 2.0
    val img = BufferedImage((root.width * scale).toInt(), (root.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.scale(scale, scale)
    root.printAll(g)
    val origin = root.locationOnScreen
    val painted = mutableListOf<String>()
    for (w in Window.getWindows()) {
        if (w === frame || !w.isShowing || w.width < 4 || w.height < 4) continue
        var o: Window? = w.owner
        var ours = false
        while (o != null) { if (o === frame) { ours = true; break }; o = o.owner }
        if (!ours) continue
        val p = w.locationOnScreen
        val gw = g.create(p.x - origin.x, p.y - origin.y, w.width, w.height)
        w.printAll(gw)
        gw.dispose()
        painted += "${w.javaClass.simpleName} ${w.width}x${w.height} @${p.x - origin.x},${p.y - origin.y}"
    }
    g.dispose()
    out.parentFile.mkdirs()
    ImageIO.write(img, "png", out)
    println("wrote $out ${img.width}x${img.height}; popups: $painted")
}
