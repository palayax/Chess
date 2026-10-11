package net.palaya.chessanalyzer.data

import java.io.File
import net.palaya.chessanalyzer.ui.model.ReviewSpeeds
import net.palaya.chessanalyzer.ui.model.ReviewStage
import org.json.JSONObject

/**
 * What this phone measured in earlier reviews ([ReviewSpeeds]): the engine's milliseconds per node and each other
 * stage's milliseconds per game ply. Read by the Review options screen (a strength's rough time) and by the processing
 * screen (the estimate of a stage that has not started). Device-specific, so it lives in `filesDir/review/` (excluded
 * from backup: another phone's speed would be a wrong estimate). One small JSON file, temp file then rename.
 */
class ReviewSpeedStore(filesDir: File) {

    private val file = File(filesDir, "${GameRepository.REVIEW_DIR_NAME}/$FILE_NAME")
    private val tmp = File(filesDir, "${GameRepository.REVIEW_DIR_NAME}/$FILE_NAME.tmp")

    @Synchronized
    fun load(): ReviewSpeeds {
        if (!file.isFile) return ReviewSpeeds()
        return try {
            val json = JSONObject(file.readText())
            val perPly = HashMap<ReviewStage, Double>()
            json.optJSONObject("msPerPly")?.let { o ->
                for (stage in ReviewStage.entries) if (o.has(stage.name)) perPly[stage] = o.getDouble(stage.name)
            }
            ReviewSpeeds(
                engineMsPerNode = if (json.has("engineMsPerNode")) json.getDouble("engineMsPerNode") else null,
                msPerPly = perPly,
            )
        } catch (e: Exception) {
            file.delete()
            ReviewSpeeds()
        }
    }

    @Synchronized
    fun save(speeds: ReviewSpeeds) {
        file.parentFile?.mkdirs()
        val json = JSONObject().apply {
            speeds.engineMsPerNode?.let { put("engineMsPerNode", it) }
            put("msPerPly", JSONObject().apply { speeds.msPerPly.forEach { (stage, rate) -> put(stage.name, rate) } })
        }
        tmp.writeText(json.toString())
        file.delete()
        tmp.renameTo(file)
    }

    /** Applies [change] to the stored speeds and saves the result. */
    @Synchronized
    fun update(change: (ReviewSpeeds) -> ReviewSpeeds) = save(change(load()))

    companion object {
        const val FILE_NAME = "speeds.json"
    }
}
