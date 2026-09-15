# Java 后端：可靠的异步讲解任务

本轮基于 3bb6483，加强现有异步讲解与团队提问链路，不改动大模型问答的业务定位。

## 实现

1. **请求幂等**：`Idempotency-Key` 按用户隔离，映射到稳定任务ID；数据库主键阻止重复创建。请求内容另存SHA-256摘要，同键不同参数返回409。竞争插入失败在事务回滚后查询已提交任务，避免在失败事务中继续操作。
2. **事务 Outbox**：任务与待发送事件通过同一数据库事务插入。只有已提交事件才能被调度器读取，避免任务入库和发送消息之间的空档。
3. **可靠发送**：RabbitMQ correlated confirm、publisher returns、mandatory routing；只有ACK且没有returned消息才标记sent。无法确认时保留事件，带退避重新投递。
4. **消费幂等与租约**：数据库短事务领取任务，校验任务状态和generation；实际模型调用在事务外执行。executionToken与generation共同拦截过期工作进程结果。
5. **失败恢复**：执行失败使用新的generation与新事件，最多3次执行；发送最多10次尝试。租约过期可重新执行；确认发送但长时间没有领取的任务重新安排投递。
6. **取消与重驱**：取消清除执行凭证，迟到结果不能覆盖取消状态。仅所有者可重驱failed任务，创建新generation，旧消息无效。正在queued/running时重复重驱不创建新批次。
7. **数据清理**：Outbox以数据库外键关联任务，任务删除时级联清理，避免账号记录删除后留下待发送事件。

## 接口使用

- `POST /api/ai/explanations/async`：可选请求头 `Idempotency-Key`。
- `POST /api/teams/{teamId}/questions`：支持相同幂等请求头，仍先校验团队成员资格。
- `GET /api/ai/jobs/{jobId}`：查询任务状态。
- `DELETE /api/ai/jobs/{jobId}`：取消本人任务。
- `POST /api/ai/jobs/{jobId}/retry`：重驱本人失败任务。

幂等键为1至128位字母、数字或 `._:-`。同一逻辑提交的网络重试必须复用同一键；省略键会创建新任务。幂等记录随任务删除而删除，不提供删除后的永久去重。相同键不等于相同内容：重新发起独立业务操作应使用新键。

失败重驱接口只在排队/执行期间提供重复请求合并；任务再度失败后重新调用代表再次授权执行，不声称任意延迟重放都永久幂等。

## 状态与一致性

`queued -> running -> completed / failed / cancelled`。瞬时执行失败或租约过期可在额度内产生新generation并回到queued。模型配置缺失等不可重试情况直接failed。旧generation和失效executionToken不能提交结果。

所有需要同时锁定两张表的操作均按任务行、Outbox行顺序；网络调用和模型推理不持有数据库锁。单个任务的并发领取通过数据库实现，因此不限于单实例内存锁。

这是至少一次投递与受控结果提交，不是外部调用Exactly Once。模型已返回但应用未保存时发生进程崩溃，恢复后仍可能再次调用模型。取消也不能保证已发出的外部请求停止计费。客户端原有HTTP重试最多3次，因此3次任务执行可能对应更多HTTP请求。

## 配置

- `app.jobs.dispatch-enabled`：默认true；测试时可关闭调度器以精确验证状态转换。
- `app.jobs.poll-ms`：默认500毫秒，单批最多20条事件。
- `app.jobs.recovery-ms`：默认5000毫秒，单批最多50条任务。
- `app.jobs.max-attempts`：默认3次任务执行。
- `app.jobs.execution-lease-seconds`：默认180秒，需长于实际模型请求预算。本轮没有租约心跳续期。
- `app.jobs.max-publish-attempts`：默认10次投递尝试。

本地线程池与RabbitMQ共用同一任务状态和Outbox协议。本地模式需要文件数据库才能在应用重启后恢复，默认内存H2仅用于开发。

现有生产profile仍使用Hibernate schema update；本轮增加任务字段和Outbox表，未声称完成版本化数据库迁移。正式生产升级仍需备份、审查DDL并采用受控迁移，这是下一阶段工作。

## 测试证据

- Maven verify：83项测试全部通过，0失败、0错误、0跳过。其中本轮新增18项，覆盖事务回滚、并发提交与领取、取消、过期租约、晚到结果、重试上限、旧消息、发送确认与返回、级联清理。
- 真实MySQL 8、RabbitMQ 3、Redis 7容器和两个Java进程：13项检查全部通过。
- 已注入：移除消息路由、停止/重启RabbitMQ、在调用模型期间强制终止两个Java工作进程。恢复后任务完成。
- 模型仅为本机HTTP测试替身，没有使用真实模型、没有测试回答质量，也没有产生真实模型计费。
- 不是吞吐量压测，没有QPS或P95提升结论。

详细结果：`reliability-test-results.json`、`reliability-infrastructure-results.json`。

## 重现基础设施验证

需要Docker、Java17、Python3。确保脚本使用的测试端口空闲。

```sh
./mvnw verify
docker compose -p travelmate-reliability -f scripts/infra-test.yml up -d --wait
python3 scripts/reliability_audit.py
docker compose -p travelmate-reliability -f scripts/infra-test.yml down -v
```

脚本固定使用该隔离测试项目，会停止和重启其RabbitMQ，并终止自己启动的Java进程。不要把连接地址或项目名替换为真实业务环境。最后的down -v仅清理该项目的测试容器与数据。
