package {{packageName}}

/**
 * {{t:depot.kdoc}}
 */
interface DepotSalutations {
    /** Enregistre une salutation et lui attribue un identifiant séquentiel. */
    fun sauver(message: String): Salutation

    /** Liste les salutations enregistrées, dans l'ordre d'ajout. */
    fun lister(): List<Salutation>
}
