#!/usr/bin/env bash
# 端到端验证：线索 → 试听排班 → 反馈 → 报名转化 → 排课课消 → 请假/转班/停课 → 退费 → 校长履约轨迹
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
get() { curl -s "$BASE$1" -H "X-Role: $2"; }

SAT=$(date -d "next Saturday" +%F)      # 老师空档：周末 09:00-18:00
SUN=$(date -d "next Sunday" +%F)
SAT2=$(date -d "$SAT +7 days" +%F)
SAT3=$(date -d "$SAT +14 days" +%F)

echo "== 0. 鉴权 =="
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/setup/campuses")
check "缺少 X-Role 返回 401" "$CODE" "401"
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/principal/overview" -H "X-Role: CONSULTANT")
check "顾问访问校长视图返回 403" "$CODE" "403"

echo "== 1. 线索（幂等 + 渠道费 + 家长承诺）=="
LEAD_BODY='{"studentName":"小明","parentPhone":"13711111111","grade":"四年级","subject":"MATH","baseLevel":"INTERMEDIATE","availableTime":"周六上午","sourceChannel":"抖音","consultantId":1,"campusId":1,"joinedActivity":true}'
R1=$(post /api/leads CONSULTANT "$LEAD_BODY" -H "X-Actor: 王顾问" -H "X-Idempotency-Key: lead-xm-001")
LEAD_A=$(echo "$R1" | jq -r '.lead.id')
check "线索创建成功" "$(echo "$R1" | jq -r '.lead.status')" "NEW"
check "首次创建非幂等重放" "$(echo "$R1" | jq -r '.idempotentReplay')" "false"
R1B=$(post /api/leads CONSULTANT "$LEAD_BODY" -H "X-Actor: 王顾问" -H "X-Idempotency-Key: lead-xm-001")
check "相同幂等键重放返回同一线索" "$(echo "$R1B" | jq -r '.lead.id')" "$LEAD_A"
check "重放标记为幂等" "$(echo "$R1B" | jq -r '.idempotentReplay')" "true"
post /api/leads/$LEAD_A/promises CONSULTANT '{"content":"保过期末数学90分","promisedBy":"王顾问"}' -H "X-Actor: 王顾问" > /dev/null
check "家长承诺登记" "$(get /api/leads/$LEAD_A ADMIN | jq -r '.status')" "NEW"

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
check "线索回到可预约状态" "$(get /api/leads/$LEAD_A ADMIN | jq -r '.status')" "NEW"

# 缺席流程（线索X）：预约→缺席→回到NEW
TRIAL_X=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_X,\"startTime\":\"${SAT}T10:00:00\",\"endTime\":\"${SAT}T11:00:00\"}" -H "X-Actor: 王顾问" | jq -r '.id')
ABS=$(post /api/trials/$TRIAL_X/absent CONSULTANT '{}' -H "X-Actor: 王顾问")
check "缺席标记" "$(echo "$ABS" | jq -r '.status')" "ABSENT"
check "缺席后线索可重新预约" "$(get /api/leads/$LEAD_X ADMIN | jq -r '.status')" "NEW"

# 重新预约 → 到课 → 参加活动发赠品
TRIAL_A2=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_A,\"startTime\":\"${SUN}T10:00:00\",\"endTime\":\"${SUN}T11:00:00\"}" -H "X-Actor: 王顾问" | jq -r '.id')
post /api/trials/$TRIAL_A2/attend CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
check "到课后状态" "$(get /api/trials/$TRIAL_A2 ADMIN | jq -r '.status')" "ATTENDED"

echo "== 3. 试听反馈与转化一致性校验 =="
FB=$(post /api/trials/$TRIAL_A2/feedback TEACHER '{"performance":"课堂专注度一般，计算能力强但概念薄弱","assessmentScore":62,"suitableClassType":"ONE_ON_ONE","suggestedLevel":"BASIC","riskWarning":"基础薄弱，不建议保分承诺"}' -H "X-Actor: 张老师")
check "反馈提交成功" "$(echo "$FB" | jq -r '.suitableClassType')" "ONE_ON_ONE"
check "线索进入试听完成" "$(get /api/leads/$LEAD_A ADMIN | jq -r '.status')" "TRIAL_DONE"

# 承诺「保过」+ 班型/水平不匹配 → 直接报名应被拦截
CONV_BODY="{\"leadId\":$LEAD_A,\"coursePackageId\":1,\"discountAmount\":600,\"installments\":[{\"amount\":3000,\"dueDate\":\"$SAT\"},{\"amount\":3000,\"dueDate\":\"$SAT2\"},{\"amount\":3000,\"dueDate\":\"$SAT3\"}],\"invoiceTitle\":\"小明家长\",\"promises\":[\"保过期末数学90分\"],\"materialsDelivered\":true}"
BLOCKED=$(post /api/conversions CONSULTANT "$CONV_BODY" -H "X-Actor: 王顾问")
check "承诺与反馈不一致被拦截" "$(echo "$BLOCKED" | jq -r '.error')" "CONSISTENCY_WARNING"
CONV=$(post /api/conversions CONSULTANT "$(echo "$CONV_BODY" | jq -c '. + {forceConfirm:true}')" -H "X-Actor: 王顾问")
STU_A=$(echo "$CONV" | jq -r '.student.id')
CT_A=$(echo "$CONV" | jq -r '.contract.id')
check "强制确认后转化成功" "$(echo "$CONV" | jq -r '.contract.status')" "ACTIVE"
check "一致性告警条数=3" "$(echo "$CONV" | jq -r '.consistencyWarnings | length')" "3"
check "实付金额=9600-600" "$(echo "$CONV" | jq -r '.contract.finalAmount')" "9000"
check "赠课=课包4+额外0" "$(echo "$CONV" | jq -r '.contract.giftLessons')" "4"
check "提成计提=9000*5%" "$(echo "$CONV" | jq -r '.commission.amount')" "450"
check "发票已开" "$(echo "$CONV" | jq -r '.invoice.status')" "ISSUED"
for IID in $(echo "$CONV" | jq -r '.installments[].id'); do
  post /api/conversions/installments/$IID/pay CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
done
check "3期分期全部回款" "$(echo "$CONV" | jq -r '.installments | length')" "3"

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
CONS=$(get /api/courses/contracts/$CT_A/consumption ADMIN)
check "课消：2到课+1缺席=扣3付费课时" "$(echo "$CONS" | jq -r '.consumedPaid')" "3"

# 事后请假批准 → 返还缺席所扣课时
LR=$(post /api/students/$STU_A/leave CONSULTANT "{\"lessonId\":$L3,\"reason\":\"当天发烧\"}" -H "X-Actor: 王顾问" | jq -r '.id')
post /api/students/leave-requests/$LR/approve ADMIN '{}' -H "X-Actor: 教务" > /dev/null
CONS=$(get /api/courses/contracts/$CT_A/consumption ADMIN)
check "请假批准后返还课时(3→2)" "$(echo "$CONS" | jq -r '.consumedPaid')" "2"

# 转班（跨校区：中关村数学提高1班 → 望京数学提高2班）
post /api/setup/classes ADMIN '{"name":"数学提高2班","campusId":2,"subject":"MATH","level":"INTERMEDIATE","classType":"REGULAR","teacherId":3,"classroomId":3,"capacity":6,"textbookVersion":"人教版数学","scheduleDesc":"每周日"}' > /dev/null
TR=$(post /api/students/$STU_A/transfer CONSULTANT '{"toClazzId":2,"reason":"家搬到望京，换校区"}' -H "X-Actor: 王顾问")
check "跨校区转班成功" "$(echo "$TR" | jq -r '.toClazzId')" "2"

# 停课 → 复课
post /api/students/$STU_A/suspend CONSULTANT "{\"reason\":\"出国游学\",\"startDate\":\"$SAT3\",\"endDate\":\"$SAT3\"}" -H "X-Actor: 王顾问" > /dev/null
check "停课状态" "$(get /api/principal/students/$STU_A/timeline PRINCIPAL | jq -r '.student.status')" "SUSPENDED"
post /api/students/$STU_A/resume CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
check "复课状态" "$(get /api/principal/students/$STU_A/timeline PRINCIPAL | jq -r '.student.status')" "ACTIVE"

echo "== 5. 退费（投诉赔付 + 优惠回收 + 教材费 + 分期手续费 + 提成冻结）=="
post /api/students/$STU_A/complaints CONSULTANT '{"content":"家长投诉换老师未提前通知"}' -H "X-Actor: 王顾问" > /dev/null
PV=$(get /api/refunds/preview/$STU_A ADMIN)
# 已上2课时，单价=9000/48=187.5，基础退费=46*187.5=8625；教材费-300；优惠回收-600；分期手续费-180(9000×2%)；投诉赔付+200 → 7745
check "试算：已上付费课时=2" "$(echo "$PV" | jq -r '.consumedPaidLessons')" "2"
check "试算：课时单价=187.5" "$(echo "$PV" | jq -r '.unitPrice')" "187.5"
check "试算：基础退费=8625" "$(echo "$PV" | jq -r '.baseRefund')" "8625"
check "试算：教材费扣300" "$(echo "$PV" | jq -r '.materialFeeDeducted')" "300"
check "试算：优惠回收600" "$(echo "$PV" | jq -r '.discountRecovered')" "600"
check "试算：分期手续费扣180" "$(echo "$PV" | jq -r '.installmentFeeDeducted')" "180"
check "试算：投诉赔付+200" "$(echo "$PV" | jq -r '.complaintCompensation')" "200"
check "试算：提成冻结450" "$(echo "$PV" | jq -r '.commissionFrozen')" "450"
check "试算：应退=7745" "$(echo "$PV" | jq -r '.refundAmount')" "7745"

RQ=$(post /api/refunds CONSULTANT "{\"studentId\":$STU_A,\"reason\":\"家长对服务不满意\"}" -H "X-Actor: 王顾问" -H "X-Idempotency-Key: refund-xm-001")
RF_A=$(echo "$RQ" | jq -r '.id')
check "退费申请创建" "$(echo "$RQ" | jq -r '.status')" "REQUESTED"
RQ2=$(post /api/refunds CONSULTANT "{\"studentId\":$STU_A,\"reason\":\"重复提交\"}" -H "X-Actor: 王顾问" -H "X-Idempotency-Key: refund-xm-001")
check "退费申请幂等(同一单据)" "$(echo "$RQ2" | jq -r '.id')" "$RF_A"
post /api/refunds/$RF_A/approve PRINCIPAL '{}' -H "X-Actor: 校长" > /dev/null
RF=$(get /api/refunds/$RF_A ADMIN)
check "退费已打款" "$(echo "$RF" | jq -r '.status')" "PAID"
check "退费金额=7745" "$(echo "$RF" | jq -r '.refundAmount')" "7745"
TL_A=$(get /api/principal/students/$STU_A/timeline PRINCIPAL)
check "退费后学员状态" "$(echo "$TL_A" | jq -r '.student.status')" "REFUNDED"
check "合同状态=REFUNDED" "$(echo "$TL_A" | jq -r '.contracts[0].status')" "REFUNDED"
check "提成已追回" "$(echo "$TL_A" | jq -r '.contracts[0].commissions[0].status')" "CLAWED_BACK"

echo "== 6. 第二学员：分期上限与减免 =="
LEAD_B=$(post /api/leads CONSULTANT '{"studentName":"小红","parentPhone":"13722222222","grade":"三年级","subject":"ENGLISH","baseLevel":"BASIC","availableTime":"周日下午","sourceChannel":"官网","consultantId":1,"campusId":1}' -H "X-Actor: 王顾问" | jq -r '.lead.id')
TRIAL_B=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_B,\"startTime\":\"${SUN}T14:00:00\",\"endTime\":\"${SUN}T15:00:00\"}" -H "X-Actor: 王顾问" | jq -r '.id')
post /api/trials/$TRIAL_B/attend CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
post /api/trials/$TRIAL_B/feedback TEACHER '{"performance":"发音标准，互动积极","assessmentScore":85,"suitableClassType":"REGULAR","suggestedLevel":"BASIC"}' -H "X-Actor: 刘老师" > /dev/null
CONV_B=$(post /api/conversions CONSULTANT "{\"leadId\":$LEAD_B,\"coursePackageId\":2,\"installments\":[{\"amount\":4400,\"dueDate\":\"$SAT\"},{\"amount\":4400,\"dueDate\":\"$SAT2\"}],\"materialsDelivered\":false}" -H "X-Actor: 王顾问")
STU_B=$(echo "$CONV_B" | jq -r '.student.id'); CT_B=$(echo "$CONV_B" | jq -r '.contract.id')
check "无告警直接转化" "$(echo "$CONV_B" | jq -r '.consistencyWarnings | length')" "0"
INST_B1=$(echo "$CONV_B" | jq -r '.installments[0].id')
post /api/conversions/installments/$INST_B1/pay CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
post /api/setup/classes ADMIN '{"name":"英语基础1班","campusId":1,"subject":"ENGLISH","level":"BASIC","classType":"REGULAR","teacherId":2,"classroomId":2,"capacity":8,"textbookVersion":"新概念青少版","scheduleDesc":"每周日"}' > /dev/null
EN_B=$(post /api/courses/classes/3/enroll CONSULTANT "{\"studentId\":$STU_B,\"contractId\":$CT_B}" -H "X-Actor: 王顾问")
check "英语班入班" "$(echo "$EN_B" | jq -r '.status')" "ACTIVE"
# 只回款4400 → 退费以已收为上限；未付第二期减免
RQ_B=$(post /api/refunds CONSULTANT "{\"studentId\":$STU_B,\"reason\":\"搬家\"}" -H "X-Actor: 王顾问")
check "退费上限=已收4400" "$(echo "$RQ_B" | jq -r '.refundAmount')" "4400"
RF_B=$(echo "$RQ_B" | jq -r '.id')
post /api/refunds/$RF_B/approve PRINCIPAL '{}' -H "X-Actor: 校长" > /dev/null
TL_B=$(get /api/principal/students/$STU_B/timeline PRINCIPAL)
check "未付分期被减免" "$(echo "$TL_B" | jq -r '.contracts[0].installments[1].status')" "WAIVED"

echo "== 7. 第三学员：赠课消耗与课时不足 =="
PKG_C=$(post /api/setup/packages ADMIN '{"name":"数学体验小包","subject":"MATH","level":"INTERMEDIATE","classType":"REGULAR","totalLessons":2,"price":200,"materialFee":0,"textbookVersion":"人教版数学","defaultGiftLessons":1,"discountKeepThreshold":0.5}' | jq -r '.id')
LEAD_C=$(post /api/leads CONSULTANT '{"studentName":"小刚","parentPhone":"13733333333","grade":"四年级","subject":"MATH","baseLevel":"INTERMEDIATE","availableTime":"周日下午","sourceChannel":"转介绍","consultantId":1,"campusId":1}' -H "X-Actor: 王顾问" | jq -r '.lead.id')
TRIAL_C=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_C,\"startTime\":\"${SUN}T16:00:00\",\"endTime\":\"${SUN}T17:00:00\"}" -H "X-Actor: 王顾问" | jq -r '.id')
post /api/trials/$TRIAL_C/attend CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
post /api/trials/$TRIAL_C/feedback TEACHER '{"performance":"基础扎实","assessmentScore":88,"suitableClassType":"REGULAR","suggestedLevel":"INTERMEDIATE"}' -H "X-Actor: 张老师" > /dev/null
CONV_C=$(post /api/conversions CONSULTANT "{\"leadId\":$LEAD_C,\"coursePackageId\":$PKG_C,\"installments\":[{\"amount\":200,\"dueDate\":\"$SAT\"}]}" -H "X-Actor: 王顾问")
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
CONS_C=$(get /api/courses/contracts/$CT_C/consumption ADMIN)
check "赠课消耗：付费2+赠课1" "$(echo "$CONS_C" | jq -r '[.consumedPaid,.consumedGift] | join(",")')" "2,1"
TL_C=$(get /api/principal/students/$STU_C/timeline PRINCIPAL)
check_true "课时不足有告警事件" "$(echo "$TL_C" | jq -r '[.events[].type] | map(select(.=="CONSUMPTION_INSUFFICIENT")) | length')"

echo "== 8. 校长履约轨迹与经营概览 =="
EV=$(echo "$TL_A" | jq -r '[.events[].type] | unique | join(",")')
for E in LEAD_CREATED CHANNEL_FEE PROMISE_RECORDED TRIAL_BOOKED TRIAL_RESCHEDULED TRIAL_RESCHEDULE_RISK TRIAL_TEACHER_LEAVE GIFT_GRANTED TRIAL_ATTENDED FEEDBACK_SUBMITTED CONSISTENCY_WARNING CONVERTED CONTRACT_CREATED INSTALLMENT_PAID INVOICE_ISSUED COMMISSION_ACCRUED ENROLLED CONSUMPTION CONSUMPTION_REVERSED LEAVE_APPROVED TRANSFERRED SUSPENDED RESUMED COMPLAINT_FILED REFUND_REQUESTED COMMISSION_FROZEN REFUND_PAID COMMISSION_CLAWED_BACK; do
  check_true "轨迹含事件 $E" "$(echo "$EV" | grep -o "$E")"
done
check "轨迹含赠品记录" "$(echo "$TL_A" | jq -r '.gifts[0].status')" "GRANTED"
check "轨迹含渠道费(抖音300)" "$(echo "$TL_A" | jq -r '.channelFees[0].amount')" "300"
OV=$(get /api/principal/overview PRINCIPAL)
check "概览含2个校区" "$(echo "$OV" | jq -r '.campuses | length')" "2"
check_true "概览含渠道成本" "$(echo "$OV" | jq -r '.channelCosts["抖音"]')"

echo "== 9. 退费扩展：分期手续费/业绩归因/校区整改/名额释放/排课减少/教材回收/渠道质量 =="
STOCK0=$(get /api/setup/material-stock ADMIN | jq -r '[.[] | select(.campusId==1 and .textbookVersion=="人教版数学")][0].quantity')
LEAD_D=$(post /api/leads CONSULTANT '{"studentName":"小华","parentPhone":"13744444444","grade":"五年级","subject":"MATH","baseLevel":"INTERMEDIATE","availableTime":"周日下午","sourceChannel":"小红书","consultantId":1,"campusId":1}' -H "X-Actor: 王顾问" | jq -r '.lead.id')
TRIAL_D=$(post /api/trials/book CONSULTANT "{\"leadId\":$LEAD_D,\"startTime\":\"${SUN}T17:00:00\",\"endTime\":\"${SUN}T18:00:00\"}" -H "X-Actor: 王顾问" | jq -r '.id')
post /api/trials/$TRIAL_D/attend CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
post /api/trials/$TRIAL_D/feedback TEACHER '{"performance":"思维活跃，冲刺潜力大","assessmentScore":90,"suitableClassType":"REGULAR","suggestedLevel":"INTERMEDIATE"}' -H "X-Actor: 张老师" > /dev/null
# 优惠1200 → 实付8400；2期分期 → 分期手续费=8400×2%=168；教材发放出库
CONV_D=$(post /api/conversions CONSULTANT "{\"leadId\":$LEAD_D,\"coursePackageId\":1,\"discountAmount\":1200,\"installments\":[{\"amount\":4200,\"dueDate\":\"$SAT\"},{\"amount\":4200,\"dueDate\":\"$SAT2\"}],\"materialsDelivered\":true}" -H "X-Actor: 王顾问")
STU_D=$(echo "$CONV_D" | jq -r '.student.id'); CT_D=$(echo "$CONV_D" | jq -r '.contract.id')
check "无告警直接转化(小华)" "$(echo "$CONV_D" | jq -r '.consistencyWarnings | length')" "0"
check "合同分期手续费=168" "$(echo "$CONV_D" | jq -r '.contract.installmentFee == 168')" "true"
STOCK1=$(get /api/setup/material-stock ADMIN | jq -r '[.[] | select(.campusId==1 and .textbookVersion=="人教版数学")][0].quantity')
check "教材出库：库存-1" "$([ "$STOCK1" -eq "$((STOCK0-1))" ] && echo ok)" "ok"
for IID in $(echo "$CONV_D" | jq -r '.installments[].id'); do
  post /api/conversions/installments/$IID/pay CONSULTANT '{}' -H "X-Actor: 王顾问" > /dev/null
done

# 1对1冲刺班（容量1）：上2节课，另排2节未上
post /api/setup/classes ADMIN '{"name":"数学1对1冲刺班","campusId":1,"subject":"MATH","level":"INTERMEDIATE","classType":"ONE_ON_ONE","teacherId":1,"classroomId":2,"capacity":1,"textbookVersion":"人教版数学","scheduleDesc":"预约制"}' > /dev/null
EN_D=$(post /api/courses/classes/4/enroll CONSULTANT "{\"studentId\":$STU_D,\"contractId\":$CT_D}" -H "X-Actor: 王顾问")
check "小华入班1对1" "$(echo "$EN_D" | jq -r '.status')" "ACTIVE"
LD1=$(post /api/courses/lessons ADMIN "{\"clazzId\":4,\"startTime\":\"${SAT3}T18:00:00\",\"endTime\":\"${SAT3}T19:00:00\",\"topic\":\"冲刺专题1\"}" -H "X-Actor: 教务" | jq -r '.id')
LD2=$(post /api/courses/lessons ADMIN "{\"clazzId\":4,\"startTime\":\"${SAT3}T19:00:00\",\"endTime\":\"${SAT3}T20:00:00\",\"topic\":\"冲刺专题2\"}" -H "X-Actor: 教务" | jq -r '.id')
LD3=$(post /api/courses/lessons ADMIN "{\"clazzId\":4,\"startTime\":\"${SAT3}T20:00:00\",\"endTime\":\"${SAT3}T21:00:00\",\"topic\":\"冲刺专题3\"}" -H "X-Actor: 教务" | jq -r '.id')
LD4=$(post /api/courses/lessons ADMIN "{\"clazzId\":4,\"startTime\":\"${SAT2}T18:00:00\",\"endTime\":\"${SAT2}T19:00:00\",\"topic\":\"冲刺专题4\"}" -H "X-Actor: 教务" | jq -r '.id')
post /api/courses/lessons/$LD1/complete TEACHER '{}' -H "X-Actor: 张老师" > /dev/null
post /api/courses/lessons/$LD2/complete TEACHER '{}' -H "X-Actor: 张老师" > /dev/null

# 试算：已上2课时，单价=8400/48=175，基础退费=46×175=8050；教材-300；优惠回收-1200；分期手续费-168 → 6382
PV_D=$(get /api/refunds/preview/$STU_D ADMIN)
check "小华试算：基础退费=8050" "$(echo "$PV_D" | jq -r '.baseRefund == 8050')" "true"
check "小华试算：优惠回收=1200" "$(echo "$PV_D" | jq -r '.discountRecovered == 1200')" "true"
check "小华试算：分期手续费=168" "$(echo "$PV_D" | jq -r '.installmentFeeDeducted == 168')" "true"
check "小华试算：应退=6382" "$(echo "$PV_D" | jq -r '.refundAmount == 6382')" "true"
check "小华试算：含报名天数" "$(echo "$PV_D" | jq -r '.daysSinceSign >= 0')" "true"

# 承诺不一致退费：申请即冻结提成
RQ_D=$(post /api/refunds CONSULTANT "{\"studentId\":$STU_D,\"reason\":\"顾问承诺保分未兑现\",\"reasonCategory\":\"PROMISE_MISMATCH\"}" -H "X-Actor: 王顾问")
RF_D=$(echo "$RQ_D" | jq -r '.id')
check "小华退费申请创建" "$(echo "$RQ_D" | jq -r '.status')" "REQUESTED"
check "申请后提成冻结420" "$(get /api/principal/overview PRINCIPAL | jq -r '[.consultants[] | select(.consultant=="王顾问")][0].commissionFrozen == 420')" "true"

# 财务审核通过：归因/整改/名额/排课/教材/渠道全联动
post /api/refunds/$RF_D/approve PRINCIPAL '{}' -H "X-Actor: 校长" > /dev/null
RD=$(get /api/refunds/$RF_D ADMIN)
check "退费单详情：PAID" "$(echo "$RD" | jq -r '.status')" "PAID"
check "退费单详情：应退6382" "$(echo "$RD" | jq -r '.refundAmount == 6382')" "true"
check "退费单详情：归因顾问责任" "$(echo "$RD" | jq -r '.attribution')" "CONSULTANT"
check "退费单详情：原因分类" "$(echo "$RD" | jq -r '.reasonCategory')" "PROMISE_MISMATCH"
check "退费单详情：整改待提交" "$(echo "$RD" | jq -r '.rectification.status')" "PENDING"
check "退费单详情：明细含手续费" "$(echo "$RD" | jq -r '.breakdown.installmentFeeDeducted == 168')" "true"
check "待整改任务=1" "$(get /api/refunds/rectifications/pending ADMIN | jq -r 'length')" "1"
check "未上课时3已取消" "$(get /api/courses/lessons/$LD3 ADMIN | jq -r '.status')" "CANCELLED"
check "未上课时4已取消" "$(get /api/courses/lessons/$LD4 ADMIN | jq -r '.status')" "CANCELLED"
check "已上课时保留" "$(get /api/courses/lessons/$LD1 ADMIN | jq -r '.status')" "COMPLETED"
STOCK2=$(get /api/setup/material-stock ADMIN | jq -r '[.[] | select(.campusId==1 and .textbookVersion=="人教版数学")][0].quantity')
check "教材回收：库存恢复" "$([ "$STOCK2" -eq "$STOCK0" ] && echo ok)" "ok"
TL_D=$(get /api/principal/students/$STU_D/timeline PRINCIPAL)
check "线索标记退费失效" "$(echo "$TL_D" | jq -r '.lead.status')" "REFUNDED"
check "渠道费已反结算" "$(echo "$TL_D" | jq -r '.channelFees[0].settled')" "false"
check "报班记录已退出" "$(echo "$TL_D" | jq -r '.enrollments[0].status')" "DROPPED"
EV_D=$(echo "$TL_D" | jq -r '[.events[].type] | unique | join(",")')
for E in MATERIAL_OUTBOUND REFUND_REQUESTED COMMISSION_FROZEN CLASS_SEAT_RELEASED LESSON_CANCELLED_REFUND REFUND_PAID COMMISSION_CLAWED_BACK REFUND_ATTRIBUTED RECTIFICATION_REQUIRED MATERIAL_RESTOCKED LEAD_INVALIDATED CHANNEL_FEE_REVERSED; do
  check_true "小华轨迹含事件 $E" "$(echo "$EV_D" | grep -o "$E")"
done

# 校区提交整改说明
RECT=$(post /api/refunds/$RF_D/rectification ADMIN '{"content":"已整改：规范顾问承诺话术，全员培训并抽查录音"}' -H "X-Actor: 中关村校区")
check "整改说明提交成功" "$(echo "$RECT" | jq -r '.status')" "SUBMITTED"
check "整改后详情已提交" "$(get /api/refunds/$RF_D ADMIN | jq -r '.rectification.status')" "SUBMITTED"
DUP=$(post /api/refunds/$RF_D/rectification ADMIN '{"content":"重复提交"}' -H "X-Actor: 中关村校区")
check "重复提交整改被拦截" "$(echo "$DUP" | jq -r '.error')" "DUPLICATE"
check "待整改任务清零" "$(get /api/refunds/rectifications/pending ADMIN | jq -r 'length')" "0"

# 经营概览：顾问责任退费 + 渠道投放质量
OV9=$(get /api/principal/overview PRINCIPAL)
check "王顾问责任退费1单" "$(echo "$OV9" | jq -r '[.consultants[] | select(.consultant=="王顾问")][0].responsibleRefunds')" "1"
check "王顾问责任退费6382" "$(echo "$OV9" | jq -r '[.consultants[] | select(.consultant=="王顾问")][0].responsibleRefundAmount == 6382')" "true"
check "小红书无效报名1" "$(echo "$OV9" | jq -r '.channelQuality["小红书"].invalidRefunds')" "1"
check "小红书有效报名0" "$(echo "$OV9" | jq -r '.channelQuality["小红书"].validConversions')" "0"

# 班级名额释放：容量1的1对1班可再入小刚
EN_C2=$(post /api/courses/classes/4/enroll CONSULTANT "{\"studentId\":$STU_C,\"contractId\":$CT_C}" -H "X-Actor: 王顾问")
check "名额释放后小刚可入班" "$(echo "$EN_C2" | jq -r '.status')" "ACTIVE"

echo ""
echo "==================================="
echo "通过 $PASS 项，失败 $FAIL 项"
[[ $FAIL -eq 0 ]] && echo "VERIFY_RESULT: PASS" || { echo "VERIFY_RESULT: FAIL"; exit 1; }
