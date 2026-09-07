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

# 等待容器启动
echo "等待容器启动..."
sleep 6

# 检查容器状态
echo "检查容器状态..."
docker compose ps

# 检查中间件连接
echo "检查 PostgreSQL..."
curl -f http://localhost:5432 && echo "PostgreSQL OK" || echo "PostgreSQL 未就绪"

echo "检查 Redis..."
curl -f http://localhost:6379 && echo "Redis OK" || echo "Redis 未就绪"

echo "检查 Kafka..."
curl -f http://localhost:9092 && echo "Kafka OK" || echo "Kafka 未就绪"

echo "检查 ClickHouse..."
curl -f http://localhost:8123 && echo "ClickHouse OK" || echo "ClickHouse 未就绪"

# 启动 Spring Boot 应用
echo "启动 Spring Boot 应用..."
./gradlew bootRun
