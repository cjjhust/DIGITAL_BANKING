#!/usr/bin/env python3
"""
真实业务批量测试脚本：
1) 注册 50 个用户
2) 为每个用户创建 2 个唯一账户
3) 生成 250 笔随机转账记录
4) 输出成功/失败状态和最终指标检查建议

使用方式:
    python3 scripts/batch_transfer_demo.py
"""

import json
import random
import subprocess
import time
from typing import Any, Dict, Tuple

BASE_URL = "http://localhost:8080"
PASSWORD = "password123"
USER_COUNT = 50
TRANSFER_PER_USER = 5
RUN_TAG = int(time.time())
USER_SUFFIX = RUN_TAG % 100000
INITIAL_BALANCE = 10000.00


def check_app_ready() -> None:
    try:
        subprocess.check_output(
            ["curl", "-fsS", f"{BASE_URL}/actuator/health"],
            stderr=subprocess.DEVNULL,
            text=True,
        )
    except subprocess.CalledProcessError as exc:
        raise RuntimeError("The application is not ready at http://localhost:8080") from exc


def http_post_json(path: str, payload: Dict[str, Any], token: str | None = None) -> Tuple[Dict[str, Any], str]:
    cmd = [
        "curl",
        "-sS",
        "-w",
        "\nHTTP_CODE:%{http_code}",
        "-X",
        "POST",
        BASE_URL + path,
        "-H",
        "Content-Type: application/json",
    ]
    if token:
        cmd += ["-H", f"Authorization: Bearer {token}"]
    cmd += ["-d", json.dumps(payload)]

    out = subprocess.check_output(cmd, text=True)
    body, _, code = out.rpartition("\nHTTP_CODE:")

    try:
        parsed = json.loads(body) if body.strip() else {}
    except json.JSONDecodeError:
        parsed = {"raw": body}

    return parsed, code.strip()


def register_user(username: str, email: str) -> str:
    payload = {
        "username": username,
        "password": PASSWORD,
        "email": email,
    }
    resp, code = http_post_json("/api/auth/register", payload)
    print(f"[register] {username} -> code={code}, payload={resp}")
    if code != "201" and code != "200":
        raise RuntimeError(f"register failed for {username}: {resp}")
    token = resp.get("token")
    if not token:
        raise RuntimeError(f"register succeeded but token missing for {username}: {resp}")
    return token


def create_account(token: str, owner_name: str, account_number: str) -> None:
    payload = {
        "ownerName": owner_name,
        "accountNumber": account_number,
    }
    resp, code = http_post_json("/api/account", payload, token=token)
    print(f"[account] {account_number} -> code={code}, payload={resp}")
    if code not in {"200", "201"}:
        print(f"account creation warning: {resp}")


def transfer(token: str, from_account: str, to_account: str, amount: float, request_id: str) -> None:
    payload = {
        "requestId": request_id,
        "fromAccountNo": from_account,
        "toAccountNo": to_account,
        "amount": round(amount, 2),
    }
    resp, code = http_post_json("/api/account/transfer", payload, token=token)
    print(f"[transfer] {from_account}->{to_account} {amount} -> code={code}, payload={resp}")


def choose_target_account(source_user: str, user_accounts: Dict[str, list[str]], all_accounts: list[str]) -> str:
    candidates = [acc for acc in all_accounts if acc not in user_accounts[source_user]]
    if not candidates:
        raise RuntimeError("No target account available")
    return random.choice(candidates)


def fund_test_accounts(account_numbers: list[str]) -> None:
    quoted_accounts = ", ".join(f"'{account_number}'" for account_number in account_numbers)
    sql = f"UPDATE accounts SET balance = {INITIAL_BALANCE} WHERE account_number IN ({quoted_accounts});"
    subprocess.check_call([
        "docker", "exec", "banking-db", "psql", "-U", "admin", "-d", "banking_db", "-c", sql,
    ])
    print(f"=== 已为 {len(account_numbers)} 个测试账户初始化余额 {INITIAL_BALANCE:.2f} ===")


def main() -> None:
    random.seed(42)
    check_app_ready()
    print("=== 批量真实交易测试开始 ===")

    user_tokens: Dict[str, str] = {}
    user_accounts: Dict[str, list[str]] = {}
    all_accounts: list[str] = []

    for i in range(1, USER_COUNT + 1):
        username = f"u{i:02d}{USER_SUFFIX:05d}"
        email = f"u{i:02d}{USER_SUFFIX:05d}@test.com"
        token = register_user(username, email)
        user_tokens[username] = token

        created_accounts = []
        for j in range(1, 3):
            account_number = f"BATCH_{username}_{j}"
            create_account(token, owner_name=username, account_number=account_number)
            created_accounts.append(account_number)
            all_accounts.append(account_number)

        user_accounts[username] = created_accounts

    # 账户接口默认余额为 0；这里仅为批量测试准备资金，转账仍走真实业务 API。
    fund_test_accounts(all_accounts)
    print(f"=== 已创建 {len(all_accounts)} 个账户，开始发起 {USER_COUNT * TRANSFER_PER_USER} 笔随机转账 ===")

    total_transfers = 0
    for i in range(1, USER_COUNT + 1):
        username = f"u{i:02d}{USER_SUFFIX:05d}"
        token = user_tokens[username]
        account_options = user_accounts[username]

        for j in range(1, TRANSFER_PER_USER + 1):
            from_account = random.choice(account_options)
            to_account = choose_target_account(username, user_accounts, all_accounts)
            amount = round(random.uniform(10.0, 500.0), 2)
            request_id = f"req-{username}-{j}-{int(time.time() * 1000)}-{total_transfers}"
            transfer(token, from_account, to_account, amount, request_id)
            total_transfers += 1
            time.sleep(0.2)

    print(f"=== 批量真实交易测试完成：共发起 {total_transfers} 笔转账 ===")
    print("监控查看：")
    print("  Prometheus: http://localhost:9090")
    print("  Grafana: http://localhost:3000 (admin/admin)")
    print("  Kibana: http://localhost:5601")
    print("  App logs: docker logs banking-app --tail 50")
    print("  DB check: docker exec banking-db psql -U admin -d banking_db -c \"SELECT count(*) FROM outbox_events; SELECT count(*) FROM processed_transactions;\"")


if __name__ == "__main__":
    main()
