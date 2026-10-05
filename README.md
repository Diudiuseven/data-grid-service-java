# data-grid-service-java

支撑可编辑数据表格的服务端能力：服务端统一查询与写入，提供分页、排序、
筛选、游标翻页、乐观并发控制、批量编辑、具名视图与 MVCC 快照隔离。

## 一致性语义

### 存储模型（MVCC）

- 每一次写入（单行或批量）产生一个**新的不可变快照（Snapshot）**，携带单调
  递增的提交序号 `commitSeq`。
- 读请求总是作用于某一个已完整提交的快照：要么读到整个旧版本，要么读到整
  个新版本，**绝不会观察到字段级或行级的混合状态**。
- 读者不加锁、不阻塞写者；写者在单个提交监视器上串行化。慢查询钉住旧快照
  继续读，写入照常提交。

### 排序与筛选

- 多列排序按声明顺序确定优先级；所有排序键相等时以**行 id 升序**作为最终
  裁决，构成全序。因此同一查询在任意翻页顺序、任意并发度下返回的顺序与
  内容完全一致。
- 筛选按列类型做类型化比较（STRING / LONG / DOUBLE / BOOLEAN / INSTANT），
  客户端值会被强制转换为列类型，无法转换时以 `INVALID_QUERY` 拒绝。

### 游标翻页

- 游标是不透明令牌，内含：快照提交序号、查询指纹（筛选+排序的规范化散
  列）、最后一行的排序键位置与行 id。
- 翻页过程中其他来源新增/删除/修改数据时，扫描始终锚定在首页快照上：
  快照内存在的行恰好交付一次，**不重不漏**；之后新增的行不会出现，之后
  删除的行仍会出现（它们属于该快照）。
- 游标失效原因可区分：
  - `CURSOR_MALFORMED`（400）：游标无法解码；
  - `CURSOR_QUERY_MISMATCH`（400）：游标被用于不同的筛选/排序查询；
  - `CURSOR_EXPIRED`（410）：钉住的快照已被淘汰（见
    `grid.snapshot-retention`）。

### 乐观并发控制

- 每行携带 `version`，每次提交写入使其 +1。
- 更新与删除必须携带期望版本；版本过期以 `VERSION_CONFLICT`（409）拒绝，
  行不存在以 `ROW_NOT_FOUND`（404）拒绝，二者可区分。并发编辑不会产生静
  默覆盖：恰好一个写者成功，其余写者收到冲突。

### 批量编辑

- `ATOMIC`（默认）：整批作为一个提交，任一行校验失败则**全部不生效**，
  返回中携带每一行的诊断（成功/失败原因）。
- `PER_ROW`：调用方显式指定后逐行独立提交，每行是自身的原子单元，结果
  逐行给出 `success` / `errorReason`，失败行不留下任何部分写入。

### 具名视图

- 视图保存筛选、排序、分页大小与可见列，并在保存时捕获当时的 schema 版
  本与列类型。
- 列被删除或改类型后旧视图仍可打开（`GET /api/grid/views/{name}/resolve`）：
  受影响的部分被丢弃，并在 `degradations` 中给出降级提示
  （`COLUMN_REMOVED` / `COLUMN_TYPE_CHANGED`）。

## HTTP API 概览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/grid/query` | 查询：filters/sorts/offset/limit 或 cursor |
| POST | `/api/grid/rows/{id}` | 新建行 |
| PUT | `/api/grid/rows/{id}?version=` | 乐观锁更新 |
| DELETE | `/api/grid/rows/{id}?version=` | 乐观锁删除 |
| POST | `/api/grid/batch` | 批量写入（mode=ATOMIC/PER_ROW） |
| GET/PUT | `/api/grid/schema` | 查看/演进列定义 |
| PUT/GET/DELETE | `/api/grid/views[/{name}]` | 视图保存/读取/删除 |
| GET | `/api/grid/views/{name}/resolve` | 按当前 schema 解析视图并给出降级提示 |

错误响应统一为 `{"reason": "...", "message": "..."}`，`reason` 取自
`ErrorReason` 枚举（见 `grid/error/ErrorReason.java`），HTTP 状态码与原因
一一对应（409 冲突、404 不存在、400 请求非法、410 游标过期、422 批量拒绝）。

## 可配置项（`application.properties`，前缀 `grid.`）

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `grid.max-page-size` | 500 | 客户端可请求的最大页大小 |
| `grid.default-page-size` | 50 | 未指定 limit 时的页大小 |
| `grid.snapshot-retention` | 64 | 为游标翻页保留的历史快照数量；窗口外的游标报 `CURSOR_EXPIRED` |

## 测试

`mvn test` 覆盖：多列排序稳定性、并发翻页不重不漏、游标三类失效原因、
乐观锁冲突与并发编辑、批量原子性与逐行部分失败、视图在列删除/改类型后的
降级、并发读写下读者永不观察到部分提交的批次、慢读者不阻塞写者。
