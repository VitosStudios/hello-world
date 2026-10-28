// Root project. Plugins are provided by buildSrc; see buildSrc/build.gradle.kts.
tasks.register("roundTripTests") {
    group = "verification"
    description = "Runs the round-trip and edit tests of every format module."
    dependsOn(":format:docx:test", ":format:xlsx:test", ":format:pptx:test", ":core:opc:test", ":core:xml:test")
}
