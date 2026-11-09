package {{packageName}};

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * {{t:test.kdoc}}
 */
class GreeterServiceTest {

    /**
     * Double en mémoire du dépôt — le service est testé sans Spring.
     */
    private static final class DepotFaux implements DepotSalutations {

        private final List<Salutation> enregistrees = new ArrayList<>();
        private long prochainId = 1L;

        @Override
        public Salutation sauver(String message) {
            Salutation salutation = new Salutation(prochainId, message);
            prochainId += 1;
            enregistrees.add(salutation);
            return salutation;
        }

        @Override
        public List<Salutation> lister() {
            return List.copyOf(enregistrees);
        }
    }

    @Test
    void salueParNom() {
        GreeterService service = new GreeterService(new DepotFaux());
        assertEquals("{{t:app.greeting}}, {{t:test.name}}!", service.greet("{{t:test.name}}"));
    }

    @Test
    void salueLeMondeSiVide() {
        GreeterService service = new GreeterService(new DepotFaux());
        assertEquals("{{t:app.greeting}}, {{t:greeter.world}}!", service.greet(""));
    }

    @Test
    void enregistreEtListeLesSalutations() {
        GreeterService service = new GreeterService(new DepotFaux());
        service.enregistrer("{{t:test.name}}");
        service.enregistrer("{{t:greeter.world}}");

        List<Salutation> historique = service.historique();
        assertEquals(2, historique.size());
        assertEquals("{{t:app.greeting}}, {{t:test.name}}!", historique.get(0).message());
        assertEquals(2L, historique.get(1).id());
    }
}
