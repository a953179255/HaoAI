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

/*
 * 测试的临时目录关进一个专用子目录，跑完就删。
 *
 * 起因：%TEMP% 里堆了五千多个 `haoai-*` 目录 —— 每条测试都用 `Files.createTempDirectory`
 * 造一个工作区/状态根，用完没人收。改调用点要动 11 个文件，而 `java.io.tmpdir` 只有一处。
 *
 * 但不能指到 `build/` 底下：那还在 HaoAI 这个 git 仓库里面，`GitToolTest` 靠"临时目录不在
 * 任何仓库里"这条前提跑，指进 build 之后它当场假失败（"不在仓库里却成功了"）。
 * 所以放在系统 TEMP 下的专用目录里，跑完整个删掉。
 */
val testTmp = File(System.getProperty("java.io.tmpdir"), "haoai-pc-test-tmp")
tasks.test {
    doFirst { testTmp.mkdirs() }
    systemProperty("java.io.tmpdir", testTmp.absolutePath)
    doLast { runCatching { testTmp.deleteRecursively() } }
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
 * `jpackage` 只在完整 JDK 里有（Android Studio 自带的 jbr 是**运行时**，实测它的
 * bin 目录只有 jarsigner/jlink，没有 jpackage），所以要自己找一个。
 * 用 `--type app-image` 而不是 installer：不碰注册表、不需要管理员、解压即用、删文件夹即卸载。
 */
val packageExe by tasks.registering(Exec::class) {
    group = "distribution"
    description = "生成 Windows 应用目录（HaoAI-PC.exe + 自带 runtime）"
    dependsOn("installDist")
    val jp = findJpackage()
    val lib = layout.buildDirectory.dir("install/haoai-pc/lib").get().asFile
    val out = layout.buildDirectory.dir("package").get().asFile
    doFirst {
        if (!file(lib).isDirectory) error("先跑 installDist")
        if (jp == null) error(
            "没找到 jpackage。它只随完整 JDK 发布（Android Studio 的 jbr 没有）。\n" +
                "  设环境变量 JPACKAGE=<某个 JDK>\\bin\\jpackage.exe 再跑一次，\n" +
                "  或把某个完整 JDK 的 bin 放进 PATH。\n" +
                "  找过的位置：JPACKAGE、%USERPROFILE%\\.gradle\\jdks\\*\\bin、" +
                "C:\\Program Files\\{Eclipse Adoptium,Java,Microsoft}\\*\\bin"
        )
        // jpackage 不肯覆盖已存在的 app-image（"目标应用目录 … 已存在"），
        // 于是第二次跑必然失败。删掉的是 build/package 下我们自己生成的目录，
        // 重跑一次就能再生出来，不是用户数据。
        val prev = File(out, "HaoAI-PC")
        if (prev.exists() && !prev.deleteRecursively()) {
            error("删不掉旧的 ${prev.absolutePath}（可能 HaoAI-PC.exe 正在运行），先关掉它")
        }
        commandLine(
            jp, "--type", "app-image", "--name", "HaoAI-PC", "--app-version", "0.62.0",
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

/**
 * 按"JPACKAGE 环境变量 → 常见安装位置 → PATH"找 jpackage，找不到返回 null。
 *
 * 为什么不把某条绝对路径写死（README 里以前就是）：这台机器的 JDK 会被别的 agent
 * 或用户装卸。实测 2026-09-27 凌晨，一小时前还能用的
 * `C:\Program Files\Eclipse Adoptium\jdk-25…\bin\jpackage.exe` 整目录都不在了，
 * 而 gradle 自己 provision 在 `~\.gradle\jdks\` 下的那份反而还在 ——
 * 依赖"别人装的 JDK"不如依赖"这条构建自己拉下来的 JDK"。
 */
fun findJpackage(): String? {
    System.getenv("JPACKAGE")?.let { if (File(it).isFile) return it }
    val home = System.getProperty("user.home")
    val roots = listOf(
        File(home, ".gradle/jdks"),
        File("C:/Program Files/Eclipse Adoptium"),
        File("C:/Program Files/Java"),
        File("C:/Program Files/Microsoft")
    )
    for (r in roots) {
        r.listFiles()?.forEach { d ->
            val exe = File(d, "bin/jpackage.exe")
            if (exe.isFile) return exe.absolutePath
        }
    }
    val path = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
    return path.firstOrNull { File(it, "jpackage.exe").isFile }?.let { File(it, "jpackage.exe").absolutePath }
}
