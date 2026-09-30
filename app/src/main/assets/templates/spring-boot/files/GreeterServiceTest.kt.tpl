package {{packageName}}

import kotlin.test.Test
import kotlin.test.assertEquals

class GreeterServiceTest {
    @Test
    fun salue_par_nom() {
        val service = GreeterService()
        assertEquals("Hello, Edsger!", service.greet("Edsger"))
    }

    @Test
    fun salue_le_monde_si_vide() {
        val service = GreeterService()
        assertEquals("Hello, World!", service.greet(""))
    }
}
