// 独立的 JVM 构建：只编译 app 里那部分纯 Kotlin 核心逻辑（core/），
// 让算法层在没有 Android SDK 的环境下也能编译并跑单元测试。
buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.0.21")
    }
}
apply(plugin = "org.jetbrains.kotlin.jvm")
apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

repositories { mavenCentral() }

dependencies {
    "implementation"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    "testImplementation"(kotlin("test"))
}

extensions.configure<org.gradle.api.plugins.JavaPluginExtension>("java") {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
    sourceSets.named("main") { kotlin.srcDir("../app/src/main/kotlin/com/ecs/core") }
    sourceSets.named("test") { kotlin.srcDir("src/test/kotlin") }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
