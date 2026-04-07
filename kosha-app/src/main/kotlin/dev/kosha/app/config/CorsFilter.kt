package dev.kosha.app.config

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

@Configuration
class CorsFilterConfig {

    @Bean
    fun corsFilterRegistration(): FilterRegistrationBean<Filter> {
        val allowedOrigins = setOf(
            "http://localhost:3000",
            "http://localhost:5173",
            "http://localhost:5174",
            "http://localhost:5175",
            "http://localhost:5176",
        )

        val filter = Filter { request: ServletRequest, response: ServletResponse, chain: FilterChain ->
            val req = request as HttpServletRequest
            val res = response as HttpServletResponse
            val origin = req.getHeader("Origin")

            if (origin != null && origin in allowedOrigins) {
                res.setHeader("Access-Control-Allow-Origin", origin)
                res.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
                res.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Accept")
                res.setHeader("Access-Control-Allow-Credentials", "true")
                res.setHeader("Access-Control-Max-Age", "3600")
            }

            if ("OPTIONS".equals(req.method, ignoreCase = true)) {
                res.status = HttpServletResponse.SC_OK
                return@Filter
            }

            chain.doFilter(request, response)
        }

        return FilterRegistrationBean<Filter>().apply {
            this.filter = filter
            order = Ordered.HIGHEST_PRECEDENCE
            addUrlPatterns("/*")
        }
    }
}
