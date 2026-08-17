#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Clone 之后还原所有 gradle-wrapper.jar。

由于 gradle-wrapper.jar 是二进制文件，无法经由 GitHub MCP 连接器（仅支持文本）推送，
因此本项目将其以 base64 文本形式随仓库分发（gradle/wrapper/gradle-wrapper.jar.b64），
克隆后运行本脚本即可把 jar 还原到所有需要它的目录（根工程 + 各版本子工程）。

用法:
    python tools/restore_wrappers.py
依赖: 仅标准库（无需联网、无需本地 Gradle 环境）
"""
import base64
import glob
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
B64 = os.path.join(ROOT, "gradle", "wrapper", "gradle-wrapper.jar.b64")


def main():
    if not os.path.isfile(B64):
        raise SystemExit(f"找不到 {B64}，请确认已完整克隆仓库。")
    with open(B64, "rb") as f:
        data = base64.b64decode(f.read().strip())

    count = 0
    for props in glob.glob(
        os.path.join(ROOT, "**", "gradle", "wrapper", "gradle-wrapper.properties"),
        recursive=True,
    ):
        wrapper_dir = os.path.dirname(props)
        jar_path = os.path.join(wrapper_dir, "gradle-wrapper.jar")
        with open(jar_path, "wb") as f:
            f.write(data)
        count += 1
        print("  restored", os.path.relpath(jar_path, ROOT))
    print(f"完成：已还原 {count} 个 gradle-wrapper.jar。现在可以直接使用 ./gradlew 了。")


if __name__ == "__main__":
    main()
