package com.training.crm.web

import com.training.crm.common.BusinessException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 轻量角色鉴权：请求头 X-Role（ADMIN/PRINCIPAL/CONSULTANT/TEACHER）+ X-Actor（操作人姓名）。
 * 校长视图（principal 前缀路径）仅 PRINCIPAL、ADMIN 可访问。
 */
@Configuration
class WebConfig : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(RoleInterceptor()).addPathPatterns("/api/**")
    }
}

class RoleInterceptor : HandlerInterceptor {
    private val roles = setOf("ADMIN", "PRINCIPAL", "FINANCE", "CONSULTANT", "TEACHER")

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val role = request.getHeader("X-Role")
        if (role == null || role !in roles) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("""{"error":"UNAUTHORIZED","message":"缺少或非法的 X-Role 请求头（ADMIN/PRINCIPAL/FINANCE/CONSULTANT/TEACHER）"}""")
            return false
        }
        if (request.requestURI.startsWith("/api/principal") && role != "PRINCIPAL" && role != "ADMIN") {
            response.status = HttpStatus.FORBIDDEN.value()
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("""{"error":"FORBIDDEN","message":"校长视图仅 PRINCIPAL/ADMIN 可访问"}""")
            return false
        }
        return true
    }
}

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException::class)
    fun biz(e: BusinessException): ResponseEntity<Map<String, Any>> {
        // FORBIDDEN 业务码返回 403，其余业务异常返回 400
        val status = if (e.code == "FORBIDDEN") HttpStatus.FORBIDDEN else HttpStatus.BAD_REQUEST
        return ResponseEntity.status(status)
            .body(mapOf("error" to e.code, "message" to e.message))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: Exception): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to "INVALID_ARGUMENT", "message" to (e.message ?: "参数错误")))

    @ExceptionHandler(Exception::class)
    fun unknown(e: Exception): ResponseEntity<Map<String, Any>> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to "INTERNAL", "message" to (e.message ?: "系统异常")))
}
