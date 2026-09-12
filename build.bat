@echo off
REM DDmusic 一键构建（Windows 入口 → WSL2 容器构建）
REM 用法: build.bat [install]   （install = 构建后自动安装到手机）
REM 构建实际在 WSL2 Ubuntu 内完成，与 Windows 驱动层完全隔离
chcp 65001 >nul
wsl -d Ubuntu-2404 -- bash /mnt/d/dev/music-app/build.sh %*
