package com.training.crm.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** OpenAPI 3 文档元信息；Swagger UI：/swagger-ui.html，JSON：/v3/api-docs */
@Configuration
class OpenApiConfig {
    @Bean
    fun api(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("培训机构退费与顾问业绩冻结服务 API")
                .version("1.1.0")
                .description(
                    "覆盖 线索 → 试听 → 报名转化 → 课消 → 扩展退费（已课消课时/赠课使用/教材领取/优惠回收/分期手续费/提成冻结）" +
                        " → 财务审核 → 名额释放/课表关闭/教材回收/业绩按原因重归因/整改说明/渠道投放质量更新 的完整闭环。\n\n" +
                        "鉴权为轻量请求头：X-Role（ADMIN/PRINCIPAL/FINANCE/CONSULTANT/TEACHER，必填）、" +
                        "X-Actor（操作人，可选）、X-Idempotency-Key（创建类接口幂等，可选）。点右上角 Authorize 填写。"
                )
                .license(License().name("Internal"))
        )
        .components(
            Components()
                .addSecuritySchemes(
                    "X-Role",
                    SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.HEADER).name("X-Role")
                        .description("角色：ADMIN / PRINCIPAL / FINANCE / CONSULTANT / TEACHER")
                )
                .addSecuritySchemes(
                    "X-Actor",
                    SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.HEADER).name("X-Actor")
                        .description("操作人姓名（可选）")
                )
                .addSecuritySchemes(
                    "X-Idempotency-Key",
                    SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.HEADER).name("X-Idempotency-Key")
                        .description("幂等键（可选，线索创建/退费申请）")
                )
        )
        .addSecurityItem(SecurityRequirement().addList("X-Role"))
}
