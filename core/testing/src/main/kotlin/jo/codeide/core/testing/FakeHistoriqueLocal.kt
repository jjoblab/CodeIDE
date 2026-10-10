package jo.codeide.core.testing

import jo.codeide.core.domain.EntreeHistorique
import jo.codeide.core.domain.HistoriqueLocal
import jo.codeide.core.domain.TypeEntreeHistorique

/**
 * Faux [HistoriqueLocal] pour les tests JVM (mission H, ADR 0104) :
 * journal des enregistrements REVUS — les appels du décorateur
 * ([HistoriqueFileSystem]) se vérifient sans disque ; les réponses de
 * lecture sont semées par le test.
 *
 * - [enregistrements] : chaque appel d'[enregistrer] (chemin, type,
 *   contenu, libellé) — l'ordre fait foi ;
 * - [purges] : nombre d'appels de [purger] ;
 * - [empreintesSemees] : réponses de [derniereEmpreinte].
 */
public class FakeHistoriqueLocal : HistoriqueLocal {
    /** Appels REVUS de [enregistrer] (null = enregistrer a retourné null). */
    public data class AppelEnregistrer(
        public val cheminRelatif: String,
        public val type: TypeEntreeHistorique,
        public val contenu: String?,
        public val libelle: String?,
        public val retour: EntreeHistorique?,
    )

    /** Journal des enregistrements, dans l'ordre des appels. */
    public val enregistrements: MutableList<AppelEnregistrer> = mutableListOf()

    /** Compteur de purges (l'espace d'édition purge à l'ouverture). */
    public var purges: Int = 0
        private set

    /** Réponses de [derniereEmpreinte] par chemin (null sinon). */
    public val empreintesSemees: MutableMap<String, String> = mutableMapOf()

    /** Contenu lu par [lireContenu] (null : indisponible). */
    public var contenuLu: String? = null

    /** Contenus par identifiant d'entrée (prioritaires — H2 : la feuille
     *  Historique lit des révisions DISTINCTES). */
    public val contenusParId: MutableMap<Long, String> = mutableMapOf()

    override suspend fun enregistrer(
        cheminRelatif: String,
        type: TypeEntreeHistorique,
        contenu: String?,
        tailleOctets: Long,
        libelle: String?,
    ): EntreeHistorique? {
        val entree =
            EntreeHistorique(
                id = enregistrements.size + 1L,
                cheminRelatif = cheminRelatif,
                type = type,
                horodatageMs = 0L,
                empreinte = contenu?.let { "empreinte-${it.hashCode()}" },
                tailleOctets = tailleOctets,
                libelle = libelle,
            )
        enregistrements += AppelEnregistrer(cheminRelatif, type, contenu, libelle, entree)
        return entree
    }

    override suspend fun listerRevisions(cheminRelatif: String): List<EntreeHistorique> =
        enregistrements.filter { it.cheminRelatif == cheminRelatif }.mapNotNull { it.retour }.asReversed()

    override suspend fun listerRevisionsSous(
        cheminDossier: String,
        limite: Int,
    ): List<EntreeHistorique> {
        val prefixe = if (cheminDossier.isBlank()) "" else "$cheminDossier/"
        return enregistrements
            .filter { it.cheminRelatif.startsWith(prefixe) }
            .mapNotNull { it.retour }
            .asReversed()
            .take(limite.coerceAtLeast(0))
    }

    override suspend fun lireContenu(id: Long): String? = contenusParId[id] ?: contenuLu

    override suspend fun derniereEmpreinte(cheminRelatif: String): String? = empreintesSemees[cheminRelatif]

    override suspend fun etiqueter(
        nom: String,
        cheminRelatif: String?,
    ): EntreeHistorique? {
        val entree =
            EntreeHistorique(
                id = 1_000L,
                cheminRelatif = cheminRelatif ?: "",
                type = TypeEntreeHistorique.ETIQUETTE,
                horodatageMs = 0L,
                empreinte = null,
                tailleOctets = 0L,
                libelle = nom,
            )
        return entree
    }

    override suspend fun purger() {
        purges++
    }
}
