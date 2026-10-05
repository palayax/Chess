package net.palaya.chessanalyzer.ui.video

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import net.palaya.chessanalyzer.video.BoardFrameRenderer
import net.palaya.chessanalyzer.video.RenderInstruction

/**
 * A plain Android [View] (no Compose) that draws one [RenderInstruction] via
 * [BoardFrameRenderer] — the exact same renderer [net.palaya.chessanalyzer.video.VideoExporter]
 * uses to burn frames into the exported MP4. Routing live playback through it too (via an
 * `AndroidView` in [net.palaya.chessanalyzer.ui.screens.VideoScreen]) means in-app playback and
 * the exported file are pixel-identical instead of two board renderers that could silently drift
 * apart — one [android.graphics.Canvas]-drawing implementation, two consumers.
 */
class BoardSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var instruction: RenderInstruction? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Shown before the first frame arrives (e.g. while the script/player is still initializing). */
    var fallbackTitle: String = ""
    var fallbackSubtitle: String = ""

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        when (val instr = instruction) {
            is RenderInstruction.Board -> BoardFrameRenderer.renderBoardFrame(canvas, width, height, instr.spec)
            is RenderInstruction.Card ->
                BoardFrameRenderer.renderCardFrame(canvas, width, height, instr.content, instr.caption)
            null -> BoardFrameRenderer.renderCardFrame(canvas, width, height, fallbackTitle, listOf(fallbackSubtitle))
        }
    }
}
