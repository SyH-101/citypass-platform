# CityPass

CityPass 是一个城市活动发现与限量名额预约平台。它面向展览、独立演出、运动场馆、研学营地和工作坊等场景，解决两个实际问题：热门活动瞬时请求会压垮数据库；用户取消或超时未支付后，空出的名额如果不能及时流转，会造成活动方的容量浪费。

项目把“找场馆、看动态、订阅创作者”和“限量预约、候补、自动补位”放在同一业务背景下。重点不是接口数量，而是让库存、订单、候补和 Redis 占位在消息重复、服务重启、支付与关单并发时仍能收敛。

## 核心能力

- 城市场馆：分类、关键词、坐标距离查询，OpenResty + Caffeine + Redis + MySQL 多级读取。
- 城市动态：发布、热榜、点赞排行、滚动 Feed、创作者订阅、评论与作者信息聚合。
- 用户体系：短信验证码登录、一次性验证码、Redis Token、滑动续期、签到位图。
- 限量预约：OpenResty 总量令牌桶、用户滑动窗口、RocketMQ 削峰、Redis Lua 原子占位、MySQL 条件扣减。
- 预约候补：MySQL 持久化 FIFO 队列、主动退出、取消或超时后的自动补位、资格支付截止时间。
- 可靠性：有效订单唯一索引、支付/关单 CAS、本地可靠任务、Redis 幂等补偿、定时对账。
- 缓存一致性：事务提交后驱逐本地与 Redis 缓存，可选 Canal 捕获绕过应用的数据库更新。

## 系统结构

```mermaid
flowchart LR
    Client[客户端] --> Gateway[OpenResty]
    Gateway --> App[Spring Boot]
    Gateway --> L0[OpenResty 场馆缓存]
    App --> L1[Caffeine]
    App --> Redis[(Redis)]
    App --> MQ[RocketMQ]
    MQ --> App
    App --> DB[(MySQL)]
    DB -. 可选 binlog .-> Canal[Canal]
    Canal --> MQ
```

预约写链路采用一种明确的生产路径：

```text
POST /reservations/{passId}
  -> 写入 PROCESSING 请求状态
  -> RocketMQ CREATE 消息
  -> 消费者校验活动时间窗与有效订单
  -> Redis Lua 原子占位
  -> MySQL 事务：条件扣库存 + 插入订单 + 写超时可靠任务
  -> RESERVED

Redis 无库存且 acceptWaitlist=true
  -> MySQL WAITING 候补
  -> WAITLISTED
```

取消或超时关单时，事务先用 `status=1` 条件更新竞争订单终态。若存在候补者，按入口生成的单调 `request_id` 选出队首，原名额直接转给候补订单，数据库库存不变；候补为空或活动结束时才回补数据库库存。Redis 名额归属转移或库存回补由同一事务写入的可靠任务异步执行，可幂等重试。

## 关键状态

| 对象 | 状态 | 含义 |
|---|---|---|
| 请求 | `PROCESSING` | 已受理，等待异步消费 |
| 请求 | `RESERVED` | 已获得限时支付资格 |
| 请求 | `WAITLISTED` | 库存不足，已进入候补 |
| 请求 | `FAIL_NOT_STARTED / FAIL_ENDED` | 不在活动预约时间窗 |
| 订单 | `1 / 2 / 4` | 未支付 / 已支付 / 已取消 |
| 候补 | `WAITING` | 正在排队 |
| 候补 | `OFFERED` | 已补位，等待支付 |
| 候补 | `ACCEPTED` | 补位订单已支付 |
| 候补 | `EXPIRED / CANCELLED` | 资格超时或主动退出 |

必须始终成立的业务不变量：

1. 同一用户对同一通行证最多存在一个有效订单或一个活动候补。
2. 数据库库存不会小于 0。
3. 名额补位只是所有权转移，不增加或减少库存。
4. 支付与关单只能有一方通过 CAS 把未支付订单推进到终态。
5. MySQL 订单和候补是最终依据，Redis 是可重建的快速状态。

## 快速启动

需要 Docker Desktop。仓库根目录执行：

```powershell
docker compose up -d --build
docker compose ps
```

服务地址：

- OpenResty 网关：`http://localhost:8080`
- Spring Boot：`http://localhost:8081`
- MySQL：`localhost:3307`
- Redis：`localhost:6379`
- RocketMQ NameServer：`localhost:9876`

全新环境会自动导入 `src/main/resources/db/citypass.sql`。旧增强版数据库依次执行：

```text
deploy/mysql/migration-v2.sql
deploy/mysql/migration-v3-waitlist.sql
```

可选的 Canal 缓存驱逐链路：

```powershell
$env:CANAL_ENABLED='true'
docker compose --profile canal up -d --build
```

## 验证

单元测试：

```powershell
mvn clean test
```

Docker 全链路测试：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1
```

脚本会创建临时用户和活动，实际验证匿名场馆读取、订阅、动态评论、直接预约、两级候补 FIFO、取消自动补位、候补支付、越权查询、支付 CAS、库存回补、取消后重新预约和活动时间窗拦截。

## 主要 API

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/venues/{id}` | 场馆详情 |
| GET | `/venues/of/type` | 分类与距离查询 |
| POST | `/passes/limited` | 发布限量活动通行证 |
| POST | `/reservations/{passId}` | 预约，可选择接受候补 |
| GET | `/reservations/requests/{requestId}` | 查询预约或候补状态 |
| PUT | `/reservations/{orderId}/pay` | 支付预约订单 |
| PUT | `/reservations/{orderId}/cancel` | 取消并触发补位 |
| DELETE | `/reservations/waitlist/{requestId}` | 退出候补 |
| POST / GET / DELETE | `/story-comments` | 发布、查询和删除动态评论 |
| PUT / DELETE / GET | `/subscriptions/{targetUserId}` | 订阅、取消订阅、查询状态 |

## 学习顺序

先读 [00-先读这里](docs/00-先读这里.md)，再按 [01-项目全景与代码地图](docs/01-项目全景与代码地图.md) 定位代码。预约与候补的核心推理在 [02-预约一致性与候补补位](docs/02-预约一致性与候补补位.md)，缓存与安全在 [03-缓存限流与安全](docs/03-缓存限流与安全.md)，面试表达和简历边界分别在 [04-面试讲法与追问](docs/04-面试讲法与追问.md) 与 [07-简历项目写法](docs/07-简历项目写法.md)。

## 工程演进说明

本项目基于一个开源本地生活服务原型做二次开发，保留了部分通用的用户与内容能力。当前 CityPass 的领域背景、包名、表结构、API、预约时间窗、单一 MQ 写链路、候补状态机、自动补位、可靠任务和端到端验收均经过重新设计与实现。面试时应准确说明自己负责的改造范围，不把开源基础描述成从零原创。
