# 兼容性与实现范围

记录日期：2026-11-13，Asia/Shanghai。

| 项目 | 锁定值 | 验证方式 |
| --- | --- | --- |
| Halo 插件 API / Platform | 2.27.0 | 实际依赖编译与测试 |
| `spec.requires` | `>=2.27.0` | 本次仅验证 2.27.0，不声明其他版本已通过 |
| Java / 字节码 | 21 | 本机构建 JDK 21.0.7，目标容器 Java 21 |
| Gradle Wrapper / DevTools | 9.4.0 / 0.9.0 | 项目构建、临时容器加载、OpenAPI 生成 |
| Console 依赖 | Halo UI 2.27.0，Vue 3，TypeScript 6 | 锁文件、类型检查与 ESM 打包 |
| UI 包管理器 | pnpm 10.33.0 | `packageManager` 与锁文件 |
| 微信官方源码 | `0ad1ae91347a938f52f1e4e2abd6dcda51e05f9e` | 固定快照复核及本地 HTTP 模拟 |
| 微信声明版本 | `3.0.0-beta-0` | 独立于插件版本 1.0.0-SNAPSHOT |
| 微信 App ID / ClientVersion / bot_type | `bot` / `196608` / `3` | 按固定上游编码并检验请求 |

保留现有 Java 包 `com.xhhao.clawbotnotify`，资源组为 `clawbot.xhhao.com`，Console 组为 `console.api.clawbot.xhhao.com`。发布前仍应核对插件名称及域名归属。

## 本次实现

- `channel`：固定 HTTPS 目的地、扫码与 PIN、文本轮询/发送、业务响应校验、无损大整数 ID、超时与取消。`WechatReceiver` 持久化并恢复待处理批次，只接收绑定主人完成的私聊文本，更新会话上下文并持久化读取与引用回复指令。
- `credential`：`CredentialStore` 接口，独立 Halo Secret 存储，AES-256-GCM，随机 nonce，Secret 名作为 AAD，外置主密钥。
- `binding`：单站点单绑定、显式启用、Halo 身份绑定、代次隔离与工作器生命周期。二维码会话仅在内存中保留，本地有效期 5 分钟；重启后重新扫码。
- `halo`：使用安装的 Halo 自身外部访问地址，通过 PAT 核验用户身份、评论读取与回复授权，并通过 Halo `RoleService` 检查账号当前超级管理员身份；后台回复同步另外核验回复作者当前具有超级管理员或评论管理角色；不接受客户端传入目标地址，禁止跟随重定向。
- `comment` / `command`：评论与楼中楼资源控制器、轮换分页补查、独立随机短编号与处理状态、删除墓碑、读取指令、原生引用精确定位、Markdown 回复、审核确认、忽略/标记处理、入站去重及指令记录查询。
- `delivery`：评论通知、指令回执和连接测试任务，持久化客户端 ID 与状态，缺上下文等待，取消及崩溃恢复保留未知结果，不自动重发。
- `api` 与 Console：带 OpenAPI 描述的受保护接口、脱敏状态、扫码/PIN/PAT、启用/断开、分页发送记录（消息类型与短编号），以及分页筛选的指令记录、正文详情与评论管理核查入口。

注册 `WechatBinding`、`OutboundTask`、`CommentLink`、`InboundCommand` Scheme，建立实际查询所需的代次、任务状态和事件键索引，使用资源版本控制更新。未增加向普通用户或匿名用户放权的 RoleTemplate。Halo 2.27.0 实际测试中，自动 CRUD 列表拒绝普通用户及匿名访问，详见联调记录。

## 接收与发送恢复策略

一次有效轮询的 `pendingBatch` 和新游标在**同一个加密 Secret 更新**中落盘。再次轮询前先处理旧批次；批次内每条授权指令先创建或取得 `InboundCommand`，再创建幂等发送任务，最后标记 QUEUED，全部成功后才移除批次。任一存储失败保留批次并暂停接收，重启后复用记录和任务，不重复执行。

当前使用单个加密 Secret 作为待处理 `InboundBatch` / 接收游标的存储形式，未拆成独立资源。`siteInstanceId` 首次生成后保存在专用普通 ConfigMap；命令名由站点、绑定、代次、Bot 和原样消息 ID 的 SHA-256 生成。指令记录保存枚举、页码、评论引用与执行状态，正文单独加密存入 Halo Secret；uint64 ID 不经过浮点数。

评论事件键与发送任务名包含来源 Comment 或 Reply、类型和绑定代次，重复观察取得既有任务。编号使用随机 6 位字符，创建前检查碰撞，作用域唯一索引约束并发写入；创建冲突后查回已有映射或重试。评论、楼中楼和各自映射分别每 10 秒处理一页最多 100 条；启动后从第一页核对，循环覆盖历史窗口和删除情况。发送前重新通过 PAT 获取源评论，已删除或不再符合范围的通知取消。

PAT 的 `/users/-` 响应列出的是令牌被授予的角色，不能当作账号的全部角色。账号管理员资格使用目标 2.27.0 的 `RoleService.getRolesByUsername` 验证；评论内容始终通过 PAT 请求 Core API，不以内部资源读取绕过 PAT 权限。标题缺权限时仅显示占位。内置 `role-template-view-comments` 已满足读取权限，无需新增 RoleTemplate。

发送工作器先以资源版本更新任务为 `SENDING` 再发 HTTP 请求，终态写入失败时暂停派发。重启或停用时将遗留 `SENDING` 转为 `UNKNOWN`。`UNKNOWN` 不会自动重发，也未提供盲重试接口。明确业务拒绝的任务保留为 `WAITING_CONTEXT`，记录本次上下文版本；只有更新的主人上下文才允许再次尝试。缺权限的评论通知保持 PENDING，每 30 秒重新检查，不将失败当作源评论删除。

当前采用单实例工作器，不支持多个 Halo 实例并发消费。

## 引用回复与内容格式

引用只在当前绑定代次、当前微信主人、已接收或结果未知的发送任务中匹配。优先使用服务器消息 ID；发送响应省略 ID 时，仅完整引用文本的唯一指纹可以定位。列表、摘要、伪造编号和关联到多条评论的相同正文不触发写入；相同正文对应多份回执时也不能选中某份待审核草稿。同一父评论下的不同楼中楼属于不同目标，正文指纹不能混用。旧消息不回填猜测关联。

引用回复先在 Secret 保存 Markdown 正文，再落盘入站指令；完成身份与源评论读取后，以资源版本将写状态从 READY 改为 EXECUTING，才调用 Halo 回复接口。成功保存回复名后补记原草稿与待处理状态，补记失败只重做补记。EXECUTING 在恢复时转为 UNKNOWN，不自动重发 HTTP。审核确认使用原草稿的版本占用确认权，重复确认不能再发布同一份草稿。

普通回复遇到未审核原评论或目标楼中楼时保存为 AWAITING_APPROVAL，不调用写接口。用户引用确认回执发送“审核并回复”后才调用 Console 回复接口。Halo 2.27 的 `ReplyServiceImpl` 在当前用户有管理权限时自动审核父评论及 `quoteReply` 引用的楼中楼并继承隐藏状态；确认后遵循该接口语义。预检查与发布之间的并发审核状态变更不具备原子条件更新能力。

Markdown 使用 CommonMark 0.21.0 生成 HTML，转义原始 HTML、过滤危险链接，再经 Jsoup 白名单清理；不接收任意 HTML 或图片。微信通知由源评论 HTML 提取兼容 Markdown，微信端的排版能力由客户端决定，不能声明完整富文本支持。

## 后台处理同步与记录管理

`ReplyProcessingReconciler` 响应回复资源变化，`ReplyProcessingSynchronizer` 每 10 秒轮换一页最多 100 条回复，补查当前绑定通知窗口内的遗漏。资源事件仅提供回复 ID 和父评论线索；更新处理状态前，通过绑定 PAT 读取当前父评论和对应回复，并检查回复已审核、未删除、作者是有效 User，且当前仍具有超级管理员或评论管理角色。更换绑定或停用后不继续更新；仅 OPEN 转为 ANSWERED，手动 IGNORED 和删除墓碑保留。后续删除管理员回复不会自动重开待处理。后台回复只更新 `quoteReply` 对应的楼中楼；没有引用时更新顶层评论，其他楼中楼状态保留。

微信引用“忽略”和“标记已处理”使用独立的本地状态执行器，只在身份、权限、来源和有效期检查通过后更新插件状态，重复消息幂等；不修改 Halo 评论。存储失败保留入站批次，恢复后继续处理。

Console 新增分页、类型/结果筛选的 `/commands` 及 `/commands/{name}` 接口。OpenAPI 生成的客户端为唯一 UI 契约来源。列表只包含状态、时间和资源引用，网站执行状态与微信回执分别查询。详情正文只对当前绑定用户开放；跨代次记录不解密正文，也不使用新凭证读取旧目标。原评论、目标楼中楼和网站回复内容通过当前 PAT 按需分别读取，UI 使用转义文本，不执行 HTML。历史结果不因源回复后续删除、内容读取失败或微信回执失败被改写。

## 已知限制与后续边界

1. 真实扫码绑定和刚互动后的手机收发已完成一次联调；必要 PIN 路径及 2/24/48 小时主动推送尚未验证。M0 退出条件尚未达到。
2. 只允许 `https://ilinkai.weixin.qq.com`。上游返回其他 IDC 主机时会拒绝并展示错误；必须取得并核实实际主机后精确加入允许列表。HTTP 3xx 不自动跟随。
3. 实现上游“绑定本人”路径，未实现替他人绑定的实名授权/票据流程。
4. 支持 Post / SinglePage 的顶层 Comment 和访客 Reply 通知，Reply 使用独立编号与状态。楼中楼创建时间须在绑定通知窗口内，父评论可早于窗口；绑定本人及当前有效管理员回复不触发访客通知。微信发布成功或后台有效管理员回复只将对应目标标记 ANSWERED。已新增智能助手设置；未实现通知器订阅和评论通知规则；AI 预览与自动历史清理见后续更新。
5. 已核验 Halo 2.27.0 Core 评论 GET 与 Console 回复 API；写入须使用“评论管理”权限。当前“查看”最多 3,000 字，回复最多 2,000 字，待处理最多 10 条/页。原生引用的手机验证结果单独记录，不能由本地模拟推断。
6. 不能依据接口接收成功证明手机送达或已读；会话上下文不设置未经证实的固定失效时间。未知业务码不推断为凭证过期。
7. Secret 内加密数据格式当前为 `encrypted-v1`。主密钥丢失时无法恢复旧凭证，需重新绑定；当前未实现在线密钥轮换。
8. Halo 插件子容器不继承宿主 `application.yaml` 的配置属性。主密钥通过 `CLAWBOT_MASTER_KEY` 进程环境变量或 `-Dclawbot.master-key` JVM 系统属性提供。

## 官方依据

- [微信 API 客户端](https://github.com/Tencent/openclaw-weixin/blob/0ad1ae91347a938f52f1e4e2abd6dcda51e05f9e/src/api/api.ts)
- [微信扫码流程](https://github.com/Tencent/openclaw-weixin/blob/0ad1ae91347a938f52f1e4e2abd6dcda51e05f9e/src/auth/login-qr.ts)
- [微信发送文本](https://github.com/Tencent/openclaw-weixin/blob/0ad1ae91347a938f52f1e4e2abd6dcda51e05f9e/src/messaging/send.ts)
- [Halo 2.27 API 变更](https://docs.halo.run/developer-guide/plugin/api-changelog.md)
- [Halo 凭证与出站请求](https://docs.halo.run/developer-guide/plugin/security/outbound-http.md)
- [Halo 自定义模型](https://docs.halo.run/developer-guide/plugin/api-reference/server/extension.md)
- [Halo 自定义 API](https://docs.halo.run/developer-guide/plugin/api-reference/server/custom-endpoints.md)

- [Halo 2.27 评论与内置权限](https://github.com/halo-dev/halo/blob/v2.27.0/application/src/main/resources/extensions/role-template-comment.yaml)
- [Halo 2.27 插件控制器管理](https://github.com/halo-dev/halo/blob/v2.27.0/application/src/main/java/run/halo/app/plugin/PluginControllerManager.java)

- [微信引用消息字段](https://github.com/Tencent/openclaw-weixin/blob/0ad1ae91347a938f52f1e4e2abd6dcda51e05f9e/src/api/types.ts)
- [微信 Markdown 过滤规则](https://github.com/Tencent/openclaw-weixin/blob/0ad1ae91347a938f52f1e4e2abd6dcda51e05f9e/src/messaging/markdown-filter.ts)
- [Halo 2.27 回复服务与审核语义](https://github.com/halo-dev/halo/blob/v2.27.0/application/src/main/java/run/halo/app/content/comment/ReplyServiceImpl.java)

- [Halo 2.27 评论 HTML 校验白名单](https://github.com/halo-dev/halo/blob/v2.27.0/application/src/main/java/run/halo/app/content/comment/AbstractCommentService.java)

## AI Foundation 与 CLI（2026-11-16）

新增 AI Foundation >=1.0.0 必需依赖，编译 SDK 1.0.0 由基座插件提供。通过 Halo ExtensionGetter 发现模型服务，不跨插件直接注入 Spring Bean。普通配置使用 Setting YAML；基座复用模型密钥。CLI 使用参数数组、专用 profile、固定工具和地址检查，业务请求使用 CLI profile 的权限。对话、AI 回执及创建账本在加密 Secret；单消息单次创建占用、固定资源名、状态恢复不重发。配置、行为与实机验证边界见 [AI 助手说明](ai-assistant.md)。

## AI 预览与运行管理（2026-11-17）

站点写工具变为加密预览，确认步骤独立执行并占用一次性凭据。新增 `AI_CONFIRM`、`AI_APPROVE`、`AI_CANCEL` 指令；引用 AI 回执通过当前绑定已持久化消息关联，普通列表和模糊摘要仍不能选中评论。已有草稿修改/发布通过固定 CLI 参数完成；读取资源与完整正文指纹后，确认时重新检查。CLI 不提供跨读取与写入的原子条件，仍需避免同时编辑同一草稿。单份预览和可修改/发布的原草稿正文限制 2,000 字。

新增仅超级管理员可读的 `/assistant/runtime`，模型检查只解析可用语言模型，不调用生成接口。命令 DTO 增加模型、时间、耗时、清理标记。原生 Setting 增加预览有效期与历史保留天数；后台维护订阅由插件生命周期启动/停止，跨代次清理闲置对话，确定终态历史隐藏并清理加密正文，保留命令/回执精简记录防重复。结果未知或仍在使用的正文保留供核查。
