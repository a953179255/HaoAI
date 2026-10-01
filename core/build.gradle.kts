// B15：两端共用的 `:core`（会话模型与工具接口的地基）。
//
// 为什么它和 pc 一样**自带 settings**（见本目录无 settings.gradle.kts？错——它属于根构建）：
// 根构建是 AGP 9.4 + 内置 Kotlin；实测 `kotlin("jvm")` 不带版本在根构建里解析不到、
// 带 2.4.10 反而一路绿（pc/settings 注释里记的"同 classpath 版本打架"在 9.4 上没有复现）。
// 所以 :core 是**根构建的真子项目**：根构建直接 `:core:test`，app 直接 `project(":core")`，
// PC 端是另一个构建，走 `publishToMavenLocal` 消费（与 pc/settings 注释里的 Phase 1 一致）。
plugins {
    kotlin("jvm") version "2.4.10"
    `maven-publish`
}

group = "com.haoai"
version = "0.1.0"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

// 刻意不写 java { sourceCompatibility }：纯 Kotlin 模块的 compileJava 是 NO-SOURCE，
// 写了反而和 Kotlin 的默认 JVM target（跟随当前 JDK）打架 —— 第一版就是这么红的。

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "core"
        }
    }
}
