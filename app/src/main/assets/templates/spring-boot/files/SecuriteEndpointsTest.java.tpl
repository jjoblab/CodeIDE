package {{packageName}};

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {{t:test.securite.kdoc}}
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecuriteEndpointsTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void lApiExigeUneAuthentification() throws Exception {
        mvc.perform(get("/greet")).andExpect(status().isUnauthorized());
    }
{{#if avecActuator}}

    @Test
    void healthEstOuvertSansAuthentification() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
{{/if}}
}
