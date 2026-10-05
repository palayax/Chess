// :desktop — the PC video producer ("palaya-review"), docs/PC_PRODUCER_DESIGN.md.
//
// Run it from the installDist output (desktop/build/install/palaya-review/bin/palaya-review.bat),
// never via `gradlew run`: producing a video must not hold the Gradle lock (design §1.1).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

application {
    applicationName = "palaya-review"
    mainClass.set("net.palaya.chessanalyzer.desktop.cli.Main")
}

// The opening book is the app's own asset, copied at build time rather than duplicated in the
// tree (design §2). PIECES_LICENSE.txt travels with it for the renderer phases.
tasks.processResources {
    from("../app/src/main/assets") { include("openings.tsv", "PIECES_LICENSE.txt") }
}

tasks.withType<Test> {
    useJUnit()
    maxHeapSize = "2g"
    // The tests drive the real Stockfish binary under pc/bin, which Gradle cannot track as an
    // input. A cached or up-to-date "pass" would therefore be vacuous: always execute.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    systemProperty("palaya.repoRoot", rootDir.absolutePath)
    // -Ppalaya.record=true (re-)records regression fixtures such as immortal_depth14.json.
    (project.findProperty("palaya.record") as String?)?.let { systemProperty("palaya.record", it) }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
