# 贡献指南

## 本地环境

```bash
cp .env.example .env      # 必须填 4 个密钥，缺失会启动失败（fail-fast）
docker compose up -d --build
```

需要 Docker Desktop。基础设施端口都绑在 `127.0.0.1`，只有应用端口 8080 对外。

## 提交前必须做的三件事

```bash
./gradlew test              # 单元测试 + ArchUnit 架构测试（不需要 Docker）
python3 scripts/verify_e2e.py   # 端到端验证（需要 Compose 栈在跑，20 项断言）
./gradlew bootJar           # 确认能打包
```

`verify_e2e.py` 退出码非 0 表示有失败项，不要带着它提交。

## 代码约定

- **分层**：`controller` → `service` → `repository`，由 `ArchitectureTest` 强制。Controller 不得直接依赖 Repository。
- **Kafka 监听器**：每个 `@KafkaListener` 必须显式声明 `containerFactory`，且不得使用 `topicPattern`。原因见 `ArchitectureTest` 里的注释——漏写会让审计失败被当成账本失败处理。
- **金额**：`BigDecimal`，两位小数，禁止浮点数。
- **数据库变更**：一律新增 Flyway 迁移（`src/main/resources/db/migration/V{n}__*.sql`），不修改已应用的迁移文件。`ddl-auto` 是 `validate`，实体与迁移不一致会启动失败。
- **状态与记账**：余额变更与复式记账分录必须在同一事务内；状态机更新使用 `REQUIRES_NEW` 独立事务。

## 提交信息

用 [Conventional Commits](https://www.conventionalcommits.org/)：

```
feat(ledger): 新增复式记账分录表
fix(dlq): 死信按来源路由，避免审计失败改写账本状态
test(arch): 断言 @KafkaListener 必须显式声明 containerFactory
docs(readme): 补充首次启动必须配置 .env
```

## 不要提交

`.env`、`backups/`、`logs/`、`build/`。这些都已在 `.gitignore` 中。
