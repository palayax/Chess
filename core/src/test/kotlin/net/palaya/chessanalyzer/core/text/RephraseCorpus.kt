package net.palaya.chessanalyzer.core.text

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.narration.NarrationOptions
import net.palaya.chessanalyzer.core.narration.RealGameFixture
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.core.narration.VideoScriptGenerator

/**
 * The recorded texts C2 is measured and checked on (docs/LLM_REPHRASE_DESIGN.md §5.4, §5.5): every card
 * text of the three audited games for no side, White and Black (C1's `CommentaryAuditDumpTest` set), and
 * every eligible narration beat of the five pacing games at the Normal pace for the three sides.
 */
object RephraseCorpus {

    data class Item(
        val id: String,
        val game: String,
        val side: Color?,
        val surface: RephraseSurface,
        val ply: Int?,
        val kind: String,
        val text: String,
    )

    private val sides = listOf<Color?>(null, Color.WHITE, Color.BLACK)

    val cards: List<Item> by lazy {
        val games = listOf("immortal" to RealGameFixture.immortal, "chesscom" to RealGameFixture.chesscom, "game01" to RealGameFixture.game01)
        buildList {
            for ((name, g) in games) for (side in sides) for (a in g.report(side).annotations) {
                add(Item("card/$name/${side?.name ?: "NONE"}/${a.ply}", name, side, RephraseSurface.CARD, a.ply, a.classification.name, a.text))
            }
        }
    }

    val narration: List<Item> by lazy {
        val games = listOf(
            "scholars" to RealGameFixture.scholars, "chesscom" to RealGameFixture.chesscom, "immortal" to RealGameFixture.immortal,
            "game01" to RealGameFixture.game01, "byrne_fischer" to RealGameFixture.byrneFischer
        )
        buildList {
            for ((name, g) in games) for (side in sides) {
                val script = VideoScriptGenerator(side).generate(g.report(side), g.pgn, NarrationOptions(speechWpm = 169, pace = VideoPace.NORMAL))
                for (s in script.segments) {
                    if (!RephrasedScript.eligible(s)) continue
                    add(Item("narr/$name/${side?.name ?: "NONE"}/${s.index}", name, side, RephraseSurface.NARRATION, s.ply, s.kind.name, s.narration))
                }
            }
        }
    }

    /** Each distinct (surface, text) once: the unit a cache and a measurement work in. */
    val distinct: List<Item> by lazy { (cards + narration).distinctBy { it.surface to it.text } }
}
