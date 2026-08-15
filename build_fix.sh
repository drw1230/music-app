#!/bin/bash
# DDmusic 一键构建脚本（修复本机 file-events DLL 崩溃问题）
# 用法：bash build_fix.sh

# 1. 杀掉残留 Gradle/Java 进程
taskkill //F //IM java.exe 2>/dev/null
sleep 1

# 2. 删除所有 native 锁文件（本机 file-events DLL 崩溃的根源是锁残留）
find /e/gradle-native -name "*.lock" -delete 2>/dev/null
find /c/Users/Administrator/.gradle-fresh -name "*.lock" -delete 2>/dev/null
rm -rf /d/dev/music-app/.gradle 2>/dev/null

# 3. 构建
export JAVA_HOME="C:/dev/android/jdk/jdk-21.0.12+8"
export ANDROID_HOME="C:/dev/android/sdkroot"
export GRADLE_USER_HOME="C:/Users/Administrator/.gradle-fresh"
export PATH="/c/dev/android/gradle/gradle-9.5.1/bin:$PATH"
export GRADLE_OPTS="-Dorg.gradle.native.dir=E://gradle-native"

gradle assembleDebug --no-daemon 2>&1 | tail -20
