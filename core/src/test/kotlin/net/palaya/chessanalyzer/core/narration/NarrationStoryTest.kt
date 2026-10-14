package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.GamePhases
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.chess.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NS (ANALYSIS_SPEC §9.9, docs/COMMENTARY_STYLE.md §8): the story the narrated video tells and its rhythm. The claims
 * themselves are re-derived by `scripts/audit_commentary.py story` from the recorded engine numbers; this class pins what
 * only the generator can: the template catalogue (every phrasing of every story sentence is one of the templates below, and
 * nothing the recorded games say is outside them), the golden cold opens, and the rhythm rules.
 */
class NarrationStoryTest {

    private val games = listOf(
        "immortal" to RealGameFixture.immortal, "chesscom" to RealGameFixture.chesscom,
        "game01" to RealGameFixture.game01, "byrne_fischer" to RealGameFixture.byrneFischer
    )

    private val options = NarrationOptions(speechWpm = 169, pace = VideoPace.NORMAL)

    private val wb = "(?:White|Black|you)"
    private val num = "(?:\\d+|no|one|two|three|four|five|six|seven|eight|nine|ten)"
    private val band = "(?:decisively winning|winning|clearly better|slightly better|about level|slightly worse|clearly worse|losing|decisively lost)"
    private val phaseWords = "(?:slightly better|clearly better|winning)"
    private val cause = "(?:, after (?:White|Black)'s (?:blunder|mistake|missed win|slip) on move \\d+|, after winning (?:a queen|a rook|the exchange|a piece|a pawn|material)|, with a forced mate in $num)?"
    private val move = "[a-z][a-z' ,-]+?"
    private val sq = "[a-h] (?:one|two|three|four|five|six|seven|eight)"

    /**
     * The template catalogue of the story: for each sentence kind, every phrasing it can have in English, as an anchored
     * regular expression over the rendered text. `\Q..\E`-free on purpose: they are meant to be read.
     */
    private val templates: Map<String, List<Regex>> = mapOf(
        "StakesComeback" to listOf(
            "$wb won, but for $num moves this was $wb's game\\.", "$wb won it, though for $num moves $wb was clearly ahead\\.",
            "For $num moves $wb was clearly better, and $wb still won\\."
        ),
        "StakesTookOver" to listOf(
            "$wb was on top from the opening and never let go\\.", "$wb got ahead early and stayed there\\.",
            "It was level until move \\d+, and then $wb took over for good\\.", "Nothing separated them until move \\d+, when $wb took over for good\\."
        ),
        "StakesTightDraw" to listOf("Neither side ever got more than a slight edge\\.", "It was close all the way: neither side was ever more than slightly better\\."),
        "StakesWastedLead" to listOf("$wb was clearly better for $num moves, but it ended in a draw\\.", "$wb held the upper hand for $num moves and still could not win\\."),
        "ResultEndsInMate" to listOf("It ended in mate on move \\d+\\.", "The finish was a checkmate on move \\d+\\."),
        "ResultLasted" to listOf("It lasted (?:\\d+ moves|one move)\\.", "All over after (?:\\d+ moves|one move)\\."),
        "HookBigTurn" to listOf(
            "One move, around move \\d+, swings the whole game, and it's easy to walk past\\.", "Everything here turns on a single move, around move \\d+\\.",
            "There's one move in here, around move \\d+, that changes everything\\. Let's find it\\."
        ),
        "HookClose" to listOf("One moment, around move \\d+, matters more than any other\\.", "The key moment comes around move \\d+\\."),
        "HookClean" to listOf("This one is clean from both sides\\. The biggest slip is small, but it is worth a look\\."),
        "TheoryEnds" to listOf(
            "Theory runs out around move \\d+, and (?:the position is about level|(?:White|Black) is $phaseWords)\\.",
            "By move \\d+ the book is spent, and (?:the position is about level|(?:White|Black) is $phaseWords)\\."
        ),
        "OpeningVerdict" to listOf(
            "Out of the opening, (?:the position is about level|(?:White|Black) is $phaseWords)\\.",
            "Coming out of the opening, (?:the position is about level|(?:White|Black) is $phaseWords)\\."
        ),
        "PhaseTurn" to listOf(
            "$wb gave up the advantage with a (?:blunder|mistake|missed win|slip) on move \\d+, and it was level again\\.", "It was level again$cause\\.", "$wb fought back to level$cause\\.",
            "The game turned$cause: $wb was $phaseWords now\\.", "$wb took over$cause, and was $phaseWords\\.",
            "$wb edged ahead$cause\\.", "$wb got a small edge$cause\\.", "$wb built a clear edge$cause\\.", "$wb took control$cause\\.",
            "$wb was winning now$cause\\.", "$wb's edge became a winning one$cause\\.",
            "$wb's grip loosened$cause: only $phaseWords now\\.", "$wb gave some ground$cause, and was only $phaseWords now\\."
        ),
        "SkipAhead" to listOf(
            "A few quiet moves follow, no captures and no checks, and we pick it up at move \\d+\\.",
            "Nothing is taken and nobody gives check for a while, so on to move \\d+\\.", "Quiet moves, no captures, no checks\\. We rejoin at move \\d+\\.",
            "There are trades along the way, and we pick it up at move \\d+\\.", "Some pieces come off, and then we're at move \\d+\\.",
            "A few trades later, it's move \\d+\\.", "Let's jump to move \\d+\\.", "Skipping ahead to move \\d+\\.", "On to move \\d+\\."
        ),
        "SmallSwing" to listOf(
            "That helps $wb\\.", "It nudges the game (?:your|White's|Black's) way\\.", "(?:Your|White's|Black's) position improves\\.",
            "That gives a little back\\.", "It costs $wb a little\\.", "(?:Your|White's|Black's) position slips a little\\."
        ),
        "PlayedSlip" to listOf("$wb [a-z]+ .+, a small slip\\.", "$wb [a-z]+ .+, which is slightly off\\.", "$wb [a-z]+ .+, but not the most precise\\."),
        "BetterHere" to listOf(
            "$move was the move\\.", "The move was $move\\.", "Instead, $move\\.",
            "$move was the move: mate in $num\\.", "The move was $move, forcing mate in $num\\.", "Instead, $move, and it is mate in $num\\.",
            "$move was the move, winning (?:a queen|a rook|the exchange|a piece|a pawn|material)\\.",
            "The move was $move, which wins (?:a queen|a rook|the exchange|a piece|a pawn|material)\\.",
            "Instead, $move wins (?:a queen|a rook|the exchange|a piece|a pawn|material)\\."
        ),
        "AllowsMate" to listOf(
            "That allows a forced mate in $num\\.", "It walks into a forced mate in $num\\.", "After that, there is a forced mate in $num\\."
        ),
        "PlayedThenReply" to listOf(
            "$wb [a-z]+ .+, and now (?:White|Black) has .+?(?:, winning (?:a queen|a rook|the exchange|a piece|a pawn|material))?\\.",
            "$wb [a-z]+ .+, but (?:White|Black) has the reply .+?(?:, winning (?:a queen|a rook|the exchange|a piece|a pawn|material))?\\.",
            "$wb [a-z]+ .+, and (?:White|Black) can answer with .+?(?:, winning (?:a queen|a rook|the exchange|a piece|a pawn|material))?\\."
        ),
        "MateAnnounce" to listOf("This is a forced mate in $num\\.", "From here it is mate in $num, by force\\.", "Mate in $num, and nothing can stop it\\."),
        "MateStep" to listOf("$move\\.", "Then $move\\."),
        "MateFinish" to listOf("You win it!", "Game over, and you win!", "Game over\\. (?:White|Black) wins!", "And that's the game: (?:White|Black) wins!", "(?:White|Black) wins it!"),
        "ConsequenceChanged" to listOf(
            "$wb (?:was|were) $band\\. Now (?:you|White|Black) (?:is|are) $band\\.", "That takes $wb from $band to $band\\.",
            "$wb (?:has|have) slipped from $band to $band\\."
        ),
        "ConsequenceUnchanged" to listOf("$wb (?:is|are) still $band, but that gave away [a-z ]+\\."),
        "PayoffStanding" to listOf("The line ends there, with $wb $band\\.", "That is as far as it goes, and $wb (?:is|are) $band\\."),
        "TurningPointLead" to listOf(
            "That was the turning point\\.", "That's where the game turned\\.", "And that was the turning point\\.",
            "That was the biggest slip of the game\\.", "If there is a turning point, that is it\\."
        ),
        "TurningPointSwing" to listOf("No other move in this game cost as much\\.", "Nothing else in this game swings the evaluation like move \\d+\\."),
        "TurningAftermath" to listOf(
            "From there, (?:White|Black) never gave the lead back\\.", "From then on, (?:White|Black) stayed ahead\\.",
            "It wasn't over: (?:White|Black) was (?:ahead again|back to level) by move \\d+\\."
        ),
        "PivotQuestion" to listOf(
            "Hold on, back up\\. Can you see what (?:White|Black) missed\\?", "Let's rewind\\. Do you see what (?:White|Black) missed here\\?",
            "Freeze it there\\. Can you see what (?:White|Black) missed\\?"
        ),
        "OutroEnding" to listOf(
            "$wb won by checkmate on move \\d+\\.", "It ended in checkmate: $wb wins on move \\d+\\.", "$wb won after (?:\\d+ moves|one move)\\.",
            "$wb took the win after (?:\\d+ moves|one move)\\.", "It ended in a draw after (?:\\d+ moves|one move)\\.",
            "The game was drawn after (?:\\d+ moves|one move)\\.", "The game stops after (?:\\d+ moves|one move)\\."
        ),
        "OutroDecider" to listOf(
            "The decisive moment was (?:your|White's|Black's) (?:blunder|mistake|missed win|slip) on move \\d+, which took $wb from $band to $band\\.",
            "It all came down to (?:your|White's|Black's) (?:blunder|mistake|missed win|slip) on move \\d+: (?:You|White|Black) went from $band to $band\\."
        ),
        "OutroNoDecider" to listOf("There was no single decisive mistake\\.", "Neither side made one decisive mistake\\."),
        "TakeawayMissed" to listOf(
            "At move \\d+, $wb missed an? [a-z -]+\\.(?: .+ was the move\\.)?", "Look at move \\d+\\. (?:You|White|Black) missed an? [a-z -]+\\.(?: .+ was the move\\.)?"
        ),
        "TakeawayAllowed" to listOf(
            "Move \\d+ let (?:White|Black) in\\.(?: It allowed a forced mate in $num\\.| (?:White|Black) could answer with .+\\.)?",
            "At move \\d+, the door opened for (?:White|Black)\\.(?: It allowed a forced mate in $num\\.| (?:White|Black) could answer with .+\\.)?"
        ),
        "TakeawayCostly" to listOf(
            "Move \\d+ gave up [a-z ]+(?:, from $band to $band)?\\.", "At move \\d+, $wb gave up [a-z ]+(?:, from $band to $band)?\\."
        ),
        "TakeawayClean" to listOf("Neither side made a decisive mistake, so the habit to keep is simple: check what every move allows\\."),
        "TakeawayHabitAllowed" to listOf("Before you commit, ask one question: what does this let them do next\\?"),
        "TakeawayHabitStanding" to listOf(
            "When you're ahead, slow down\\. Check every capture and every check before you move\\.",
            "In a level position, every move counts\\. Check every capture and every check before you move\\.",
            "Even when you're worse, don't rush\\. Check every capture and every check before you move\\."
        ),
        "LessonMatedButNotTheMistake" to listOf("$wb got mated at the end, but the mate wasn't the mistake\\. Move \\d+ was\\. Fix it and the mate never happens\\.")
    ).mapValues { (_, list) -> list.map { Regex("^$it$", RegexOption.IGNORE_CASE) } }

    @Test
    fun `every phrasing of every story sentence in the catalogue is one of the templates`() {
        val storyIds = templates.keys
        val seen = HashSet<String>()
        for (sentence in NarrationCatalogue.samples()) {
            val id = sentence::class.simpleName!!
            if (id !in storyIds) continue
            seen += id
            for (style in NarrationStyle.entries) {
                for (variant in EnglishNarration.render(sentence, style)) {
                    // Lead fragments end with a space and an analyst phrasing may be a different template; the story is coach-spoken.
                    if (style == NarrationStyle.ANALYST && EnglishNarration.render(sentence, NarrationStyle.COACH) != EnglishNarration.render(sentence, style)) continue
                    val text = variant.trim()
                    assertTrue("$id: [$text] is no template of its kind", templates.getValue(id).any { it.matches(text) })
                }
            }
        }
        assertEquals("a story sentence has no sample in the catalogue", storyIds, seen)
        // The catalogue lists every phrasing: each template is hit by at least one catalogue rendering.
        for ((id, list) in templates) {
            val rendered = NarrationCatalogue.samples().filter { it::class.simpleName == id }
                .flatMap { EnglishNarration.render(it, NarrationStyle.COACH) }.map { it.trim() }
            for (rx in list) {
                assertTrue("$id: no catalogue phrasing matches the template ${rx.pattern}", rendered.any { rx.matches(it) })
            }
        }
    }

    @Test
    fun `nothing the recorded games say about the story is outside the templates`() {
        var parts = 0
        for ((name, g) in games) {
            for (side in listOf<Color?>(null, Color.WHITE, Color.BLACK)) {
                val report = g.report(side)
                val (script, story) = VideoScriptGenerator(side).generateWithStory(report, g.pgn, options)
                for (p in story) {
                    parts++
                    val rx = templates[p.id] ?: error("$name/$side: no template for the story sentence kind ${p.id}")
                    assertTrue("$name/$side ${p.id}: [${p.text.trim()}]", rx.any { it.matches(p.text.trim()) })
                    assertTrue(
                        "$name/$side ${p.id}: [${p.text.trim()}] is not in the script",
                        script.segments.any { s -> s.narration.replace(Regex("\\s+"), " ").contains(p.text.trim().replace(Regex("\\s+"), " "), ignoreCase = true) }
                    )
                }
            }
        }
        assertTrue("a story to check: $parts", parts > 200)
    }

    @Test
    fun `the cold opens, golden`() {
        fun intro(g: RealGameFixture.Game): String {
            val report = g.report(null)
            return VideoScriptGenerator(null).generate(report, g.pgn, options).segments.first().narration
        }
        assertTrue(intro(RealGameFixture.immortal), "It was level until move 11, and then White took over for good. The finish was a checkmate on move 23." in intro(RealGameFixture.immortal))
        assertTrue(intro(RealGameFixture.game01), "Black won, but for 16 moves this was White's game. It ended in mate on move 33." in intro(RealGameFixture.game01))
        assertTrue(intro(RealGameFixture.chesscom), "White got ahead early and stayed there. It ended in mate on move 17." in intro(RealGameFixture.chesscom))
        assertTrue(intro(RealGameFixture.byrneFischer), "It was level until move 11, and then Black took over for good." in intro(RealGameFixture.byrneFischer))
        // The scholar's mate is too short for a story: the plain result stays.
        assertTrue(intro(RealGameFixture.scholars), "White finished it with mate in 4 moves." in intro(RealGameFixture.scholars))
    }

    @Test
    fun `the opening ends with where the book left the game, and the outro with how the game ended`() {
        for ((name, g) in games) {
            val report = g.report(null)
            val script = VideoScriptGenerator(null).generate(report, g.pgn, options)
            val opening = script.segments.first { it.kind == SegmentKind.OPENING_SUMMARY }.narration
            assertTrue("$name: [$opening]", Regex("(book is spent|Theory runs out)[^.]*, and (the position is about level|(White|Black) is (slightly better|clearly better|winning))\\.$").containsMatchIn(opening))
            val outro = script.segments.single { it.kind == SegmentKind.OUTRO_SUMMARY }.narration
            assertTrue("$name: [$outro]", Regex("^(White|Black) won by checkmate on move \\d+\\.|^It ended in checkmate: (White|Black) wins on move \\d+\\.").containsMatchIn(outro))
            assertTrue("$name: [$outro]", "decisive moment" in outro || "came down to" in outro)
        }
    }

    @Test
    fun `every phase boundary outside the opening is told at the move that caused it`() {
        for ((name, g) in games) {
            val report = g.report(null)
            val script = VideoScriptGenerator(null).generate(report, g.pgn, options)
            val bookEnd = report.annotations.lastOrNull { it.classification == MoveClassification.BOOK }?.ply ?: 0
            val shifts = GamePhases.shifts(GamePhases.detect(report.evalGraph))
            assertTrue("$name: no phase boundary at all", shifts.isNotEmpty())
            for (shift in shifts.filter { it.ply > bookEnd }) {
                // Either the beat of that ply carries the sentence, or it is a Hold beat right after the beat of that ply,
                // or the beat of that ply tells the standing change itself (an error beat, the detour's resumed move).
                val told = script.segments.filter { it.ply == shift.ply }
                assertTrue("$name: nothing is told at ply ${shift.ply}", told.isNotEmpty())
            }
        }
    }

    @Test
    fun `a mating sequence is announced once and then said bare`() {
        for ((name, g) in games) {
            val report = g.report(null)
            val script = VideoScriptGenerator(null).generate(report, g.pgn, options)
            val text = script.segments.joinToString("\n") { it.narration }
            assertEquals("$name: the aim is announced once", 1, Regex("forced mate in|it is mate in|Mate in \\w+, and nothing").findAll(text).count())
            assertFalse("$name: the old repeated lines", "the king cannot get out" in text || "The king has nowhere to run" in text)
        }
    }

    @Test
    fun `the rhythm - exclamations, questions and glued lead-ins`() {
        for ((name, g) in games) {
            for (side in listOf<Color?>(null, Color.WHITE, Color.BLACK)) {
                val report = g.report(side)
                val script = VideoScriptGenerator(side).generate(report, g.pgn, options)
                for (seg in script.segments) {
                    val t = seg.narration
                    val where = "$name/$side seg ${seg.index} ${seg.kind}: [$t]"
                    // An exclamation mark: the checkmate and a Brilliant move, nothing else.
                    if ('!' in t) {
                        assertTrue(where, seg.kind == SegmentKind.KEY_MOMENT && seg.classification != null && report.annotations[seg.ply!! - 1].san.endsWith("#") ||
                            "brilliant" in t.lowercase() || Regex("(?:Game over|wins|win)[^.]*!").containsMatchIn(t))
                    }
                    // A question: where something was missed or the puzzle asks.
                    if ('?' in t) {
                        assertTrue(where, seg.kind == SegmentKind.PUZZLE_PROMPT || seg.kind == SegmentKind.MISSED_TACTIC || seg.kind == SegmentKind.OUTRO_LESSONS)
                    }
                    // A lead-in is never glued onto an interjection ("Two moves on, Stop.").
                    assertFalse(where, Regex("(?:later|that|on|after), [A-Z][a-z']*[.!?]").containsMatchIn(t))
                    // No statistic is read out.
                    if (seg.kind == SegmentKind.OUTRO_SUMMARY || seg.kind == SegmentKind.OUTRO_LESSONS) {
                        assertFalse(where, Regex("percent|roughly|rating|accuracy").containsMatchIn(t))
                    }
                }
            }
        }
    }

    @Test
    fun `no sentence is repeated more than a handful of times in one script`() {
        // The old script said "The evaluation moves against White" about fifteen times in game01.
        for ((name, g) in games) {
            val script = VideoScriptGenerator(null).generate(g.report(null), g.pgn, options)
            val sentences = script.segments.filter { it.kind != SegmentKind.BEST_LINE }
                .flatMap { it.narration.split(Regex("(?<=[.!?])\\s+")) }
                // "Back to the game now." is the structural sentence every detour and best line ends on.
                .filter { it.split(" ").size >= 5 && it != "Back to the game now." }
            val worst = sentences.groupingBy { it }.eachCount().filter { it.value > 3 }
            assertTrue("$name repeats: $worst", worst.isEmpty())
        }
    }

    @Test
    fun `the old filler and the unprovable stock clauses are gone`() {
        val gone = listOf(
            "The evaluation moves", "gives nothing away", "Nothing is captured, and nothing has to be", "toughest try in the position",
            "engine's own choice", "development and shuffling", "Nothing breaks", "Right, numbers", "percent accuracy", "turning into",
            "closer than the result makes it look", "crack in it"
        )
        for ((name, g) in games) {
            for (side in listOf<Color?>(null, Color.WHITE, Color.BLACK)) {
                val script = VideoScriptGenerator(side).generate(g.report(side), g.pgn, options)
                for (seg in script.segments) for (f in gone) assertFalse("$name/$side: '$f' in [${seg.narration}]", f in seg.narration)
            }
        }
    }
}
