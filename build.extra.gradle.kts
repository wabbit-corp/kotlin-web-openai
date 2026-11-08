// These types occur in the public client/DTO signatures. Consumers must see them on the
// compile classpath, not only at runtime. Keep this correction in the generated build's
// supported extension point rather than editing setup-managed build.gradle.kts.
dependencies {
    add("commonMainApi", "one.wabbit:kotlin-web-common:1.2.0")
    add("commonMainApi", "io.ktor:ktor-client-core:3.3.0")
    add("commonMainApi", "org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")
    add("commonMainApi", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    add("commonMainApi", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
