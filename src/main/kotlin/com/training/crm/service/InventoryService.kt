package com.training.crm.service

import com.training.crm.common.BusinessException
import com.training.crm.domain.MaterialMovement
import com.training.crm.domain.MaterialStock
import com.training.crm.domain.Subject
import com.training.crm.repo.MaterialMovementRepo
import com.training.crm.repo.MaterialStockRepo
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 教材库存服务：
 * - 报名发放教材（materialsDelivered=true）时按「科目+教材版本」出库；
 * - 退费完成、家长交回教材时入库，并记录流水便于追溯。
 */
@Service
class InventoryService(
    private val stockRepo: MaterialStockRepo,
    private val movementRepo: MaterialMovementRepo
) {
    @Transactional
    fun issue(subject: Subject, version: String, contractId: Long, studentId: Long) {
        val stock = stockRepo.findBySubjectAndTextbookVersion(subject, version)
            ?: throw BusinessException("NOT_FOUND", "教材库存未初始化: $subject/$version")
        stock.available -= 1
        stock.updatedAt = LocalDateTime.now()
        stockRepo.save(stock)
        movementRepo.save(
            MaterialMovement(
                stockId = stock.id, subject = subject, textbookVersion = version, direction = "OUT",
                contractId = contractId, studentId = studentId
            )
        )
    }

    /** 退费交回教材入库，返回更新后的库存 */
    @Transactional
    fun receiveBack(subject: Subject, version: String, contractId: Long, refundOrderId: Long, studentId: Long): MaterialStock {
        val stock = stockRepo.findBySubjectAndTextbookVersion(subject, version)
            ?: throw BusinessException("NOT_FOUND", "教材库存未初始化: $subject/$version")
        stock.available += 1
        stock.updatedAt = LocalDateTime.now()
        stockRepo.save(stock)
        movementRepo.save(
            MaterialMovement(
                stockId = stock.id, subject = subject, textbookVersion = version, direction = "IN",
                contractId = contractId, refundOrderId = refundOrderId, studentId = studentId
            )
        )
        return stock
    }

    fun stocks(): List<MaterialStock> = stockRepo.findAll()
    fun movements(): List<MaterialMovement> = movementRepo.findAll()
}
