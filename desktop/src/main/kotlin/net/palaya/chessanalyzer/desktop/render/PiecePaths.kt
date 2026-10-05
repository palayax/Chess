package net.palaya.chessanalyzer.desktop.render

import net.palaya.chessanalyzer.core.chess.PieceType
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D

/**
 * The **Cburnett** piece set (Wikimedia Commons user Cburnett, CC BY-SA 3.0; attribution in
 * `PIECES_LICENSE.txt`, copied into this module's resources) as `java.awt.geom` shapes: a
 * mechanical port of `app/src/main/kotlin/net/palaya/chessanalyzer/ui/board/PieceVectors.kt`
 * (design §8.3). Coordinates are the native 45x45 viewBox (the app's SCALE factor existed only to
 * suit its fixed-width renderers and is dropped); relative curves become absolute; the
 * `PathOperation.Difference` carve-outs become [Area.subtract]; unions become [Area.add].
 * The modifications the app made to the original art (omitted neckline arcs and nostril, carved
 * bishop slit/bands and knight eye, widened king cross) carry over unchanged.
 */
object PiecePaths {

    const val VIEWBOX = 45.0

    private val cache = HashMap<PieceType, Area>()

    fun shape(type: PieceType): Area = synchronized(cache) {
        cache.getOrPut(type) {
            when (type) {
                PieceType.PAWN -> pawn()
                PieceType.ROOK -> rook()
                PieceType.BISHOP -> bishop()
                PieceType.KNIGHT -> knight()
                PieceType.QUEEN -> queen()
                PieceType.KING -> king()
            }
        }
    }

    /** Path builder that tracks the current point so relative cubics can be made absolute. */
    private class P {
        val path = Path2D.Double(Path2D.WIND_NON_ZERO)
        private var x = 0.0
        private var y = 0.0
        fun mv(x: Double, y: Double) = apply { path.moveTo(x, y); this.x = x; this.y = y }
        fun ln(x: Double, y: Double) = apply { path.lineTo(x, y); this.x = x; this.y = y }
        fun cv(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) = apply {
            path.curveTo(x1, y1, x2, y2, x, y); this.x = x; this.y = y
        }
        fun rcv(dx1: Double, dy1: Double, dx2: Double, dy2: Double, dx: Double, dy: Double) =
            cv(x + dx1, y + dy1, x + dx2, y + dy2, x + dx, y + dy)
        fun close(): Area { path.closePath(); return Area(path) }
    }

    private fun rect(l: Double, t: Double, r: Double, b: Double) = Area(Rectangle2D.Double(l, t, r - l, b - t))
    private fun oval(cx: Double, cy: Double, rx: Double, ry: Double) = Area(Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry))
    private fun union(vararg parts: Area): Area = Area().apply { parts.forEach { add(it) } }

    private fun pawn(): Area = P().mv(22.5, 9.0)
        .rcv(-2.21, 0.0, -4.0, 1.79, -4.0, 4.0)
        .rcv(0.0, 0.89, 0.29, 1.71, 0.78, 2.38)
        .cv(17.33, 16.5, 16.0, 18.59, 16.0, 21.0)
        .rcv(0.0, 2.03, 0.94, 3.84, 2.41, 5.03)
        .cv(15.41, 27.09, 11.0, 31.58, 11.0, 39.5)
        .ln(34.0, 39.5)
        .cv(34.0, 31.58, 29.59, 27.09, 26.59, 26.03)
        .cv(28.06, 24.84, 29.0, 23.03, 29.0, 21.0)
        .cv(29.0, 18.59, 27.67, 16.5, 25.72, 15.38)
        .cv(26.21, 14.71, 26.5, 13.89, 26.5, 13.0)
        .rcv(0.0, -2.21, -1.79, -4.0, -4.0, -4.0)
        .close()

    private fun rook(): Area {
        val top = P().mv(11.0, 14.0).ln(11.0, 9.0).ln(15.0, 9.0).ln(15.0, 11.0).ln(20.0, 11.0).ln(20.0, 9.0)
            .ln(25.0, 9.0).ln(25.0, 11.0).ln(30.0, 11.0).ln(30.0, 9.0).ln(34.0, 9.0).ln(34.0, 14.0)
            .ln(31.0, 17.0).ln(14.0, 17.0).close()
        val taper = P().mv(14.0, 29.5).ln(31.0, 29.5).ln(32.5, 32.0).ln(12.5, 32.0).close()
        return union(top, rect(14.0, 17.0, 31.0, 29.5), taper, rect(12.0, 32.0, 33.0, 36.0), rect(9.0, 36.0, 36.0, 39.0))
    }

    private fun bishop(): Area {
        val base = P().mv(9.0, 36.0)
            .cv(12.39, 35.03, 19.11, 36.43, 22.5, 34.0)
            .cv(25.89, 36.43, 32.61, 35.03, 36.0, 36.0)
            .cv(36.0, 36.0, 37.65, 36.54, 39.0, 38.0)
            .cv(38.32, 38.97, 37.35, 38.99, 36.0, 38.5)
            .cv(32.61, 37.53, 25.89, 38.96, 22.5, 37.5)
            .cv(19.11, 38.96, 12.39, 37.53, 9.0, 38.5)
            .cv(7.65, 38.99, 6.68, 38.97, 6.0, 38.0)
            .cv(7.35, 36.54, 9.0, 36.0, 9.0, 36.0)
            .close()
        val body = P().mv(15.0, 32.0)
            .cv(17.5, 34.5, 27.5, 34.5, 30.0, 32.0)
            .cv(30.5, 30.5, 30.0, 30.0, 30.0, 30.0)
            .cv(30.0, 27.5, 27.5, 26.0, 27.5, 26.0)
            .cv(33.0, 24.5, 33.5, 14.5, 22.5, 10.5)
            .cv(11.5, 14.5, 12.0, 24.5, 17.5, 26.0)
            .cv(17.5, 26.0, 15.0, 27.5, 15.0, 30.0)
            .cv(15.0, 30.0, 14.5, 30.5, 15.0, 32.0)
            .close()
        val all = union(base, body, oval(22.5, 8.0, 2.5, 2.5))
        val d = 0.75
        all.subtract(union(
            rect(22.5 - d, 15.5, 22.5 + d, 20.5),
            rect(20.0, 18.0 - d, 25.0, 18.0 + d),
            rect(17.5, 26.0 - d, 27.5, 26.0 + d),
            rect(15.0, 30.0 - d, 30.0, 30.0 + d),
        ))
        return all
    }

    private fun knight(): Area {
        val outline = P().mv(22.0, 10.0).cv(32.5, 11.0, 38.5, 18.0, 38.0, 39.0).ln(15.0, 39.0)
            .cv(15.0, 30.0, 25.0, 32.5, 23.0, 18.0).close()
        val face = P().mv(24.0, 18.0)
            .cv(24.38, 20.91, 18.45, 25.37, 16.0, 27.0)
            .cv(13.0, 29.0, 13.18, 31.34, 11.0, 31.0)
            .cv(9.958, 30.06, 12.41, 27.96, 11.0, 28.0)
            .cv(10.0, 28.0, 11.19, 29.23, 10.0, 30.0)
            .cv(9.0, 30.0, 5.997, 31.0, 6.0, 26.0)
            .cv(6.0, 24.0, 12.0, 14.0, 12.0, 14.0)
            .cv(12.0, 14.0, 13.89, 12.1, 14.0, 10.5)
            .cv(13.27, 9.506, 13.5, 8.5, 13.5, 7.5)
            .cv(14.5, 6.5, 16.5, 10.0, 16.5, 10.0)
            .ln(18.5, 10.0)
            .cv(18.5, 10.0, 19.28, 8.008, 21.0, 7.0)
            .cv(22.0, 7.0, 22.0, 10.0, 22.0, 10.0)
            .close()
        val all = union(outline, face)
        all.subtract(oval(9.0, 25.5, 0.5, 0.5))
        return all
    }

    private fun queen(): Area {
        val crown = P().mv(9.0, 26.0).cv(17.5, 24.5, 30.0, 24.5, 36.0, 26.0).ln(38.5, 13.5).ln(31.0, 25.0)
            .ln(30.7, 10.9).ln(25.5, 24.5).ln(22.5, 10.0).ln(19.5, 24.5).ln(14.3, 10.9).ln(14.0, 25.0)
            .ln(6.5, 13.5).ln(9.0, 26.0).close()
        val body = P().mv(9.0, 26.0)
            .cv(9.0, 28.0, 10.5, 28.0, 11.5, 30.0)
            .cv(12.5, 31.5, 12.5, 31.0, 12.0, 33.5)
            .cv(10.5, 34.5, 11.0, 36.0, 11.0, 36.0)
            .cv(9.5, 37.5, 11.0, 38.5, 11.0, 38.5)
            .cv(17.5, 39.5, 27.5, 39.5, 34.0, 38.5)
            .cv(34.0, 38.5, 35.5, 37.5, 34.0, 36.0)
            .cv(34.0, 36.0, 34.5, 34.5, 33.0, 33.5)
            .cv(32.5, 31.0, 32.5, 31.5, 33.5, 30.0)
            .cv(34.5, 28.0, 36.0, 28.0, 36.0, 26.0)
            .cv(27.5, 24.5, 17.5, 24.5, 9.0, 26.0)
            .close()
        val pearls = listOf(6.0 to 12.0, 14.0 to 9.0, 22.5 to 8.0, 31.0 to 9.0, 39.0 to 12.0).map { (x, y) -> oval(x, y, 2.0, 2.0) }
        return union(crown, body, *pearls.toTypedArray())
    }

    private fun king(): Area {
        val body = P().mv(12.5, 37.0).cv(18.0, 40.5, 27.0, 40.5, 32.5, 37.0).ln(32.5, 30.0)
            .cv(32.5, 30.0, 41.5, 25.5, 38.5, 19.5).cv(34.5, 13.0, 25.0, 16.0, 22.5, 23.5)
            .ln(22.5, 27.0).ln(22.5, 23.5).cv(20.0, 16.0, 10.5, 13.0, 6.5, 19.5)
            .cv(3.5, 25.5, 12.5, 30.0, 12.5, 30.0).ln(12.5, 37.0).close()
        val head = P().mv(22.5, 25.0).cv(22.5, 25.0, 27.0, 17.5, 25.5, 14.5).cv(25.5, 14.5, 24.5, 12.0, 22.5, 12.0)
            .cv(20.5, 12.0, 19.5, 14.5, 19.5, 14.5).cv(18.0, 17.5, 22.5, 25.0, 22.5, 25.0).close()
        return union(body, head, rect(20.5, 3.5, 24.5, 16.0), rect(16.5, 7.5, 28.5, 11.5))
    }
}
