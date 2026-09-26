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
