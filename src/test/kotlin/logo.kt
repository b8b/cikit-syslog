import org.junit.Test
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.QuadCurve2D
import java.awt.image.BufferedImage
import java.io.FileOutputStream
import javax.imageio.ImageIO

operator fun Point2D.plus(other: Point2D): Point2D.Double = Point2D.Double(x + other.x, y + other.y)
operator fun Point2D.minus(other: Point2D): Point2D.Double = Point2D.Double(x - other.x, y - other.y)
operator fun Point2D.times(factor: Double): Point2D.Double = Point2D.Double(x * factor, y * factor)

fun Path2D.moveTo(p: Point2D) = moveTo(p.x, p.y)
fun Path2D.lineTo(p: Point2D) = lineTo(p.x, p.y)

fun quadCurve(p1: Point2D, ctrl: Point2D, p2: Point2D) =
        QuadCurve2D.Double(p1.x, p1.y, ctrl.x, ctrl.y, p2.x, p2.y)

fun logo() {
    val size = 160.0
    val height = size * 3.0 / 4.0
    val a = height * 2 / Math.sqrt(3.0)
    val rc = 0.05

    val im = BufferedImage(Math.round(size).toInt(), Math.round(size).toInt(), BufferedImage.TYPE_INT_ARGB)
    val gfx = im.createGraphics()
    gfx.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    gfx.color = Color.BLACK

    var triangle = Triple(
            Point2D.Double(height, (size - a) / 2),
            Point2D.Double(height, (size + a) / 2),
            Point2D.Double(0.0, a/2)
    )

    for (i in 0 until 10) {
        val path = Path2D.Double()
        listOf(
                Triple(triangle.first, triangle.second, triangle.third),
                Triple(triangle.second, triangle.third, triangle.first),
                Triple(triangle.third, triangle.first, triangle.second)
        ).forEach {
            val diff = it.second - it.first
            val lineStart = it.first + diff * rc
            val lineEnd = it.second - diff * rc
            val nextLineStart = it.second + (it.third - it.second) * rc
            path.moveTo(lineStart)
            path.lineTo(lineEnd)
            path.append(quadCurve(lineEnd, it.second, nextLineStart), true)
        }
        gfx.draw(path)

        triangle = Triple(
                triangle.first + (triangle.second - triangle.first) * 0.1,
                triangle.second + (triangle.third - triangle.second) * 0.1,
                triangle.third + (triangle.first - triangle.third) * 0.1
        )
    }

    FileOutputStream("/workspace/cikit-syslog/logo_big.png").use { out ->
        ImageIO.write(im, "png", out)
    }
}

class Logo {

    @Test
    fun testCreateLogo() {
        logo()
    }

}
