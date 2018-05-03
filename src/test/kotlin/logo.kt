import ClosedPath.Companion.closedPath
import Node.Companion.node
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

interface Node {
    val x: Double
    val y: Double

    class NodeImpl(override val x: Double, override val y: Double): Node {
        override fun toString(): String {
            return String.format("(x=%.02f y=%.02f)", x, y)
        }
    }

    companion object {
        fun node(x: Double, y: Double): Node = NodeImpl(x, y)
        fun node(node: Node) = node(node.x, node.y)
    }

    fun toPoint2D(): Point2D = Point2D.Double(x, y)
}

operator fun Node.plus(other: Node) = node(x + other.x, y + other.y)
operator fun Node.minus(other: Node) = node(x - other.x, y - other.y)
operator fun Node.times(factor: Double) = node(x * factor, y * factor)
operator fun Node.div(factor: Double) = node(x / factor, y / factor)

class ClosedPath private constructor(override val x: Double,
                                     override val y: Double,
                                     private val nodes: List<ClosedPath>,
                                     private val index: Int = 0) : Node, Iterable<ClosedPath> {

    companion object {
        fun closedPath(vararg node: Node): ClosedPath {
            return closedPath(node.toList())
        }

        fun closedPath(node: List<Node>): ClosedPath {
            val nodes = mutableListOf<ClosedPath>()
            node.forEach { nodes.add(ClosedPath(it.x, it.y, nodes, nodes.size)) }
            return nodes.first()
        }
    }

    fun map(transform: (ClosedPath) -> Node): ClosedPath {
        val list: List<Node> = (this as Iterable<ClosedPath>).map(transform)
        return ClosedPath.closedPath(list)
    }

    override fun iterator(): Iterator<ClosedPath> {
        var p = 0
        return generateSequence {
            return@generateSequence if (p < nodes.size) {
                nodes[(index + p) % nodes.size].also { p++ }
            } else {
                null
            }
        }.iterator()
    }

    fun next() = nodes[if (index + 1 < nodes.size) index + 1 else 0]
    fun prev() = nodes[if (index - 1 < 0) nodes.size - 1 else index - 1]

    fun toPath2D(action: Path2D.Double.(ClosedPath) -> Unit = Path2D::lineTo): Path2D.Double {
        val path = Path2D.Double()
        path.moveTo(prev())
        for (node in this) {
            action(path, node)
        }
        return path
    }

    override fun toString(): String {
        return "ClosedPath[" +
                joinToString("..") { String.format("(x=%.02f y=%.02f)", x, y) } +
                "]"
    }
}

fun Path2D.moveTo(p: Node) = moveTo(p.x, p.y)
fun Path2D.lineTo(p: Node) = lineTo(p.x, p.y)

fun curve(p1: Node, ctrl: Node, p2: Node) =
        QuadCurve2D.Double(p1.x, p1.y, ctrl.x, ctrl.y, p2.x, p2.y)

class Logo {

    private fun drawLogo(gfx: Graphics2D, size: Double) {
        val h = size * 3.0 / 4.0
        val a = h * 2 / Math.sqrt(3.0)
        val rc = 0.05

        gfx.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        gfx.color = Color.BLACK

        val firstTriangle = closedPath(
                node(h, (size - a) / 2),
                node(h, (size + a) / 2),
                node(0.0, a / 2)
        )

        val triangles = generateSequence(firstTriangle) {
            it.map { node -> node + (node.next() - node) * 0.1 }
        }

        for (triangle in triangles.take(10)) {
            val path = triangle.toPath2D { node ->
                val diff = node - node.prev()
                val lineStart = node.prev() + diff * rc
                val lineEnd = node - diff * rc
                val nextLineStart = node + (node.next() - node) * rc
                if (node == triangle) moveTo(lineStart)
                lineTo(lineEnd)
                append(curve(lineEnd, node, nextLineStart), true)
            }

            println("drawing triangle with bounds ${path.bounds}")

            gfx.draw(path)
        }
    }

    @Test
    fun saveLogo() {
        val size = 160.0
        val im = BufferedImage(Math.round(size).toInt(), Math.round(size).toInt(), BufferedImage.TYPE_INT_ARGB)
        val gfx = im.createGraphics()
        drawLogo(gfx, size)
        FileOutputStream("/workspace/cikit-syslog/logo_big.png").use { out ->
            ImageIO.write(im, "png", out)
        }
    }

}
