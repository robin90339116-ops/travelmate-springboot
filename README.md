# TravelMate Backend

面向 City Walk 的 Java AI 应用后端。Java 17 / Spring Boot 3.3.5，提供目录、AI讲解与问答、受约束路线规划、图片理解、异步任务、同游、账号与收藏接口。

本仓库是服务端，不包含可安装的手机客户端。原鸿蒙客户端采用不同接口及WebSocket协议，需要按本仓库契约适配。

## 本地启动

```sh
./mvnw verify
./mvnw spring-boot:run
```

默认端口8787，开发模式使用H2内存数据库，重启清空数据。需保留演示数据时：

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

`local` 使用 `./data/travelmate` 文件数据库。测试务必使用独立数据库，不要指向现有账号数据。

## 账号

- `POST /api/auth/register` 和 `/api/auth/password/login`：`{"phone":"13800000000","password":"至少12字符的密码","deviceName":"demo"}`。此处phone仅作为登录标识，注册不证明号码所有权。
- 开发专用：`POST /api/auth/login`，传phone、code（默认246810）、deviceName。生产模式禁用该入口，未集成正式短信发送。
- 返回统一结构：`{"code":0,"message":"ok","data":...}`，data含accessToken、refreshToken、sessionId。
- 私有接口使用 `Authorization: Bearer <accessToken>`。刷新令牌使用SHA-256摘要保存，数据库行锁保证同一个旧令牌仅能刷新一次。
- `POST /api/auth/refresh` 接收refreshToken；`POST /api/auth/logout` 可不传正文，撤销当前设备；`POST /api/auth/logout-all` 撤销全部设备。访问令牌每次请求均校验会话有效性。
- `GET /api/auth/sessions`、`DELETE /api/auth/sessions/{sessionId}` 管理设备。
- 密码采用SHA-256预摘要后BCrypt保存，不记录明文；与高熵刷新令牌采用不同存储方式。

## AI主流程

1. `GET /api/catalog/cities`，`GET /api/catalog/cities/{cityKey}/spots` 获取景点资料和数字字符串ID。
2. `POST /api/ai/explanations`：`{"spotId":"1","style":"story","routeContext":"..."}`。
3. `POST /api/ai/chat`：`{"spotId":"1","question":"这里有什么值得观察的？"}`。
4. `POST /api/routes/generate`：`{"cityKey":"beijing","durationHours":2,"interests":"建筑"}`。模型仅选候选景点，服务端验证ID、去重和非空；返回建议次序，不保证导航时长或开放时间。
5. `POST /api/ai/vision`：spotId、mode、mediaUrl（HTTPS地址或data:image）、question。外部模型须支持对应图片/视频格式；不包含摄像头采集或实时视频流。

AI配置通过进程环境变量传入：`DASHSCOPE_API_KEY`、`AI_BASE_URL`、`AI_TEXT_MODEL`、`AI_VISION_MODEL`、`AI_PROVIDER`。请求采用兼容chat/completions协议。更换供应商时必须同时设置URL、模型及凭证；更改provider名称本身不会自动切换服务。

连接超时5秒，读取超时30秒，429/5xx最多3次请求，其他错误不盲目重试。输入限长，AI写请求按账号限流；该限流为单实例实现，多实例需入口共享配额。

讲解携带景点资料与待核实状态，提示词要求基于资料回答。**提示词不是事实正确性的保证**，尚无真实模型幻觉率评测。种子景点一律标记为演示资料，不能当作已核实的开放时间。

## 异步任务

- `POST /api/ai/explanations/async` 提交，返回jobId和streamUrl。
- `GET /api/ai/jobs/{jobId}` 查询持久化状态；`GET /api/ai/jobs/{jobId}/stream` 接收SSE；`DELETE /api/ai/jobs/{jobId}` 取消。
- 状态：queued → running → completed / failed / cancelled。SSE的progress/result事件携带状态及结果；百分比不伪装为模型真实进度。
- 任务归属当前用户，其他账号返回404。SSE请求必须携带Authorization；浏览器原生EventSource不能直接自定义此请求头，请用支持流读取的fetch客户端。
- 任务保存在数据库；订阅实例轮询数据库，支持晚订阅和跨实例查询。工作进程失联的任务5分钟后标记失败，不静默重新调用收费接口。客户端可显式重新提交。
- 取消阻止保存完成结果，不保证已经发往供应商的请求停止计费。
- RabbitMQ失败任务保存失败状态并拒绝入死信队列；不宣称具备自动死信重放。消费者通过数据库原子claim避免重复执行已领取任务。

## 同游

`POST /api/teams`创建，`POST /api/teams/join`邀请码加入；房间详情、播放、踢人、转让和退出路径见控制器。非成员不能读取房间。队长退出自动交给最早加入的其他成员；最后一人退出删除房间。

STOMP连接 `/ws`，在CONNECT原生头中设置 `Authorization:Bearer ...`。订阅 `/topic/teams/{id}`，向 `/app/teams/{id}/playback` 发送播放消息。身份来自验证后的会话，客户端userId字段无授权作用。连接、发送、订阅及下发时检查身份/成员资格；事务提交后广播，避免失败事务产生假事件。

共享追问：`POST /api/teams/{id}/questions`传spotId、question；`GET`同路径返回团队问题及生成状态、答案；`DELETE /{jobId}`允许提问者取消。当前为并发异步任务列表，不承诺严格优先级队列或音频同步。

## 地图、语音与个人数据

- 高德搜索 `POST /api/map/search`：keyword、city。步行路线 `POST /api/map/route-validate`：origin、destination，格式经度,纬度。需要AMAP_WEB_KEY。
- NLS合成 `POST /api/tts`：text（1~300字），返回MP3 Base64、mimeType、sampleRate。
- NLS识别 `POST /api/asr`：audioBase64、format（pcm/wav），16kHz单声道，最多约60秒。需要ALIYUN_NLS_APP_KEY、ALIYUN_NLS_ACCESS_TOKEN。令牌需按供应商有效期更新，未实现自动获取/续期。
- 原始媒体不在本服务写盘；供应商留存策略需独立确认，不宣称第三方已删除。
- 收藏：`GET/POST /api/favorites`、`DELETE /api/favorites/{id}`。
- 数据：`GET /api/data/export`、`DELETE /api/data/records`、`DELETE /api/data/account`。注销清理本服务用户数据并撤销会话。

## 基础设施与部署边界

开发环境基础设施见docker-compose.yml。设置环境变量后以prod,mq,redis启动，生产必须提供32字节以上JWT_SECRET；固定开发验证码和H2控制台在prod禁用。不要直接将开发容器的默认密码用于公网部署。

`.env`不会被Spring Boot自动加载。应由终端、IDE运行配置或部署系统注入环境变量。

Redis使用带类型信息的受限序列化、每次写入独立TTL抖动。未实现分布式回源锁，不宣称sync=true保证跨实例防击穿。Redis属于运行依赖，生产应配健康检查与告警。

当前数据库使用Hibernate update便于原型迭代；正式承载用户前还应建立版本化迁移、备份恢复、TLS/网关、监控及密钥管理。这不是生产上线认证。

## 验证与求职展示

`./mvnw verify`运行JWT、业务权限、并发刷新、任务状态、Redis序列化及AI/语音HTTP契约测试。
AI/语音成功响应在测试中使用受控替身，不等同真实云服务联调；模型质量、费用和时延需要真实调用评测。

可展示：基于问题复现修复令牌轮换和越权，数据库持久化AI任务，结构化路线结果校验，鉴权实时通信，供应商异常契约测试，以及AI辅助开发后的人工验收过程。不要写未经测量的QPS、留存率或零幻觉承诺。

接口参考：
- 阿里云语音合成：https://help.aliyun.com/zh/isi/developer-reference/restful-api-3
- 阿里云短句识别：https://www.alibabacloud.com/help/en/isi/developer-reference/restful-api-2

## AI 旅行助手升级

新增多轮会话、工具调用、轻量RAG、路线校验和交互卡片。运行后访问 `/assistant/index.html`。完整接口、评测方法和验证边界见 [升级说明](docs/AI_ASSISTANT.md)。

## Java 后端可靠任务升级

异步讲解和团队提问新增请求幂等、事务Outbox、消息确认、执行租约与失败重驱。83项自动化测试和13项真实基础设施检查通过。使用方式与边界见 [可靠任务说明](docs/RELIABLE_GUIDE_JOBS.md)。
