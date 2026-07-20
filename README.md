# AutoTradingSystem · Futu 股票监听与 AI 趋势分析系统

> 基于 Java 17 + Spring Boot 3.3.6 的本地化股票监控服务。对接 Futu OpenD，实时订阅行情、计算 MA 均线、扫描交易信号、监控日内波动，并内置多 LLM 大盘趋势分析与 Web 控制台。事件触发邮件告警，支持 Docker 一键部署。

一个进程里跑完「行情接入 → 指标计算 → 信号扫描 → 告警分发 → Web UI → AI 分析」全链路，无需外部数据库与消息中间件，适合个人投资者在自己的机器上长期常驻。

---

## 目录

1. [功能特性](#功能特性)
2. [系统架构](#系统架构)
3. [技术栈](#技术栈)
4. [项目结构](#项目结构)
5. [快速开始](#快速开始)
6. [配置说明](#配置说明)
7. [Docker 部署](#docker-部署)
8. [Web 控制台](#web-控制台)
9. [REST API](#rest-api)
10. [告警机制](#告警机制)
11. [AI 趋势分析](#ai-趋势分析)
12. [定时任务](#定时任务)
13. [健康检查与日志](#健康检查与日志)
14. [测试](#测试)
15. [常见问题](#常见问题)

---

## 功能特性

### 行情与指标
- 对接 Futu OpenD（OpenAPI SDK `9.3.5308`），拉取账户分组并订阅实时报价
- 实时计算 MA5 / MA13 / MA30 / MA55 多周期均线
- 支持 **美股 / 港股 / A 股** 三个市场，美股覆盖盘前、盘中、盘后、夜盘四时段
- K 线按 TTL 缓存（默认 6 小时），趋势分析前强制失效重取，兼顾性能与新鲜度

### 交易信号
- **均线突破/跌破**：价格上穿/下穿均线即时触发
- **均线交叉**：金叉 / 死叉检测（带频率去抖）
- **日内波动**：多时间窗口 × 阈值的规则引擎，窗口之间支持 `OR` / `AND` 组合逻辑
- **综合交易信号扫描**：聚合均线、波动、形态等维度生成买卖点

### 告警
- 邮件告警（SSL/STARTTLS 可选），异步线程池发送，SMTP 慢不阻塞监控主线程
- 每个告警类型独立冷却期 + 噪声过滤窗口，防止盘中刷屏
- 告警可在 Web 界面一键开关，波动规则、均线告警配置均可在前端动态调整

### AI 趋势分析
- **多供应商 LLM**：DeepSeek / 智谱 GLM / Kimi，任意 OpenAI 兼容 Chat Completions 接口
- 前端下拉切换模型，定时任务供应商与前端默认解耦
- **大盘右侧趋势分析**：定时拉取 US / HK / CN 分组全部标的的 K 线，批量喂给 LLM 输出结构化判断
- **图片加自选股**：上传持仓截图，多模态视觉模型 OCR 出股票代码写入分组

### 运维
- Web 控制台：实时状态、自选股管理、信号历史、趋势分析、错误日志、配置面板
- 内置 H2 文件数据库持久化（告警、信号、分析记录），`ddl-auto` 自动建表
- Actuator 健康检查，断线指数退避自动重连，无内存泄漏
- 完整日志 + 错误日志页（`ErrorLogAppender` 把 ERROR 实时采进库）

---

## 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                        Futu OpenD                           │
│                  (本地常驻，OpenAPI 网关)                    │
└───────────────┬─────────────────────────────┬───────────────┘
                │ 订阅报价 / 拉取K线 / 分组    │
                ▼                             │
┌──────────────────────────────┐              │
│      futu / market 层        │              │
│  QuoteSubscription           │              │
│  SnapshotPolling  KLine      │              │
│  MarketSession               │              │
└──────────────┬───────────────┘              │
               │ 收盘价 / K线                  │
               ▼                              │
┌──────────────────────────────┐    ┌─────────┴──────────┐
│        monitor 层            │    │    indicator 层     │
│  MACrossoverMonitor          │◄──►│  MA Calculator      │
│  MABreakdownScanner          │    └────────────────────┘
│  MARuleEngine                │
│  TimeWindowFluctuation       │    ┌────────────────────┐
│  TradingSignalScanner        │───►│   外部 LLM / 视觉   │
│  AlertCoordinator            │    │ LlmAnalysisClient  │
│  AlertNoiseFilter            │    │ VisionOcrClient    │
└──────────────┬───────────────┘    └────────────────────┘
               │ 告警事件
               ▼
┌──────────────────────────────┐    ┌────────────────────┐
│     notification 层          │───►│   repository/entity │
│  NotificationTemplate        │    │  H2 文件库持久化     │
│  (异步 emailExecutor)        │    └────────────────────┘
└──────────────────────────────┘
               ▲
               │ REST / 页面
┌──────────────┴───────────────┐
│          web 层 (Spring MVC)  │
│  index / stock / right-trend  │
│  / error-logs 控制台          │
└───────────────────────────────┘
```

数据流：OpenD 行情 → 指标计算 → 信号扫描 → 噪声过滤 → 告警协调 → 异步邮件 + 持久化；AI 分析链路（前端触发或定时任务）→ K 线聚合 → LLM 调用 → 结构化落库 → 邮件 + 页面展示。

---

## 技术栈

| 类别 | 选型 |
|------|------|
| 语言 / 运行时 | Java 17 |
| 框架 | Spring Boot 3.3.6（Web MVC + Actuator + Scheduling + Async）|
| 调度 | Spring `@Scheduled`（定时趋势分析任务）|
| 持久化 | Spring Data JPA + H2 文件库（`MODE=MySQL`，`AUTO_SERVER`）|
| 邮件 | Spring Boot Mail（JavaMail）|
| 行情 SDK | `com.futunn.openapi:futu-api:9.3.5308` |
| AI | 任意 OpenAI 兼容 Chat Completions 端点（DeepSeek / GLM / Kimi）+ 多模态视觉 |
| 构建 | Maven 3.9.x |
| 部署 | Docker（`eclipse-temurin:17-jre`）+ docker-compose |
| 时区 | Asia/Shanghai |

> 说明：项目根 `package.json` / `.loop/` / `scripts/loop-*.sh` 等是 Loop 工程编排工具链，与 Java 运行时无关，部署时不需要 Node。

---

## 项目结构

```
AutoTradingSystem/
├── src/main/java/com/autotrading/
│   ├── AutoTradingApplication.java   # 启动类（@EnableScheduling / @EnableAsync）
│   ├── startup/                      # 启动后接线 + 报价分发
│   │   ├── ApplicationStartupRunner.java
│   │   └── QuoteProcessor.java
│   ├── futu/                         # OpenD 连接、重连、分组、K线拉取
│   ├── market/                       # 行情订阅、快照轮询、市场时段、分析服务
│   │   ├── QuoteSubscriptionService / SnapshotPollingService / KLineService
│   │   ├── MarketSessionService      # 美股四时段 / 港 A 市场状态
│   │   ├── RightTrendAnalysisService # 大盘趋势 LLM 分析主流程
│   │   ├── LlmAnalysisClient         # OpenAI 兼容多供应商客户端
│   │   ├── VisionOcrClient           # 多模态图片 OCR
│   │   ├── RiskAssessmentService     # 风险评估
│   │   ├── TradingSignalService      # 交易信号聚合
│   │   └── StockAnalysisService / ExternalAnalysisService
│   ├── monitor/                      # 监控与告警核心
│   │   ├── MACrossoverMonitor        # 金叉死叉
│   │   ├── MABreakdownScanner        # 突破/跌破
│   │   ├── MARuleEngine              # 均线规则引擎
│   │   ├── TimeWindowFluctuationMonitor / FluctuationAlertScheduler
│   │   ├── TradingSignalScanner      # 综合信号扫描
│   │   ├── AlertCoordinator          # 告警协调（去重、冷却）
│   │   ├── AlertNoiseFilter          # 噪声过滤窗口
│   │   ├── RightTrendScheduler       # 定时趋势分析任务
│   │   └── ErrorLogAppender          # ERROR → 库
│   ├── indicator/                    # MA 计算等技术指标
│   ├── web/                          # REST Controller（7 个）
│   ├── entity/ + repository/         # JPA 实体与仓储（8 张表）
│   ├── config/                       # AiProvider / Futu / Notification / RightTrend 配置
│   ├── notification/                 # 邮件模板 + 发送
│   ├── model/                        # 值对象
│   └── account/                      # 账户/分组
├── src/main/resources/
│   ├── application.yml               # 全部配置（env 占位）
│   └── static/                       # 前端页面（无框架，原生 HTML/JS）
│       ├── index.html                # 主控台（实时状态 + 自选股 + 信号）
│       ├── right-trend.html          # AI 趋势分析页
│       ├── stock.html                # 个股详情（K线 + 分析）
│       └── error-logs.html           # 错误日志
├── src/test/java/                    # 26 个测试（见下）
├── Dockerfile / docker-compose.yml   # 容器化
├── .env.example                      # 环境变量模板
├── docs/                             # PRD / DESIGN / ARCHITECTURE / specs
└── pom.xml
```

---

## 快速开始

### 前置要求

- JDK 17+
- Maven 3.9+
- **Futu OpenD 已在本机常驻运行**（默认 `127.0.0.1:11111`，且需登录富途账户）
- 一组 SMTP 邮箱凭据（Gmail / 126 / 企业邮箱等）
- （可选）一个 OpenAI 兼容的 LLM API Key，用于 AI 趋势分析与图片加自选股

### 本地运行

```bash
# 1. 克隆
git clone git@github.com:TigerPengPeng/TraddingAssistant.git
cd AutoTradingSystem

# 2. 准备配置（复制模板，填入 OpenD / SMTP / AI Key）
cp .env.example .env
$EDITOR .env

# 3. 编译
mvn compile

# 4. （可选）跑测试
mvn test

# 5. 启动（读取 .env 里的变量；或直接 export）
export $(grep -v '^#' .env | xargs) && mvn spring-boot:run
```

启动后访问：<http://localhost:8080/>

健康检查：<http://localhost:8080/actuator/health>

---

## 配置说明

所有运行参数都通过环境变量注入，`application.yml` 里用 `${VAR:默认值}` 占位。分六个配置组。

### 1. Futu OpenD 与分组

| 变量 | 默认 | 说明 |
|------|------|------|
| `OPEND_IP` | `127.0.0.1` | OpenD 地址（容器内用 `host.docker.internal`）|
| `OPEND_PORT` | `11111` | OpenD 端口 |
| `OPEND_ENCRYPT` | `false` | 是否启用加密连接 |
| `OPEND_RSA_KEY` | （空）| 加密连接所需的 RSA Key |
| `FUTU_GROUP_NAME` | （空）| 监控分组名；留空则取账户第一个分组 |

> 容器部署时，OpenD 必须监听 `0.0.0.0`（而非 `127.0.0.1`），否则容器经 `host.docker.internal` 连不到。

### 2. 监控参数

| 变量 | 默认 | 说明 |
|------|------|------|
| `PRICE_CHANGE_THRESHOLD` | `2.0` | 单次价格波动告警阈值（%）|
| `ALERT_COOLDOWN_MINUTES` | `15` | 告警冷却期（分钟）|
| `KLINE_REFRESH_INTERVAL` | `60000` | K 线刷新间隔（毫秒）|
| `MARKET_STATE_POLL_INTERVAL` | `30000` | 市场状态轮询间隔（毫秒）|
| `SNAPSHOT_POLL_INTERVAL` | `10000` | 快照轮询间隔（毫秒）|
| `MA_NOISE_MINUTES` | （空）| 均线类告警噪声窗口（分钟）|
| `FLUCTUATION_NOISE_MINUTES` | （空）| 波动类告警噪声窗口 |
| `SIGNAL_NOISE_MINUTES` | （空）| 信号类告警噪声窗口 |
| `BREAKDOWN_NOISE_MINUTES` | （空）| 跌破类告警噪声窗口 |
| `FLUCTUATION_LOGIC` | `OR` | 多窗口波动规则组合逻辑（`OR`/`AND`）|
| `FLUCTUATION_EVAL_INTERVAL` | `30000` | 波动评估周期（毫秒）|

均线周期在 `application.yml` 的 `futu.monitor.ma-periods: [5, 13, 30, 55]` 配置。波动规则默认两条：`3分钟/±3%` 与 `5分钟/±5%`。

### 3. 重连

| 变量 | 默认 | 说明 |
|------|------|------|
| `RECONNECT_INITIAL_DELAY_MS` | `5000` | 断线重连初始延迟 |
| `RECONNECT_MAX_DELAY_MS` | `60000` | 重连最大延迟 |
| `RECONNECT_MULTIPLIER` | `2.0` | 指数退避倍数 |

### 4. 邮件

| 变量 | 默认 | 说明 |
|------|------|------|
| `MAIL_ENABLED` | `true` | 是否启用邮件通知 |
| `MAIL_HOST` / `MAIL_PORT` | （空）/`465` | SMTP 服务器与端口 |
| `MAIL_SSL` / `MAIL_STARTTLS` | `true`/`false` | 加密方式 |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | （空）| SMTP 登录凭据 |
| `MAIL_FROM` / `MAIL_TO` | （空）| 发件人 / 收件人（逗号分隔多个）|

### 5. AI 供应商（趋势分析 + 图片 OCR）

系统支持任意 OpenAI 兼容的 Chat Completions 端点。每个供应商独立 label / key / url / model / 超时 / 限流。

| 变量 | 默认 | 说明 |
|------|------|------|
| `AI_DEFAULT_PROVIDER` | `deepseek` | 默认/前端默认选中供应商 |
| `AI_DEEPSEEK_*` | — | DeepSeek：label/key/api-url/model/json-mode/timeout |
| `AI_KIMI_*` | — | Kimi（`kimi-k3`，超时 180s）|
| `AI_GLM_*` | — | 智谱 GLM（`glm-4.6`）|
| `DEEPSEEK_*` | — | 旧变量，`AI_DEEPSEEK_*` 缺失时自动回退 |
| `AI_VISION_*` | — | 多模态视觉（图片加自选股），独立于上面文本模型 |
| `RIGHT_TREND_SCHEDULED_PROVIDER` | `deepseek` | 定时任务专用供应商（与前端默认解耦）|

> 只有填了 `api-key` 的供应商才会在前端模型下拉里出现。没有 LLM Key 也能正常跑监控与告警，仅 AI 分析功能不可用。

### 6. 趋势分析分组与缓存

| 变量 | 默认 | 说明 |
|------|------|------|
| `RIGHT_TREND_GROUP_US/HK/CN` | `US`/`HK`/`CN` | 三市场对应的 Futu 分组名 |
| `RIGHT_TREND_GROUP_ADD` | `pool` | 手动加入的「自选池」分组 |
| `KLINE_CACHE_TTL_MINUTES` | `360` | K 线缓存 TTL（0 = 不过期）|

---

## Docker 部署

镜像期望先在本地打好 JAR（`Dockerfile` 只拷贝 `target/*.jar`）。

```bash
# 1. 配置
cp .env.example .env
$EDITOR .env        # 至少填 OPEND_IP / SMTP / AI Key

# 2. 构建 JAR（跳过测试加快）
mvn clean package -DskipTests

# 3. 构建镜像并启动
docker compose up -d --build

# 4. 看日志 / 健康
docker compose logs -f
curl http://localhost:8080/actuator/health
```

容器配置要点：

- 时区 `Asia/Shanghai`，JVM 堆 `-Xms256m -Xmx768m`，G1GC
- 端口 `8080:8080`
- 挂载 `./logs` 与 `./data`（H2 文件库 `data/autotrading` 持久化）
- 内置 healthcheck：`wget /actuator/health`，30s 一次
- `OPEND_IP` 默认 `host.docker.internal`，故 OpenD 跑在宿主机即可被容器访问

---

## Web 控制台

启动后浏览器访问 <http://localhost:8080/>（容器同理，换主机 IP）。页面均为原生 HTML/JS，无前端构建步骤。

| 页面 | 路径 | 功能 |
|------|------|------|
| 主控台 | `/` (`index.html`) | 实时持仓状态、价格、信号；自选股管理；图片加自选股；邮件开关；波动/均线告警配置 |
| 个股详情 | `/stock.html` | K 线图、均线、单股 AI 分析、信号与历史 |
| AI 趋势分析 | `/right-trend.html` | 分组（美股/港股/A股/自选池）选择、模型切换、一键分析与历史报告 |
| 错误日志 | `/error-logs.html` | 运行期 ERROR 实时落库后的查询与清理 |

---

## REST API

主要 HTTP 端点（均挂在 `:8080`）：

**状态与刷新**
- `GET  /api/status` — 监控状态总览
- `POST /api/refresh-prices` — 手动刷新价格
- `POST /api/refresh-stocks` — 重新拉取分组股票

**个股**
- `GET  /api/stock/{market}/{code}/kline` — K 线数据
- `GET  /api/stock/{market}/{code}/analysis` — 历史分析
- `GET  /api/stock/{market}/{code}/analysis-history`
- `GET  /api/stock/{market}/{code}/signals` — 信号历史
- `POST /api/stock/{market}/{code}/analyze-ai` — 触发单股 AI 分析
- `GET  /api/stock/strategies` — 可用策略

**自选股（图片 OCR）**
- `POST /api/add-stocks/ocr` — 上传图片，OCR 出股票代码
- `GET  /api/add-stocks/groups` — 可写入分组列表
- `POST /api/add-stocks` — 把代码写入指定分组

**AI 趋势分析**
- `GET  /api/right-trend/models` — 已配置的可用供应商
- `POST /api/right-trend/analyze` — 触发分组趋势分析（可选 `provider`）
- `GET  /api/right-trend/latest`
- `GET  /api/right-trend/history`
- `GET  /api/right-trend/{date}` — 按日期取报告

**配置**
- `GET/POST /api/email-toggle` — 邮件开关
- `GET/POST /api/fluctuation-config` — 波动规则
- `GET/POST /api/ma-alert-config` — 均线告警配置
- `POST /api/ma-scan` — 手动均线扫描

**运维**
- `GET  /api/error-logs` / `DELETE /api/error-logs` — 错误日志查询与清理
- `GET  /actuator/health` — 健康检查（含明细）

---

## 告警机制

告警链路：**信号源 → 噪声过滤（`AlertNoiseFilter`）→ 协调去重与冷却（`AlertCoordinator`）→ 异步邮件（`emailExecutor`）+ 落库**。

- **冷却期**：同一标的 + 同一告警类型在 `ALERT_COOLDOWN_MINUTES` 内只发一次
- **噪声窗口**：每类信号（均线/波动/信号/跌破）可单独设 `*_NOISE_MINUTES`，窗口内重复信号被吞掉
- **异步发送**：邮件走独立线程池（core 2 / max 4 / queue 50），SMTP 卡住不影响行情处理
- **可前端开关**：邮件总开关、波动规则、均线告警均能在主控台实时改，无需重启

---

## AI 趋势分析

「右侧趋势分析」面向大盘/分组批量判断当前是否处于可操作的右侧（趋势确立）阶段。

- **数据**：拉取分组内全部标的近 60 根 K 线，聚合成统一 prompt
- **模型**：DeepSeek / 智谱 GLM / Kimi 任选，前端下拉即时切换；定时任务用 `RIGHT_TREND_SCHEDULED_PROVIDER`
- **输出**：结构化 JSON（趋势方向、强度、风险、建议），落库后页面可查历史与按日期回看
- **兼容性**：对不支持 `response_format=json` 的模型自动省略该字段并剥离代码围栏；Kimi `kimi-k3` 不传 temperature（仅接受 1）

图片加自选股走独立的 `VisionOcrClient`（多模态视觉模型，与文本趋势模型分离），上传截图 → 识别代码 → 选分组写入。

详细设计见 [docs/specs/multi-llm-trend-analysis.md](docs/specs/multi-llm-trend-analysis.md)。

---

## 定时任务

由 `RightTrendScheduler` 驱动，时区 `Asia/Shanghai`，仅工作日：

| Cron | 触发对象 | 说明 |
|------|----------|------|
| `0 0 9 * * MON-FRI` | 美股分组 | 美股盘前/开盘前出右侧趋势判断 |
| `0 0 17 * * MON-FRI` | 港股 + A 股分组 | 收盘后复盘 |

定时任务使用 `RIGHT_TREND_SCHEDULED_PROVIDER` 指定的供应商，与前端默认供应商互不影响。

---

## 健康检查与日志

- **健康检查**：`GET /actuator/health`（`show-details: always`）。邮件健康指标已关闭——SMTP 不通不应把聚合健康拉成 503（邮件是异步通知通道，非核心可用性）。
- **日志**：输出到 `./logs/`，`com.autotrading=INFO`、`com.futu.openapi=WARN`。
- **错误日志页**：`ErrorLogAppender` 在启动时注册，把运行期 `ERROR` 实时写进 H2，供 `/error-logs.html` 查询。

---

## 测试

`src/test/java/` 下 26 个测试，覆盖核心监控与 AI 链路：

```bash
mvn test                                  # 全量
mvn -Dtest=MACrossoverMonitorTest test    # 单测
```

代表性测试：`MACrossoverMonitorTest`、`CrossoverDetectorTest`、`TimeWindowFluctuationMonitorTest`、`FluctuationAlertSchedulerTest`、`TradingSignalScannerTest`、`LlmAnalysisClientTest`、`VisionOcrClientTest`、`AlertCoordinatorTest`、`AlertNoiseFilterTest`、`ReconnectLifecycleTest`、`MarketSessionServiceTest`、`RightTrendAnalysisServiceTest`、`RightTrendSchedulerTest`。

---

## 常见问题

- **连不上 OpenD**：确认 OpenD 进程在跑、监听 `0.0.0.0:11111`（容器部署时尤其重要）、账户已登录。容器里把 `OPEND_IP` 设成 `host.docker.internal`。
- **邮件不发**：先看 `/actuator/health` 与 `./logs/`；确认 `MAIL_ENABLED=true`、SSL/STARTTLS 与端口匹配（126 用 465+SSL）、`MAIL_FROM` 与 `MAIL_USERNAME` 一致。前端主控台也可临时关邮件。
- **AI 分析报错**：前端模型下拉只显示配了 Key 的供应商；没 Key 则跑监控无碍但 AI 不可用。Kimi 用 `kimi-k3`、智谱用 `glm-4.6`（model id 以官方为准）。
- **数据存哪**：H2 文件库 `./data/autotrading.*`，`ddl-auto=update` 自动建表，直接删文件即重置。
- **告警刷屏/漏报**：调 `ALERT_COOLDOWN_MINUTES` 与对应 `*_NOISE_MINUTES`；波动规则组合逻辑看 `FLUCTUATION_LOGIC`。

---

## 开发命令速查

```bash
mvn compile                 # 编译
mvn test                    # 测试
mvn package -DskipTests     # 打包（产出 target/futu-stock-monitor-*.jar）
mvn spring-boot:run         # 本地运行
docker compose up -d --build # 容器化部署
```

## 相关文档

- [docs/PRD.md](docs/PRD.md) — 产品需求
- [docs/DESIGN.md](docs/DESIGN.md) — 设计说明
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 架构
- [docs/specs/multi-llm-trend-analysis.md](docs/specs/multi-llm-trend-analysis.md) — 多 LLM 趋势分析设计

---

> 本项目仅供个人投资辅助与学习，不构成任何投资建议。
