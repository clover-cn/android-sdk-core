# Compose Compiler Gradle 插件

## 使用 Gradle 版本目录进行设置

以下说明概述了如何设置 Compose 编译器 Gradle 插件：

1. 在 `libs.versions.toml` 文件中，移除对 Compose 编译器的所有引用
2. 在“plugins”部分，添加以下新依赖项

```
[versions]
kotlin = "2.0.0"

[plugins]
org-jetbrains-kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }

// Add this line
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

1. 在项目的根 `build.gradle.kts` 文件中，将以下内容添加到“plugins”部分：

```
plugins {
   // Existing plugins
   alias(libs.plugins.compose.compiler) apply false
}
```

1. 在使用 Compose 的每个模块中，应用该插件：

```
plugins {
   // Existing plugins
   alias(libs.plugins.compose.compiler)
}
```

如果您使用的是默认设置，您的应用现在应该已构建并编译完毕。如果您在 Compose 编译器上配置了自定义选项，请参阅以下部分。

## 在不使用 Gradle 版本目录的情况下进行设置

如需在不使用版本目录的情况下设置 Compose 编译器 Gradle 插件，请将以下插件添加到与您使用 Compose 的模块关联的 `build.gradle.kts` 文件中：

```
plugins {
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" // this version matches your Kotlin version
}
```

## Compose 编译器 Gradle 插件配置选项

如需使用 Gradle 插件配置 Compose 编译器，请将 `composeCompiler` 块添加到模块的顶级 `build.gradle.kts` 文件中。

```
android { … }

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    stabilityConfigurationFile = rootProject.layout.projectDirectory.file("stability_config.conf")
}
```