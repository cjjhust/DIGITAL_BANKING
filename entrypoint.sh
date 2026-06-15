#!/bin/bash
set -e

# 启动 SSH 服务
service ssh start
# 执行 CMD 中传递过来的命令 (即启动 Spring Boot 应用)
exec "$@"