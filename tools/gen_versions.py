#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 MCPServer 多版本 Fabric 构建工程。

每个 Minecraft 版本在 versions/<mc>/ 下生成一个独立的 Gradle 工程，
复用根目录 src/main 与 src/client 源码（通过 sourceSets.srcDirs 指向 ../../src）。

- 1.20.1 由根工程负责（loom 1.14.10），本脚本跳过它。
- 其余 21 个版本统一使用 loom __LOOM__ / Gradle 9.6.1 / JDK25 运行、各自 targetJavaVersion。
- yarn_mappings 与 fabric_version 来自下方矩阵；缓存中已有的版本用精确值，
  未缓存版本用估计值（联网后可用 resolve 步骤校正）。

用法:
  python tools/gen_versions.py            # 生成所有版本工程
  python tools/gen_versions.py --list     # 仅打印矩阵
"""
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERSIONS_DIR = os.path.join(ROOT, "versions")
TEMPLATE_SRC = os.path.join(VERSIONS_DIR, "26.2")  # 复用 26.2 的 wrapper 文件

LOOM_VERSION = "1.17.19"
GRADLE_DIST = "gradle-9.6.1-bin.zip"

# 矩阵: mc -> (yarn_mappings, fabric_version, target_java, cached?)
# cached=True 表示本环境已缓存 yarn+fabric-api，可离线构建。
MATRIX = {
    "1.20.1":  ("1.20.1+build.10",     "0.92.2+1.20.1",   17, True),   # 由根工程负责，跳过
    "1.20.2":  ("1.20.2+build.1",      "0.91.2+1.20.2",   17, False),
    "1.20.3":  ("1.20.3+build.1",      "0.91.1+1.20.3",   17, False),
    "1.20.4":  ("1.20.4+build.3",      "0.91.2+1.20.4",   17, True),   # yarn 已缓存
    "1.20.5":  ("1.20.5+build.1",      "0.97.8+1.20.5",  21, False),
    "1.20.6":  ("1.20.6+build.1",      "0.100.8+1.20.6",  21, True),
    "1.21":    ("1.21+build.2",        "0.102.0+1.21",    21, False),
    "1.21.1":  ("1.21.1+build.3",      "0.102.0+1.21.1",  21, True),
    "1.21.2":  ("1.21.2+build.1",      "0.106.1+1.21.2",  21, False),
    "1.21.3":  ("1.21.3+build.1",      "0.114.1+1.21.3",  21, False),
    "1.21.4":  ("1.21.4+build.2",      "0.110.5+1.21.4",  21, True),
    "1.21.5":  ("1.21.5+build.1",      "0.115.0+1.21.5",  21, False),
    "1.21.6":  ("1.21.6+build.1",      "0.128.2+1.21.6",  21, False),
    "1.21.7":  ("1.21.7+build.1",      "0.129.0+1.21.7",  21, False),
    "1.21.8":  ("1.21.8+build.1",      "0.129.0+1.21.8",  21, True),
    "1.21.9":  ("1.21.9+build.1",      "0.130.0+1.21.9",  21, False),
    "1.21.10": ("1.21.10+build.1",     "0.138.4+1.21.10", 21, False),
    "1.21.11": ("1.21.11+build.1",     "0.141.6+1.21.11", 21, False),
    "26.1":    ("26.1+build.1",        "0.155.0+26.1",    25, False),
    "26.1.1":  ("26.1.1+build.1",      "0.155.1+26.1.1",  25, False),
    "26.1.2":  ("26.1.2+build.1",      "0.155.2+26.1.2",  25, True),   # fabric 已缓存，但无 yarn
    "26.2":    ("26.2+build.1",        "0.156.0+26.2",    25, True),   # fabric 已缓存，但无 yarn
}

BUILD_GRADLE = r'''plugins {
    id 'fabric-loom' version '__LOOM__'
    id 'maven-publish'
}

version = project.mod_version
group = project.maven_group

base {
    archivesName = "${project.archives_base_name}-${project.minecraft_version}"
}

sourceSets.main {
    java { srcDirs = ['../../src/main/java'] }
    resources { srcDirs = ['../../src/main/resources'] }
}
afterEvaluate {
    sourceSets.named('client') {
        java { srcDirs = ['../../src/client/java'] }
        resources { srcDirs = ['../../src/client/resources'] }
    }
}

loom {
    splitEnvironmentSourceSets()
    mods {
        "mcpserver" {
            sourceSet sourceSets.main
            sourceSet sourceSets.client
        }
    }
}

fabricApi {
    configureDataGeneration {
        client = true
    }
}

repositories {
    maven {
        name = 'Aliyun'
        url = 'https://maven.aliyun.com/repository/public'
    }
    maven {
        name 'luck-repo'
        url 'https://repo.lucko.me/'
        content { includeModule 'me.lucko', 'spark-api' }
    }
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings "net.fabricmc:yarn:${project.yarn_mappings}:v2"
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"
    implementation "com.google.code.gson:gson:2.10.1"
    implementation 'org.bouncycastle:bcprov-jdk18on:1.76'
    implementation 'org.bouncycastle:bcpkix-jdk18on:1.76'
    modCompileOnly 'me.lucko:spark-api:0.1-SNAPSHOT'
    modImplementation include('me.lucko:spark-api:0.1-SNAPSHOT')
}

processResources {
    inputs.property "version", project.version
    inputs.property "minecraft_version", project.minecraft_version
    inputs.property "loader_version", project.loader_version
    inputs.property "minecraft_dependency", project.minecraft_version
    filteringCharset "UTF-8"
    filesMatching("fabric.mod.json") {
        expand "version": project.version,
                "minecraft_version": project.minecraft_version,
                "loader_version": project.loader_version,
                "minecraft_dependency": project.minecraft_version
    }
}

def targetJavaVersion = project.target_java_version as int
tasks.withType(JavaCompile).configureEach {
    it.options.encoding = "UTF-8"
    if (targetJavaVersion >= 10 || JavaVersion.current().isJava10Compatible()) {
        it.options.release.set(targetJavaVersion)
    }
}

java {
    def javaVersion = JavaVersion.toVersion(targetJavaVersion)
    if (JavaVersion.current() < javaVersion) {
        toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    }
    withSourcesJar()
}

jar {
    from("../../LICENSE.txt") {
        rename { "${it}_${project.archives_base_name}" }
    }
}

publishing {
    publications {
        create("mavenJava", MavenPublication) {
            artifactId = project.archives_base_name
            from components.java
        }
    }
    repositories { }
}
'''

GRADLE_PROPS = '''org.gradle.jvmargs=-Xmx4G
mod_version=1.3
maven_group=org.du
archives_base_name=MCPServer

# ===== 版本坐标（由 tools/gen_versions.py 生成）=====
minecraft_version=__MC__
yarn_mappings=__YARN__
loader_version=0.19.3
fabric_version=__FABRIC__
target_java_version=__JAVA__

# Network timeout (ms)
systemProp.org.gradle.internal.http.connectionTimeout=180000
systemProp.org.gradle.internal.http.socketTimeout=180000
'''

SETTINGS = r'''pluginManagement {
    repositories {
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://maven.fabricmc.net/' }
        gradlePluginPortal()
    }
}
rootProject.name = 'MCPServer-__MC__'
'''


def gen_one(mc, yarn, fabric, java_ver):
    d = os.path.join(VERSIONS_DIR, mc)
    os.makedirs(os.path.join(d, "gradle", "wrapper"), exist_ok=True)
    with open(os.path.join(d, "build.gradle"), "w", encoding="utf-8") as f:
        f.write(BUILD_GRADLE.replace("__LOOM__", LOOM_VERSION))
    with open(os.path.join(d, "gradle.properties"), "w", encoding="utf-8") as f:
        f.write(GRADLE_PROPS
                .replace("__MC__", mc)
                .replace("__YARN__", yarn)
                .replace("__FABRIC__", fabric)
                .replace("__JAVA__", str(java_ver)))
    with open(os.path.join(d, "settings.gradle"), "w", encoding="utf-8") as f:
        f.write(SETTINGS.replace("__MC__", mc))
    # 复用 26.2 的 wrapper 文件（指向 Gradle 9.6.1）
    for name in ("gradlew", "gradlew.bat",
                 os.path.join("gradle", "wrapper", "gradle-wrapper.jar"),
                 os.path.join("gradle", "wrapper", "gradle-wrapper.properties")):
        src = os.path.join(TEMPLATE_SRC, name)
        dst = os.path.join(d, name)
        if os.path.exists(src) and os.path.abspath(src) != os.path.abspath(dst):
            shutil.copy(src, dst)
    # 修正 gradlew 脚本权限（Windows 下无所谓，但保留）
    gw = os.path.join(d, "gradlew")
    if os.path.exists(gw):
        os.chmod(gw, 0o755)


def main():
    if "--list" in sys.argv:
        for mc, (y, f, j, c) in MATRIX.items():
            print(f"{mc:8} yarn={y:18} fabric={f:20} java={j} cached={c}")
        return
    os.makedirs(VERSIONS_DIR, exist_ok=True)
    for mc, (yarn, fabric, java_ver, cached) in MATRIX.items():
        if mc == "1.20.1":
            continue  # 根工程负责
        gen_one(mc, yarn, fabric, java_ver)
        print(f"generated versions/{mc}/  (java={java_ver}, cached={cached})")
    print("DONE")


if __name__ == "__main__":
    main()
