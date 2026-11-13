package {{packageName}};

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * {{t:depot.memoire.kdoc}}
 */
@Repository
public class GreeterRepository implements DepotSalutations {

    private final List<Salutation> enregistrees = new ArrayList<>();
    private long prochainId = PREMIER_ID;

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

    private static final long PREMIER_ID = 1L;
}
