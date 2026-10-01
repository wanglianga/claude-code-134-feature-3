package com.training.crm.web

import com.training.crm.service.*
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/courses")
class CourseController(private val courseService: CourseService) {

    @PostMapping("/classes/{clazzId}/enroll")
    fun enroll(
        @PathVariable clazzId: Long,
        @RequestBody req: Map<String, Any>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = courseService.enroll(
        clazzId,
        (req["studentId"] as Number).toLong(),
        (req["contractId"] as Number).toLong(),
        req["force"] as? Boolean ?: false,
        resolveActor(role, actor)
    )

    @PostMapping("/lessons")
    fun scheduleLesson(
        @RequestBody req: LessonScheduleReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = courseService.scheduleLesson(req, resolveActor(role, actor))

    @PostMapping("/lessons/{id}/complete")
    fun completeLesson(
        @PathVariable id: Long,
        @RequestBody(required = false) req: Map<String, List<Long>>?,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = courseService.completeLesson(id, req?.get("absentStudentIds") ?: emptyList(), resolveActor(role, actor))

    @PostMapping("/lessons/{id}/teacher-leave")
    fun teacherLeaveLesson(
        @PathVariable id: Long,
        @RequestBody req: Map<String, String>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = courseService.teacherLeaveLesson(id, req["reason"] ?: "老师临时请假", resolveActor(role, actor))

    @GetMapping("/contracts/{contractId}/consumption")
    fun consumption(@PathVariable contractId: Long): Any {
        val (paid, gift) = courseService.consumptionOf(contractId)
        return mapOf("contractId" to contractId, "consumedPaid" to paid, "consumedGift" to gift)
    }
}

@RestController
@RequestMapping("/api/students")
class StudentOpsController(private val ops: StudentOpsService) {

    @PostMapping("/{id}/leave")
    fun requestLeave(
        @PathVariable id: Long,
        @RequestBody req: Map<String, Any>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = ops.requestLeave(id, (req["lessonId"] as Number).toLong(), req["reason"] as? String ?: "", resolveActor(role, actor))

    @PostMapping("/leave-requests/{id}/approve")
    fun approveLeave(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = ops.approveLeave(id, resolveActor(role, actor))

    @PostMapping("/leave-requests/{id}/reject")
    fun rejectLeave(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = ops.rejectLeave(id, resolveActor(role, actor))

    @PostMapping("/{id}/transfer")
    fun transfer(
        @PathVariable id: Long,
        @RequestBody req: Map<String, Any>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = ops.transfer(id, (req["toClazzId"] as Number).toLong(), req["reason"] as? String ?: "", resolveActor(role, actor))

    @PostMapping("/{id}/suspend")
    fun suspend(
        @PathVariable id: Long,
        @RequestBody req: Map<String, String>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = ops.suspend(
        id,
        req["reason"] ?: "",
        LocalDate.parse(req["startDate"]!!),
        req["endDate"]?.let { LocalDate.parse(it) },
        resolveActor(role, actor)
    )

    @PostMapping("/{id}/resume")
    fun resume(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = ops.resume(id, resolveActor(role, actor))

    @PostMapping("/{id}/complaints")
    fun fileComplaint(
        @PathVariable id: Long,
        @RequestBody req: Map<String, String>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = ops.fileComplaint(id, req["content"]!!, resolveActor(role, actor))

    @PostMapping("/complaints/{id}/resolve")
    fun resolveComplaint(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = ops.resolveComplaint(id, resolveActor(role, actor))
}

@RestController
@RequestMapping("/api/refunds")
class RefundController(private val refundService: RefundService) {

    /** 退费试算：可传 appliedAt（家长实际申请时间）与 reasonCode（退费原因，决定提成归因） */
    @GetMapping("/preview/{studentId}")
    fun preview(
        @PathVariable studentId: Long,
        @RequestParam(required = false) appliedAt: java.time.LocalDateTime?,
        @RequestParam(required = false) reasonCode: String?
    ) = refundService.preview(
        studentId,
        appliedAt,
        reasonCode?.let { com.training.crm.domain.RefundReason.of(it) }
    )

    @PostMapping
    fun request(
        @RequestBody req: Map<String, Any>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?,
        @RequestHeader(value = "X-Idempotency-Key", required = false) idemKey: String?
    ) = refundService.request(
        (req["studentId"] as Number).toLong(),
        com.training.crm.domain.RefundReason.of(req["reasonCode"] as? String),
        req["reason"] as? String ?: "",
        (req["appliedAt"] as? String)?.let { java.time.LocalDateTime.parse(it) },
        idemKey,
        resolveActor(role, actor)
    )

    /** 校区提交整改说明（服务承诺不一致类退费，财务审核前必须完成；仅校区管理方 ADMIN/PRINCIPAL） */
    @PostMapping("/{id}/rectification")
    fun rectification(
        @PathVariable id: Long,
        @RequestBody req: Map<String, String>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ): Any {
        if (role != "ADMIN" && role != "PRINCIPAL") {
            throw com.training.crm.common.BusinessException("FORBIDDEN", "整改说明仅校区管理方（ADMIN/PRINCIPAL）可提交")
        }
        return refundService.submitRectification(id, req["note"] ?: "", resolveActor(role, actor))
    }

    /** 财务审核通过并打款；materialsReturned=true 表示家长已交回教材，库存回收入库 */
    @PostMapping("/{id}/approve")
    fun approve(
        @PathVariable id: Long,
        @RequestBody(required = false) req: Map<String, Boolean>?,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ): Any {
        if (role != "FINANCE" && role != "PRINCIPAL" && role != "ADMIN") {
            throw com.training.crm.common.BusinessException("FORBIDDEN", "退费财务审核仅 FINANCE/PRINCIPAL/ADMIN 可操作")
        }
        return refundService.approve(id, resolveActor(role, actor), req?.get("materialsReturned") ?: false)
    }

    @PostMapping("/{id}/reject")
    fun reject(
        @PathVariable id: Long,
        @RequestBody req: Map<String, String>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ): Any {
        if (role != "FINANCE" && role != "PRINCIPAL" && role != "ADMIN") {
            throw com.training.crm.common.BusinessException("FORBIDDEN", "退费驳回仅 FINANCE/PRINCIPAL/ADMIN 可操作")
        }
        return refundService.reject(id, resolveActor(role, actor), req["reason"] ?: "")
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long) = refundService.get(id)
}

/** 教材库存：查看余量与出入库流水 */
@RestController
@RequestMapping("/api/inventory")
class InventoryController(private val inventoryService: InventoryService) {
    @GetMapping("/stocks")
    fun stocks() = inventoryService.stocks()

    @GetMapping("/movements")
    fun movements() = inventoryService.movements()
}

@RestController
@RequestMapping("/api/principal")
class PrincipalController(private val principalService: PrincipalService) {

    /** 校长视角：学员完整履约轨迹 */
    @GetMapping("/students/{id}/timeline")
    fun timeline(@PathVariable id: Long) = principalService.timeline(id)

    /** 经营概览 */
    @GetMapping("/overview")
    fun overview() = principalService.overview()

    /** 渠道投放质量：有效报名/退费/有效率/单条有效报名成本 */
    @GetMapping("/channels/quality")
    fun channelQuality() = principalService.channelQuality()
}
