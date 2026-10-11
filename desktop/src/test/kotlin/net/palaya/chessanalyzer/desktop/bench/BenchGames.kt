package net.palaya.chessanalyzer.desktop.bench

import kotlinx.serialization.json.Json
import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.core.pgn.PgnParser
import java.nio.file.Files
import java.nio.file.Path

/** The measured games: the F1 calibration set (spec §8.1) and a held-out set from the Famous games library. */
object BenchGames {
    /** F1's 226 calibration positions: the thresholds are tuned on these. */
    val CALIBRATION = listOf("games/game01.txt", "fixtures/chesscom_style_game.pgn", "fixtures/immortal.pgn", "fixtures/byrne_fischer.pgn")

    /** Not used for tuning; the tuned policy is checked on them afterwards. */
    val HELD_OUT = listOf("famous:evergreen-game-1852", "famous:opera-game-1858", "famous:kasparovs-immortal-1999", "famous:wcc-2024-game-14")

    val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    fun idOf(source: String): String = source.substringAfterLast('/').substringAfter("famous:").substringBeforeLast('.')

    fun load(root: Path, source: String): PgnGame {
        if (source.startsWith("famous:")) {
            val id = source.removePrefix("famous:")
            val ids = Files.readAllLines(root.resolve("app/src/main/assets/famous_games_index.tsv"))
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("id\t") }
                .map { it.substringBefore('\t') }
            val games = PgnParser.parse(Files.readString(root.resolve("app/src/main/assets/famous_games.pgn")))
            return games[ids.indexOf(id).also { require(it >= 0) { "unknown famous game $id" } }]
        }
        return PgnParser.parse(Files.readString(root.resolve(source))).first()
    }

    fun fens(game: PgnGame): List<String> {
        if (game.moves.isEmpty()) return listOf(game.startFen ?: net.palaya.chessanalyzer.core.chess.Position.STANDARD_START_FEN)
        return listOf(game.moves.first().positionFenBefore) + game.moves.map { it.positionFenAfter }
    }

    fun recordFile(out: Path, label: String, id: String): Path = out.resolve(label).resolve("$id.json")

    fun read(out: Path, label: String, id: String): GameRecord? {
        val f = recordFile(out, label, id)
        return if (Files.isRegularFile(f)) json.decodeFromString(GameRecord.serializer(), Files.readString(f)) else null
    }

    fun write(out: Path, label: String, record: GameRecord) {
        val f = recordFile(out, label, record.gameId)
        Files.createDirectories(f.parent)
        Files.writeString(f, json.encodeToString(GameRecord.serializer(), record))
    }
}
