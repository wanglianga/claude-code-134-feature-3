package com.training.crm.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** OpenAPI 3.x 文档：/v3/api-docs；Swagger UI：/swagger-ui.html */
@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("培训机构试听课排班转化退费与课消服务")
            .description("线索 → 试听排班 → 试听反馈 → 报名转化 → 排课课消 → 请假/转班/停课 → 退费与顾问业绩冻结 → 校长履约轨迹。鉴权：请求头 X-Role（ADMIN/PRINCIPAL/CONSULTANT/TEACHER）。")
            .version("1.0.0")
    )
}
