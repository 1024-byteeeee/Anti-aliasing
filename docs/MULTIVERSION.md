# 多版本构建

构建布局参考 Carpet AMS Addition 的 `v26.3-legacy` 配置；保留 Mojang 官方映射。Gradle wrapper 固定 9.2.1，Loom 固定同系列的稳定版 1.14.10，Fallen-Breath preprocessor 固定 `c5abb4fb12`。

## 配置职责

- `gradle.properties`：Loom、preprocessor、Loader 默认版本和模组元信息。
- `settings.gradle`：将 preprocessor 插件指向 Fallen-Breath fork，读取版本列表并为子项目指定 `common.gradle`。
- `build.gradle`：版本节点、映射连接、汇总发布 JAR。
- `common.gradle`：官方映射、依赖、Java 字节码目标、资源替换与 Loom 运行配置。
- `versions/<版本>/gradle.properties`：对应游戏的 Fabric API、Mod Menu 和 Java 目标。
- `versions/mainProject`：主源码版本，目前为 1.21.8。

## 版本依赖

| Minecraft | Java 字节码 | Fabric API | 可选 Mod Menu |
| --- | --- | --- | --- |
| 1.16.5 | 8 | 0.42.0+1.16 | 1.16.23 |
| 1.17.1 | 16 | 0.46.1+1.17 | 2.0.17 |
| 1.18.2 | 17 | 0.77.0+1.18.2 | 3.2.5 |
| 1.19.4 | 17 | 0.87.2+1.19.4 | 6.3.1 |
| 1.20.6 | 21 | 0.100.8+1.20.6 | 10.0.0 |
| 1.21.8 | 21 | 0.136.1+1.21.8 | 15.0.2 |
| 1.21.11 | 21 | 0.141.6+1.21.11 | 17.0.0 |

## 构建

使用 JDK 21，并确保 IDE 的 Gradle JVM 同样设置为 JDK 21。无需 JDK 25。首次构建需要访问 Fabric Maven、JitPack、Maven Central、Terraformers Maven 和 Mojang 下载服务。

```powershell
.\gradlew.bat clean build -x test
.\gradlew.bat buildAndGather
.\gradlew.bat :1.21.8:remapJar
```

前两条汇总七个版本的发布 JAR 到 `build/libs`。第三条只生成一个版本的发布 JAR。汇总任务不依赖测试；裸 `build` 会同时选中子项目的构建及单元测试，使用 `-x test` 可显式跳过。用户可自行运行对应子项目的 `test` 或客户端开发任务。

主源码在 `src/main`。旧版 OpenGL、界面与渲染接口覆盖在 `versions/1.20.6/src/main`，沿版本链下传；1.16.5 独有矩阵接口位于其版本目录。1.21.11 的资源标识符改名通过独立映射文件处理。不要直接修改 `build/preprocessed` 下的生成源码。
