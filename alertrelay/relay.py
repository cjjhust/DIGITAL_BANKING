#!/usr/bin/env python3
"""
Alertmanager → ntfy 转发器（2026-09-14 新增）

为什么需要这个「中间人」？
  Alertmanager 的 webhook 只会推送固定结构的 JSON：
      {"version":"4","status":"firing","alerts":[{"labels":{...},"annotations":{...}}], ...}
  而 ntfy 的推送接口期望的是：
      {"topic":"...","title":"...","message":"...","priority":5,"tags":[...]}
  两者字段完全不搭。直接把 Alertmanager 指向 ntfy，手机上会显示一坨原始 JSON。

  所以这里做一个**只做翻译、不做判断**的极小转发器：
      Alertmanager ──webhook──► 本服务（重排版）──HTTPS──► ntfy.sh ──推送──► 手机

设计原则：
  1. **只用标准库** —— 不需要 pip install 任何东西，镜像极小，没有依赖供应链风险
  2. **永不阻塞 Alertmanager** —— 推送到 ntfy 失败也返回 200，否则 Alertmanager 会一直重试
  3. **手机通知要短** —— 标题放「级别 + 告警名」，正文只放 summary + 第一条要执行的命令
     （完整内容看邮件，手机上刷长文没意义）
"""

import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer

NTFY_SERVER = os.environ.get("NTFY_SERVER", "https://ntfy.sh").rstrip("/")
NTFY_TOPIC = os.environ.get("NTFY_TOPIC", "").strip()
PORT = int(os.environ.get("PORT", "8080"))

# 严重程度 → 手机端的视觉/触觉表现
# priority: 1=min 2=low 3=default 4=high 5=max(会绕过勿扰模式，Android 上会亮屏)
SEVERITY_STYLE = {
    "critical": {"emoji": "🚨", "priority": 5, "tags": ["rotating_light"]},
    "warning":  {"emoji": "⚠️", "priority": 3, "tags": ["warning"]},
}


def build_payload(alert: dict, status: str, common: dict) -> dict:
    """把 Alertmanager 的单个 alert 翻译成 ntfy 的一条推送。"""
    labels = alert.get("labels") or {}
    ann = alert.get("annotations") or {}
    severity = labels.get("severity", "warning")
    style = SEVERITY_STYLE.get(severity, SEVERITY_STYLE["warning"])
    resolved = status == "resolved"

    if resolved:
        title = f"✅ 已恢复 {labels.get('alertname', '')}"
        priority, tags = 2, ["white_check_mark"]
    else:
        title = f"{style['emoji']} 告警 {labels.get('alertname', '')}"
        priority, tags = style["priority"], style["tags"]

    # 正文：摘要 + 第一行要执行的命令。手机上越短越好。
    body_lines = [ann.get("summary", "(no summary)")]
    action = (ann.get("action") or "").strip()
    if action:
        first = action.splitlines()[0].strip()
        # 去掉编号前缀，例如 "1) docker ps | grep x 看容器在不在"
        for prefix in ("1)", "1."):
            if first.startswith(prefix):
                first = first[len(prefix):].strip()
        body_lines.append("")
        body_lines.append(f"👉 {first}")

    return {
        "topic": NTFY_TOPIC,
        "title": title,
        "message": "\n".join(body_lines),
        "priority": priority,
        "tags": tags,
    }


def push(payload: dict) -> None:
    """推送到 ntfy。失败只记日志，不抛异常。"""
    if not NTFY_TOPIC:
        print("[relay] NTFY_TOPIC 未配置，跳过推送", flush=True)
        return
    req = urllib.request.Request(
        NTFY_SERVER,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=8) as resp:
            print(f"[relay] pushed title={payload['title']!r} -> HTTP {resp.status}", flush=True)
    except urllib.error.HTTPError as e:
        print(f"[relay] ntfy 返回 HTTP {e.code}: {e.read()[:200]!r}", flush=True)
    except Exception as e:  # noqa: BLE001 - 网络问题不该影响上游
        print(f"[relay] ntfy 推送失败: {e}", flush=True)


class Handler(BaseHTTPRequestHandler):
    # 让日志里带上时间戳，方便和 Alertmanager 日志对齐
    def log_message(self, fmt, *args):  # noqa: A003
        ts = datetime.now(timezone.utc).strftime("%H:%M:%S")
        print(f"[relay {ts}] {fmt % args}", flush=True)

    def do_GET(self):
        # 给容器健康检查用
        if self.path in ("/health", "/healthz"):
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"ok")
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""

        try:
            data = json.loads(raw or b"{}")
        except json.JSONDecodeError as e:
            print(f"[relay] 无法解析 payload: {e}", flush=True)
            # 仍然返回 200：格式问题重试也没用
            self.send_response(200)
            self.end_headers()
            return

        status = data.get("status", "firing")
        alerts = data.get("alerts") or []
        print(f"[relay] 收到 webhook: status={status} alerts={len(alerts)}", flush=True)

        for alert in alerts:
            push(build_payload(alert, status, data.get("commonAnnotations") or {}))

        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"ok")


if __name__ == "__main__":
    print(f"[relay] 启动：server={NTFY_SERVER} topic="
          f"{NTFY_TOPIC if NTFY_TOPIC else '(未配置，仅打印)'} port={PORT}", flush=True)
    if not NTFY_TOPIC:
        print("[relay] ⚠️ 未设置 NTFY_TOPIC —— 请在 .env 里配置后重启本容器", flush=True)
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
