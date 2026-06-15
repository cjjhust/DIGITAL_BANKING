FROM eclipse-temurin:21-jdk
WORKDIR /app



# 1. 安装 OpenSSH Server
# 注意：为了减小镜像大小，安装后清理 apt 缓存
RUN apt-get update && apt-get install -y openssh-server \
    && rm -rf /var/lib/apt/lists/*

# 2. 配置 SSH
RUN mkdir -p /var/run/sshd
# 确保权限正确，否则 sshd 可能会拒绝启动
RUN chmod 0755 /var/run/sshd
# 允许 root 用户通过密码登录 (仅用于测试，生产环境强烈不建议)
RUN sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin yes/' /etc/ssh/sshd_config
# 不要求 TTY (伪终端) 分配，这对于自动化脚本或某些 SSH 客户端可能有用
RUN sed -i 's/UsePAM yes/UsePAM no/' /etc/ssh/sshd_config
# 设置 root 用户的密码 (仅用于测试，生产环境强烈不建议)
# 密码设置为 'rootpassword'，请根据需要修改
RUN echo 'cjjhust:198851' | chpasswd

# 3. 暴露 SSH 端口
EXPOSE 22

# 4. 复制并设置 entrypoint 脚本
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# 5. 使用 entrypoint 脚本作为容器的入口点
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
# 6. 保持容器运行，防止它因为没任务直接退出
CMD ["sleep", "infinity"]