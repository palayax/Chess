package net.palaya.chessanalyzer.desktop.work

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The one JSON configuration every work-dir artifact is written and read with
 * (docs/PC_PRODUCER_DESIGN.md §2): pretty-printed so a human can diff two runs, nulls omitted
 * (`explicitNulls = false`, so an absent `mateIn` reads back as null), defaults written so a
 * field's value never depends on which code version wrote it, unknown keys tolerated so an older
 * binary can still read a newer artifact's known fields.
 */
@OptIn(ExperimentalSerializationApi::class)
val WorkJson: Json = Json {
    prettyPrint = true
    explicitNulls = false
    encodeDefaults = true
    ignoreUnknownKeys = true
}
