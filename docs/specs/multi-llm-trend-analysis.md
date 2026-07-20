# 右侧趋势分析：多模型切换（DeepSeek / GLM / Kimi）

> 来源：用户需求 `$spec`（2026-07-20）。状态：**draft，待用户确认 Phase 4**。
> 归档：本项目 git origin 指向 `DevLoopEngineerTemplate`（脚手架模板仓库），无项目自身 GitHub 仓库，因此本 spec 以本地文档归档，不调用 `gh issue create`。
> 字段：spec_filed_at=2026-07-20 · spec_branch=main · spec_plan_mode=inactive · spec_executed=false

## Context

「右侧趋势分析」板块（`src/main/resources/static/right-trend.html`）通过后端 `POST /api/right-trend/analyze` 对自选股分组逐只调用大模型，判断是否进入右侧趋势。当前模型硬编码为单一供应商：后端 `DeepSeekProperties`（`@ConfigurationProperties(prefix = "deepseek")`）只绑定一组 `api.key / api.url / api.model`，`.env` 里只有 `DEEPSEEK_*` 三个变量，前端没有任何模型选择入口。

用户希望：

1. **前端**：趋势分析页提供下拉框，可在 DeepSeek / 智谱 GLM / Kimi 之间切换本次分析所用模型。
2. **配置**：`.env` 能友好地配置多个大模型；当前已有 DeepSeek，新增 GLM（用户称「glm5.2」）和 Kimi（用户称「k3」）。

**关键有利事实**：DeepSeek、智谱 GLM、Kimi（Moonshot）三家都提供 **OpenAI 兼容的 Chat Completions 接口**，现有 `DeepSeekClient` 构造的请求体（`model / messages / response_format=json_object / temperature`）可直接复用，差异仅在 `url / key / model` 三项。因此本次改动是「单一供应商 → 多供应商注册表 + 每次调用可指定供应商」，而非重写模型调用层。

## Current State（已读代码核实）

| 文件 | 现状 |
|------|------|
| `.env` | 仅 `DEEPSEEK_API_KEY / DEEPSEEK_API_URL / DEEPSEEK_MODEL` 三项（model=`deepseek-chat`） |
| `.env.example` | **完全没有** DeepSeek / AI 相关配置段（遗漏） |
| `src/main/resources/application.yml` | `deepseek.api.{key,url,model,timeout-ms,rate-limit-ms}` 绑定 `${DEEPSEEK_*}` |
| `config/DeepSeekProperties.java` | `@ConfigurationProperties(prefix="deepseek")`，单一 `Api` 嵌套对象，全应用单例 |
| `market/DeepSeekClient.java` | `analyzeRightTrend(...)` 内部直接读 `properties.getApi().getModel()/getUrl()/getKey()`；请求体含 `response_format: json_object`、`temperature:0.1`；解析 `choices[0].message.content` 为 JSON |
| `market/RightTrendAnalysisService.java` | 对分组内每只股票循环调用 `deepSeekClient.analyzeRightTrend(...)`，调用间用 `rateLimitMs` 限速；`RightTrendReport` record 不含 provider 字段 |
| `web/RightTrendController.java` | `POST /api/right-trend/analyze?groups=&sendEmail=`，**无 model/provider 参数**；另有 `GET /latest /history /{date}` |
| `monitor/RightTrendScheduler.java` | `@Scheduled` 09:00（美股）/ 17:00（港股+沪深）定时任务，走默认模型 |
| `static/right-trend.html` | 顶部有「分组」下拉 + 「发邮件」勾选 + 「立即分析」按钮 + 「历史报告」下拉；**无模型下拉**；`analyze()` 硬编码提示「正在调用 DeepSeek」 |
| `test/.../DeepSeekClientTest.java` | mock RestTemplate，验证 JSON 解析与错误处理（4 用例） |
| `test/.../RightTrendAnalysisServiceTest.java` | mock 全链路，验证编排（4 用例） |

## Proposed Change

引入**供应商注册表（Provider Registry）**模式：把「单一 DeepSeek 配置」升级为「N 个 OpenAI 兼容供应商配置 + 一个默认供应商」，前端下拉框从注册表动态拉取已配置（有 API key）的供应商，每次分析按用户选择路由到对应 `url/key/model`。

向后兼容：旧的 `DEEPSEEK_API_*` 仍可直接工作（作为 `deepseek` 供应商的回退来源），不强制改写现有 `.env`。

### 1. `.env` 配置（友好、扁平、自解释）

新增统一的 `AI_*` 命名空间，每个供应商一组，外加一个默认值开关。可直接粘贴进当前打开的 `.env`：

```dotenv
# ============ 大模型供应商（OpenAI 兼容 Chat Completions）============
# 默认供应商 id（定时任务与前端默认选中项）；从 deepseek / glm / kimi 中选一个
AI_DEFAULT_PROVIDER=deepseek

# --- DeepSeek（已配置，保持不变即可；AI_DEEPSEEK_* 优先于旧 DEEPSEEK_*）---
AI_DEEPSEEK_LABEL=DeepSeek
AI_DEEPSEEK_API_KEY=<填入你的 DeepSeek API Key>
AI_DEEPSEEK_API_URL=https://api.deepseek.com/v1/chat/completions
AI_DEEPSEEK_MODEL=deepseek-chat

# --- 智谱 GLM（bigmodel）---
# 注意：model id 以智谱官方文档为准；用户口述「glm5.2」，公开稳定版为 glm-4.6，
#       实际取值见下方「Open Questions / Assumptions」Q1，落地前需用户确认。
AI_GLM_LABEL=智谱GLM
AI_GLM_API_KEY=<填入你的智谱 API Key>
AI_GLM_API_URL=https://open.bigmodel.cn/api/paas/v4/chat/completions
AI_GLM_MODEL=glm-4.6

# --- Kimi（Moonshot）---
# 注意：model id 以 Moonshot 官方文档为准；用户口述「k3」，见 Q2 确认。
AI_KIMI_LABEL=Kimi
AI_KIMI_API_KEY=<填入你的 Moonshot API Key>
AI_KIMI_API_URL=https://api.moonshot.cn/v1/chat/completions
AI_KIMI_MODEL=moonshot-v1-auto
```

规则：
- 只有 `*_API_KEY` 非空的供应商才视为「已配置」，出现在前端下拉与 `/models` 接口中。
- `AI_DEFAULT_PROVIDER` 未设置或指向未配置的供应商时，回退到第一个已配置的供应商。
- **向后兼容**：若 `AI_DEEPSEEK_*` 缺失但旧 `DEEPSEEK_API_KEY/URL/MODEL` 存在，自动映射为 `deepseek` 供应商（保证现有部署零改动）。

`.env.example` 同步补齐上述整段（含占位值），消除「AI 段缺失」的遗漏。

### 2. 后端改动

#### 2.1 新增 `config/AiProviderProperties.java`（替换/扩展 `DeepSeekProperties`）

```java
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AiProviderProperties {
    private String defaultProvider = "deepseek";
    private Map<String, Provider> providers = new LinkedHashMap<>(); // key: deepseek/glm/kimi

    public static class Provider {
        private String label;     // 展示名：DeepSeek / 智谱GLM / Kimi
        private String apiKey;
        private String apiUrl;
        private String model;
        private boolean jsonMode = true;   // 是否发送 response_format=json_object
        private int timeoutMs = 60000;
        private long rateLimitMs = 500;
        // getters/setters ...
    }
    // getters/setters + getConfiguredProviders()（过滤 apiKey 非空）
}
```

`application.yml`：
```yaml
ai:
  default-provider: ${AI_DEFAULT_PROVIDER:deepseek}
  providers:
    deepseek:
      label: ${AI_DEEPSEEK_LABEL:DeepSeek}
      api-key: ${AI_DEEPSEEK_API_KEY:${DEEPSEEK_API_KEY:}}
      api-url: ${AI_DEEPSEEK_API_URL:${DEEPSEEK_API_URL:https://api.deepseek.com/v1/chat/completions}}
      model: ${AI_DEEPSEEK_MODEL:${DEEPSEEK_MODEL:deepseek-chat}}
      json-mode: ${AI_DEEPSEEK_JSON_MODE:true}
    glm:
      label: ${AI_GLM_LABEL:智谱GLM}
      api-key: ${AI_GLM_API_KEY:}
      api-url: ${AI_GLM_API_URL:https://open.bigmodel.cn/api/paas/v4/chat/completions}
      model: ${AI_GLM_MODEL:glm-4.6}
      json-mode: ${AI_GLM_JSON_MODE:true}
    kimi:
      label: ${AI_KIMI_LABEL:Kimi}
      api-key: ${AI_KIMI_API_KEY:}
      api-url: ${AI_KIMI_API_URL:https://api.moonshot.cn/v1/chat/completions}
      model: ${AI_KIMI_MODEL:moonshot-v1-auto}
      json-mode: ${AI_KIMI_JSON_MODE:true}   # 见 Q3：Kimi 若不支持需置 false
```

> 旧 `deepseek.*` 段保留但标注 `@Deprecated`，内部委托给 `ai.providers.deepseek`；建议迁移完成后删除，避免两套配置并存（属本次范围）。

#### 2.2 `DeepSeekClient` → `LlmAnalysisClient`（重命名，语义已变）

- 类名建议改为 `LlmAnalysisClient`（原 `DeepSeekClient` 名字会误导）。`analyzeRightTrend(...)` 新增参数 `AiProviderProperties.Provider provider`（或 provider id），用其 `apiUrl/apiKey/model/jsonMode` 构造请求。
- `jsonMode=false` 时**不发送** `response_format` 字段（兼容不支持该参数的供应商，见 Q3），并强化 `parseResponse`：先剥除 ```` ```json ```` 代码围栏、再截取首个 `{` 到末个 `}` 的子串后解析，提升对纯文本返回的鲁棒性。
- 现有 `response_format=json_object` + `temperature=0.1` + system prompt 不变。
- `RestTemplate` 仍按 provider 的 `timeoutMs` 构造（可改为共享单例 + 每请求超时，但 MVP 不必）。

#### 2.3 `RightTrendAnalysisService` 透传 provider

- `analyzeGroups(List<String> groupNames)` → `analyzeGroups(List<String> groupNames, String providerId)`。
- `analyzeSingleStock(...)` 把解析出的 `Provider` 传给 `LlmAnalysisClient`。
- 限速改用所选 provider 的 `rateLimitMs`。
- `RightTrendReport` record 新增 `String providerId` 与 `String providerLabel` 字段，供前端展示「本次由 X 模型分析」。
- `RightTrendAnalysisRecord` 实体新增 `provider` 列（`ddl-auto: update` 会自动加列，无需手写迁移；属 H2 文件库，安全）。

#### 2.4 `RightTrendController`

- `POST /api/right-trend/analyze` 新增可选 `@RequestParam(required=false) String provider`，缺省走 `AI_DEFAULT_PROVIDER`；若传入未配置的 id，返回 `400` + `{status:"error", message:"未配置的模型: <id>"}`。
- 新增 `GET /api/right-trend/models`：
  ```json
  { "default": "deepseek",
    "models": [ {"id":"deepseek","label":"DeepSeek","default":true},
                 {"id":"glm","label":"智谱GLM","default":false},
                 {"id":"kimi","label":"Kimi","default":false} ] }
  ```
  仅返回已配置（key 非空）的供应商。

#### 2.5 `RightTrendScheduler`

定时任务使用 `ai.default-provider`，无需前端参数。`runAnalysis(groupNames, sendEmail)` 签名增加 `String providerId`（controller 透传；scheduler 内部传默认）。

### 3. 前端改动（`right-trend.html`）

- 顶部在「分组」下拉左侧新增「模型」下拉 `<select id="model-select">`，页面加载时 `fetch('/api/right-trend/models')` 动态填充 `<option>`，默认选中 `data.default`。
- `analyze()` 把所选 `provider` 拼进请求：`/api/right-trend/analyze?groups=...&sendEmail=...&provider=...`。
- 加载中文案从硬编码「正在调用 DeepSeek」改为「正在调用「{选中模型 label}」逐只分析…」。
- `renderReport` 的 summary-box 在分组/日期行旁展示 `report.providerLabel`（如「DeepSeek · 港股+沪深 · 2026-07-20」）。
- 历史报告下拉项也可附加当时所用 provider label（取决于是否在 `RightTrendReport` 持久化；MVP 可仅在 latest/实时报告显示）。

## Acceptance Criteria

1. `.env` 中按上述 `AI_*` 段配置三家供应商后，应用正常启动，`GET /api/right-trend/models` 返回全部三家（均 key 非空）。
2. 只配置 DeepSeek（保留旧 `DEEPSEEK_*`、不写 `AI_*`）时，应用仍正常工作，`/models` 返回 `[{deepseek}]`，证明向后兼容。
3. 前端「模型」下拉默认选中 `AI_DEFAULT_PROVIDER`；切换到 GLM 后点「立即分析」，后端日志与 `RightTrendReport.providerLabel` 显示「智谱GLM」，且实际请求打到 `open.bigmodel.cn`。
4. `AI_GLM_API_KEY` 留空时，GLM **不出现在**下拉与 `/models`；选不到、也调不到。
5. 传入未配置的 `provider=foo` 时，`/analyze` 返回 HTTP 400 与清晰错误信息，不抛 500。
6. 选 Kimi 且其 `json-mode=false` 时，请求体不含 `response_format` 字段；对返回的纯文本/带 ```json 围栏内容仍能正确解析（测试覆盖）。
7. 定时任务（09:00 / 17:00）使用 `AI_DEFAULT_PROVIDER` 指定的模型，行为与原来一致。
8. 既有 `DeepSeekClientTest` / `RightTrendAnalysisServiceTest` 全部通过（适配新签名后）。
9. `right-trend.html` 在桌面与窄屏下「模型 / 分组 / 发邮件 / 立即分析 / 历史」一行不溢出换行错乱（沿用现有 `.btn`/`select` 样式即可）。

## Testing Plan

| 层 | 内容 | 数量 |
|----|------|------|
| Unit | `AiProviderProperties`：3 家已配置→返回 3；仅旧 `DEEPSEEK_*`→返回 1（deepseek 回退）；default 指向未配置 id→回退首个已配置 | +3 |
| Unit | `LlmAnalysisClient`：捕获 HttpEntity，验证 model/url/bearer 随传入 provider 变化；`jsonMode=false` 时 body 无 `response_format`；带 ```json 围栏的 content 能解析 | +3 |
| Unit | `RightTrendAnalysisService`：`analyzeGroups(groups, "glm")` 把 glm provider 透传给 client（verify mock 参数）；report.providerLabel 正确 | +1（扩展现有测试） |
| Unit(Web) | `RightTrendController`：`/models` 过滤未配置项；`/analyze?provider=glm` 路由正确；`?provider=foo` 返回 400 | +3 |
| 集成 | 启动后 `curl /api/right-trend/models` 与 `curl -XPOST .../analyze?provider=glm&groups=watch`（可用 mock server 或 `@RestClientTest`） | +1 |

## Rollback Plan

- 全部改动在 `config`、`market`、`web`、`monitor`、`static` 范围内，H2 用 `ddl-auto=update` 自动加列，回滚 = `git revert` 该 commit + 删除新增列（或直接重用，列存在不影响旧代码）。
- `.env` 回滚 = 删除新增 `AI_*` 段，旧 `DEEPSEEK_*` 仍生效。

## Effort Estimate

约 1 人日：配置/Properties 2h + Client 重命名+jsonMode 1.5h + Service/Controller 透传 2h + 前端下拉 1.5h + 测试 2h + 文档/`.env.example` 1h。

## Files Reference

| 文件 | 改动 |
|------|------|
| `.env` | 新增 `AI_*` 多供应商段（见上） |
| `.env.example` | 补齐整段 AI 配置占位 |
| `src/main/resources/application.yml` | 新增 `ai:` 段；`deepseek:` 段标 deprecated |
| `config/AiProviderProperties.java` | **新增** |
| `config/DeepSeekProperties.java` | 标 `@Deprecated`，委托 `ai.providers.deepseek`（或删） |
| `market/DeepSeekClient.java` → `LlmAnalysisClient.java` | **重命名**；按传入 provider 构造请求；`jsonMode` 开关；强化 JSON 解析 |
| `market/RightTrendAnalysisService.java` | `analyzeGroups` 加 `providerId`；透传 client；report 加 provider 字段 |
| `entity/RightTrendAnalysisRecord.java` | 新增 `provider` 列 |
| `web/RightTrendController.java` | `/analyze` 加 `provider` 参数；新增 `GET /models` |
| `monitor/RightTrendScheduler.java` | 用默认 provider |
| `static/right-trend.html` | 新增模型下拉 + 动态填充 + 文案/展示适配 |
| 测试 | 见 Testing Plan |

## Out of Scope

- 不做同一供应商内的多模型切换（如 DeepSeek 的 `deepseek-reasoner`）。每供应商一个 model，留作后续。
- 不持久化「每只股票历史上用不同模型的分析对比」视图（DB 只加 `provider` 列做标记，不做对比 UI）。
- 不接入非 OpenAI 兼容协议的模型（如需 Anthropic/Claude 原生协议，另开 spec）。
- 不改定时任务的时间/分组逻辑。
- 不重构 `RestTemplate` 为 WebClient / HTTP 接口池。

## Open Questions / Assumptions（落地前需用户确认）

1. **GLM model id**：用户口述「glm5.2」。智谱（bigmodel）当前公开稳定版为 `glm-4.6` / `glm-4-plus`；是否存在 `glm-5.2` 待用户确认。spec 默认填 `glm-4.6` 作为占位，请提供实际可用的 model id 字符串。
2. **Kimi model id**：用户口述「k3」。Moonshot 官方 model id 形如 `moonshot-v1-8k/32k/128k`、`kimi-k2-...`；「k3」是否指某个具体 id 待确认。spec 默认填 `moonshot-v1-auto` 占位。
3. **Kimi 的 `response_format: json_object` 支持**：Moonshot 早期版本对该参数支持不稳定。spec 已设计 `json-mode` 开关 + 围栏剥离兜底，但需在拿到 Kimi key 后实测一次确认默认值。
4. **API Key**：GLM 与 Kimi 的 key 需用户提供并填入 `.env`（spec 仅留占位）。
5. **类重命名**：`DeepSeekClient → LlmAnalysisClient` 属语义性重命名，影响 import 与测试类名。若你希望最小改动、保留旧名，可改为「保留 `DeepSeekClient` 类名 + 注释说明其为多供应商客户端」，spec 按推荐（重命名）写。

## Related

- `docs/PRD.md` v1.3（MA 事件聚合）——本特性是右侧趋势分析的独立增强，不在 v1.3 范围内，建议作为 v1.4 单独冻结。
