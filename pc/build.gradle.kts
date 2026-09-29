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
/**
 * 打包件的版本号从 `Main.kt` 的 `PC_VERSION` 读，不再手写第二份。
 *
 * 之前这里是写死的 `"0.64.0"`，而代码已经走到 0.79：右键 exe → 属性看到的是旧版本，
 * 而界面顶栏显示的是新版本——两处各写一遍，最先错的一定是没被改的那一处
 * （同一类病：README 里那条"41 份剧本"、界面里那句写死的 v0.2.0）。
 * jpackage 只收"数字与点"，所以把 `-pc` 后缀切掉。
 */
fun appVersion(): String {
    val src = File(projectDir, "src/main/kotlin/com/haoai/pc/Main.kt")
    val v = Regex("""const val PC_VERSION = "([0-9.]+)""").find(src.readText())?.groupValues?.get(1)
        ?: error("从 ${src.name} 里没读到 PC_VERSION，别在构建脚本里再写死一个版本号")
    return v
}

/**
 * 打一个**自带 JVM** 的 Windows 应用目录：`build/package/HaoAI-PC/HaoAI-PC.exe`。
 *
 * 为什么要这一步：在那之前"PC 端 HaoAI"是"一个要会敲 gradle 的仓库"，
 * 之后才是"一个能双击、能放进启动菜单的东西"。
 * 用 `--type app-image` 而不是 installer：不碰注册表、不需要管理员、删文件夹即卸载。
 */
val packageExe by tasks.registering(Exec::class) {
    group = "distribution"
    description = "生成 Windows 应用目录（HaoAI-PC.exe + 自带 runtime）"
    dependsOn("installDist")
    val classes = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
    val need = classMajor(classes)
    val jp = findJpackage(need)
    val lib = layout.buildDirectory.dir("install/haoai-pc/lib").get().asFile
    val out = layout.buildDirectory.dir("package").get().asFile
    doFirst {
        if (!file(lib).isDirectory) error("先跑 installDist")
        if (jp == null) error(
            "没找到版本不低于 Java $need 的 jpackage。它只随完整 JDK 发布（Android Studio 的 jbr 没有）。\n" +
                "  设环境变量 JPACKAGE=<某个 JDK>\\bin\\jpackage.exe 再跑一次，\n" +
                "  或把某个完整 JDK 的 bin 放进 PATH。\n" +
                "  找过的位置：PATH/JAVA_HOME 上那个 java 的 bin、JPACKAGE、%LOCALAPPDATA%\\Programs\\*、" +
                "%USERPROFILE%\\.gradle\\jdks\\*\\bin、C:\\Program Files\\{Eclipse Adoptium,Java,Microsoft}\\*\\bin"
        )
        // 删掉的是 build/package 下我们自己生成的目录，
        // 重跑一次就能再生出来，不是用户数据。
        //
        // 但要先**清掉只读位**：jpackage 产出来的 `HaoAI-PC.exe` 是 r-xr-xr-x，
        // Windows 上 `deleteRecursively()` 对着只读文件就是删不掉，报的错还写着
        // "可能 HaoAI-PC.exe 正在运行"——今天被这句误导了一次，进程列表里根本没有它。
        val prev = File(out, "HaoAI-PC")
        if (prev.exists()) {
            prev.walkBottomUp().forEach { runCatching { it.setWritable(true, false) } }
            if (!prev.deleteRecursively() || prev.exists()) {
                error("删不掉旧的 ${prev.absolutePath}（只读位已清还删不掉，那就是真被占用了）——先关掉 HaoAI-PC.exe")
            }
        }
        commandLine(
            jp, "--type", "app-image", "--name", "HaoAI-PC", "--app-version", appVersion(),
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
    /*
     * 打完必须真跑一次产物。**"BUILD SUCCESSFUL" 不等于 exe 能用**——2026-09-29 那次
     * 就是构建全绿、双击全黑：捆绑的 runtime 是 Java 21，而 class 文件是 69（Java 25），
     * 只有 `UnsupportedClassVersionError`，而这行字只有在双击时才看得见。
     * `help` 是特意挑的：它打印完就退 0，不会顺手起一个服务占着端口。
     */
    doLast {
        val exe = File(out, "HaoAI-PC/HaoAI-PC.exe")
        if (!exe.isFile) error("jpackage 说成功了，但 ${exe.absolutePath} 不在")
        val p = ProcessBuilder(exe.absolutePath, "help").redirectErrorStream(true).start()
        val txt = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val rc = p.waitFor()
        if (rc != 0 || !txt.contains("HaoAI PC")) error(
            "打出来的 exe 跑不起来（rc=$rc）：\n${txt.take(600)}\n" +
                "  多半是捆绑的 runtime 版本低于 class 版本（要 Java $need）。"
        )
        println("  自检通过：${exe.absolutePath} 能跑（内嵌 runtime 认 class 版本 ${need + 44}）")
    }
}

/**
 * 按"JAVA_HOME 与 PATH 上的那个 java → JPACKAGE 环境变量 → 常见安装位置 → PATH"找 jpackage。
 *
 * **必须挑一个版本不低于产物 class 版本的 JDK**，否则打出来的 exe 双击只会报
 * `UnsupportedClassVersionError`——这是 2026-09-29 实测抓出来的：机器上唯一被旧逻辑找到的
 * 是 `~\.gradle\jdks` 里 Gradle 自己拉的 JetBrains JDK 21，而产物是 class 69（Java 25），
 * 于是 `gradle packageExe` 一路"BUILD SUCCESSFUL"，双击却什么都起不来。
 *
 * 为什么不把某条绝对路径写死（README 里以前就是）：这台机器的 JDK 会被别的 agent
 * 或用户装卸。实测 2026-09-27 凌晨，一小时前还能用的
 * `C:\Program Files\Eclipse Adoptium\jdk-25…\bin\jpackage.exe` 整目录都不在了 ——
 * 而现在那把 JDK 25 装在 `%LOCALAPPDATA%\Programs\` 下（用户级安装，旧逻辑根本没搜这里）。
 */
fun findJpackage(needMajor: Int): String? {
    val cands = mutableListOf<File>()
    // ① 真正在跑编译的那个 JDK：PATH / JAVA_HOME 上的 java 的上一级就是它的 bin
    fun fromJava(exe: String?) = exe?.let { runCatching { File(it).canonicalFile.parentFile }.getOrNull() }
    fromJava(System.getenv("JAVA_HOME")?.let { File(it, "bin/java.exe").absolutePath })?.let { cands += it }
    (System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()).forEach { p ->
        if (File(p, "java.exe").isFile) cands += File(p)
    }
    System.getenv("JPACKAGE")?.let { cands += File(it).parentFile }
    val local = System.getenv("LOCALAPPDATA")
    val roots = mutableListOf(
        File(System.getProperty("user.home"), ".gradle/jdks"),
        File("C:/Program Files/Eclipse Adoptium"),
        File("C:/Program Files/Java"),
        File("C:/Program Files/Microsoft")
    )
    if (!local.isNullOrBlank()) roots += listOf(
        File(local, "Programs/Eclipse Adoptium"), File(local, "Programs/Java"), File(local, "Programs")
    )
    for (r in roots) r.listFiles()?.forEach { d -> cands += File(d, "bin") }
    val seen = LinkedHashSet<String>()
    val usable = cands.filter { it != null && File(it, "jpackage.exe").isFile && seen.add(it.absolutePath) }
    val ok = usable.firstOrNull { jdkMajor(File(it.parentFile, "release")) >= needMajor }
    if (ok == null && usable.isNotEmpty()) {
        val got = usable.joinToString(", ") { "${it.absolutePath} = Java ${jdkMajor(File(it.parentFile, "release"))}" }
        error(
            "产物要 Java $needMajor 才能跑，但找到的 jpackage 都属于更低的 JDK：\n  $got\n" +
                "  装一个不低于 $needMajor 的完整 JDK（要带 jpackage.exe），或把它的路径写进 JPACKAGE 环境变量。\n" +
                "  宁可不打包，也不要打出一个双击只会报 UnsupportedClassVersionError 的 exe。"
        )
    }
    return ok?.let { File(it, "jpackage.exe").absolutePath }
}

/** 从 JDK 根目录的 `release` 文件读主版本号；读不到当 0（永远不满足要求，宁可报错）。 */
fun jdkMajor(release: File): Int = runCatching {
    val v = Regex("""JAVA_VERSION="(\d+)""").find(release.readText())?.groupValues?.get(1) ?: return 0
    v.toInt()
}.getOrDefault(0)

/**
 * 编译产物的 class 主版本号（69 = Java 25）。
 *
 * 不靠"我以为 Gradle 用哪个 JDK"——那个数会变（这台机器上 PATH 的 java 从 21 换成 25
 * 就是这几天发生的事），而 class 文件头两个字节是**事实**。
 */
fun classMajor(dir: File): Int {
    val f = File(dir, "com/haoai/pc/MainKt.class")
    if (!f.isFile) return 21
    val b = f.readBytes()
    return if (b.size < 8) 21 else ((b[6].toInt() and 0xFF) shl 8 or (b[7].toInt() and 0xFF)) - 44
}

