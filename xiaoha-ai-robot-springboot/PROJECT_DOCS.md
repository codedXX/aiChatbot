# 小哈 AI 机器人 - 项目文档

## 1. 项目概述

**项目名称：** xiaoha-ai-robot-springboot（小哈 AI 机器人）  
**描述：** 基于 Spring Boot 构建的 AI 聊天机器人，集成 OpenAI 兼容接口，支持多轮对话记忆、网络搜索增强和消息持久化。

### 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 编程语言 |
| Spring Boot | 3.4.5 | 核心框架 |
| Spring AI | 1.0.0 | AI 集成框架 |
| PostgreSQL | - | 数据库 |
| MyBatis Plus | 3.5.12 | ORM 框架 |
| Log4j2 | - | 日志框架 |
| OkHttp | 4.12.0 | HTTP 客户端 |
| JSoup | 1.17.2 | HTML 解析 |

---

## 2. 项目结构

```
xiaoha-ai-robot-springboot/
├── src/main/
│   ├── java/com/quanxiaoha/ai/robot/
│   │   ├── advisor/                 # AI Advisor 实现（拦截/增强 AI 请求）
│   │   ├── aspect/                  # AOP 切面（接口日志）
│   │   ├── config/                  # Spring Bean 配置类
│   │   ├── constant/                # 常量定义
│   │   ├── controller/              # REST 控制器
│   │   ├── domain/
│   │   │   ├── dos/                 # 数据库实体类
│   │   │   └── mapper/              # MyBatis Mapper
│   │   ├── enums/                   # 枚举类型
│   │   ├── exception/               # 异常处理
│   │   ├── model/
│   │   │   ├── common/              # 通用模型（分页等）
│   │   │   ├── dto/                 # 数据传输对象
│   │   │   └── vo/chat/             # 视图对象（请求/响应）
│   │   ├── service/                 # 业务逻辑接口与实现
│   │   │   └── impl/
│   │   ├── utils/                   # 工具类
│   │   └── XiaohaAiRobotSpringbootApplication.java
│   └── resources/
│       ├── application.yml          # 主配置
│       ├── application-dev.yml      # 开发环境配置
│       ├── application-prod.yml     # 生产环境配置
│       ├── log4j2.xml               # 日志配置
│       └── spy.properties           # P6Spy SQL 日志配置
└── pom.xml
```

---

## 3. 配置说明

### application.yml（主配置）
- 服务端口：`8080`
- 激活环境：`dev`
- 日志配置：`classpath:log4j2.xml`

### application-dev.yml（开发环境）

**数据库（PostgreSQL + P6Spy）**
```yaml
url: jdbc:p6spy:postgresql://localhost:5432/robot
username: postgres
password: postgres
# HikariCP 连接池：最大 20 个连接，空闲 5 分钟
```

**AI 接口（阿里云 DashScope，兼容 OpenAI 格式）**
```yaml
base-url: https://dashscope.aliyuncs.com/compatible-mode
# 需要配置 API Key
```

**OkHttp 客户端**
```yaml
connect-timeout: 5000ms
read-timeout: 30000ms
write-timeout: 15000ms
max-idle-connections: 200
```

**SearXNG 搜索引擎**
```yaml
url: http://localhost:8888/search
result-count: 10   # 每次返回 10 条结果
```

---

## 4. 核心模块详解

### 4.1 Controller 层 — `ChatController`

所有聊天相关 REST API 入口：

| 接口 | 方法 | 说明 |
|------|------|------|
| `/chat/new` | POST | 创建新会话 |
| `/chat/completion` | POST | 流式 AI 响应（SSE） |
| `/chat/list` | POST | 分页查询会话列表 |
| `/chat/message/list` | POST | 分页查询会话消息 |
| `/chat/summary/rename` | POST | 重命名会话标题 |
| `/chat/delete` | POST | 删除会话及消息 |

**聊天请求参数（`AiChatReqVO`）：**
- `message` — 用户输入内容
- `chatId` — 会话 UUID
- `networkSearch` — 是否开启网络搜索
- `modelName` — 模型名称（运行时选择）
- `temperature` — 温度参数（控制创造性）

---

### 4.2 Advisor 层 — AI 请求增强

Advisor 是 Spring AI 提供的拦截器模式，可以在 AI 请求前后注入逻辑，多个 Advisor 按 `order` 顺序执行。

#### `NetworkSearchAdvisor`（网络搜索，order=1）

开启联网搜索时使用，流程：

```
用户提问
  → 调用 SearXNG 搜索
  → 并发抓取搜索结果页面内容（OkHttp + JSoup）
  → 拼接搜索上下文到 Prompt
  → 发送给 AI 模型
```

- 并发抓取使用 `httpRequestExecutor` 线程池
- 每个 URL 抓取超时 7 秒
- AI 被要求在回答中注明信息来源

#### `CustomChatMemoryAdvisor`（对话记忆，order=2）

不开启联网搜索时使用：
- 从数据库加载该会话最近 **50 条**消息
- 将历史消息转换为 `UserMessage` / `AssistantMessage` 对象
- 拼接到当前提问前，实现多轮对话上下文

#### `CustomStreamLoggerAndMessage2DBAdvisor`（持久化，order=99）

始终执行，负责：
- 将流式响应分块转发给客户端（SSE）
- 聚合完整响应内容
- 将用户消息和 AI 回复**持久化到数据库**
- 使用 `TransactionTemplate` 编程式事务保障原子性

---

### 4.3 Service 层

#### `ChatService` — 会话管理

| 方法 | 说明 |
|------|------|
| `newChat()` | 创建新会话，生成 UUID |
| `findChatHistoryPageList()` | 分页查询会话列表 |
| `findChatHistoryMessagePageList()` | 分页查询会话消息 |
| `renameChatSummary()` | 修改会话标题 |
| `deleteChat()` | 删除会话及关联消息（事务） |

#### `SearXNGService` — 网络搜索

- 调用本地部署的 SearXNG API
- 聚合 14+ 个搜索引擎的结果
- 按相关性评分排序，返回 Top 10

#### `SearchResultContentFetcherService` — 网页内容抓取

- 批量并发抓取网页（`httpRequestExecutor` 线程池）
- 使用 JSoup 将 HTML 转换为纯文本
- 超时兜底，失败时返回空内容

---

### 4.4 数据库设计

#### `t_chat`（会话表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 自增主键 |
| uuid | String | 会话唯一标识 |
| summary | String | 会话标题/摘要 |
| createTime | LocalDateTime | 创建时间 |
| updateTime | LocalDateTime | 更新时间 |

#### `t_chat_message`（消息表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long | 自增主键 |
| chatUuid | String | 关联的会话 UUID |
| content | String | 消息内容 |
| role | String | 角色（`user` / `assistant`） |
| createTime | LocalDateTime | 消息时间 |

---

### 4.5 Config 层 — 配置类

| 配置类 | 说明 |
|--------|------|
| `ChatClientConfig` | 初始化 Spring AI ChatClient |
| `CorsConfig` | 允许所有跨域请求 |
| `JacksonConfig` | JSON 序列化配置 |
| `MybatisPlusConfig` | MyBatis Plus 分页插件（PostgreSQL） |
| `OkHttpConfig` | OkHttp 连接池与超时设置 |
| `ThreadPoolConfig` | 两个线程池（IO 密集型 + CPU 密集型） |

**线程池策略：**
- `httpRequestExecutor`：核心 50 线程，最大 200 线程（用于并发 HTTP 请求）
- `resultProcessingExecutor`：CPU 核心数 × 2（用于 HTML 解析、JSON 处理）

---

### 4.6 通用工具

| 类 | 说明 |
|----|------|
| `Response<T>` | 统一 API 响应包装（code、message、data） |
| `PageResponse<T>` | 分页响应包装（含总数、当前页等） |
| `JsonUtil` | Jackson 封装工具 |
| `StringUtil` | 字符串截断等工具 |
| `ApiOperationLogAspect` | AOP 切面，记录所有接口调用、耗时、参数 |

---

## 5. 主要业务流程

### 5.1 发起聊天（联网搜索模式）

```
POST /chat/completion  (networkSearch=true)
       │
       ▼
  NetworkSearchAdvisor (order=1)
       │ 调用 SearXNG → 并发抓取网页内容
       │ 构建增强 Prompt（含搜索结果上下文）
       ▼
  OpenAI 兼容 API（DashScope）
       │ 流式返回
       ▼
  CustomStreamLoggerAndMessage2DBAdvisor (order=99)
       │ 分块转发给客户端（SSE）
       │ 聚合完整内容
       │ 持久化用户消息 + AI 回复到数据库
       ▼
  客户端收到流式响应
```

### 5.2 发起聊天（记忆模式）

```
POST /chat/completion  (networkSearch=false)
       │
       ▼
  CustomChatMemoryAdvisor (order=2)
       │ 从数据库加载最近 50 条消息
       │ 拼接到当前 Prompt 前
       ▼
  OpenAI 兼容 API（DashScope）
       │ 流式返回
       ▼
  CustomStreamLoggerAndMessage2DBAdvisor (order=99)
       │ （同上）
       ▼
  客户端收到流式响应
```

---

## 6. 错误码

| 错误码 | 说明 |
|--------|------|
| 10000 | 系统错误 |
| 10001 | 参数校验失败 |
| 20000 | 会话不存在 |

---

## 7. 关键文件索引

| 文件 | 路径 |
|------|------|
| 启动类 | [XiaohaAiRobotSpringbootApplication.java](src/main/java/com/quanxiaoha/ai/robot/XiaohaAiRobotSpringbootApplication.java) |
| 主控制器 | [ChatController.java](src/main/java/com/quanxiaoha/ai/robot/controller/ChatController.java) |
| 对话记忆 Advisor | [CustomChatMemoryAdvisor.java](src/main/java/com/quanxiaoha/ai/robot/advisor/CustomChatMemoryAdvisor.java) |
| 联网搜索 Advisor | [NetworkSearchAdvisor.java](src/main/java/com/quanxiaoha/ai/robot/advisor/NetworkSearchAdvisor.java) |
| 持久化 Advisor | [CustomStreamLoggerAndMessage2DBAdvisor.java](src/main/java/com/quanxiaoha/ai/robot/advisor/CustomStreamLoggerAndMessage2DBAdvisor.java) |
| 会话服务 | [ChatServiceImpl.java](src/main/java/com/quanxiaoha/ai/robot/service/impl/ChatServiceImpl.java) |
| 搜索服务 | [SearXNGServiceImpl.java](src/main/java/com/quanxiaoha/ai/robot/service/impl/SearXNGServiceImpl.java) |
| 网页抓取服务 | [SearchResultContentFetcherServiceImpl.java](src/main/java/com/quanxiaoha/ai/robot/service/impl/SearchResultContentFetcherServiceImpl.java) |
| 线程池配置 | [ThreadPoolConfig.java](src/main/java/com/quanxiaoha/ai/robot/config/ThreadPoolConfig.java) |
| 开发环境配置 | [application-dev.yml](src/main/resources/application-dev.yml) |
