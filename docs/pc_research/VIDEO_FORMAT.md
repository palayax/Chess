# Research: format spec for an automated "chess recap" analysis video

Round 12, task 52. This came from a research subagent and has been lightly edited. It copies the
format, pacing and energy only: no names, catchphrases, voice or likeness. **[S]** marks the
agent's own synthesis. Sourced claims carry links.

## 1. Structure (target 8–14 min)

| Segment | Share | Content |
|---|---|---|
| **Cold open** | 0:00–0:15 | Jump to the climax position with the eval bar frozen, one line of narration ("One move here flips +4 into getting mated."), then cut. No logo and no slow branded intro. Drop-off is steepest in the first 30 s ([prepublish.ai](https://prepublish.ai/guides/first-30-seconds), [socialrails](https://socialrails.com/blog/youtube-audience-retention-complete-guide)). |
| **Intro / player cards** | ~15 s | Player names, ratings (or hide them for "guess the rating"), time control, result. |
| **Opening** | 10–15% | Name the opening from ECO data by moves 3–6. Auto-play book moves at 0.6–1 s each, with one comment every 3–4 moves. |
| **Middlegame** | 40–50% | Slow down at every eval swing or classified move. |
| **Climax** | 20–25% | The biggest swing. Pause and ask the viewer, reveal, show the variation, rewind, then show the eval graph ([ESPN on agadmator's pauses](https://www.espn.com/chess/story/_/id/29591948/meet-antonio-radic-creator-agadmator-most-popular-chess-channel-youtube)). |
| **Ending / outro** | ~10% | Speed-run the rest, then a recap card (accuracy, key moment, eval graph) and a 5 s call to action. |

## 2. Pacing tiers [S]

- **Skip:** auto-play with no narration. Covers book moves, best/excellent moves with Δeval < 0.3, and forced recaptures. Batch 3–6 of them and cover them in one sentence.
- **Brief (3–6 s):** inaccuracies and plan changes.
- **Dwell (15–30 s):** mistakes and "great" moves.
- **Full (40–90 s):** blunders, brilliancies and missed wins, at most 3–4 per video. The beat is: ask the viewer (2–3 s freeze with a "Find the move" caption), reveal, react, variation.
- **What-if variations:** a "VARIATION" banner plus a tinted board or border, 2–6 engine moves at about 0.8 s each with the eval bar live, then rewind at about 0.15 s per move with a whoosh, and "back to the game".

## 3. Visual language

- **Layout (16:9, no facecam) [S]:**
  - The board takes 60–65% of the frame height, left of centre, with a thin eval bar on its left edge.
  - The right panel has player cards at the top and bottom, a move list with icons, and a caption area.
  - An optional eval graph strip runs under the board.
- **Facecam replacement:**
  - A reactive avatar or mascot with idle, happy, shocked and facepalm states driven by eval swing.
  - Large punchline captions.
  - Player cards that shake or flash red on a blunder.
- **Classification colours** follow the common convention: brilliant teal, great blue, best/excellent green, book brown, inaccuracy yellow, mistake orange, blunder red ([chess.com help](https://support.chess.com/en/articles/8572705-how-are-moves-classified-what-is-a-blunder-or-brilliant-etc)). **Draw our own icon art.** Badges pop in on the destination square over 150 ms.
- **Arrows [S]:**
  - Green: the best or engine move.
  - Red: a threat or refutation.
  - Blue: an idea or plan.
  - Yellow: the move played, when comparing.
  - Hanging pieces get a pulsing ring.
- **Animation [S]:** 250–350 ms per move, 150 ms when skipping. A 1.3× zoom only at the climax, plus one short shake on a blunder.
- **Captions:** burned-in word-level subtitles of 2–4 words with the key word highlighted, plus punchline text cards.

## 4. Narration style

Energetic, honest and self-deprecating, and it always explains the chess
([chess.com blog](https://www.chess.com/blog/The-Chapper/levy-rozman-the-internets-chess-teacher-who-made-millions-love-the-game)).

Techniques [S]:
- Rhetorical questions, and voicing what the player might be thinking.
- Personified pieces ("the knight has been on vacation since move 9").
- Reactions scaled to the eval swing.
- Running gags built from game data.
- Tactics named in plain English.
- **Always one sentence of *why* after the joke.**

Original example lines:
- **Blunder:** "White is completely winning, up a full rook, and decides the queen needs some fresh air. On the one square where a knight can fork it. The eval bar just fell down the stairs."
- **Brilliancy:** "Wait, wait. Black is giving the rook away? On purpose? Take it and the bishop comes through on the long diagonal. Mate is coming. That is a move you frame and hang on the wall."
- **Missed tactic:** "Pause here. There's a move that wins on the spot. Three, two, one. Knight takes f7 forks king and queen. And what did our friend play instead? Pawn to h3, the universal I-have-no-idea move."
- **Boring opening:** "e4, e5, knights come out, bishops come out. Everybody's following the recipe. Nothing to see yet, keep scrolling."

The LLM prompt carries style rules and a **ban-list of real creators' catchphrases**.

## 5. Audio

- **Levels:** voice around −16 LUFS, final mix at −14 LUFS. Music sits 20–30 dB under the voice with sidechain ducking ([Descript](https://www.descript.com/blog/article/how-to-set-the-perfect-audio-levels-for-video)).
- **Music drops out** for about 1 s before a reveal. Use a tense loop for "find the move" and an upbeat bed for the opening and outro [S].
- **SFX:** move click, capture thud, check tick, a chime for brilliant, "wah-wah" for blunder, a whoosh on rewind, and a riser into the climax. Keep them at about −10 to −20 dB.
- **License-clean sources:**
  - [Kenney.nl](https://kenney.nl/assets/category:Audio): CC0, no account, safe to bundle.
  - FreePD CC0 music: the site closed, but a mirror is at [archive.org/details/freepd](https://archive.org/details/freepd).
  - Pixabay: free, but raw files may not be redistributed, so ship them as rendered content only.
- **Avoid:**
  - Lichess sound sets (AGPLv3 / CC BY-NC-SA).
  - Freesound (needs an account).

## 6. Build order

1. Moment selection and pacing tiers (the watchability core).
2. A personality script plus TTS, with captions.
3. A board renderer with arrows, highlights, badges and a live eval bar.
4. Variation show-and-rewind.
5. Cold open and outro recap.

**Nice-to-have:** reactive avatar, zoom/shake, automated ducking, running gags, a Guess-the-Elo mode, a
vertical Shorts cut.

**Caveat:** no detailed published analysis of GothamChess's editing structure was found, so the timings,
colours and tiers are synthesis.
