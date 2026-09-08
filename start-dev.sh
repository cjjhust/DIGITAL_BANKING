#!/bin/bash

# 设置 Java 环境（智能检查）
if [ -z "$JAVA_HOME" ] || [ "$JAVA_HOME" != "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home" ]; then
    export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
    echo "JAVA_HOME 设置为: $JAVA_HOME"
    echo "建议将此设置添加到 ~/.zshrc 以永久生效:"
    echo 'echo "export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home" >> ~/.zshrc'
else
    echo "JAVA_HOME 已正确设置为: $JAVA_HOME"
fi

# 验证 Java 版本
echo "验证 Java 版本..."
java -version

# 启动所有 Docker 容器（使用新版命令）
echo "启动 Docker 容器..."
docker compose up -d

# 检查容器状态
echo "检查容器状态..."
docker compose ps

# Spring Boot 由 app 容器启动，避免在宿主机运行时无法解析 Docker 内部主机名 db
echo "Spring Boot 正在 app 容器中启动..."
docker compose logs -f app
