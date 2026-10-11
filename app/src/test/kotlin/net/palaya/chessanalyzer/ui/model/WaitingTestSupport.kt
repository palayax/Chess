package net.palaya.chessanalyzer.ui.model

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import net.palaya.chessanalyzer.core.analysis.EngineLineInput
import net.palaya.chessanalyzer.core.analysis.OpeningBook
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseSan

/** Shared helpers of the "While you wait" host tests. */
internal object WaitingTestSupport {

    /** A file of the repository, found from either the module or the repository root as the working directory. */
    fun repoFile(relative: String): File =
        listOf(File(relative), File("..", relative), File("app/$relative"), File("../app/$relative"))
            .firstOrNull { it.exists() } ?: throw AssertionError("file $relative not found")

    fun asset(name: String): File = repoFile("src/main/assets/$name").takeIf { it.exists() }
        ?: repoFile("app/src/main/assets/$name")

    fun assetText(name: String): String = asset(name).readText(Charsets.UTF_8)

    private val stringsXml: Map<String, String> by lazy {
        val file = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml"))
            .first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to n.textContent
        }
    }

    /** The English sentence the app shows for string resource [name] with [args], as Android would format it. */
    fun render(name: String, vararg args: Any): String {
        val raw = stringsXml[name] ?: throw AssertionError("strings.xml has no '$name'")
        val unescaped = raw.replace("\\'", "'").replace("\\\"", "\"")
        return String.format(Locale.ROOT, unescaped, *args)
    }

    fun hasString(name: String): Boolean = stringsXml.containsKey(name)

    /** The insight as the user reads it: White/Black written with the app's own words. */
    fun renderInsight(insight: LiveInsight): String {
        val args = insight.args.map {
            when (it) {
                Color.WHITE -> render("side_white")
                Color.BLACK -> render("side_black")
                else -> it
            }
        }
        return render(insight.kind.resName, *args.toTypedArray())
    }

    /** White-relative [cpWhite] (or [mateWhite]) at the position after [ply] of [sans], as the engine would report it. */
    fun evalAt(sans: List<String>, ply: Int, cpWhite: Int? = null, mateWhite: Int? = null, depth: Int = 12): PositionEval {
        var pos = Position.startPosition()
        for (i in 0 until ply) pos = pos.makeMove(pos.parseSan(sans[i]))
        val stmWhite = pos.sideToMove == Color.WHITE
        val line = if (mateWhite != null) {
            EngineLineInput(1, null, if (stmWhite) mateWhite else -mateWhite, depth, emptyList())
        } else {
            EngineLineInput(1, if (stmWhite) cpWhite else -cpWhite!!, null, depth, emptyList())
        }
        return PositionEval(pos.toFen(), listOf(line), depth)
    }

    fun evalsOf(sans: List<String>, cps: List<Int>): Map<Int, PositionEval> =
        cps.mapIndexed { ply, cp -> ply to evalAt(sans, ply, cpWhite = cp) }.toMap()

    /** A tiny opening book: Italian Game and the Giuoco Piano. */
    fun miniBook(): OpeningBook = OpeningBook.load(
        """eco	name	pgn
C50	Italian Game	1. e4 e5 2. Nf3 Nc6 3. Bc4
C54	Italian Game: Giuoco Piano	1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5
""".reader(),
    )

    val ITALIAN = listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5", "O-O", "Nf6", "d3", "d6")
}
