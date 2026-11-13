package {{packageName}};

/**
 * {{t:salutation.kdoc}}
 *
 * @param id identifiant séquentiel de la salutation.
 * @param message texte salué.
 */
public record Salutation(long id, String message) {
}
