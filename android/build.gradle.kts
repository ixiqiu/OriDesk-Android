// 根构建脚本：只声明插件版本，不在这里 apply（各模块自己 apply）。
// 版本组合是刻意选的稳定搭配，改动前请确认 AGP↔Gradle↔Kotlin 的兼容矩阵：
//   AGP 8.7.x 需要 Gradle 8.9+ 与 JDK 17；Kotlin 2.0.x 与 AGP 8.7 兼容。
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
