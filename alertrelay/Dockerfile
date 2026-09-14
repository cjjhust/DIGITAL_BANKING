# 告警转发器镜像（2026-09-14 新增）
#
# 只用标准库，不需要 pip install，因此没有 requirements.txt。
# python:3.12-alpine 基础镜像约 50MB，整个镜像不到 60MB。
FROM python:3.12-alpine

WORKDIR /app
COPY relay.py .

# 不用 root 跑（安全基线）
RUN adduser -D -u 10001 relay
USER relay

EXPOSE 8080
CMD ["python", "relay.py"]
