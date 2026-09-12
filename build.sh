#!/bin/bash
# DDmusic 容器/WSL2 一键构建脚本
# 用法: bash build.sh [install]
#   install   构建后自动 adb 覆盖安装到手机（默认 R5CWC08ZBET）
#
# 设计要点（解决本机 Windows 驱动层 Gradle native 崩溃问题）:
# 1. 构建全在 WSL2 ext4 文件系统进行（/opt/music-app），与 Windows 驱动层零接触
#    - GRADLE_USER_HOME=/root/.gradle（native 库、依赖缓存、锁文件全在 Linux 侧）
#    - 项目副本 /opt/music-app（build 产物、.gradle 全在 Linux 侧）
# 2. 源码从宿主机 D:\dev\music-app rsync 同步（排除产物目录，秒级）
# 3. APK 构建完拷回宿主机路径，adb 用 Windows 侧 adb 安装
#
# 首次构建会下依赖（有宿主机 444M 缓存加速）；增量构建 < 2 分钟

set -e

# ---------- 环境 ----------
JAVA_HOME="/usr/lib/jvm/java-21-openjdk-amd64"
ANDROID_HOME="/opt/android-sdk"
GRADLE_HOME="/opt/gradle/gradle-8.11.1"
GRADLE_USER_HOME="/root/.gradle"
SRC_DIR="/mnt/d/dev/music-app"      # 宿主机源码（Windows）
WORK_DIR="/opt/music-app"           # 构建副本（WSL ext4）

# 宿主 adb（WSL 无法直连 USB，用 Windows 侧 adb 安装）
WIN_ADB="/mnt/c/dev/android/sdkroot/platform-tools/adb.exe"
DEVICE_ID="${DEVICE_ID:-R5CWC08ZBET}"

export JAVA_HOME ANDROID_HOME GRADLE_USER_HOME
export PATH="$GRADLE_HOME/bin:$JAVA_HOME/bin:$PATH"

echo "=== DDmusic 容器构建（WSL2） ==="
echo "JAVA_HOME    : $JAVA_HOME"
echo "ANDROID_HOME : $ANDROID_HOME"
echo "Gradle       : $($GRADLE_HOME/bin/gradle --version 2>/dev/null | grep Gradle | head -1)"
echo ""

# ---------- 1. 同步源码（排除产物目录） ----------
echo ">>> [1/4] 同步源码 $SRC_DIR -> $WORK_DIR ..."
mkdir -p "$WORK_DIR"
T0=$(date +%s)
rsync -a --delete \
    --exclude '.gradle' \
    --exclude 'app/build' \
    --exclude 'build' \
    --exclude '.git' \
    --exclude 'local.properties' \
    "$SRC_DIR/" "$WORK_DIR/"
SYNC_T=$(( $(date +%s) - T0 ))
echo "    同步完成（${SYNC_T}s）"

# ---------- 2. 构建 ----------
echo ">>> [2/4] 构建 assembleDebug ..."
cd "$WORK_DIR"
T0=$(date +%s)
gradle assembleDebug --console=plain
STATUS=$?
BUILD_T=$(( $(date +%s) - T0 ))
echo "    构建完成（${BUILD_T}s），退出码 $STATUS"
[ $STATUS -ne 0 ] && exit $STATUS

# ---------- 3. APK 拷回宿主机 ----------
echo ">>> [3/4] APK 拷回宿主机 ..."
APK_SRC="$WORK_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_SRC" ]; then
    echo "!! APK 未找到: $APK_SRC"
    exit 1
fi
APK_DST="$SRC_DIR/app/build/outputs/apk/debug/app-debug.apk"
mkdir -p "$(dirname "$APK_DST")"
cp "$APK_SRC" "$APK_DST"
echo "    APK: $APK_DST ($(du -h "$APK_DST" | cut -f1))"

# ---------- 4. 安装（可选） ----------
if [ "${1:-}" = "install" ]; then
    echo ">>> [4/4] adb 安装到 $DEVICE_ID ..."
    "$WIN_ADB" devices | grep -q "$DEVICE_ID" || { echo "!! 设备 $DEVICE_ID 未连接"; exit 1; }
    # WSL 调用 Windows adb 需把 Linux 路径转成 Windows 路径（D:\...）
    APK_WIN=$(wslpath -w "$APK_DST" 2>/dev/null || echo "$APK_DST")
    "$WIN_ADB" -s "$DEVICE_ID" install -r "$APK_WIN"
    echo "    安装完成，启动 DDmusic ..."
    "$WIN_ADB" -s "$DEVICE_ID" shell am start -n com.dengdeng.music/.MainActivity || true
fi

echo "=== 完成（构建 ${BUILD_T}s，同步 ${SYNC_T}s） ==="
