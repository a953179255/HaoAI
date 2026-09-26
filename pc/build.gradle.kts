plugins {
    kotlin("jvm") version "2.4.10"
    application
}

// 刻意把依赖压到一件：JSON 用 kotlinx.serialization 的**动态 API**（不写 @Serializable，
// 因此不需要序列化编译器插件），HTTP 与本地服务器全用 JDK 自带的
// java.net.http.HttpClient / com.sun.net.httpserver.HttpServer。
// 理由：手机端那套引擎在 JVM 单测里已经证明能脱开 Android 跑，PC 端没必要再拖
// OkHttp/协程/Ktor 进来增加解析与启动面。
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.haoai.pc.MainKt")
}

// 不写 jvmToolchain：那玩意在找不到匹配 JDK 时会去**下载**一个，本机是共享环境、
// 网络还时好时坏，宁可跟着 gradle 自己的 JVM 走（当前 = Android Studio 的 jbr 21）。

tasks.withType<JavaExec>().configureEach {
    standardInput = System.`in`
}

// `gradle run --args="task '统计 pc 下有多少行 kt'"`
tasks.named<JavaExec>("run") {
    jvmArgs("-Dfile.encoding=UTF-8")
}

/**
 * 打一个**自带 JVM** 的 Windows 应用目录：`build/package/HaoAI-PC/HaoAI-PC.exe`。
 *
 * 为什么要这一步：在那之前"PC 端 HaoAI"是"一个要会敲 gradle 的仓库"，
 * 之后才是"一个能双击、能放进启动菜单的东西"。
 *
 * `jpackage` 只在完整 JDK 里有（Android Studio 自带的 jbr 是**运行时**，没有它），
 * 所以路径从环境来：设 `JPACKAGE` 指向 jpackage，或让它自己在 PATH 里。
 * 用 `--type app-image` 而不是 installer：不碰注册表、不需要管理员、解压即用、删文件夹即卸载。
 */
val packageExe by tasks.registering(Exec::class) {
    group = "distribution"
    description = "生成 Windows 应用目录（HaoAI-PC.exe + 自带 runtime）"
    dependsOn("installDist")
    val jp = System.getenv("JPACKAGE") ?: "jpackage"
    val lib = layout.buildDirectory.dir("install/haoai-pc/lib").get().asFile
    val out = layout.buildDirectory.dir("package").get().asFile
    doFirst {
        if (!file(lib).isDirectory) error("先跑 installDist")
        commandLine(
            jp, "--type", "app-image", "--name", "HaoAI-PC", "--app-version", "0.1.0",
            "--vendor", "HaoAI", "--win-console",
            "--input", lib.absolutePath,
            "--main-jar", "haoai-pc.jar",
            "--main-class", "com.haoai.pc.MainKt",
            "--dest", out.absolutePath,
            "--java-options", "-Dfile.encoding=UTF-8",
            "--java-options", "-Dstdout.encoding=UTF-8",
            "--java-options", "-Dstderr.encoding=UTF-8"
        )
    }
}
