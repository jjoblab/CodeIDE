package {{packageName}};

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {{t:service.kdoc}}
 */
@Service
public class GreeterService {

    private final DepotSalutations depot;

    public GreeterService(DepotSalutations depot) {
        this.depot = depot;
    }

    public String greet(String name) {
        String destinataire = (name == null || name.isBlank()) ? "{{t:greeter.world}}" : name;
        return "{{t:app.greeting}}, " + destinataire + "!";
    }

    public Salutation enregistrer(String name) {
        return depot.sauver(greet(name));
    }

    public List<Salutation> historique() {
        return depot.lister();
    }
}
