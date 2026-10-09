package net.palaya.chessanalyzer.rephrase

/**
 * Marks an instrumented test that produces evidence by hand (a measurement on the owner's phone) rather than
 * checking a contract. Excluded from `connectedDebugAndroidTest` (the module's testInstrumentationRunnerArguments
 * "notAnnotation"), run with `am instrument -e class ...` instead, as :app's ManualEvidenceTool.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class ManualEvidenceTool
