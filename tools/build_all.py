#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键构建 MCPServer 多版本 Fabric 模组 jar。

- 在【联网】环境下运行（各版本需要拉取 yarn / intermediary / fabric-api）。
- 自动构建 1.20.1（根工程）+ versions/ 下所有【非 26.x】版本。
- 26.x 系列因沙箱/真实环境均无任何 yarn / intermediary 映射，无法编译，自动跳过。
- 每个版本按其 targetJavaVersion 选择匹配的 JDK（找不到精确版本时回退到 >=target 的最小 JDK）。

用法:
  python tools/build_all.py                 # 实际构建全部可构建版本
  python tools/build_all.py --dry-run      # 只打印将要执行的命令，不构建
  python tools/build_all.py --only 1.20.6 1.21.1   # 只构建指定版本
  python tools/build_all.py --offline              # 离线模式（仅用已缓存依赖；沙箱/受限网络用）
  JAVA21_HOME="D:/Program Files/Java/jdk-21.0.10" python tools/build_all.py   # 用环境变量指定 JDK
注意：运行 Gradle 的 JDK 对 target<=21 的版本统一优先用 JDK21（Gradle/loom 在 21 下最稳），
编译仍按 release=target 进行；找不到 JDK 时用 JAVA<VER>_HOME 环境变量指定。
"""
import os
import re
import sys
import atexit
import shutil
import glob
import subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERSIONS_DIR = os.path.join(ROOT, "versions")

# JDK 候选安装根目录（跨平台：Windows 用盘符正斜杠，非 Windows 用标准路径）。
if os.name == "nt":
    COMMON_JDK_ROOTS = [
        "C:/Program Files/Java",
        "D:/Program Files/Java",
        "C:/Program Files/Eclipse Adoptium",
        "C:/Program Files/AdoptOpenJDK",
    ]
else:
    COMMON_JDK_ROOTS = ["/usr/lib/jvm", "/opt/java", os.path.expanduser("~/java")]

# 这些版本前缀在任何环境都无映射，跳过（26.x 是沙箱模拟的“未来版本”）。
SKIP_PREFIX = ("26",)


def normalize_jdk_path(p):
    """把 JDK 路径规范化为当前 shell 友好的形式，避免混合分隔符导致 Gradle 解析异常。"""
    if os.environ.get("MSYSTEM"):
        # Git Bash：统一为正斜杠并把盘符写成 /c、/d ...
        p = p.replace("\\", "/")
        m = re.match(r"^([A-Za-z]):/(.*)$", p)
        if m:
            p = "/" + m.group(1).lower() + "/" + m.group(2)
    else:
        p = os.path.normpath(p)
    return p


def find_jdk(target):
    """返回可用于 targetJavaVersion 的 JDK 路径。
    优先级：环境变量 JAVA<target>_HOME > 对 Fabric(target<=21)优先 JDK21（Gradle/loom 在 21 下最稳）
            > 精确匹配 > >=target 的最小版本。
    """
    env = os.environ.get(f"JAVA{target}_HOME")
    if env and os.path.isdir(env):
        return env
    cands = []
    for root in COMMON_JDK_ROOTS:
        if not os.path.isdir(root):
            continue
        for name in os.listdir(root):
            m = re.match(r"jdk-(\d+)", name)
            if not m:
                continue
            cands.append((int(m.group(1)), os.path.join(root, name)))
    if target <= 21:
        j21 = [p for v, p in cands if v == 21]
        if j21:
            return j21[0]
    exact = [p for v, p in cands if v == target]
    if exact:
        return exact[0]
    ge = [(v, p) for v, p in cands if v >= target]
    if ge:
        return min(ge)[1]
    return None


def read_props(path):
    d = {}
    if not os.path.exists(path):
        return d
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            d[k.strip()] = v.strip()
    return d


def pick_gradlew(cwd):
    # 非 Windows 与 Git Bash(MinGW/MSYSTEM)直接用 sh 版；原生 Windows(cmd/PowerShell)用 .bat。
    if os.name == "nt" and not os.environ.get("MSYSTEM"):
        return "gradlew.bat"
    return "gradlew"


def run_build(label, cwd, jdk, dry_run, offline=False, clean=False):
    gw = pick_gradlew(cwd)
    gradlew = os.path.join(cwd, gw)
    jdk = normalize_jdk_path(jdk)
    env = dict(os.environ)
    env["JAVA_HOME"] = jdk
    env["PATH"] = os.path.join(jdk, "bin") + os.pathsep + env.get("PATH", "")
    gradle_args = ["clean", "remapJar", "--no-daemon"] if clean else ["remapJar", "--no-daemon"]
    if offline:
        gradle_args.append("--offline")
    if os.name == "nt" and gw == "gradlew":
        # Git Bash 下经过 sh 解释 shebang，避免 Windows 直接 exec sh 脚本报 193，
        # 也避免路径里反斜杠被 shell 转义。
        sh_exe = shutil.which("sh") or "sh"
        cmd = [sh_exe, gradlew] + gradle_args
    else:
        cmd = [gradlew] + gradle_args
    print(f"\n>>> [{label}] JAVA_HOME={jdk}")
    print("    " + " ".join(cmd) + ("" if not dry_run else "   (dry-run)"))
    if dry_run:
        return 0
    return subprocess.run(cmd, cwd=cwd, env=env).returncode


def pid_alive(pid):
    """跨平台判断进程是否存活。"""
    if os.name == "nt":
        try:
            out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}"],
                                 capture_output=True, text=True, timeout=10).stdout
            return str(pid) in out
        except Exception:
            return False
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def acquire_lock():
    """单实例锁：防止多个 build_all.py 同时运行导致 loom 缓存锁冲突/损坏。"""
    lock = os.path.join(ROOT, ".build_all.lock")
    if os.path.exists(lock):
        try:
            with open(lock, encoding="utf-8") as f:
                pid = int(f.read().strip())
            if pid_alive(pid):
                sys.stderr.write(
                    f"ERROR: 另一个 build_all.py 正在运行 (pid {pid})，"
                    f"并发构建会破坏 loom 缓存锁。请先等待其完成或结束该进程后再试。\n")
                sys.exit(1)
        except Exception:
            pass
        try:
            os.remove(lock)
        except Exception:
            pass
    with open(lock, "w", encoding="utf-8") as f:
        f.write(str(os.getpid()))


def release_lock():
    try:
        os.remove(os.path.join(ROOT, ".build_all.lock"))
    except Exception:
        pass


def main():
    acquire_lock()
    atexit.register(release_lock)
    dry = "--dry-run" in sys.argv
    offline = "--offline" in sys.argv
    clean = "--clean" in sys.argv
    only = [a for a in sys.argv[1:] if not a.startswith("--")]
    results = []

    # 1) 根工程（1.20.1）
    root_props = read_props(os.path.join(ROOT, "gradle.properties"))
    root_mc = root_props.get("minecraft_version", "1.20.1")
    root_target = int(root_props.get("target_java_version", "17"))
    if (not only) or root_mc in only:
        if root_mc.startswith(SKIP_PREFIX):
            print(f"SKIP {root_mc} (no mappings available)")
            results.append((root_mc, "SKIP"))
        else:
            jdk = find_jdk(root_target)
            if not jdk:
                print(f"ERROR: cannot find a JDK for target {root_target} (set JAVA{root_target}_HOME)")
                results.append((root_mc, "NO_JDK"))
            else:
                rc = run_build(root_mc, ROOT, jdk, dry, offline, clean)
                results.append((root_mc, "OK" if rc == 0 else f"FAIL({rc})"))

    # 2) versions/* 子工程
    if os.path.isdir(VERSIONS_DIR):
        for name in sorted(os.listdir(VERSIONS_DIR)):
            vdir = os.path.join(VERSIONS_DIR, name)
            if not os.path.isdir(vdir):
                continue
            if only and name not in only:
                continue
            props = read_props(os.path.join(vdir, "gradle.properties"))
            mc = props.get("minecraft_version", name)
            target = int(props.get("target_java_version", "17"))
            if mc.startswith(SKIP_PREFIX) or name.startswith(SKIP_PREFIX):
                print(f"SKIP {mc} (26.x has no yarn/intermediary mappings)")
                results.append((mc, "SKIP"))
                continue
            jdk = find_jdk(target)
            if not jdk:
                print(f"ERROR: cannot find a JDK for target {target} (set JAVA{target}_HOME)")
                results.append((mc, "NO_JDK"))
                continue
            rc = run_build(mc, vdir, jdk, dry, offline, clean)
            results.append((mc, "OK" if rc == 0 else f"FAIL({rc})"))

    print("\n===== SUMMARY =====")
    for mc, st in results:
        print(f"  {mc:10} {st}")
    if dry:
        print("(dry-run: no builds executed)")


if __name__ == "__main__":
    main()
