package {{packageName}}

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * {{t:test.securite.kdoc}}
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecuriteEndpointsTest(
    @Autowired private val mvc: MockMvc,
) {
    @Test
    fun l_api_exige_une_authentification() {
        mvc.get("/greet").andExpect {
            status { isUnauthorized() }
        }
    }
{{#if avecActuator}}

    @Test
    fun health_est_ouvert_sans_authentification() {
        mvc.get("/actuator/health").andExpect {
            status { isOk() }
        }
    }
{{/if}}
}
