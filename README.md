# MCPServer — Minecraft Fabric 模组的 MCP 服务端

一个基于 Fabric 的 Minecraft 服务端模组，把服务器能力以 **MCP (Model Context Protocol)** 的形式通过 HTTP/HTTPS 暴露给 AI 助手，让 AI 可以查询服务器状态、读取日志、管理玩家、触发性能分析（Spark）等。

## 特性

- **多 Minecraft 版本支持**：单一共享 yarn 源码 + 每 MC 版本独立 Gradle 工程，覆盖 1.20.1 ~ 1.21.11 共 18 个真实版本（26.x 为沙箱模拟版本，仅供本地结构演示，无法联网编译）。
- **MCP over HTTP/HTTPS**：提供标准 MCP JSON-RPC 端点，支持可选 SSL。
- **日志监控**：实时采集服务端日志，供 AI 检索与上下文注入。
- **玩家行为记录**：记录玩家行为用于回放/审计（可配置）。
- **Spark 集成**：AI 可调用性能分析、堆转储、TPS/健康报告等；未安装 Spark 时优雅降级（相关工具自动隐藏并提示安装）。
- **关服优雅退出**：正确关闭 HTTP 线程池与后台守护线程，避免进程卡死。

## 目录结构

```
.
├── src/main/java/...        # 共享源码（yarn 命名，由 loom 在构建时映射为 intermediary）
├── tools/                   # build_all.py 全量构建脚本、restore_wrappers.py 等
├── versions/<mc-version>/   # 每个 MC 版本的独立 Gradle 工程（复用 src 源码）
├── gradle/wrapper/          # Gradle wrapper（jar 以 base64 形式 gradle-wrapper.jar.b64 分发）
├── fabric.mod.json
└── README.md
```

## 快速开始

### 1. 还原 Gradle Wrapper

> 二进制 `gradle-wrapper.jar` 无法经 GitHub MCP 推送，故以 base64 文本随仓库分发。克隆后需先还原：

```bash
python tools/restore_wrappers.py
```

脚本会把 `gradle/wrapper/gradle-wrapper.jar.b64` 解码并写入根工程及所有 `versions/*` 子工程的 `gradle/wrapper/` 目录。还原后即可正常使用 `./gradlew`。

### 2. 构建

```bash
# 仅构建根工程（1.20.1）
./gradlew clean remapJar

# 全量构建所有版本（需要各版本对应的 JDK，离线环境仅 1.20.1 可出包）
python tools/build_all.py
```

构建产物位于各工程的 `build/libs/`（可分发 jar 由 `remapJar` 产出）。

### 3. 运行

把对应版本的 `build/libs/MCPServer-*.jar` 放入服务端的 `mods/` 目录，启动 Fabric 服务端即可。
配置文件位于服务器运行目录的 `config/MCPServer/config.json`（首次运行自动生成，含 apiKey、SSL、端口等）。

## 安全提示

- `config/MCPServer/config.json` 包含 `apiKey` 等敏感信息，**已被 `.gitignore` 排除，不会进入版本库**。请勿手动提交。
- `run/` 运行时目录（含可能的 SSL 密钥库 `*.jks`、真实配置）同样被忽略。
- 对外暴露 MCP HTTP 端点时请务必配置强 `apiKey` 并尽量启用 SSL。

## 许可证

本项目基于 [GPL-3.0 License](LICENSE) 开源。
