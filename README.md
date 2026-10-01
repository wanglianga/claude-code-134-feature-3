# 培训机构退费与顾问业绩冻结服务（training-crm）

覆盖培训机构从 **线索 → 试听排班 → 试听反馈 → 报名转化（分期手续费/教材出库）→ 排课课消 → 请假/转班/停课 → 扩展退费（已课消课时/赠课使用/教材领取/优惠回收/分期手续费/提成冻结）→ 校区整改 → 财务审核 → 名额释放/未使用课表关闭/教材回收/顾问业绩按原因重归因/渠道投放质量更新 → 校长履约轨迹** 的完整业务闭环。

- 技术栈：Kotlin 1.9 + Spring Boot 3.3 + Spring Data JPA（H2 内存库）+ Redis 7（分布式锁 / 幂等）+ springdoc-openapi（OpenAPI 3 / Swagger UI）
- 构建：Maven（`pom.xml`），JDK 17

## 一、本地启动

### 方式 A：一键 Docker Compose（推荐）

```bash
docker compose up --build
# 应用 http://localhost:8080，Redis 容器内可达；停止：docker compose down
```

### 方式 B：本机运行

```bash
# 1. 启动 Redis（默认 localhost:6379，可用 REDIS_HOST/REDIS_PORT 覆盖）
redis-server --daemonize yes

# 2. 启动应用（二选一）
mvn spring-boot:run
# 或
mvn package -DskipTests && java -jar target/training-crm-1.0.0.jar
```

应用监听 `http://localhost:8080`。H2 为内存库，重启后数据重置并重新播种；H2 控制台：`/h2-console`（JDBC URL `jdbc:h2:mem:trainingdb`，用户 `sa`，空密码）。

- **OpenAPI 3 文档（JSON）**：`/v3/api-docs`
- **Swagger UI（在线调试）**：`/swagger-ui.html`（点 Authorize 填 `X-Role` 即可直接调用）

## 二、测试账号（角色）

鉴权为轻量请求头模式（无口令）：

| 请求头 | 说明 |
|---|---|
| `X-Role` | 必填：`ADMIN`（教务/管理员）、`PRINCIPAL`（校长）、`FINANCE`（财务）、`CONSULTANT`（顾问）、`TEACHER`（老师） |
| `X-Actor` | 可选，操作人姓名，写入履约轨迹（支持中文） |
| `X-Idempotency-Key` | 可选，线索创建/退费申请的幂等键 |

- `/api/principal/**` 仅 `PRINCIPAL` / `ADMIN` 可访问；退费审批（approve/reject）仅 `FINANCE`/`PRINCIPAL`/`ADMIN`；整改说明仅 `ADMIN`/`PRINCIPAL`；其余按业务角色放开。越权返回 403，缺 `X-Role` 返回 401。

种子数据（启动时自动写入）：

| 数据 | 内容 |
|---|---|
| 校区 | #1 中关村校区、#2 望京校区 |
| 顾问 | #1 王顾问（中关村）、#2 李顾问（望京） |
| 老师 | #1 张老师（数学，**跨校区共享** 中关村+望京）、#2 刘老师（英语，中关村）、#3 陈老师（数学/物理，望京）；空档均为工作日 18:00-21:00、周末 09:00-18:00 |
| 教室 | A101(4人)/A102(8人)@中关村、B201(6人)@望京 |
| 课包 | #1 数学思维提高班课包（48课时 ¥9600，教材费 ¥300，赠课4）；#2 英语听说基础课包（48课时 ¥8800，教材费 ¥200，赠课2） |
| 班级 | #1 数学提高1班（中关村，张老师，容量4） |
| 教材库存 | 人教版数学 / 新概念青少版 / 人教版物理 各 20 本 |
| 渠道质量基准 | 昨日 抖音、官网 线索/有效报名/成本（用于与当日实时数据对比） |

## 三、核心业务规则

**试听排班匹配**（`POST /api/trials/book`）：自动匹配 ①老师科目=线索目标科目 ②老师在线索校区可授课（多校区共享）③老师空档覆盖整个时段 ④老师/教室时段无冲突；教材版本按科目默认，试听水平=学生基础水平。老师+时段、教室+时段用 Redis 锁防并发双订。

**课消规则**（`POST /api/courses/lessons/{id}/complete`）：到课扣 1 课时；缺席照常扣课时（`app.absent-consumes-lesson`）；已批准请假不扣课时，事后批准会返还已扣课时；扣课顺序按合同 `consumeOrder`（先付费 PAID_FIRST / 先赠课 GIFT_FIRST），付费课时耗尽后扣赠课，余额不足记告警事件。

**报名转化**（`POST /api/conversions`）：分期数 >1 时按期本金×费率（`app.installment-fee-rate` 默认 2%）计收**分期手续费**（随每期回款入账）；`materialsDelivered=true` 教材按「科目+版本」**出库**；记录合同 `signedAt`（可补录，用于退费账龄）；同一家长手机号在已有**有效报名**期间重复转化会被拦截（`DUPLICATE_ENROLLMENT`），避免同一客户重复算作有效报名；报名时还会比对试听反馈与合同承诺，不一致需 `forceConfirm=true` 并留痕告警（`CONSISTENCY_WARNING`）。

**扩展退费计算**（家长报名两周后申请退费；`GET /api/refunds/preview/{studentId}?reasonCode=&appliedAt=` 试算 / `POST /api/refunds` 申请）：

```
课时单价   = 实付金额 / 付费课时数
基础退费   = 剩余付费课时 × 单价             （赠课不退现）
赠课抵扣   = GIFT_FIRST 时已消耗赠课 × 单价 （PAID_FIRST 时为 0）
教材费     = 教材已发放则全额扣除
优惠回收   = 已课消占比 < 课包阈值(默认50%) 时全额回收优惠
分期手续费 = 报名满冷静期(app.refund-cooling-days 默认14天)后申请，已回款分期的手续费不退；冷静期内免收
投诉赔付   = 未结投诉数 × 200（app.complaint-compensation）
应退金额   = max(0, 基础退费 - 教材费 - 优惠回收 - 赠课抵扣 - 手续费回收 + 投诉赔付)，并以已收款为上限
```

退费原因结构化（`reasonCode`）并决定**业绩重新归因**：

| reasonCode | 含义 | 归因 | 整改 | 提成处理 |
|---|---|---|---|---|
| `SERVICE_PROMISE_MISMATCH` | 服务承诺不一致 | 顾问 CONSULTANT | **必须提交整改说明** | 全额追回 |
| `TEACHER_QUALITY` / `DISSATISFIED` | 教学质量/其他服务不满意 | 校区服务 SERVICE | 否 | 按已课消比例部分结算，其余追回 |
| `SCHEDULE_CONFLICT` / `MOVING` / `PERSONAL` / `OTHER` | 排课冲突/搬家/个人/其他 | 客户 CUSTOMER | 否 | 按已课消比例部分结算，其余追回 |

**生命周期与联动**：

1. `POST /api/refunds` 申请即**冻结顾问本单提成**（FROZEN），幂等键防重复申请；
2. 若原因为服务承诺不一致，校区须先 `POST /api/refunds/{id}/rectification` 提交**整改说明**，否则财务审核被拒（`RECTIFICATION_REQUIRED`）；
3. `POST /api/refunds/{id}/approve`（财务）审核通过并打款后：
   - 合同/学员状态联动关闭；**班级名额释放**，班级再无在读学员时**关闭未使用课表**（待上课节数取消，**老师排课相应减少**）；
   - `materialsReturned=true` 时家长交回教材，库存**回收入库**并记流水；
   - 未收分期（**本金+手续费**）全部**减免**；
   - **顾问业绩按退费原因重新归因**：顾问责任全额追回（CLAWED_BACK）；其余按已课消比例部分结算（SETTLED_PARTIAL / SETTLED）；
   - 线索 `validEnrollment` 核销、标记已退费，并**更新渠道投放质量**（有效报名 -1、退费 +1，重算有效率与单条有效报名成本），防止同一线索被重复算作有效报名；
4. `POST /api/refunds/{id}/reject` 驳回则解冻提成。

**教材库存**（`GET /api/inventory/stocks`、`/api/inventory/movements`）：报名发放出库、退费交回入库，按「科目+教材版本」维护余量与出入库流水。

**渠道投放质量**（`GET /api/principal/channels/quality`）：按渠道+日期聚合线索数、有效报名数、退费数、成本、有效率（有效报名/线索）、单条有效报名成本。

**校长履约轨迹**（`GET /api/principal/students/{id}/timeline`）：学员从线索进入、试听体验、报名承诺、分期回款、教材发放、实际上课到退费结束（名额释放/课表关闭/整改/教材回收/提成重归因/渠道核销）的全部事件流 + 合同/分期(含手续费)/发票/提成(含结算额)/渠道费/赠品/投诉财务摘要；`GET /api/principal/overview` 为分校区、分顾问经营概览（含提成部分结算）与渠道成本/质量。

## 四、主要 API

| 模块 | 接口 |
|---|---|
| 基础数据 | `POST/GET /api/setup/campuses|classrooms|teachers|consultants|packages|classes`，`POST /api/setup/teachers/{id}/availability` |
| 线索 | `POST /api/leads`（幂等，同手机号重复风险留痕）、`POST /api/leads/{id}/campus`、`POST /api/leads/{id}/promises`、`POST /api/leads/{id}/lost` |
| 试听 | `POST /api/trials/book`、`/{id}/reschedule`、`/{id}/attend`、`/{id}/absent`、`/{id}/teacher-leave`、`/{id}/feedback` |
| 转化 | `POST /api/conversions`（分期手续费+教材出库+合同签署时间+重复报名拦截+承诺一致性校验）、`POST /api/conversions/installments/{id}/pay` |
| 排课课消 | `POST /api/courses/classes/{id}/enroll`、`POST /api/courses/lessons`、`POST /api/courses/lessons/{id}/complete`、`POST /api/courses/lessons/{id}/teacher-leave`、`GET /api/courses/contracts/{id}/consumption` |
| 学员异动 | `POST /api/students/{id}/leave`、`POST /api/students/leave-requests/{id}/approve|reject`、`POST /api/students/{id}/transfer`、`POST /api/students/{id}/suspend|resume`、`POST /api/students/{id}/complaints` |
| 退费 | `GET /api/refunds/preview/{studentId}`（reasonCode/appliedAt）、`POST /api/refunds`（幂等）、`POST /api/refunds/{id}/rectification`、`POST /api/refunds/{id}/approve`（materialsReturned）、`POST /api/refunds/{id}/reject` |
| 库存 | `GET /api/inventory/stocks`、`GET /api/inventory/movements` |
| 校长 | `GET /api/principal/students/{id}/timeline`、`GET /api/principal/overview`、`GET /api/principal/channels/quality` |

完整字段与枚举见 Swagger UI（`/swagger-ui.html`）。

## 五、本地开发与验证方式

```bash
# 启动依赖与应用（见第一节）；然后在全新启动的应用上运行：
bash scripts/verify.sh        # 需应用已启动；BASE 环境变量可改地址
```

> 请在**全新启动**的应用上运行（H2 为内存库，重启即重置为种子数据）；脚本会自行清理其使用的 Redis 幂等键（脚本依赖 `curl`、`jq`、`redis-cli`）。

脚本用 curl+jq 跑通 4 条学员主线，共 **183 项断言**：

1. **小明（服务承诺不一致，报名 20 天后）**：线索(幂等)→承诺→试听(冲突拦截/改约3次风险/老师请假/重约/到课发赠品)→反馈→报名(一致性拦截→强制确认3告警,3期分期每期手续费60,教材出库)→3期回款→入班→3节课(2到课1缺席)→请假返还→跨校区转班(新班预排2节未上课)→停课/复课→投诉→同手机号重复报名被拦截→退费试算（已上2课时,单价187.5,基础8625,教材-300,优惠-600,超冷静期手续费-180,投诉+200,**应退7745**；归因顾问、必须整改、提成预计全追回450）→未整改财务审核被拒→校区提交整改说明→财务审核打款（**名额释放、2节未使用课表关闭、教材回收入库、提成全额追回450、抖音渠道有效报名核销**）；
2. **小红（搬家，冷静期内）**：英语 2 期分期（每期费88）只回 1 期且当天报名 → 手续费不收（应退上限=已收 4400+88=**4488**），未付本金+费减免，提成全额追回，无教材不回收；
3. **小刚（不退费对照）**：2付费+1赠课小包→插班→4节课课消付费2+赠课1、余额不足告警；数学1班仍有在读学员**保持开放**；转介绍渠道**有效报名保留**；
4. **小强（教学质量责任，GIFT_FIRST）**：物理小班 10 课时，报名 20 天后，3 节课（赠课2+付费1）→ 基础退费1800、赠课抵扣400、手续费40不退、**应退1260**；归因校区服务、无需整改，提成 100 元按已课消10% **部分结算10 / 追回90**；
5. 另验证：鉴权 401/403（含财务审批与整改角色限制）、排课老师冲突、教材库存与出入库流水、渠道投放质量（当日核销/昨日基准保留/无有效报名成本为空）、OpenAPI 3 与 Swagger UI、校长轨迹全量事件与经营概览。

**最近一次执行结果（2026-10-01，本机 JDK17 + Redis7）：`通过 183 项，失败 0 项`，VERIFY_RESULT: PASS。**
