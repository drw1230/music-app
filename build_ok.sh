#!/bin/bash
# DDmusic 构建脚本（本机 file-events DLL 崩溃的终极绕行）
# 每次构建前清空 E 盘 native 缓存 + 杀进程，让 Gradle 9.5.1 重新解压（首次解压加载成功率高）

cd /d/dev/music-app

# 1. 杀残留 java
taskkill //F //IM java.exe 2>/dev/null
sleep 2

# 2. 清 E 盘 native 缓存 + F 盘 journal 锁 + 项目 .gradle
rm -rf /e/gradle-native/* 2>/dev/null
rm -rf /f/gradle-v4/caches/journal-1 2>/dev/null
rm -rf /d/dev/music-app/.gradle 2>/dev/null

# 3. 构建
export JAVA_HOME="C:/dev/android/jdk/jdk-21.0.12+8"
export ANDROID_HOME="C:/dev/android/sdkroot"
export GRADLE_USER_HOME="F:/gradle-v4"
export PATH="/c/dev/android/gradle/gradle-9.5.1/bin:$PATH"
export GRADLE_OPTS="-Dorg.gradle.native.dir=E://gradle-native"

gradle assembleDebug --no-daemon --console=plain 2>&1 | tail -8
