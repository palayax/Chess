package net.palaya.chessanalyzer.core.chess

/**
 * Zobrist hashing table used for cheap, incrementally-maintained position keys
 * ([Position.zobristKey]), which in turn power threefold-repetition detection
 * (see [RepetitionTracker]). Not used by move generation or perft.
 */
internal object Zobrist {
    private val random = java.util.Random(0x5EED_C0FFEEL)

    // Index 0 (empty) is unused; indices 1..12 correspond to the piece codes in Position.
    val pieceKeys: Array<LongArray> = Array(13) { LongArray(64) { random.nextLong() } }
    val sideToMoveKey: Long = random.nextLong()

    // One key per castling-right bit (see Position.CASTLE_WK etc.).
    val castlingBitKeys: LongArray = LongArray(4) { random.nextLong() }

    // One key per en-passant file (a..h).
    val enPassantFileKeys: LongArray = LongArray(8) { random.nextLong() }

    /** XOR of the keys for every set bit in [mask] (a 4-bit castling-rights mask). */
    fun castlingKey(mask: Int): Long {
        var key = 0L
        for (bit in 0..3) {
            if ((mask shr bit) and 1 == 1) key = key xor castlingBitKeys[bit]
        }
        return key
    }
}
