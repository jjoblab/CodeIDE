package {{packageName}};

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * {{t:securite.kdoc}}
 */
@Configuration
@EnableWebSecurity
public class SecuriteConfiguration {

    /**
     * {{t:securite.chaine.kdoc}}
     */
    @Bean
    public SecurityFilterChain chaineSecurite(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(autoriser -> {
{{#if avecActuator}}
                autoriser.requestMatchers("/actuator/health").permitAll();
{{/if}}
                autoriser.anyRequest().authenticated();
            })
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
