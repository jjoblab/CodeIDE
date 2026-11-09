package {{packageName}}

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * {{t:securite.kdoc}}
 */
@Configuration
@EnableWebSecurity
class SecuriteConfiguration {
    /**
     * {{t:securite.chaine.kdoc}}
     */
    @Bean
    fun chaineSecurite(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { autoriser ->
{{#if avecActuator}}
                autoriser.requestMatchers("/actuator/health").permitAll()
{{/if}}
                autoriser.anyRequest().authenticated()
            }
            .httpBasic(Customizer.withDefaults())
        return http.build()
    }
}
