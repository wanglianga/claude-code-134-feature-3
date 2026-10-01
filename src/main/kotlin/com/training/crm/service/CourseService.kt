package com.training.crm.service

import com.training.crm.common.AuditService
import com.training.crm.common.BusinessException
import com.training.crm.common.LockService
import com.training.crm.common.bizFail
import com.training.crm.domain.*
import com.training.crm.repo.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class CourseService(
    private val clazzRepo: ClazzRepo,
    private val enrollmentRepo: EnrollmentRepo,
    private val lessonRepo: LessonRepo,
    private val attendanceRepo: AttendanceRepo,
    private val contractRepo: ContractRepo,
    private val coursePackageRepo: CoursePackageRepo,
    private val studentRepo: StudentRepo,
    private val teacherRepo: TeacherRepo,
    private val classroomRepo: ClassroomRepo,
    private val trialClassRepo: TrialClassRepo,
    private val lockService: LockService,
    private val auditService: AuditService,
    @Value("\${app.absent-consumes-lesson}") private val absentConsumesLesson: Boolean
) {

    /** 入班/插班：校验班级容量、科目与合同课包一致；开班后中途加入记为插班 */
    @Transactional
    fun enroll(clazzId: Long, studentId: Long, contractId: Long, force: Boolean, actor: String): Enrollment {
        val clazz = clazzRepo.findById(clazzId).orElseThrow { BusinessException("NOT_FOUND", "班级不存在: $clazzId") }
        if (clazz.status != "OPEN") bizFail("INVALID_STATE", "班级已关闭")
        val student = studentRepo.findById(studentId).orElseThrow { BusinessException("NOT_FOUND", "学员不存在: $studentId") }
        if (student.status != StudentStatus.ACTIVE) bizFail("INVALID_STATE", "学员状态(${student.status})不能入班")
        val contract = contractRepo.findById(contractId).orElseThrow { BusinessException("NOT_FOUND", "合同不存在: $contractId") }
        if (contract.studentId != studentId) bizFail("INVALID_STATE", "合同不属于该学员")
        if (contract.status != ContractStatus.ACTIVE) bizFail("INVALID_STATE", "合同状态(${contract.status})不能入班")
        val pkg = coursePackageRepo.findById(contract.coursePackageId).get()
        if (pkg.subject != clazz.subject) bizFail("SUBJECT_MISMATCH", "合同科目(${pkg.subject})与班级科目(${clazz.subject})不一致")
        if (pkg.level != clazz.level && !force) {
            bizFail("LEVEL_MISMATCH", "课包水平(${pkg.level})与班级水平(${clazz.level})不一致，确认插班请带 force=true")
        }
        if (enrollmentRepo.findByStudentIdAndStatus(studentId, EnrollmentStatus.ACTIVE).any { it.clazzId == clazzId }) {
            bizFail("DUPLICATE", "学员已在该班级中")
        }

        return lockService.withLock("clazz:$clazzId") {
            val activeCount = enrollmentRepo.findByClazzIdAndStatus(clazzId, EnrollmentStatus.ACTIVE).size
            if (activeCount >= clazz.capacity) bizFail("CLASS_FULL", "班级容量已满(${clazz.capacity})")
            // 已有上过的课 → 插班
            val midTerm = lessonRepo.findByClazzIdAndStatus(clazzId, LessonStatus.COMPLETED).isNotEmpty()
            val enrollment = enrollmentRepo.save(
                Enrollment(studentId = studentId, clazzId = clazzId, contractId = contractId, midTermInsertion = midTerm)
            )
            // 为已排未上的课补出勤记录
            lessonRepo.findByClazzIdAndStatus(clazzId, LessonStatus.SCHEDULED).forEach { lesson ->
                attendanceRepo.save(Attendance(lessonId = lesson.id, studentId = studentId, contractId = contractId))
            }
            auditService.record(
                "ENROLLED",
                "入班：班级=${clazz.name}(#$clazzId)${if (midTerm) "（插班）" else ""}，合同#$contractId",
                studentId = studentId, actor = actor
            )
            enrollment
        }
    }

    /** 排课：校验老师/教室时段冲突（Redis 锁 + 数据库复核） */
    @Transactional
    fun scheduleLesson(req: LessonScheduleReq, actor: String): Lesson {
        val clazz = clazzRepo.findById(req.clazzId).orElseThrow { BusinessException("NOT_FOUND", "班级不存在: ${req.clazzId}") }
        if (!req.endTime.isAfter(req.startTime)) bizFail("INVALID_TIME", "结束时间必须晚于开始时间")
        val teacherId = clazz.teacherId
        val classroomId = clazz.classroomId

        return lockService.withLock("slot:teacher:$teacherId:${req.startTime}") {
            lockService.withLock("slot:classroom:$classroomId:${req.startTime}") {
                val trialConflict = trialClassRepo.findByTeacherIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
                    teacherId, TrialStatus.SCHEDULED, req.endTime, req.startTime
                )
                val lessonConflict = lessonRepo.findByTeacherIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
                    teacherId, LessonStatus.SCHEDULED, req.endTime, req.startTime
                )
                if (trialConflict.isNotEmpty() || lessonConflict.isNotEmpty()) bizFail("TEACHER_BUSY", "老师在该时段已有安排")
                val roomConflict = lessonRepo.findByClassroomIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
                    classroomId, LessonStatus.SCHEDULED, req.endTime, req.startTime
                ) + trialClassRepo.findByClassroomIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
                    classroomId, TrialStatus.SCHEDULED, req.endTime, req.startTime
                )
                if (roomConflict.isNotEmpty()) bizFail("CLASSROOM_BUSY", "教室在该时段已被占用")

                val lesson = lessonRepo.save(
                    Lesson(
                        clazzId = req.clazzId, teacherId = teacherId, classroomId = classroomId,
                        startTime = req.startTime, endTime = req.endTime, topic = req.topic
                    )
                )
                // 为班内学员生成待出勤记录
                enrollmentRepo.findByClazzIdAndStatus(req.clazzId, EnrollmentStatus.ACTIVE).forEach { en ->
                    attendanceRepo.save(Attendance(lessonId = lesson.id, studentId = en.studentId, contractId = en.contractId))
                }
                auditService.record("LESSON_SCHEDULED", "排课：${clazz.name} ${req.startTime}~${req.endTime}（${req.topic}）", actor = actor)
                lesson
            }
        }
    }

    /**
     * 完成课时并课消：到课扣 1 课时；缺席按机构规则照常扣课时；
     * 已批准请假不扣课时。扣课顺序按合同 consumeOrder（先付费/先赠课）。
     */
    @Transactional
    fun completeLesson(lessonId: Long, absentStudentIds: List<Long>, actor: String): Lesson {
        val lesson = lessonRepo.findById(lessonId).orElseThrow { BusinessException("NOT_FOUND", "课时不存在: $lessonId") }
        if (lesson.status != LessonStatus.SCHEDULED) bizFail("INVALID_STATE", "课时状态(${lesson.status})不能完成")

        attendanceRepo.findByLessonId(lessonId).filter { it.status == AttendStatus.PENDING }.forEach { att ->
            if (att.studentId in absentStudentIds) {
                att.status = AttendStatus.ABSENT
                if (absentConsumesLesson) consume(att, "缺席照常扣课时", actor)
            } else {
                att.status = AttendStatus.ATTENDED
                consume(att, "到课扣课时", actor)
            }
            att.recordedAt = LocalDateTime.now()
            attendanceRepo.save(att)
        }
        lesson.status = LessonStatus.COMPLETED
        lessonRepo.save(lesson)
        auditService.record("LESSON_COMPLETED", "课时完成：${lesson.topic}（${lesson.startTime}），缺席=${absentStudentIds}", actor = actor)
        return lesson
    }

    /** 老师临时请假：取消课时，出勤记录转为请假不扣课时 */
    @Transactional
    fun teacherLeaveLesson(lessonId: Long, reason: String, actor: String): Lesson {
        val lesson = lessonRepo.findById(lessonId).orElseThrow { BusinessException("NOT_FOUND", "课时不存在: $lessonId") }
        if (lesson.status != LessonStatus.SCHEDULED) bizFail("INVALID_STATE", "仅待上的课时可取消")
        lesson.status = LessonStatus.CANCELLED_TEACHER_LEAVE
        attendanceRepo.findByLessonId(lessonId).filter { it.status == AttendStatus.PENDING }.forEach { att ->
            att.status = AttendStatus.LEAVE
            att.recordedAt = LocalDateTime.now()
            attendanceRepo.save(att)
        }
        lessonRepo.save(lesson)
        auditService.record("LESSON_TEACHER_LEAVE", "老师临时请假，课时取消：${lesson.topic}（$reason），不扣课时", actor = actor)
        return lesson
    }

    /**
     * 课消核心：按合同扣课顺序扣 1 课时。
     * PAID_FIRST：先扣付费课时，耗尽后扣赠课；GIFT_FIRST 反之。
     * 课时不足时记 0 并留告警事件。
     */
    fun consume(att: Attendance, reason: String, actor: String) {
        val contract = contractRepo.findById(att.contractId).orElse(null) ?: return
        val consumed = consumptionOf(contract.id)
        val remainPaid = contract.paidLessons - consumed.first
        val remainGift = contract.giftLessons - consumed.second
        when (contract.consumeOrder) {
            ConsumeOrder.PAID_FIRST -> when {
                remainPaid > 0 -> att.consumedPaid = 1
                remainGift > 0 -> att.consumedGift = 1
                else -> insufficient(att, actor)
            }
            ConsumeOrder.GIFT_FIRST -> when {
                remainGift > 0 -> att.consumedGift = 1
                remainPaid > 0 -> att.consumedPaid = 1
                else -> insufficient(att, actor)
            }
        }
        if (att.consumedPaid + att.consumedGift > 0) {
            auditService.record(
                "CONSUMPTION",
                "课消：学员#${att.studentId} 课时#${att.lessonId} $reason，扣付费=${att.consumedPaid} 赠课=${att.consumedGift}",
                studentId = att.studentId, actor = actor
            )
        }
    }

    private fun insufficient(att: Attendance, actor: String) {
        auditService.record("CONSUMPTION_INSUFFICIENT", "学员#${att.studentId} 课时余额不足，本次未扣课时，请尽快续费", studentId = att.studentId, actor = actor)
    }

    /** 合同已消耗课时：Pair(付费课时消耗, 赠课消耗) */
    fun consumptionOf(contractId: Long): Pair<Int, Int> {
        val atts = attendanceRepo.findByContractId(contractId)
        return atts.sumOf { it.consumedPaid } to atts.sumOf { it.consumedGift }
    }

    fun getLesson(lessonId: Long): Lesson =
        lessonRepo.findById(lessonId).orElseThrow { BusinessException("NOT_FOUND", "课时不存在: $lessonId") }
}

data class LessonScheduleReq(
    val clazzId: Long,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val topic: String
)
