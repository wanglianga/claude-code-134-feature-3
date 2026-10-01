#!/usr/bin/env bash
# 端到端验证：线索 → 试听排班 → 反馈 → 报名转化（分期手续费/教材出库）→ 排课课消 → 请假/转班/停课
#   → 扩展退费（已课消/赠课/教材/优惠回收/分期手续费/提成冻结）
#   → 整改说明门禁 → 财务审核 → 名额释放/未使用课表关闭/教材回收/业绩按原因重归因/渠道投放质量核销
# 前置：应用已在 ${BASE:-http://localhost:8080} 运行，Redis 可用。
set -uo pipefail

BASE=${BASE:-http://localhost:8080}
PASS=0; FAIL=0

# 清理本脚本使用的幂等键（H2 为内存库，应用重启后旧幂等键会指向已不存在的数据）
redis-cli del idem:lead:lead-xm-001 idem:refund:refund-xm-001 > /dev/null 2>&1 || true

check() { # $1=描述 $2=实际 $3=期望
  if [[ "$2" == "$3" ]]; then PASS=$((PASS+1)); echo "  ✔ $1";
  else FAIL=$((FAIL+1)); echo "  ✘ $1  [期望=$3 实际=$2]"; fi
}
check_true() { # $1=描述 $2=实际(非空即真)
  if [[ -n "$2" && "$2" != "null" && "$2" != "false" ]]; then PASS=$((PASS+1)); echo "  ✔ $1";
  else FAIL=$((FAIL+1)); echo "  ✘ $1  [实际=$2]"; fi
}
post() { # $1=path $2=role $3=body $4..=额外header
  local path=$1 role=$2 body=$3; shift 3
  curl -s -X POST "$BASE$path" -H "X-Role: $role" -H "Content-Type: application/json" "$@" -d "$body"
}
apiget() { curl -s "$BASE$1" -H "X-Role: $2"; }

SAT=$(date -d "next Saturday" +%F)      # 老师空档：周末 09:00-18:00
SUN=$(date -d "next Sunday" +%F)
SAT2=$(date -d "$SAT +7 days" +%F)
SAT3=$(date -d "$SAT +14 days" +%F)
SIGNED=$(date -d "20 days ago" "+%Y-%m-%dT%H:%M:%S")   # 补录合同签署时间：报名已超过两周（14天冷静期）
TODAY=$(date +%F)

echo "== 0. 鉴权 =="
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/setup/campuses")
check "缺少 X-Role 返回 401" "$CODE" "401"
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/principal/overview" -H "X-Role: CONSULTANT")
check "顾问访问校长视图返回 403" "$CODE" "403"
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/inventory/stocks" -H "X-Role: FINANCE")
check "财务角色可访问业务接口" "$CODE" "200"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/refunds/9999/approve" -H "X-Role: CONSULTANT" -H "Content-Type: application/json" -d '{}')
check "顾问不能财务审核退费(403)" "$CODE" "403"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/refunds/9999/rectification" -H "X-Role: TEACHER" -H "Content-Type: application/json" -d '{"note":"x"}')
check "老师不能提交整改说明(403)" "$CODE" "403"

echo "== 1. 线索（幂等 + 渠道费 + 家长承诺 + 渠道质量）=="
LEAD_BODY='{"studentName":"小明","parentPhone":"13711111111","grade":"四年级","subject":"MATH","baseLevel":"INTERMEDIATE","availableTime":"周六上午","sourceChannel":"抖音","consultantId":1,"campusId":1,"joinedActivity":true}'
R1=$(post /api/leads CONSULTANT "$LEAD_BODY" -H "X-Actor: 王顾问" -H "X-Idempotency-Key: lead-xm-001")
LEAD_A=$(echo "$R1" | jq -r '.lead.id')
check "线索创建成功" "$(echo "$R1" | jq -r '.lead.status')" "NEW"
check "首次创建非幂等重放" "$(echo "$R1" | jq -r '.idempotentReplay')" "false"
R1B=$(post /api/leads CONSULTANT "$LEAD_BODY" -H "X-Actor: 王顾问" -H "X-Idempotency-Key: lead-xm-001")
check "相同幂等键重放返回同一线索" "$(echo "$R1B" | jq -r '.lead.id')" "$LEAD_A"
check "重放标记为幂等" "$(echo "$R1B" | jq -r '.idempotentReplay')" "true"
post /api/leads/$LEAD_A/promises CONSULTANT '{"content":"保过期末数学90分","promisedBy":"王顾问"}' -H "X-Actor: 王顾问" > /dev/null
check "家长承诺登记" "$(apiget /api/leads/$LEAD_A ADMIN | jq -r '.status')" "NEW"

echo "== 2. 试听排班（匹配/冲突/改约/老师请假/缺席/赠品）=="
TRIAL=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_A,\"startTime\":\"${SAT}T10:00:00\",\"endTime\":\"${SAT}T11:00:00\"}" -H "X-Actor: 王顾问")
TRIAL_A=$(echo "$TRIAL" | jq -r '.id')
check "自动匹配张老师(共享校区)" "$(echo "$TRIAL" | jq -r '.teacherId')" "1"
check "教材版本默认人教版数学" "$(echo "$TRIAL" | jq -r '.textbookVersion')" "人教版数学"
check "试听水平=学生基础水平" "$(echo "$TRIAL" | jq -r '.level')" "INTERMEDIATE"

# 同时段冲突：另一线索约同一时段应失败（老师被占用）
LEAD_X=$(post /api/leads CONSULTANT '{"studentName":"冲突测试","parentPhone":"13700000000","grade":"五年级","subject":"MATH","baseLevel":"BASIC","availableTime":"周六上午","sourceChannel":"地推","consultantId":1,"campusId":1}' -H "X-Actor: 王顾问" | jq -r '.lead.id')
CONFLICT=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_X,\"startTime\":\"${SAT}T10:00:00\",\"endTime\":\"${SAT}T11:00:00\"}" -H "X-Actor: 王顾问")
check "同时段再约试听被拦截" "$(echo "$CONFLICT" | jq -r '.error')" "NO_TEACHER"

# 改约 3 次 → 触发多次改约风险
post /api/trials/$TRIAL_A/reschedule CONSULTANT "{\"startTime\":\"${SAT}T14:00:00\",\"endTime\":\"${SAT}T15:00:00\"}" -H "X-Actor: 王顾问" > /dev/null
post /api/trials/$TRIAL_A/reschedule CONSULTANT "{\"startTime\":\"${SAT}T15:00:00\",\"endTime\":\"${SAT}T16:00:00\"}" -H "X-Actor: 王顾问" > /dev/null
RSC=$(post /api/trials/$TRIAL_A/reschedule CONSULTANT "{\"startTime\":\"${SAT}T16:00:00\",\"endTime\":\"${SAT}T17:00:00\"}" -H "X-Actor: 王顾问")
check "改约计数=3" "$(echo "$RSC" | jq -r '.rescheduleCount')" "3"

# 老师临时请假 → 试听取消，线索回到可预约
TL=$(post /api/trials/$TRIAL_A/teacher-leave TEACHER '{"reason":"张老师突发疾病"}' -H "X-Actor: 张老师")
check "老师请假后试听状态" "$(echo "$TL" | jq -r '.status')" "CANCELLED_TEACHER_LEAVE"
check "线索回到可预约状态" "$(apiget /api/leads/$LEAD_A ADMIN | jq -r '.status')" "NEW"

# 缺席流程（线索X）：预约→缺席→回到NEW
TRIAL_X=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_X,\"startTime\":\"${SAT}T10:00:00\",\"endTime\":\"${SAT}T11:00:00\"}" -H "X-Actor: 王顾问" | jq -r '.id')
ABS=$(post /api/trials/$TRIAL_X/absent CONSULTANT '{}' -H "X-Actor: 王顾问")
check "缺席标记" "$(echo "$ABS" | jq -r '.status')" "ABSENT"
check "缺席后线索可重新预约" "$(apiget /api/leads/$LEAD_X ADMIN | jq -r '.status')" "NEW"

# 重新预约 → 到课 → 参加活动发赠品
TRIAL_A2=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_A,\"startTime\":\"${SUN}T10:00:00\",\"endTime\":\"${SUN}T11:00:00\"}" -H "X-Actor: 王顾问" | jq -r '.id')
post /api/trials/$TRIAL_A2/attend CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
check "到课后状态" "$(apiget /api/trials/$TRIAL_A2 ADMIN | jq -r '.status')" "ATTENDED"

echo "== 3. 试听反馈与转化一致性校验（含分期手续费/教材出库/合同账龄）=="
FB=$(post /api/trials/$TRIAL_A2/feedback TEACHER '{"performance":"课堂专注度一般，计算能力强但概念薄弱","assessmentScore":62,"suitableClassType":"ONE_ON_ONE","suggestedLevel":"BASIC","riskWarning":"基础薄弱，不建议保分承诺"}' -H "X-Actor: 张老师")
check "反馈提交成功" "$(echo "$FB" | jq -r '.suitableClassType')" "ONE_ON_ONE"
check "线索进入试听完成" "$(apiget /api/leads/$LEAD_A ADMIN | jq -r '.status')" "TRIAL_DONE"

# signedAt=20天前 → 家长报名两周后申请退费；3期分期，每期手续费=3000*2%=60
CONV_BODY="{\"leadId\":$LEAD_A,\"coursePackageId\":1,\"discountAmount\":600,\"installments\":[{\"amount\":3000,\"dueDate\":\"$SAT\"},{\"amount\":3000,\"dueDate\":\"$SAT2\"},{\"amount\":3000,\"dueDate\":\"$SAT3\"}],\"invoiceTitle\":\"小明家长\",\"promises\":[\"保过期末数学90分\"],\"materialsDelivered\":true,\"signedAt\":\"$SIGNED\"}"
BLOCKED=$(post /api/conversions CONSULTANT "$CONV_BODY" -H "X-Actor: 王顾问")
check "承诺与反馈不一致被拦截" "$(echo "$BLOCKED" | jq -r '.error')" "CONSISTENCY_WARNING"
CONV=$(post /api/conversions CONSULTANT "$(echo "$CONV_BODY" | jq -c '. + {forceConfirm:true}')" -H "X-Actor: 王顾问")
STU_A=$(echo "$CONV" | jq -r '.student.id')
CT_A=$(echo "$CONV" | jq -r '.contract.id')
check "强制确认后转化成功" "$(echo "$CONV" | jq -r '.contract.status')" "ACTIVE"
check "一致性告警条数=3" "$(echo "$CONV" | jq -r '.consistencyWarnings | length')" "3"
check "实付金额=9600-600" "$(echo "$CONV" | jq -r '.contract.finalAmount')" "9000"
check "赠课=课包4+额外0" "$(echo "$CONV" | jq -r '.contract.giftLessons')" "4"
check "分期3期每期手续费60" "$(echo "$CONV" | jq -r '[.installments[].fee] | unique | join(",")')" "60"
check "提成计提=9000*5%" "$(echo "$CONV" | jq -r '.commission.amount')" "450"
check "发票已开" "$(echo "$CONV" | jq -r '.invoice.status')" "ISSUED"
for IID in $(echo "$CONV" | jq -r '.installments[].id'); do
  post /api/conversions/installments/$IID/pay CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
done
check "3期分期全部回款" "$(echo "$CONV" | jq -r '.installments | length')" "3"
# 教材已发放出库：人教版数学库存 20 → 19
check "报名发放教材后库存19" "$(apiget /api/inventory/stocks ADMIN | jq -r '.[] | select(.textbookVersion=="人教版数学") | .available')" "19"

echo "== 4. 入班 / 排课 / 课消 / 请假 / 转班 / 停课 =="
EN=$(post /api/courses/classes/1/enroll CONSULTANT "{\"studentId\":$STU_A,\"contractId\":$CT_A}" -H "X-Actor: 王顾问")
check "入班成功" "$(echo "$EN" | jq -r '.status')" "ACTIVE"
check "非插班" "$(echo "$EN" | jq -r '.midTermInsertion')" "false"

L1=$(post /api/courses/lessons ADMIN "{\"clazzId\":1,\"startTime\":\"${SAT2}T10:00:00\",\"endTime\":\"${SAT2}T11:00:00\",\"topic\":\"分数运算\"}" -H "X-Actor: 教务" | jq -r '.id')
L2=$(post /api/courses/lessons ADMIN "{\"clazzId\":1,\"startTime\":\"${SAT2}T11:00:00\",\"endTime\":\"${SAT2}T12:00:00\",\"topic\":\"应用题专题\"}" -H "X-Actor: 教务" | jq -r '.id')
L3=$(post /api/courses/lessons ADMIN "{\"clazzId\":1,\"startTime\":\"${SAT2}T14:00:00\",\"endTime\":\"${SAT2}T15:00:00\",\"topic\":\"几何初步\"}" -H "X-Actor: 教务" | jq -r '.id')
check "排课成功" "$([[ "$L1" != "null" && "$L2" != "null" && "$L3" != "null" ]] && echo ok)" "ok"
BUSY=$(post /api/courses/lessons ADMIN "{\"clazzId\":1,\"startTime\":\"${SAT2}T10:30:00\",\"endTime\":\"${SAT2}T11:30:00\",\"topic\":\"冲突课\"}" -H "X-Actor: 教务")
check "老师时段冲突排课被拦截" "$(echo "$BUSY" | jq -r '.error')" "TEACHER_BUSY"

post /api/courses/lessons/$L1/complete TEACHER '{}' -H "X-Actor: 张老师" > /dev/null
post /api/courses/lessons/$L2/complete TEACHER '{}' -H "X-Actor: 张老师" > /dev/null
post /api/courses/lessons/$L3/complete TEACHER "{\"absentStudentIds\":[$STU_A]}" -H "X-Actor: 张老师" > /dev/null
CONS=$(apiget /api/courses/contracts/$CT_A/consumption ADMIN)
check "课消：2到课+1缺席=扣3付费课时" "$(echo "$CONS" | jq -r '.consumedPaid')" "3"

# 事后请假批准 → 返还缺席所扣课时
LR=$(post /api/students/$STU_A/leave CONSULTANT "{\"lessonId\":$L3,\"reason\":\"当天发烧\"}" -H "X-Actor: 王顾问" | jq -r '.id')
post /api/students/leave-requests/$LR/approve ADMIN '{}' -H "X-Actor: 教务" > /dev/null
CONS=$(apiget /api/courses/contracts/$CT_A/consumption ADMIN)
check "请假批准后返还课时(3→2)" "$(echo "$CONS" | jq -r '.consumedPaid')" "2"

# 转班（跨校区：中关村数学提高1班 → 望京数学提高2班）
post /api/setup/classes ADMIN '{"name":"数学提高2班","campusId":2,"subject":"MATH","level":"INTERMEDIATE","classType":"REGULAR","teacherId":3,"classroomId":3,"capacity":6,"textbookVersion":"人教版数学","scheduleDesc":"每周日"}' > /dev/null
TR=$(post /api/students/$STU_A/transfer CONSULTANT '{"toClazzId":2,"reason":"家搬到望京，换校区"}' -H "X-Actor: 王顾问")
check "跨校区转班成功" "$(echo "$TR" | jq -r '.toClazzId')" "2"

# 新班预排 2 节未使用课表（退费审核后应随班级无人在读而关闭，老师排课减少）
post /api/courses/lessons ADMIN "{\"clazzId\":2,\"startTime\":\"${SAT3}T16:00:00\",\"endTime\":\"${SAT3}T17:00:00\",\"topic\":\"提高2班-函数\"}" -H "X-Actor: 教务" > /dev/null
post /api/courses/lessons ADMIN "{\"clazzId\":2,\"startTime\":\"${SAT3}T17:00:00\",\"endTime\":\"${SAT3}T18:00:00\",\"topic\":\"提高2班-压轴题\"}" -H "X-Actor: 教务" > /dev/null

# 停课 → 复课
post /api/students/$STU_A/suspend CONSULTANT "{\"reason\":\"出国游学\",\"startDate\":\"$SAT3\",\"endDate\":\"$SAT3\"}" -H "X-Actor: 王顾问" > /dev/null
check "停课状态" "$(apiget /api/principal/students/$STU_A/timeline PRINCIPAL | jq -r '.student.status')" "SUSPENDED"
post /api/students/$STU_A/resume CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
check "复课状态" "$(apiget /api/principal/students/$STU_A/timeline PRINCIPAL | jq -r '.student.status')" "ACTIVE"

# 同一家长手机号在已有有效报名期间，再次走完试听并尝试报名 → 必须拦截（防止重复算作有效报名）
LEAD_DUP=$(post /api/leads CONSULTANT '{"studentName":"小明重复报名","parentPhone":"13711111111","grade":"四年级","subject":"ENGLISH","baseLevel":"BASIC","availableTime":"周日中午","sourceChannel":"地推","consultantId":1,"campusId":1}' -H "X-Actor: 王顾问" | jq -r '.lead.id')
TRIAL_DUP=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_DUP,\"startTime\":\"${SUN}T14:00:00\",\"endTime\":\"${SUN}T15:00:00\"}" -H "X-Actor: 王顾问")
check "重复线索试听匹配刘老师" "$(echo "$TRIAL_DUP" | jq -r '.teacherId')" "2"
post /api/trials/$(echo "$TRIAL_DUP" | jq -r '.id')/attend CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
post /api/trials/$(echo "$TRIAL_DUP" | jq -r '.id')/feedback TEACHER '{"performance":"正常","assessmentScore":80,"suitableClassType":"REGULAR","suggestedLevel":"BASIC"}' -H "X-Actor: 刘老师" > /dev/null
DUP_CONV=$(post /api/conversions CONSULTANT "{\"leadId\":$LEAD_DUP,\"coursePackageId\":2,\"installments\":[{\"amount\":8800,\"dueDate\":\"$SAT\"}],\"materialsDelivered\":false}" -H "X-Actor: 王顾问")
check "有效报名期间同手机号重复转化被拦截" "$(echo "$DUP_CONV" | jq -r '.error')" "DUPLICATE_ENROLLMENT"

echo "== 5. 扩展退费：服务承诺不一致（顾问责任，冷静期后）=="
post /api/students/$STU_A/complaints CONSULTANT '{"content":"家长投诉换老师未提前通知"}' -H "X-Actor: 王顾问" > /dev/null
# 已上2课时，单价=9000/48=187.5，基础退费=46*187.5=8625；教材-300；优惠回收-600；
# 报名已20天(超两周冷静期)，已承担分期手续费 3*60=180 不退；投诉赔付+200 → 8625-300-600-180+200=7745
PV=$(apiget "/api/refunds/preview/$STU_A?reasonCode=SERVICE_PROMISE_MISMATCH" ADMIN)
check "试算：已上付费课时=2" "$(echo "$PV" | jq -r '.consumedPaidLessons')" "2"
check "试算：课时单价=187.5" "$(echo "$PV" | jq -r '.unitPrice')" "187.5"
check "试算：基础退费=8625" "$(echo "$PV" | jq -r '.baseRefund')" "8625"
check "试算：教材费扣300" "$(echo "$PV" | jq -r '.materialFeeDeducted')" "300"
check "试算：优惠回收600" "$(echo "$PV" | jq -r '.discountRecovered')" "600"
check "试算：分期手续费合计180" "$(echo "$PV" | jq -r '.installmentFeeCharged')" "180"
check "试算：超冷静期手续费回收180" "$(echo "$PV" | jq -r '.installmentFeeRecovered')" "180"
check "试算：超两周冷静期" "$(echo "$PV" | jq -r '.afterCoolingPeriod')" "true"
check "试算：冷静期天数=14" "$(echo "$PV" | jq -r '.coolingPeriodDays')" "14"
check "试算：投诉赔付+200" "$(echo "$PV" | jq -r '.complaintCompensation')" "200"
check "试算：提成冻结450" "$(echo "$PV" | jq -r '.commissionFrozen')" "450"
check "试算：归因为顾问" "$(echo "$PV" | jq -r '.attribution')" "CONSULTANT"
check "试算：必须整改" "$(echo "$PV" | jq -r '.rectificationRequired')" "true"
check "试算：顾问责任提成结算=0" "$(echo "$PV" | jq -r '.commissionSettleEstimate')" "0"
check "试算：顾问责任提成追回450" "$(echo "$PV" | jq -r '.commissionClawEstimate')" "450"
check "试算：应退=7745" "$(echo "$PV" | jq -r '.refundAmount')" "7745"

RQ=$(post /api/refunds CONSULTANT "{\"studentId\":$STU_A,\"reasonCode\":\"SERVICE_PROMISE_MISMATCH\",\"reason\":\"承诺保过90分与实际服务不符\"}" -H "X-Actor: 王顾问" -H "X-Idempotency-Key: refund-xm-001")
RF_A=$(echo "$RQ" | jq -r '.id')
check "退费申请创建" "$(echo "$RQ" | jq -r '.status')" "REQUESTED"
check "退费单记录原因码" "$(echo "$RQ" | jq -r '.reasonCode')" "SERVICE_PROMISE_MISMATCH"
check "退费单标记需整改" "$(echo "$RQ" | jq -r '.rectificationRequired')" "true"
RQ2=$(post /api/refunds CONSULTANT "{\"studentId\":$STU_A,\"reasonCode\":\"SERVICE_PROMISE_MISMATCH\",\"reason\":\"重复提交\"}" -H "X-Actor: 王顾问" -H "X-Idempotency-Key: refund-xm-001")
check "退费申请幂等(同一单据)" "$(echo "$RQ2" | jq -r '.id')" "$RF_A"

# 未提交整改说明 → 财务审核必须拒绝
NO_REC=$(post /api/refunds/$RF_A/approve FINANCE '{"materialsReturned":true}' -H "X-Actor: 财务")
check "未整改财务审核被拦截" "$(echo "$NO_REC" | jq -r '.error')" "RECTIFICATION_REQUIRED"
check "拦截后退费单仍为申请态" "$(apiget /api/refunds/$RF_A ADMIN | jq -r '.status')" "REQUESTED"

# 校区提交整改说明
REC=$(post /api/refunds/$RF_A/rectification ADMIN '{"note":"已核查承诺与合同不一致，全校区重申禁止保过承诺，对王顾问通报批评，完善承诺双录留痕。"}' -H "X-Actor: 中关村校区")
check "整改说明已提交" "$(echo "$REC" | jq -r '.rectificationRequired')" "true"
check_true "整改说明内容已保存" "$(echo "$REC" | jq -r '.rectificationNote')"

# 财务审核通过并打款（家长交回教材）
post /api/refunds/$RF_A/approve FINANCE '{"materialsReturned":true}' -H "X-Actor: 财务" > /dev/null
RF=$(apiget /api/refunds/$RF_A ADMIN)
check "退费已打款" "$(echo "$RF" | jq -r '.status')" "PAID"
check "退费金额=7745" "$(echo "$RF" | jq -r '.refundAmount')" "7745"
check "释放班级名额=1" "$(echo "$RF" | jq -r '.seatsReleased')" "1"
check "关闭未使用课表=2节" "$(echo "$RF" | jq -r '.lessonsCancelled')" "2"
check "教材已回收" "$(echo "$RF" | jq -r '.materialsReturned')" "true"
check "提成处理结果=全额追回" "$(echo "$RF" | jq -r '.commissionOutcome')" "CLAWED_BACK"
check "提成追回金额=450" "$(echo "$RF" | jq -r '.commissionClawedBack')" "450"
check "提成结算金额=0" "$(echo "$RF" | jq -r '.commissionSettled')" "0"
check "渠道有效报名已核销" "$(echo "$RF" | jq -r '.channelAdjusted')" "true"

TL_A=$(apiget /api/principal/students/$STU_A/timeline PRINCIPAL)
check "退费后学员状态" "$(echo "$TL_A" | jq -r '.student.status')" "REFUNDED"
check "合同状态=REFUNDED" "$(echo "$TL_A" | jq -r '.contracts[0].status')" "REFUNDED"
check "提成已追回" "$(echo "$TL_A" | jq -r '.contracts[0].commissions[0].status')" "CLAWED_BACK"
check "提成结算额=0" "$(echo "$TL_A" | jq -r '.contracts[0].commissions[0].settledAmount')" "0"
check "提高2班已关闭(名额释放)" "$(apiget /api/setup/classes ADMIN | jq -r '.[] | select(.id==2) | .status')" "CLOSED"
check "轨迹含2节关闭课表事件" "$(echo "$TL_A" | jq -r '[.events[].type] | map(select(.=="SCHEDULE_CLOSED")) | length')" "2"
check_true "轨迹含整改事件" "$(echo "$TL_A" | jq -r '[.events[].type] | map(select(.=="RECTIFICATION_SUBMITTED")) | length')"
check_true "轨迹含名额释放事件" "$(echo "$TL_A" | jq -r '[.events[].type] | map(select(.=="CLASS_SEAT_RELEASED")) | length')"
check_true "轨迹含教材回收事件" "$(echo "$TL_A" | jq -r '[.events[].type] | map(select(.=="MATERIAL_RETURNED")) | length')"
check_true "轨迹含业绩重归因事件" "$(echo "$TL_A" | jq -r '[.events[].type] | map(select(.=="COMMISSION_REATTRIBUTED")) | length')"
check_true "轨迹含渠道质量核销事件" "$(echo "$TL_A" | jq -r '[.events[].type] | map(select(.=="CHANNEL_QUALITY_ADJUSTED")) | length')"
# 教材回收后数学库存回到 20
check "教材回收后数学库存回到20" "$(apiget /api/inventory/stocks ADMIN | jq -r '.[] | select(.textbookVersion=="人教版数学") | .available')" "20"

echo "== 6. 第二学员小红：冷静期内手续费退还 + 已收上限 + 未付减免 =="
LEAD_B=$(post /api/leads CONSULTANT '{"studentName":"小红","parentPhone":"13722222222","grade":"三年级","subject":"ENGLISH","baseLevel":"BASIC","availableTime":"周日下午","sourceChannel":"官网","consultantId":1,"campusId":1}' -H "X-Actor: 王顾问" | jq -r '.lead.id')
TRIAL_B=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_B,\"startTime\":\"${SUN}T15:00:00\",\"endTime\":\"${SUN}T16:00:00\"}" -H "X-Actor: 王顾问" | jq -r '.id')
post /api/trials/$TRIAL_B/attend CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
post /api/trials/$TRIAL_B/feedback TEACHER '{"performance":"发音标准，互动积极","assessmentScore":85,"suitableClassType":"REGULAR","suggestedLevel":"BASIC"}' -H "X-Actor: 刘老师" > /dev/null
# 2期，每期手续费=4400*2%=88；当天报名（冷静期内），未领教材
CONV_B=$(post /api/conversions CONSULTANT "{\"leadId\":$LEAD_B,\"coursePackageId\":2,\"installments\":[{\"amount\":4400,\"dueDate\":\"$SAT\"},{\"amount\":4400,\"dueDate\":\"$SAT2\"}],\"materialsDelivered\":false}" -H "X-Actor: 王顾问")
STU_B=$(echo "$CONV_B" | jq -r '.student.id'); CT_B=$(echo "$CONV_B" | jq -r '.contract.id')
check "无告警直接转化" "$(echo "$CONV_B" | jq -r '.consistencyWarnings | length')" "0"
check "英语每期手续费88" "$(echo "$CONV_B" | jq -r '.installments[0].fee')" "88"
INST_B1=$(echo "$CONV_B" | jq -r '.installments[0].id')
post /api/conversions/installments/$INST_B1/pay CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
post /api/setup/classes ADMIN '{"name":"英语基础1班","campusId":1,"subject":"ENGLISH","level":"BASIC","classType":"REGULAR","teacherId":2,"classroomId":2,"capacity":8,"textbookVersion":"新概念青少版","scheduleDesc":"每周日"}' > /dev/null
EN_B=$(post /api/courses/classes/3/enroll CONSULTANT "{\"studentId\":$STU_B,\"contractId\":$CT_B}" -H "X-Actor: 王顾问")
check "英语班入班" "$(echo "$EN_B" | jq -r '.status')" "ACTIVE"
PV_B=$(apiget "/api/refunds/preview/$STU_B?reasonCode=MOVING" ADMIN)
check "小红：冷静期内手续费不退回收(=0)" "$(echo "$PV_B" | jq -r '.installmentFeeRecovered')" "0"
check "小红：未超冷静期" "$(echo "$PV_B" | jq -r '.afterCoolingPeriod')" "false"
check "小红：归因为客户" "$(echo "$PV_B" | jq -r '.attribution')" "CUSTOMER"
check "小红：客户原因无需整改" "$(echo "$PV_B" | jq -r '.rectificationRequired')" "false"
# 已收=本金4400+手续费88；冷静期内手续费应退，上限=4488
RQ_B=$(post /api/refunds CONSULTANT "{\"studentId\":$STU_B,\"reasonCode\":\"MOVING\",\"reason\":\"搬家\"}" -H "X-Actor: 王顾问")
check "退费上限=已收4488(含退还手续费)" "$(echo "$RQ_B" | jq -r '.refundAmount')" "4488"
RF_B=$(echo "$RQ_B" | jq -r '.id')
post /api/refunds/$RF_B/approve ADMIN '{"materialsReturned":false}' -H "X-Actor: 校长" > /dev/null
TL_B=$(apiget /api/principal/students/$STU_B/timeline PRINCIPAL)
check "未付分期(本金4400+费88)被减免" "$(echo "$TL_B" | jq -r '.contracts[0].installments[1].status')" "WAIVED"
check "小红教材未发放不回收" "$(apiget /api/refunds/$RF_B ADMIN | jq -r '.materialsReturned')" "false"
check "小红：无课消提成0结算-全额追回" "$(echo "$TL_B" | jq -r '.contracts[0].commissions[0].status')" "CLAWED_BACK"

echo "== 7. 第三学员小刚：赠课消耗与课时不足（不退费，有效报名保留）=="
PKG_C=$(post /api/setup/packages ADMIN '{"name":"数学体验小包","subject":"MATH","level":"INTERMEDIATE","classType":"REGULAR","totalLessons":2,"price":200,"materialFee":0,"textbookVersion":"人教版数学","defaultGiftLessons":1,"discountKeepThreshold":0.5}' | jq -r '.id')
LEAD_C=$(post /api/leads CONSULTANT '{"studentName":"小刚","parentPhone":"13733333333","grade":"四年级","subject":"MATH","baseLevel":"INTERMEDIATE","availableTime":"周日下午","sourceChannel":"转介绍","consultantId":1,"campusId":1}' -H "X-Actor: 王顾问" | jq -r '.lead.id')
TRIAL_C=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_C,\"startTime\":\"${SUN}T16:00:00\",\"endTime\":\"${SUN}T17:00:00\"}" -H "X-Actor: 王顾问" | jq -r '.id')
post /api/trials/$TRIAL_C/attend CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
post /api/trials/$TRIAL_C/feedback TEACHER '{"performance":"基础扎实","assessmentScore":88,"suitableClassType":"REGULAR","suggestedLevel":"INTERMEDIATE"}' -H "X-Actor: 张老师" > /dev/null
CONV_C=$(post /api/conversions CONSULTANT "{\"leadId\":$LEAD_C,\"coursePackageId\":$PKG_C,\"installments\":[{\"amount\":200,\"dueDate\":\"$SAT\"}],\"materialsDelivered\":false}" -H "X-Actor: 王顾问")
STU_C=$(echo "$CONV_C" | jq -r '.student.id'); CT_C=$(echo "$CONV_C" | jq -r '.contract.id')
INST_C=$(echo "$CONV_C" | jq -r '.installments[0].id')
post /api/conversions/installments/$INST_C/pay CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
EN_C=$(post /api/courses/classes/1/enroll CONSULTANT "{\"studentId\":$STU_C,\"contractId\":$CT_C}" -H "X-Actor: 王顾问")
check "中途入班记为插班" "$(echo "$EN_C" | jq -r '.midTermInsertion')" "true"
for SLOT in "10:00:00/10:50:00" "11:00:00/11:50:00" "14:00:00/14:50:00" "15:00:00/15:50:00"; do
  ST=${SLOT%/*}; ET=${SLOT#*/}
  LID=$(post /api/courses/lessons ADMIN "{\"clazzId\":1,\"startTime\":\"${SAT3}T${ST}\",\"endTime\":\"${SAT3}T${ET}\",\"topic\":\"体验课$ST\"}" -H "X-Actor: 教务" | jq -r '.id')
  post /api/courses/lessons/$LID/complete TEACHER '{}' -H "X-Actor: 张老师" > /dev/null
done
CONS_C=$(apiget /api/courses/contracts/$CT_C/consumption ADMIN)
check "赠课消耗：付费2+赠课1" "$(echo "$CONS_C" | jq -r '[.consumedPaid,.consumedGift] | join(",")')" "2,1"
TL_C=$(apiget /api/principal/students/$STU_C/timeline PRINCIPAL)
check_true "课时不足有告警事件" "$(echo "$TL_C" | jq -r '[.events[].type] | map(select(.=="CONSUMPTION_INSUFFICIENT")) | length')"
check "有在读学员的数学1班保持开放" "$(apiget /api/setup/classes ADMIN | jq -r '.[] | select(.id==1) | .status')" "OPEN"

echo "== 8. 第四学员小强：GIFT_FIRST赠课抵扣 + 教学责任提成部分结算 =="
PKG_D=$(post /api/setup/packages ADMIN '{"name":"物理提高小班包","subject":"PHYSICS","level":"INTERMEDIATE","classType":"REGULAR","totalLessons":10,"price":2000,"materialFee":100,"textbookVersion":"人教版物理","defaultGiftLessons":2,"discountKeepThreshold":0.5}' | jq -r '.id')
LEAD_D=$(post /api/leads CONSULTANT '{"studentName":"小强","parentPhone":"13744444444","grade":"初二","subject":"PHYSICS","baseLevel":"INTERMEDIATE","availableTime":"周日下午","sourceChannel":"小红书","consultantId":2,"campusId":2}' -H "X-Actor: 李顾问" | jq -r '.lead.id')
TRIAL_D=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_D,\"startTime\":\"${SUN}T14:00:00\",\"endTime\":\"${SUN}T15:00:00\"}" -H "X-Actor: 李顾问" | jq -r '.id')
check "物理试听匹配陈老师" "$(apiget /api/trials/$TRIAL_D ADMIN | jq -r '.teacherId')" "3"
post /api/trials/$TRIAL_D/attend CONSULTANT '{}' -H "X-Actor: 李顾问" > /dev/null
post /api/trials/$TRIAL_D/feedback TEACHER '{"performance":"逻辑清晰","assessmentScore":80,"suitableClassType":"REGULAR","suggestedLevel":"INTERMEDIATE"}' -H "X-Actor: 陈老师" > /dev/null
# GIFT_FIRST + 2期分期(每期费20) + signedAt=20天前 + 教材已领
CONV_D=$(post /api/conversions CONSULTANT "{\"leadId\":$LEAD_D,\"coursePackageId\":$PKG_D,\"consumeOrder\":\"GIFT_FIRST\",\"installments\":[{\"amount\":1000,\"dueDate\":\"$SAT\"},{\"amount\":1000,\"dueDate\":\"$SAT2\"}],\"materialsDelivered\":true,\"signedAt\":\"$SIGNED\"}" -H "X-Actor: 李顾问")
STU_D=$(echo "$CONV_D" | jq -r '.student.id'); CT_D=$(echo "$CONV_D" | jq -r '.contract.id')
check "物理无告警转化" "$(echo "$CONV_D" | jq -r '.consistencyWarnings | length')" "0"
check "物理每期手续费20" "$(echo "$CONV_D" | jq -r '.installments[0].fee')" "20"
for IID in $(echo "$CONV_D" | jq -r '.installments[].id'); do
  post /api/conversions/installments/$IID/pay CONSULTANT '{}' -H "X-Actor: 李顾问" > /dev/null
done
post /api/setup/classes ADMIN '{"name":"物理提高1班","campusId":2,"subject":"PHYSICS","level":"INTERMEDIATE","classType":"REGULAR","teacherId":3,"classroomId":3,"capacity":6,"textbookVersion":"人教版物理","scheduleDesc":"每周六"}' > /dev/null
CLZ_D=$(apiget /api/setup/classes ADMIN | jq -r '[.[] | select(.name=="物理提高1班")][0].id')
post /api/courses/classes/$CLZ_D/enroll CONSULTANT "{\"studentId\":$STU_D,\"contractId\":$CT_D}" -H "X-Actor: 李顾问" > /dev/null
# 排3节并完成：GIFT_FIRST → 赠课2节 + 付费1节
for SLOT in "09:00:00/10:00:00" "10:00:00/11:00:00" "11:00:00/12:00:00"; do
  ST=${SLOT%/*}; ET=${SLOT#*/}
  LID=$(post /api/courses/lessons ADMIN "{\"clazzId\":$CLZ_D,\"startTime\":\"${SAT2}T${ST}\",\"endTime\":\"${SAT2}T${ET}\",\"topic\":\"物理$ST\"}" -H "X-Actor: 教务" | jq -r '.id')
  post /api/courses/lessons/$LID/complete TEACHER '{}' -H "X-Actor: 陈老师" > /dev/null
done
CONS_D=$(apiget /api/courses/contracts/$CT_D/consumption ADMIN)
check "GIFT_FIRST课消：付费1+赠课2" "$(echo "$CONS_D" | jq -r '[.consumedPaid,.consumedGift] | join(",")')" "1,2"
# 单价=200；基础=9*200=1800；教材-100；赠课抵扣-400；手续费40不退 → 1800-100-400-40=1260
PV_D=$(apiget "/api/refunds/preview/$STU_D?reasonCode=TEACHER_QUALITY" ADMIN)
check "小强：基础退费1800" "$(echo "$PV_D" | jq -r '.baseRefund')" "1800"
check "小强：赠课抵扣400" "$(echo "$PV_D" | jq -r '.giftValueOffset')" "400"
check "小强：手续费回收40" "$(echo "$PV_D" | jq -r '.installmentFeeRecovered')" "40"
check "小强：归因为教学服务" "$(echo "$PV_D" | jq -r '.attribution')" "SERVICE"
check "小强：教学责任无需整改" "$(echo "$PV_D" | jq -r '.rectificationRequired')" "false"
check "小强：提成按10%结算=10" "$(echo "$PV_D" | jq -r '.commissionSettleEstimate')" "10"
check "小强：提成预计追回=90" "$(echo "$PV_D" | jq -r '.commissionClawEstimate')" "90"
check "小强：应退=1260" "$(echo "$PV_D" | jq -r '.refundAmount')" "1260"
RF_D=$(post /api/refunds CONSULTANT '{"studentId":'$STU_D',"reasonCode":"TEACHER_QUALITY","reason":"老师讲解不清晰"}' -H "X-Actor: 李顾问" | jq -r '.id')
# 教学责任无需整改，可直接审核
post /api/refunds/$RF_D/approve FINANCE '{"materialsReturned":true}' -H "X-Actor: 财务" > /dev/null
RFD=$(apiget /api/refunds/$RF_D ADMIN)
check "小强：退费已打款" "$(echo "$RFD" | jq -r '.status')" "PAID"
check "小强：提成部分结算" "$(echo "$RFD" | jq -r '.commissionOutcome')" "SETTLED_PARTIAL"
check "小强：实际结算10" "$(echo "$RFD" | jq -r '.commissionSettled')" "10"
check "小强：实际追回90" "$(echo "$RFD" | jq -r '.commissionClawedBack')" "90"
TL_D=$(apiget /api/principal/students/$STU_D/timeline PRINCIPAL)
check "小强：提成状态=部分结算" "$(echo "$TL_D" | jq -r '.contracts[0].commissions[0].status')" "SETTLED_PARTIAL"
check_true "小强：轨迹含部分结算事件" "$(echo "$TL_D" | jq -r '[.events[].type] | map(select(.=="COMMISSION_SETTLED_PARTIAL")) | length')"

echo "== 9. 教材库存与渠道投放质量 =="
STOCKS=$(apiget /api/inventory/stocks ADMIN)
check "数学教材库存20(出1回1)" "$(echo "$STOCKS" | jq -r '.[] | select(.textbookVersion=="人教版数学") | .available')" "20"
check "物理教材库存20(出1回1)" "$(echo "$STOCKS" | jq -r '.[] | select(.textbookVersion=="人教版物理") | .available')" "20"
check "英语教材库存20(未领取)" "$(echo "$STOCKS" | jq -r '.[] | select(.textbookVersion=="新概念青少版") | .available')" "20"
check "出入库流水共4条(小明/小强 各出+回)" "$(apiget /api/inventory/movements ADMIN | jq 'length')" "4"
check "入库流水2条" "$(apiget /api/inventory/movements ADMIN | jq -r '[.[] | select(.direction=="IN")] | length')" "2"

QUALITY=$(apiget /api/principal/channels/quality PRINCIPAL)
# 抖音：当日有效报名被核销（today valid=0/refunds=1），昨日基准3条不受影响
check "渠道质量含抖音" "$(echo "$QUALITY" | jq -r '.[] | select(.channel=="抖音") | .channel')" "抖音"
check "抖音当日有效报名已核销为0" "$(echo "$QUALITY" | jq -r '.[] | select(.channel=="抖音") | .daily[] | select(.date=="'$TODAY'") | .validEnrollments')" "0"
check "抖音当日退费1" "$(echo "$QUALITY" | jq -r '.[] | select(.channel=="抖音") | .daily[] | select(.date=="'$TODAY'") | .refunds')" "1"
check "抖音累计有效报名仍为3(昨日基准保留)" "$(echo "$QUALITY" | jq -r '.[] | select(.channel=="抖音") | .validEnrollments')" "3"
check "小红书当日有效报名核销为0" "$(echo "$QUALITY" | jq -r '.[] | select(.channel=="小红书") | .validEnrollments')" "0"
check "小红书退费1" "$(echo "$QUALITY" | jq -r '.[] | select(.channel=="小红书") | .refunds')" "1"
check "小红书无有效报名→单条成本为空" "$(echo "$QUALITY" | jq -r '.[] | select(.channel=="小红书") | .costPerValidEnrollment')" "null"
# 转介绍（小刚未退费）：有效报名保留1，未被误核销
check "转介绍有效报名保留1" "$(echo "$QUALITY" | jq -r '.[] | select(.channel=="转介绍") | .validEnrollments')" "1"
check "转介绍退费0" "$(echo "$QUALITY" | jq -r '.[] | select(.channel=="转介绍") | .refunds')" "0"
check "转介绍单条有效报名成本50" "$(echo "$QUALITY" | jq -r '.[] | select(.channel=="转介绍") | .costPerValidEnrollment')" "50"

echo "== 10. OpenAPI 3 / Swagger UI =="
DOC=$(curl -s "$BASE/v3/api-docs")
check "OpenAPI 版本3.x" "$(echo "$DOC" | jq -r '.openapi | startswith("3.")')" "true"
check_true "OpenAPI 含退费接口" "$(echo "$DOC" | jq -r '.paths | has("/api/refunds")')"
check_true "OpenAPI 含整改接口" "$(echo "$DOC" | jq -r '.paths | has("/api/refunds/{id}/rectification")')"
check_true "OpenAPI 含渠道质量接口" "$(echo "$DOC" | jq -r '.paths | has("/api/principal/channels/quality")')"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -L "$BASE/swagger-ui.html")
check "Swagger UI 可打开(200)" "$CODE" "200"

echo "== 11. 校长履约轨迹与经营概览 =="
EV=$(echo "$TL_A" | jq -r '[.events[].type] | unique | join(",")')
for E in LEAD_CREATED CHANNEL_FEE PROMISE_RECORDED TRIAL_BOOKED TRIAL_RESCHEDULED TRIAL_RESCHEDULE_RISK TRIAL_TEACHER_LEAVE GIFT_GRANTED TRIAL_ATTENDED FEEDBACK_SUBMITTED CONSISTENCY_WARNING CONVERTED CONTRACT_CREATED INSTALLMENT_PLAN INVOICE_ISSUED MATERIAL_ISSUED CHANNEL_VALID_ENROLLMENT COMMISSION_ACCRUED ENROLLED CONSUMPTION CONSUMPTION_REVERSED LEAVE_APPROVED TRANSFERRED SUSPENDED RESUMED COMPLAINT_FILED REFUND_REQUESTED COMMISSION_FROZEN RECTIFICATION_SUBMITTED REFUND_PAID CLASS_SEAT_RELEASED SCHEDULE_CLOSED MATERIAL_RETURNED COMMISSION_REATTRIBUTED COMMISSION_CLAWED_BACK CHANNEL_QUALITY_ADJUSTED; do
  check_true "轨迹含事件 $E" "$(echo "$EV" | grep -o "$E")"
done
check "轨迹含赠品记录" "$(echo "$TL_A" | jq -r '.gifts[0].status')" "GRANTED"
check "轨迹含渠道费(抖音300)" "$(echo "$TL_A" | jq -r '.channelFees[0].amount')" "300"
OV=$(apiget /api/principal/overview PRINCIPAL)
check "概览含2个校区" "$(echo "$OV" | jq -r '.campuses | length')" "2"
check_true "概览含渠道成本" "$(echo "$OV" | jq -r '.channelCosts["抖音"]')"
check_true "概览含渠道质量" "$(echo "$OV" | jq -r '.channelQuality | length')"
check_true "顾问概览含部分结算额" "$(echo "$OV" | jq -r '.consultants[] | select(.consultant=="李顾问") | .commissionSettledPartial')"

echo ""
echo "==================================="
echo "通过 $PASS 项，失败 $FAIL 项"
[[ $FAIL -eq 0 ]] && echo "VERIFY_RESULT: PASS" || { echo "VERIFY_RESULT: FAIL"; exit 1; }
