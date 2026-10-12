package net.palaya.chessanalyzer.ui.model

import kotlin.random.Random

/*
 * The rotation of the "While you wait" cards (docs/WAITING_CONTENT.md §4). Pure Kotlin and fully deterministic: the
 * order comes from a seeded shuffle, the clock is a parameter, nothing reads a real timer, so every rule is a host
 * test (WaitingSchedulerTest).
 *
 * The rules:
 *  - The live insights card comes first, and again whenever the insights have changed since it was last shown, but
 *    never twice in a row: between two insights cards there is always a fun fact, a player, a game or a puzzle.
 *  - Every other card is drawn from a seeded order that mixes the four kinds and never repeats a card until every
 *    card has been shown once (a session would need over half an hour of waiting to get there).
 *  - A card rotates away on its own only after its dwell time, and never while the user is using the panel: not for
 *    TOUCH_HOLD_MS after the last touch, not while a puzzle that has been started is unfinished, and not at all
 *    when the panel is paused or auto-rotation is off (TalkBack).
 */

/** What a card shows. */
sealed interface WaitingCard {
    /** A stable key: the same card has the same key, so state (a half-solved puzzle) can be kept per card. */
    val key: String

    /** The live insights; [page] says which slice of the list this showing displays (it moves on at each showing). */
    data class Insights(val page: Int) : WaitingCard {
        override val key: String get() = "insights-$page"
    }

    data class Fact(val fact: WaitingFact) : WaitingCard {
        override val key: String get() = "fact-${fact.id}"
    }

    data class Player(val player: WaitingPlayer) : WaitingCard {
        override val key: String get() = "player-${player.id}"
    }

    data class Game(val game: WaitingGame) : WaitingCard {
        override val key: String get() = "game-${game.game.id}"
    }

    data class Puzzle(val puzzle: WaitingPuzzle) : WaitingCard {
        override val key: String get() = "puzzle-${puzzle.id}"
    }
}

/** The order the kinds are served in: facts and puzzles most often, because they are the shortest to enjoy. */
private val KIND_PATTERN = listOf('F', 'P', 'G', 'F', 'L', 'P')

/**
 * The seeded source of cards other than the insights card. [draw] returns every card of [content] once, in a
 * kind-interleaved order fixed by [seed], and starts a new shuffled round only when all of them have been served
 * (the first card of a new round is never the last card of the old one).
 */
class WaitingDeck(private val content: WaitingContent, seed: Long) {
    private val random = Random(seed)
    private val queues = HashMap<Char, ArrayDeque<WaitingCard>>()
    private var patternIndex = 0
    private var last: WaitingCard? = null

    /** The total number of cards (not counting insights). */
    val size: Int = content.facts.size + content.players.size + content.games.size + content.puzzles.size

    private fun fill() {
        queues['F'] = ArrayDeque(content.facts.map { WaitingCard.Fact(it) }.shuffled(random))
        queues['L'] = ArrayDeque(content.players.map { WaitingCard.Player(it) }.shuffled(random))
        queues['G'] = ArrayDeque(content.games.map { WaitingCard.Game(it) }.shuffled(random))
        queues['P'] = ArrayDeque(content.puzzles.map { WaitingCard.Puzzle(it) }.shuffled(random))
    }

    /** The next card, or null when the content is empty. */
    fun draw(): WaitingCard? {
        if (size == 0) return null
        if (queues.isEmpty() || queues.values.all { it.isEmpty() }) {
            fill()
            // A new round must not open with the card that closed the last one.
            val previous = last
            if (previous != null && size > 1) {
                val kindOf = kindChar(previous)
                val q = queues.getValue(kindOf)
                if (q.firstOrNull()?.key == previous.key && q.size > 1) q.addLast(q.removeFirst())
            }
        }
        // Walk the pattern until a kind that still has cards.
        repeat(KIND_PATTERN.size) {
            val kind = KIND_PATTERN[patternIndex % KIND_PATTERN.size]
            patternIndex++
            val q = queues[kind]
            if (q != null && q.isNotEmpty()) return q.removeFirst().also { last = it }
        }
        // The pattern's kinds are all empty (can happen only for a kind absent from the pattern, which none is).
        return queues.values.firstOrNull { it.isNotEmpty() }?.removeFirst()?.also { last = it }
    }

    private fun kindChar(card: WaitingCard): Char = when (card) {
        is WaitingCard.Fact -> 'F'
        is WaitingCard.Player -> 'L'
        is WaitingCard.Game -> 'G'
        is WaitingCard.Puzzle -> 'P'
        is WaitingCard.Insights -> 'I'
    }
}

/** The timings, in milliseconds. */
object WaitingTiming {
    /** A fact, a player or an insights card stays this long before it rotates. */
    const val DWELL_TEXT_MS = 12_000L

    /** A game card (a board to look at) stays a little longer. */
    const val DWELL_GAME_MS = 15_000L

    /** An untouched puzzle stays long enough to read the position and think. */
    const val DWELL_PUZZLE_MS = 40_000L

    /** After any touch on the panel nothing rotates away for this long. */
    const val TOUCH_HOLD_MS = 25_000L

    /** How many insights one showing of the insights card lists. */
    const val INSIGHTS_PER_PAGE = 3
}

/**
 * Everything the panel remembers. [history] holds the cards shown so far; [cursor] points at the current one, so
 * going back and forward works. [engaged] is true while the user has started the current puzzle and not finished it.
 */
data class WaitingPanelState(
    val history: List<WaitingCard>,
    val cursor: Int,
    val shownAtMs: Long,
    val lastTouchMs: Long?,
    val paused: Boolean,
    val engaged: Boolean,
    /** The insights signature at the moment the insights card was last shown (null = never). */
    val insightsSeen: String?,
    val insightsShown: Int,
) {
    val current: WaitingCard get() = history[cursor]
    val canGoBack: Boolean get() = cursor > 0
}

/**
 * The reducer over [WaitingPanelState]. One instance per session; it owns the [WaitingDeck] (which is stateful), so
 * a state must always be advanced by the controller that made it.
 */
class WaitingPanelController(content: WaitingContent, seed: Long) {
    private val deck = WaitingDeck(content, seed)

    /** True when there is nothing to show but the insights. */
    val hasDeck: Boolean get() = deck.size > 0

    fun start(nowMs: Long, signature: String): WaitingPanelState = WaitingPanelState(
        history = listOf(WaitingCard.Insights(page = 0)),
        cursor = 0,
        shownAtMs = nowMs,
        lastTouchMs = null,
        paused = false,
        engaged = false,
        insightsSeen = signature,
        insightsShown = 1,
    )

    /** How long the current card stays before it rotates on its own. */
    fun dwellMs(card: WaitingCard): Long = when (card) {
        is WaitingCard.Game -> WaitingTiming.DWELL_GAME_MS
        is WaitingCard.Puzzle -> WaitingTiming.DWELL_PUZZLE_MS
        else -> WaitingTiming.DWELL_TEXT_MS
    }

    /** True when the panel may rotate by itself at [nowMs]. */
    fun mayRotate(state: WaitingPanelState, nowMs: Long, autoRotate: Boolean): Boolean {
        if (!autoRotate || state.paused || state.engaged) return false
        if (state.cursor != state.history.lastIndex) return false
        state.lastTouchMs?.let { if (nowMs - it < WaitingTiming.TOUCH_HOLD_MS) return false }
        return nowMs - state.shownAtMs >= dwellMs(state.current)
    }

    /** Called about once a second: rotates when the rules allow it. */
    fun tick(state: WaitingPanelState, nowMs: Long, signature: String, autoRotate: Boolean = true): WaitingPanelState =
        if (mayRotate(state, nowMs, autoRotate)) advance(state, nowMs, signature) else state

    /** Next (arrow, swipe or button): forward in the history, or a new card at its end. */
    fun next(state: WaitingPanelState, nowMs: Long, signature: String): WaitingPanelState {
        if (state.cursor < state.history.lastIndex) {
            return state.copy(cursor = state.cursor + 1, shownAtMs = nowMs, engaged = false)
        }
        return advance(state, nowMs, signature)
    }

    fun previous(state: WaitingPanelState, nowMs: Long): WaitingPanelState =
        if (state.canGoBack) state.copy(cursor = state.cursor - 1, shownAtMs = nowMs, engaged = false) else state

    /** The user touched the panel: nothing rotates away for [WaitingTiming.TOUCH_HOLD_MS]. */
    fun touch(state: WaitingPanelState, nowMs: Long): WaitingPanelState = state.copy(lastTouchMs = nowMs)

    fun setPaused(state: WaitingPanelState, paused: Boolean, nowMs: Long): WaitingPanelState =
        state.copy(paused = paused, shownAtMs = if (paused) state.shownAtMs else nowMs)

    /** The current puzzle was started (true) or finished (false). */
    fun setEngaged(state: WaitingPanelState, engaged: Boolean, nowMs: Long): WaitingPanelState =
        state.copy(engaged = engaged, lastTouchMs = nowMs)

    private fun advance(state: WaitingPanelState, nowMs: Long, signature: String): WaitingPanelState {
        val showInsights = state.current !is WaitingCard.Insights && signature != state.insightsSeen
        val card = if (showInsights || !hasDeck) {
            WaitingCard.Insights(page = state.insightsShown)
        } else {
            deck.draw() ?: WaitingCard.Insights(page = state.insightsShown)
        }
        val isInsights = card is WaitingCard.Insights
        return state.copy(
            history = state.history + card,
            cursor = state.history.size,
            shownAtMs = nowMs,
            engaged = false,
            insightsSeen = if (isInsights) signature else state.insightsSeen,
            insightsShown = if (isInsights) state.insightsShown + 1 else state.insightsShown,
        )
    }
}

/** The slice of [insights] one showing of the insights card lists: [WaitingTiming.INSIGHTS_PER_PAGE], wrapping around. */
fun insightsPage(insights: List<LiveInsight>, page: Int): List<LiveInsight> {
    if (insights.isEmpty()) return emptyList()
    val per = WaitingTiming.INSIGHTS_PER_PAGE
    if (insights.size <= per) return insights
    val start = (page * per) % insights.size
    return List(per) { insights[(start + it) % insights.size] }
}
