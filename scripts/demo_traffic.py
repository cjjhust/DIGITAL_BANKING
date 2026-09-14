#!/usr/bin/env python3
"""
生成一批真实转账流量，用于产出监控截图。

覆盖：成功 / 余额不足 / 自转账 / 超限额 / 重复 requestId（幂等命中）
这样可以同时让 TPS、成功率、失败率、延迟、幂等命中率几个面板都有形状。
"""
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8080"
PASSWORD = "password123"


def _parse(raw: str):
    """受理接口返回的是纯文本（"Transfer request accepted. ID: ..."），不是 JSON。"""
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"raw": raw}


def call(method, path, payload=None, token=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(payload).encode() if payload is not None else None
    try:
        with urllib.request.urlopen(req, data, timeout=20) as r:
            return r.status, _parse(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, _parse(e.read().decode())


def main():
    tag = uuid.uuid4().hex[:6]
    user = f"demo{tag}"

    status, body = call("POST", "/api/auth/register",
                        {"username": user, "password": PASSWORD, "email": user + "@test.com"})
    assert status in (200, 201), f"register failed: {status} {body}"
    token = body["token"]

    src = f"DEMO{tag}A"
    dst = f"DEMO{tag}B"
    call("POST", "/api/account", {"ownerName": user, "accountNumber": src}, token)
    call("POST", "/api/account", {"ownerName": user, "accountNumber": dst}, token)

    # 建账户不带初始余额，这里给源账户充值，让后面的转账能成功
    import subprocess
    subprocess.run(["docker", "exec", "banking-db", "psql", "-U", "admin", "-d", "banking_db",
                    "-q", "-c", f"UPDATE accounts SET balance=5000 WHERE account_number='{src}';"],
                   check=True, capture_output=True)

    def transfer(request_id, amount, to=None):
        return call("POST", "/api/account/transfer",
                    {"requestId": request_id, "fromAccountNo": src,
                     "toAccountNo": to or dst, "amount": amount}, token)

    print(f"用户 {user} / 账户 {src} -> {dst}")

    # 1) 成功转账 6 笔，间隔 3 秒，让曲线有形状
    amounts = ["25.00", "40.50", "12.75", "88.00", "33.25", "60.00"]
    for i, amt in enumerate(amounts, 1):
        st, _ = transfer(f"{user}-ok-{i}", amt)
        print(f"  成功转账 {i}: {amt}  -> HTTP {st}")
        time.sleep(3)

    # 2) 业务失败：余额不足（源账户只剩一点，转 99999 一定不够）
    st, _ = transfer(f"{user}-insufficient", "99999.00")
    print(f"  余额不足          -> HTTP {st}")
    time.sleep(2)

    # 3) 业务失败：自转账
    st, _ = transfer(f"{user}-self", "5.00", to=src)
    print(f"  自转账            -> HTTP {st}")
    time.sleep(2)

    # 4) 业务失败：超单笔限额（默认 10000）
    st, _ = transfer(f"{user}-overlimit", "12000.00")
    print(f"  超限额            -> HTTP {st}")
    time.sleep(2)

    # 5) 幂等命中：同一个 requestId 连发 3 次
    dup = f"{user}-dup"
    for i in range(3):
        st, _ = transfer(dup, "10.00")
        print(f"  重复提交 {i + 1}/3      -> HTTP {st}")
        time.sleep(1)

    print("\n完成。等 20 秒让审计写入 + 指标刷新...")
    time.sleep(20)

    # 取最后一笔成功转账的 traceId，供截图用
    trace = subprocess.run(
        ["docker", "exec", "banking-clickhouse", "clickhouse-client", "--user", "admin",
         "--password", "secret", "-q",
         f"SELECT trace_id FROM banking_analytics.transaction_audit "
         f"WHERE client_request_id='{user}-ok-6' FORMAT TSV"],
        capture_output=True, text=True).stdout.strip()
    print(f"\n用于截图的 traceId = {trace}")
    with open("/tmp/demo_ids.txt", "w") as f:
        f.write(f"{user}\n{src}\n{dst}\n{trace}\n")


if __name__ == "__main__":
    sys.exit(main())
