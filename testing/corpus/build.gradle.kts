// Shared test support: corpus discovery, part-by-part comparison and the second-reader
// (LibreOffice) harness. Only ever used as a test dependency.
plugins {
    id("suite.jvm-library")
}

dependencies {
    api(project(":core:opc"))
    api(kotlin("test-junit"))
    api(libs.junit)
}
