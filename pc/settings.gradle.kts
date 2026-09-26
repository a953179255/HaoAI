// HaoAI PC 端 —— 独立 Gradle 构建（同仓库、不同构建根）。
//
// 为什么先不并进根 settings.gradle.kts：根构建是**正在出货的手机 App**，
// 而 AGP 9 的"内置 Kotlin"与 kotlin.jvm 插件在同一个 buildSrc classpath 上
// 版本声明会互相打架（"plugin already on the classpath must not include a version"）。
// 为了让手机侧零风险，PC 端先自带一份 settings，跑通后再随 :core 抽取并进来 ——
// 这一步在方案文档的 Phase 1 里本来就欠着，别把它当成品结构。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name = "haoai-pc"
