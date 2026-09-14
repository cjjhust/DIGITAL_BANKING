# 本地开发镜像：JDK + Gradle，源码通过 compose 的 bind mount 挂进来，直接跑 bootRun。
#
# 安全收敛（2026-09-12）：
#   早期版本在这里安装 OpenSSH、把 root 口令设成 123456、EXPOSE 22，compose 还挂载了宿主机的
#   ~/.ssh/authorized_keys —— 等于把宿主机 shell 暴露给整个 Docker 网络，在德国金融场景属于红线。
#   以上内容已全部移除，现在只暴露 8080 应用端口。
#
# 生产镜像建议（面试可讲）：多阶段构建 -> 只拷贝 bootJar -> 以非 root 用户运行 -> 只暴露 8080，
# 并且不要把源码目录挂进容器。
FROM eclipse-temurin:21-jdk
WORKDIR /app

# 先拷贝构建描述文件，让依赖解析单独成层，重建时命中缓存
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
RUN chmod +x ./gradlew

# 在 Docker 网络中启动 Spring Boot（db / cache / kafka 用服务名解析）
CMD ["./gradlew", "bootRun"]