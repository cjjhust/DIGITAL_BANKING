#!/usr/bin/env python3
"""
Digital Banking 端到端验证脚本（可复现、无第三方依赖）。

作用：对着已经跑起来的 Compose 全栈，按真实业务路径打一遍，验证：
  1. 服务健康
  2. 认证与账户归属（IDOR 保护）
  3. 转账成功 → 余额变化 → 状态查询到 COMPLETED
  4. 余额不足 → 状态 FAILED + 原因，且不动账
  5. 重复 requestId → 幂等（只落一行、只扣一次）
  6. 参数校验（requestId 缺失 400、未知 requestId 404）
  7. GDPR 导出（本人可、他人 403）
  8. ClickHouse 审计落库（可选，需要 docker）
  9. 死信守卫：终态不可改写（17）、无来源标识不得改账本状态（18）、
     带 banking-group 来源必须兜底成 FAILED（19）、审计死信隔离到独立主题/消费组（20）
     以上 17-20 为可选，需要 docker

用法：
    docker compose up -d
    python3 scripts/verify_e2e.py

常用参数：
    --base-url http://localhost:8080
    --skip-infra             # 跳过需要 docker 的检查（3、8、9 里的容器相关项）
    --postgres-container banking-db
    --clickhouse-container banking-clickhouse
    --kafka-container     banking-kafka
    --kafka-exporter-container banking-kafka-exporter
    --kafka-exporter-url  http://localhost:9308/metrics
    --clickhouse-user admin --clickhouse-password secret

退出码：0 = 全部通过（或仅有 SKIP）；1 = 存在 FAIL。
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

PASSWORD = "password123"          # 脚本自己注册的临时用户
TRANSFER_LIMIT = 10_000           # validateRisk 里的单笔限额，用于构造"超限"类失败
STATUS_TIMEOUT_S = 25             # 等待异步链路（Outbox → Kafka → 消费者）完成的超时
READY_TIMEOUT_S = 120             # 冷启动时等 Kafka 消费者分区分派完成的上限

PASS, FAIL, SKIP = "PASS", "FAIL", "SKIP"
results: list[tuple[str, str, str]] = []


def record(status: str, name: str, detail: str = "") -> None:
    results.append((status, name, detail))
    print(f"{status:<4} {name}" + (f"  | {detail}" if detail else ""))


def check(name: str, condition: bool, detail: str = "") -> bool:
    record(PASS if condition else FAIL, name, detail)
    return condition


# ------------------------------------------------------------------- 冷启动就绪门


def wait_for_consumers(kafka_container: str, timeout_s: int = READY_TIMEOUT_S) -> bool:
    """等 Kafka 消费者组完成首次分区分派。

    【为什么必须有这一步】`/actuator/health` 返回 200 只说明 Spring 上下文就绪，
    但 Kafka 的消费者组还要**额外几秒**做首次 rebalance（2026-09-15 实测：
    冷启动时 health 已 200，而 `banking-group` 的分区在 4~7 秒后才分配完）。
    这期间发出去的转账消息只是躺在 broker 上没有消费者，脚本若立刻断言，
    就会看到「卡在 PROCESSING」并误判为功能坏了。

    判据：`banking-group` 的每个分区都分到了 CONSUMER-ID。
    这比「睡固定秒数」可靠 —— 快的时候立刻返回，慢的时候愿意等。
    拿不到 docker/Kafka 时返回 False，由调用方决定只告警不中断。
    """
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            out = subprocess.run(
                ["docker", "exec", kafka_container, "kafka-consumer-groups",
                 "--bootstrap-server", "localhost:9092", "--describe", "--group", "banking-group"],
                capture_output=True, text=True, timeout=15).stdout
            rows = [l.split() for l in out.splitlines()
                    if "banking-transfers" in l and "banking-transfers." not in l]
            # 有行说明分区已存在；CONSUMER-ID 非空说明已分派给消费者
            if rows and all(len(r) >= 6 and r[5] != "-" for r in rows):
                return True
        except Exception:
            pass
        time.sleep(2)
    return False


# --------------------------------------------------------------------------- HTTP


def call(base_url: str, method: str, path: str, payload=None, token: str | None = None):
    """返回 (http_status, body)。body 尽量解析成 dict，解析不了就返回原文。"""
    request = urllib.request.Request(base_url + path, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)

    data = json.dumps(payload).encode() if payload is not None else None
    try:
        with urllib.request.urlopen(request, data, timeout=15) as response:
            raw = response.read().decode()
            return response.status, _parse(raw)
    except urllib.error.HTTPError as e:
        return e.code, _parse(e.read().decode())
    except urllib.error.URLError as e:
        return 0, {"error": str(e)}


def _parse(raw: str):
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"raw": raw}


# --------------------------------------------------------------------------- docker


def docker_exec(container: str, *args: str) -> tuple[bool, str]:
    try:
        out = subprocess.run(["docker", "exec", container, *args],
                             capture_output=True, text=True, timeout=30)
        return out.returncode == 0, (out.stdout or out.stderr).strip()
    except Exception as e:  # docker 不可用 / 容器不存在
        return False, str(e)


def db_scalar(container: str, sql: str) -> tuple[bool, str]:
    """在 Postgres 容器里跑一条查询，返回单值（-t -A 不带表头和边框）。"""
    return docker_exec(container, "psql", "-U", "admin", "-d", "banking_db", "-t", "-A", "-c", sql)


def publish_to_dlt(container: str, key: str, value: str, origin: str | None) -> subprocess.CompletedProcess:
    """
    往账本死信主题丢一条消息，可选带上 x-origin-consumer header。

    kafka-console-producer 的 header 语法：header 块在最前面，用 headers.delimiter（默认制表符）
    与后面的 key:value 分隔；因此有 header 时行格式是 ``x-origin-consumer:banking-group<TAB>key:value``。
    这里不用 shell 拼字符串，直接把真正包含制表符的参数传给 docker，避免转义踩坑。
    """
    args = ["docker", "exec", "-i", container, "kafka-console-producer",
            "--bootstrap-server", "kafka:9092", "--topic", "banking-transfers.DLT",
            "--property", "parse.key=true", "--property", "key.separator=:"]
    if origin is None:
        line = f"{key}:{value}\n"
    else:
        args += ["--property", "parse.headers=true", "--property", "headers.delimiter=\t"]
        line = f"x-origin-consumer:{origin}\t{key}:{value}\n"
    return subprocess.run(args, input=line, capture_output=True, text=True, timeout=30)


# --------------------------------------------------------------------------- helpers


def register(base_url: str, prefix: str) -> str:
    username = f"{prefix}{uuid.uuid4().hex[:8]}"
    status, body = call(base_url, "POST", "/api/auth/register",
                        {"username": username, "password": PASSWORD, "email": username + "@test.com"})
    assert status in (200, 201), f"register failed: {status} {body}"
    return body["token"]


def create_account(base_url: str, token: str, account_number: str) -> None:
    status, body = call(base_url, "POST", "/api/account",
                        {"ownerName": "e2e-owner", "accountNumber": account_number}, token)
    assert status in (200, 201), f"create account failed: {status} {body}"


def balance_of(base_url: str, token: str, account_number: str) -> str:
    status, body = call(base_url, "GET", f"/api/account/{account_number}", token=token)
    assert status == 200, f"balance lookup failed: {status} {body}"
    return str(body.get("balance"))


def await_status(base_url: str, token: str, request_id: str, expected: set[str]) -> dict:
    """轮询受理结果：转账是异步的（Outbox → Kafka → 消费者）。"""
    last: dict = {}
    for _ in range(STATUS_TIMEOUT_S):
        status, body = call(base_url, "GET", f"/api/account/transfer/{request_id}", token=token)
        if status == 200:
            last = body
            if body.get("status") in expected:
                return body
        time.sleep(1)
    return last


def transfer_body(request_id: str, source: str, target: str, amount: str) -> dict:
    return {"requestId": request_id, "fromAccountNo": source, "toAccountNo": target,
            "amount": float(amount)}


# --------------------------------------------------------------------------- main


def main() -> int:
    parser = argparse.ArgumentParser(description="Digital Banking end-to-end verification")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--skip-infra", action="store_true", help="跳过需要 docker 的检查")
    parser.add_argument("--postgres-container", default="banking-db")
    parser.add_argument("--clickhouse-container", default="banking-clickhouse")
    parser.add_argument("--kafka-container", default="banking-kafka")
    parser.add_argument("--kafka-exporter-container", default="banking-kafka-exporter")
    parser.add_argument("--kafka-exporter-url", default="http://localhost:9308/metrics")
    parser.add_argument("--clickhouse-user", default="admin")
    parser.add_argument("--clickhouse-password", default="secret")
    args = parser.parse_args()
    base = args.base_url

    tag = uuid.uuid4().hex[:10]
    owner_account = f"E2E_A_{tag}"
    other_account = f"E2E_B_{tag}"

    print(f"=== Digital Banking E2E verification @ {base} ===")

    # 1. 健康检查
    status, body = call(base, "GET", "/actuator/health")
    check("1. 服务健康 UP", status == 200 and body.get("status") == "UP", f"{status} {body}")

    # 1b. 冷启动就绪门：health=200 只代表 Spring 上下文就绪，
    #     Kafka 消费者组的首次分区分派还要额外几秒。不等这一步，
    #     下面的异步断言会在冷启动时误判（实测：全新克隆首次运行 3 项失败，
    #     第二次立刻运行 26 项全过 —— 纯粹是时序问题）。
    if args.skip_infra:
        record(SKIP, "1b. 等待 Kafka 消费者分区分派（冷启动就绪门）", "--skip-infra")
    else:
        ready = wait_for_consumers(args.kafka_container)
        record(PASS if ready else FAIL,
               "1b. 等待 Kafka 消费者分区分派（冷启动就绪门）",
               "banking-group 全部分区已分派" if ready else f"{READY_TIMEOUT_S}s 内仍未分派完成")

    # 2. 注册两个用户并开户（owner 与 attacker）
    token_a = register(base, "e2ea")
    token_b = register(base, "e2eb")
    create_account(base, token_a, owner_account)
    create_account(base, token_b, other_account)
    status, body = call(base, "GET", f"/api/account/{owner_account}", token=token_a)
    check("2. 开户成功且带上 user_id（归属已落库）", status == 200 and body.get("userId") is not None,
          f"userId={body.get('userId')}")

    # 3. 归属校验：他人不能读、不能查流水、不能代付、不能查状态
    status_read, _ = call(base, "GET", f"/api/account/{owner_account}", token=token_b)
    status_hist, _ = call(base, "GET", f"/api/account/{owner_account}/transactions", token=token_b)
    status_spend, _ = call(base, "POST", "/api/account/transfer",
                           transfer_body(f"stolen-{tag}", owner_account, other_account, "10.00"), token_b)
    anon_status, _ = call(base, "GET", f"/api/account/{owner_account}")
    check("3. 越权全部 403（读/流水/代付/匿名）",
          {status_read, status_hist, status_spend} == {403} and anon_status in (401, 403),
          f"read={status_read} history={status_hist} transfer={status_spend} anon={anon_status}")

    # 4. 充值（没有充值 API，直接改库；与 scripts/batch_transfer_demo.py 一致）
    funded = False
    if not args.skip_infra:
        ok, out = docker_exec(args.postgres_container, "psql", "-U", "admin", "-d", "banking_db", "-q",
                              "-c", f"UPDATE accounts SET balance = 1000 WHERE account_number = '{owner_account}';")
        funded = ok
        check("4. 测试账户充值 1000（docker + psql）", ok, out[:80])
    else:
        record(SKIP, "4. 测试账户充值 1000（--skip-infra）")

    if not funded:
        record(SKIP, "5-6. 转账成功路径（依赖充值）")
        ok_tx_id = None
    else:
        # 5. 转账成功 → 余额变化 → 状态查询
        request_ok = f"e2e-ok-{tag}"
        status, _ = call(base, "POST", "/api/account/transfer",
                         transfer_body(request_ok, owner_account, other_account, "150.25"), token_a)
        check("5. 受理转账返回 202", status == 202, f"status={status}")

        final = await_status(base, token_a, request_ok, {"COMPLETED", "FAILED"})
        ok_tx_id = final.get("transactionId")
        check("6. 异步链路跑完并变为 COMPLETED", final.get("status") == "COMPLETED",
              f"status={final.get('status')} tx={ok_tx_id}")
        check("7. 余额正确（源 849.75 / 目标 150.25）",
              balance_of(base, token_a, owner_account) == "849.75"
              and balance_of(base, token_b, other_account) == "150.25",
              f"{balance_of(base, token_a, owner_account)} / {balance_of(base, token_b, other_account)}")

        check("8. 状态查询的越权保护（他人 403）",
              call(base, "GET", f"/api/account/transfer/{request_ok}", token=token_b)[0] == 403)

    # 9. 余额不足 → FAILED + 原因，且不动账
    if funded:
        insufficient = f"e2e-ins-{tag}"
        before = balance_of(base, token_a, owner_account)
        call(base, "POST", "/api/account/transfer",
             transfer_body(insufficient, owner_account, other_account, "5000.00"), token_a)
        final = await_status(base, token_a, insufficient, {"COMPLETED", "FAILED"})
        check("9. 余额不足 → FAILED 且带原因",
              final.get("status") == "FAILED" and "insufficient" in str(final.get("errorMessage", "")).lower(),
              f"status={final.get('status')} reason={final.get('errorMessage')}")
        check("10. 业务失败不动账", balance_of(base, token_a, owner_account) == before,
              f"before={before} after={balance_of(base, token_a, owner_account)}")
    else:
        record(SKIP, "9-10. 余额不足路径（依赖充值）")

    # 11. 幂等：同一个 requestId 提交两次
    if funded:
        duplicate = f"e2e-dup-{tag}"
        before_from = balance_of(base, token_a, owner_account)
        before_to = balance_of(base, token_b, other_account)
        first, _ = call(base, "POST", "/api/account/transfer",
                        transfer_body(duplicate, owner_account, other_account, "100.00"), token_a)
        second, _ = call(base, "POST", "/api/account/transfer",
                         transfer_body(duplicate, owner_account, other_account, "100.00"), token_a)
        await_status(base, token_a, duplicate, {"COMPLETED", "FAILED"})
        check("11. 重复 requestId：首次 202，重复请求 202（已受理）或 409（在途/上次中断）",
              first == 202 and second in (202, 409), f"first={first} second={second}")

        after_from = balance_of(base, token_a, owner_account)
        after_to = balance_of(base, token_b, other_account)
        check("12. 只扣一次（100.00）",
              float(after_from) == float(before_from) - 100.0 and float(after_to) == float(before_to) + 100.0,
              f"{before_from}->{after_from} / {before_to}->{after_to}")

        if not args.skip_infra:
            ok, out = docker_exec(args.postgres_container, "psql", "-U", "admin", "-d", "banking_db", "-t", "-A",
                                  "-c", f"SELECT count(*) FROM processed_transactions WHERE client_request_id = '{duplicate}';")
            check("13. processed_transactions 只有一行", ok and out.strip() == "1", f"rows={out!r}")
        else:
            record(SKIP, "13. processed_transactions 唯一性（--skip-infra）")
    else:
        record(SKIP, "11-13. 幂等路径（依赖充值）")

    # 14. 参数校验
    missing_id, _ = call(base, "POST", "/api/account/transfer",
                         {"fromAccountNo": owner_account, "toAccountNo": other_account, "amount": 1.0}, token_a)
    unknown_id, _ = call(base, "GET", f"/api/account/transfer/does-not-exist-{tag}", token=token_a)
    check("14. requestId 缺失 → 400，未知 requestId → 404",
          missing_id == 400 and unknown_id == 404, f"missing={missing_id} unknown={unknown_id}")

    # 15. GDPR 导出
    me_status, me = call(base, "GET", "/api/auth/me", token=token_a)
    user_id = me.get("id") if me_status == 200 else None
    export_self, export_body = call(base, "GET", f"/api/gdpr/user/{user_id}/export", token=token_a)
    export_other, _ = call(base, "GET", f"/api/gdpr/user/{user_id}/export", token=token_b)
    check("15. GDPR 导出本人 200 / 他人 403",
          export_self == 200 and export_other == 403 and isinstance(export_body, dict)
          and "user" in export_body,
          f"self={export_self} other={export_other} accounts={len(export_body.get('accounts', [])) if isinstance(export_body, dict) else '-'}")

    # 16. ClickHouse 审计（可选）
    if args.skip_infra or not funded:
        record(SKIP, "16. ClickHouse 审计落库（--skip-infra 或未充值）")
    else:
        ok, out = docker_exec(args.clickhouse_container, "clickhouse-client",
                              "--user", args.clickhouse_user, "--password", args.clickhouse_password, "-q",
                              f"SELECT count(*) FROM banking_analytics.transaction_audit WHERE client_request_id = 'e2e-ok-{tag}'")
        check("16. ClickHouse 审计已落库", ok and out.strip().isdigit() and int(out) >= 1, f"rows={out!r}")

    # 17-19. 死信守卫（可选，需要 docker）
    #   17. 终态不可改写：DLT 里出现某条已 COMPLETED 的交易，状态必须保持 COMPLETED
    #   18. 来源守卫：DLT 消息若没有 x-origin-consumer=banking-group，不得据此断定转账失败
    #   19. 正向对照：同一条 PENDING 交易，带正确来源标识的死信**必须**能兜底成 FAILED
    #       （没有 19，17/18 就有可能是"监听器根本没在跑"造成的假通过）
    if args.skip_infra or not funded:
        record(SKIP, "17-19. 死信守卫（--skip-infra 或未充值）")
    else:
        ok, tx_id = docker_exec(args.postgres_container, "psql", "-U", "admin", "-d", "banking_db", "-t", "-A",
                                "-c", "SELECT transaction_id FROM processed_transactions WHERE status = 'COMPLETED' ORDER BY created_at DESC LIMIT 1;")
        if not ok or not tx_id.strip():
            record(SKIP, "17-19. 死信守卫（找不到 COMPLETED 交易）")
        else:
            tx_id = tx_id.strip()
            produce = publish_to_dlt(args.kafka_container, tx_id, '{"source":"verify_e2e"}',
                                     origin="banking-group")
            time.sleep(12)
            ok_db, still = db_scalar(args.postgres_container,
                                     f"SELECT status FROM processed_transactions WHERE transaction_id = '{tx_id}';")
            check("17. 死信不得改写终态 COMPLETED",
                  produce.returncode == 0 and ok_db and still == "COMPLETED",
                  f"tx={tx_id} status={still!r}")

            # 18/19 用一条脚本自己插进去的 PENDING 交易做对照
            pending_tx = f"e2e-dlq-{tag}"
            ok_ins, _ = docker_exec(args.postgres_container, "psql", "-U", "admin", "-d", "banking_db", "-q", "-c",
                                    "INSERT INTO processed_transactions "
                                    "(transaction_id, client_request_id, from_account_no, to_account_no, amount, status) "
                                    f"VALUES ('{pending_tx}', '{pending_tx}', '{owner_account}', '{other_account}', 1.00, 'PENDING') "
                                    "ON CONFLICT (transaction_id) DO NOTHING;")
            if not ok_ins:
                record(SKIP, "18-19. 死信来源守卫（无法插入 PENDING 测试行）")
            else:
                # 18. 不带来源标识 → 记账链路没失败过，状态必须原样不动
                no_origin = publish_to_dlt(args.kafka_container, pending_tx, '{"source":"verify_e2e"}', origin=None)
                time.sleep(12)
                ok_db, after_no_origin = db_scalar(args.postgres_container,
                                                   f"SELECT status FROM processed_transactions WHERE transaction_id = '{pending_tx}';")
                check("18. 无来源标识的死信不得改写账本状态（仍为 PENDING）",
                      no_origin.returncode == 0 and ok_db and after_no_origin == "PENDING",
                      f"tx={pending_tx} status={after_no_origin!r}")

                # 19. 正向对照：带上 banking-group → 必须兜底成 FAILED，证明监听器真的在处理死信
                with_origin = publish_to_dlt(args.kafka_container, pending_tx, '{"source":"verify_e2e"}',
                                             origin="banking-group")
                time.sleep(12)
                ok_db, after_with_origin = db_scalar(args.postgres_container,
                                                     f"SELECT status FROM processed_transactions WHERE transaction_id = '{pending_tx}';")
                check("19. 带 banking-group 来源的死信把 PENDING 兜底为 FAILED（正向对照）",
                      with_origin.returncode == 0 and ok_db and after_with_origin == "FAILED",
                      f"tx={pending_tx} status={after_with_origin!r}")

    # 20. 审计死信隔离：审计失败必须进自己的主题（banking-transfers.audit-DLT），
    #     且由独立的消费组处理，不能落在账本死信里
    if args.skip_infra:
        record(SKIP, "20. 审计死信主题与消费组（--skip-infra）")
    else:
        groups = subprocess.run(
            ["docker", "exec", args.kafka_container, "kafka-consumer-groups",
             "--bootstrap-server", "kafka:9092", "--describe", "--group", "banking-audit-dlt-group"],
            capture_output=True, text=True, timeout=30)
        output = groups.stdout + groups.stderr
        check("20. 审计死信主题由 banking-audit-dlt-group 独立消费",
              "banking-transfers.audit-DLT" in output,
              "audit-DLT 消费组未注册（审计死信可能与账本死信混在一起）"
              if "banking-transfers.audit-DLT" not in output else "topic=banking-transfers.audit-DLT")

    # 21. 自转账必须被拒（规则在消费者侧生效 → 落成 FAILED + 原因）
    if funded:
        self_tx = f"e2e-self-{tag}"
        call(base, "POST", "/api/account/transfer",
             transfer_body(self_tx, owner_account, owner_account, "1.00"), token_a)
        final = await_status(base, token_a, self_tx, {"COMPLETED", "FAILED"})
        check("21. 自转账被拒（FAILED + 原因）",
              final.get("status") == "FAILED"
              and "self transfer" in str(final.get("errorMessage", "")).lower(),
              f"status={final.get('status')} reason={final.get('errorMessage')}")
    else:
        record(SKIP, "21. 自转账（依赖充值）")

    # 22. 金额精度：三位小数必须在受理阶段就被拒（400），否则会先被 Postgres 静默四舍五入
    precision, _ = call(base, "POST", "/api/account/transfer",
                        transfer_body(f"e2e-scale-{tag}", owner_account, other_account, "1.001"), token_a)
    check("22. 三位小数被拒 400（不允许静默四舍五入）", precision == 400, f"status={precision}")

    # 23-24. 复式记账（可选，需要 docker）
    #   23. 一笔转账必须写借贷两条分录，金额相等、方向相反
    #   24. 全账不变量：每个币种下借方合计 == 贷方合计
    if args.skip_infra or not funded or not ok_tx_id:
        record(SKIP, "23-24. 复式记账（--skip-infra 或缺少成功交易）")
    else:
        ok, rows = db_scalar(args.postgres_container,
                             "SELECT direction || ':' || amount FROM ledger_entries "
                             f"WHERE transaction_id = '{ok_tx_id}' ORDER BY direction;")
        check("23. 一笔转账写入借贷两条分录",
              ok and rows.replace("\n", ",") == "CREDIT:150.25,DEBIT:150.25",
              f"entries={rows!r}")

        ok, unbalanced = db_scalar(args.postgres_container,
                                   "SELECT count(*) FROM (SELECT currency FROM ledger_entries GROUP BY currency "
                                   "HAVING COALESCE(SUM(CASE WHEN direction='DEBIT' THEN amount ELSE 0 END),0) "
                                   "<> COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount ELSE 0 END),0)) x;")
        check("24. 全账借贷平衡（无不平的币种）", ok and unbalanced == "0",
              f"unbalanced_currencies={unbalanced!r}")

    # 25. 分区数与消费者并发度必须匹配：concurrency=3 但 topic 只有 1 个分区时，
    #     另外 2 个消费者线程纯闲置（2026-09-13 发现：实测 PartitionCount 为 1）。
    if args.skip_infra:
        record(SKIP, "25. 转账主题分区数 ≥ 3（--skip-infra）")
    else:
        ok, desc = docker_exec(args.kafka_container, "kafka-topics", "--bootstrap-server", "kafka:9092",
                               "--describe", "--topic", "banking-transfers")
        partitions = len([line for line in desc.splitlines() if "Partition:" in line])
        check("25. 转账主题分区数 ≥ 3（与 concurrency=3 匹配）",
              ok and partitions >= 3, f"partitions={partitions}")

    # 25b. kafka-exporter 必须活着并真的产出 lag 指标。
    #      它是 prometheus/banking-alerts.yml 里 4 条 pipeline 规则（消费积压、
    #      DLT 堆积等）的唯一数据源。2026-09-15 冷启动审计发现：它原先用短格式
    #      `depends_on: - kafka`（等价 service_started）抢跑，broker 未就绪就
    #      Exited(255)，且没有重启策略 —— lag 指标从此永久消失，告警静默不报。
    #      静默失败比告警误报更危险，所以这里直接断言指标在。
    if args.skip_infra:
        record(SKIP, "25b. kafka-exporter 产出 consumer lag 指标（--skip-infra）")
    else:
        running = subprocess.run(
            ["docker", "inspect", "-f", "{{.State.Running}}", args.kafka_exporter_container],
            capture_output=True, text=True).stdout.strip()
        metric = ""
        try:
            with urllib.request.urlopen(args.kafka_exporter_url, timeout=10) as response:
                metric = next((line for line in response.read().decode().splitlines()
                               if line.startswith("kafka_consumergroup_lag{")), "")
        except Exception:
            pass
        check("25b. kafka-exporter 产出 consumer lag 指标（4 条 pipeline 告警的数据源）",
              running == "true" and bool(metric),
              f"running={running or 'not-found'} " +
              ("已提供 kafka_consumergroup_lag" if metric else "未取到 kafka_consumergroup_lag"))

    # 26. GDPR 匿名化必须断开关联链：只抹用户名/邮箱是「假名化」，
    #     user_id → account_no → 流水 这条链还在就仍然属于个人数据（Art. 4(1)）。
    #     用一个一次性用户验证，避免破坏前面检查依赖的账号。
    if args.skip_infra:
        record(SKIP, "26. GDPR 匿名为断开关联链（--skip-infra）")
    else:
        token_c = register(base, "e2egdpr")
        account_c = f"E2EGDPR{tag}"
        create_account(base, token_c, account_c)
        me_c, me_body_c = call(base, "GET", "/api/auth/me", token=token_c)
        user_c = me_body_c.get("id") if me_c == 200 else None
        del_c, del_body_c = call(base, "DELETE", f"/api/gdpr/user/{user_c}", token=token_c)
        ok_db, uid = db_scalar(args.postgres_container,
                               "SELECT COALESCE(user_id::text, 'NULL') FROM accounts "
                               f"WHERE account_number = '{account_c}';")
        check("26. GDPR 匿名化断开关联链（accounts.user_id → NULL）",
              del_c == 200 and ok_db and uid == "NULL", f"delete={del_c} user_id={uid!r}")

    # 汇总
    failed = [r for r in results if r[0] == FAIL]
    skipped = [r for r in results if r[0] == SKIP]
    print(f"\n=== {len(results) - len(failed) - len(skipped)} PASS / {len(failed)} FAIL / {len(skipped)} SKIP ===")
    if failed:
        print("失败项：")
        for _, name, detail in failed:
            print(f"  - {name}  {detail}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
