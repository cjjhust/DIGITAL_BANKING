#!/bin/bash

# 容器内启动脚本
set -o pipefail

echo "=== 容器内启动 Spring Boot 应用 - $(date) ==="

# 生成带日期时间的日志文件名
LOG_FILE="bootrun_$(date +%Y%m%d_%H%M%S).log"
echo "日志文件: $LOG_FILE"

# 直接创建新日志文件（不移动旧文件，避免冲突）
echo "=== 启动 Spring Boot 应用 ===" > "$LOG_FILE"

# 直接使用容器内的 Java 环境
echo "使用容器内 Java 环境..." | tee -a "$LOG_FILE"
java -version 2>&1 | tee -a "$LOG_FILE"

# 使用容器独立的构建目录，避免与宿主机或 VS Code 的 Gradle 进程竞争
export GRADLE_BUILD_DIR=/tmp/banking-build
./gradlew --no-daemon bootRun 2>&1 | tee -a "$LOG_FILE"

exit_code=${PIPESTATUS[0]}
if ! grep -q "Started BankingApplication" "$LOG_FILE"; then
	exit_code=1
fi
echo "应用已停止 - $(date)，退出码: $exit_code" | tee -a "$LOG_FILE"
exit "$exit_code"