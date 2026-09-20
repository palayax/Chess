package net.palaya.chessanalyzer

/**
 * Marks an instrumented "test" that is really an evidence-producing tool: it renders artifacts a
 * human has to look at (or listen to) rather than asserting a behaviour the suite should police
 * on every run.
 *
 * Excluded from `connectedDebugAndroidTest` by `notAnnotation` in `app/build.gradle.kts`, for two
 * reasons:
 *  - it is slow (the voice sweep synthesizes the same paragraph through twelve different voices);
 *  - Gradle **uninstalls the app when the connected run finishes**, which deletes
 *    `/sdcard/Android/data/<pkg>/` and every artifact written there before anyone can `adb pull`
 *    it (see `CLAUDE.md`). Running it under Gradle would therefore produce nothing anyway.
 *
 * Run one deliberately, bypassing Gradle, exactly as `CLAUDE.md` describes:
 * ```
 * adb install -r -t app/build/outputs/apk/debug/app-debug.apk
 * adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w -r -e class '<fqcn>#<method>' \
 *     net.palaya.chessanalyzer.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * `am instrument` takes its filters from the command line only, so the `notAnnotation` exclusion
 * does not apply there.
 *
 * This is NOT an escape hatch for a flaky or slow assertion. Anything that asserts a behaviour
 * belongs in the suite, where `skipped="0"` can police it.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class ManualEvidenceTool
