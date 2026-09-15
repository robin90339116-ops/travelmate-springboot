# TravelMate AI 应用研发升级

基线：a23713b。新增模块位于 `com.travelmate.assistant`，原文字、视觉、实时音视频及异步任务接口继续保留。原 `/api/routes/generate` 返回新增 `validation` 字段，候选路线会经过同一时间校验。

## 功能与岗位对应

| 岗位要求 | 实现 | 验证范围 |
|---|---|---|
| 对话、上下文 | 用户隔离的会话表；最近6轮且最多8000字符；独立保存城市、时间、兴趣、同行人和路线；乐观锁防并发覆盖 | 连续追问、预算更新、跨用户隔离、并发版本冲突 |
| Prompt、Tool Calling | 版本化Prompt；兼容工具调用的模型客户端；7个只读或会话内工具；参数白名单；最多8次执行 | 模拟模型契约、错误工具、参数校验、调用上限 |
| RAG、信息处理 | 文档清洗、500字符分段/80字符重叠、内容摘要版本ID；中文双字与英文词的TF-IDF检索；来源引用校验 | 跨城市隔离、无匹配、来源状态、引用ID拦截 |
| 生成式UI | 模型调用决定路线/对比数据，服务端校验后生成固定组件；答案卡、来源、路线卡、对比卡 | 本机浏览器生成、替换站点和对比操作 |
| 评测、Badcase | 36条场景样例、批量执行、自动规则、JSON/Markdown报告、版本对比 | 规则单元测试与本机模拟链路；真实模型质量待测 |
| 工程交付 | 鉴权复用、会话/调用追踪、历史数据导出删除、错误降级、原项目回归 | 完整JUnit与打包、前端语法、浏览器检查 |

## 运行

需要 Java 17 和 Maven（或项目 Maven Wrapper）。

```sh
./mvnw verify
java -jar target/travelmate-backend-1.0.0.jar --spring.profiles.active=local
```

打开 `http://localhost:8787/assistant/index.html`。注册或登录后选择城市和条件，开启新对话。`local` 使用文件H2，默认无profile使用内存H2，重启会清空。

真实模型沿用环境变量 `DASHSCOPE_API_KEY`、`AI_TEXT_MODEL`，地图使用 `AMAP_WEB_KEY`。密钥只在服务端配置，不放在网页或提交到Git。模型必须支持兼容接口的 `tools/tool_calls`。未配置时返回可解释的降级结果，不冒充AI成功。

## 核心接口

所有 `/api/ai/assistant/**` 接口要求 Bearer Token，错误沿用统一响应包装。

- `POST /api/ai/assistant/sessions`：`{cityKey,durationMinutes,interests,companions}`。
- `GET /api/ai/assistant/sessions`：本人会话列表。
- `GET /api/ai/assistant/sessions/{id}`：恢复历史、条件、最近结果。
- `POST /api/ai/assistant/sessions/{id}/messages`：`{message,durationMinutes?,interests?,companions?}`。省略的条件保留原值。
- `DELETE /api/ai/assistant/sessions/{id}`：删除本人会话与对应追踪。
- `GET /api/ai/assistant/traces/{id}`：本人调用步骤、模型/Prompt版本、耗时及失败原因。

城市在会话内固定，跨城市请创建新会话。并发修改返回409，调用者应重新读取后重试。历史采用完整轮次窗口，不宣称已实现模型摘要或精确token计数。

## 工具

`search_spots`、`get_spot_details`、`get_walking_route`、`search_knowledge`、`update_constraints`、`plan_route`、`compare_spots`。

工具由服务端注册，参数类型/长度/字段白名单校验。最多8次执行，模型请求间检查120秒和32000字符预算；进行中的HTTP请求仍受客户端超时限制，因此120秒不是硬中断时限。工具结果进入当前请求上下文，最终保存旅行条件及保留路线，下一轮不无限重放工具历史。

## 路线校验的边界

- 校验城市、合法ID、去重、1至6个站点。
- 停留时间仅解析明确分钟值；步行时间使用高德路径结果。
- 可计算的超预算站点被移除；没有可保留地点时为 `infeasible`。
- 地图未配置或资料缺失时，`totalMinutes=null`、`timingStatus=unverified`，不会把未知时间记作0。
- `within_budget` 仅表示所用资料下的时间算术通过，不等于现实行程已确认；不包括出发地/返程/排队，开放时间、儿童设施与通行条件仍需独立核实。

## 知识资料

默认读取 `classpath*:knowledge/*.json`；可用 `app.assistant.knowledge-location=file:/absolute/path/*.json` 配置受控文档目录。每个文件是Document数组：

```json
[{"id":"document-id","cityKey":"beijing","title":"资料标题","text":"正文","sourceUrl":"https://example.org/source","updatedAt":"2026-09-15","sourceStatus":"pending"}]
```

上面URL只是格式示例。上线前应替换为实际核对过的来源。内置3份资料均为演示数据、`pending`，没有伪造官方来源。检索为小语料词法方案，未引入向量模型或重排器；不宣称语义检索准确率。引用ID存在性由程序校验，引用是否真正支持答案还需评测与人工复核。

## 评测

```sh
python3 -m unittest discover -s eval -p 'test_*.py'
# 在环境中设置专用测试账号的 TRAVELMATE_EVAL_TOKEN 后执行：
python3 eval/run_eval.py --base-url http://127.0.0.1:8787 --output eval/results/current.json
# 比较两个版本：
python3 eval/run_eval.py --baseline eval/results/current.json --output eval/results/next.json
```

真实执行会使用服务端模型配置并可能产生费用。脚本会建立隔离会话，结束后删除，仅输出测试结果；每条保留人工作业状态。自动检查覆盖结构、工具执行、约束、引用有无和不确定性关键词，不将自动通过率称为回答准确率。异常样例列入Badcase；真实事实正确性、引用支持关系、亲子适宜性等需人工复核。

`eval/fixture_provider.py` 是本机确定性协议测试工具，不是模型。用它运行得到的报告标为 `fixture_endpoint_not_model_quality`，仅检验调用和报告链路。

## 仍需完成的外部验收

1. 使用真实模型跑36条评测并人工检查事实、引用、连续追问与工具选择。
2. 使用真实高德配置验证实际路线、时长与不可用情形。
3. 替换/扩充演示资料，核对来源和更新时间。
4. 生产部署前处理数据库迁移、共享配额和监控；本轮未部署到公网。
5. 本轮未扩展模型文本逐字SSE；现有SSE仍用于异步任务进度，实时音视频能力沿用原实现。
