package jo.codeide.feature.preview

import jo.codeide.preview.xml.CatalogueAttributs
import jo.codeide.preview.xml.EditionAttributsXml

/**
 * Les items du volet ATTRIBUTS (PE11b — PREVIEW_UI.md § 8, ADR 0132) :
 * titre, barre d'outils épinglée, cartes de section repliables, trait de
 * fin, groupes de LECTURE SEULE — la liste consommée par
 * [AdaptateurAttributs]. Une carte porte SES attributs lus : son corps
 * n'est gonflé qu'à l'ouverture, une carte fermée ne coûte rien.
 */
internal sealed interface ItemAttributs {
    /** Item 0 — le titre « MODIFIER · CLASSE » (entete_editeur). */
    data class Titre(
        val texte: String,
    ) : ItemAttributs

    /** Item 1 — la barre d'outils épinglée (barre_outils_attributs). */
    data object BarreOutils : ItemAttributs

    /**
     * Une carte de section repliable (carte_section) : titre, badge « n
     * définis », état ouvert/fermé porté par le volet (mémorisé par
     * titre, miroir `S.so`).
     */
    data class Carte(
        val section: CatalogueAttributs.SectionAttributs,
        val controles: List<CatalogueAttributs.ControleAttribut>,
        val attrs: List<EditionAttributsXml.AttributSource>,
        val position: Int,
        val titre: String,
        val definis: Int,
    ) : ItemAttributs

    /** Le trait de fin de l'éditeur (separateur_editeur). */
    data object Separateur : ItemAttributs

    /** Un bloc LECTURE SEULE pleine largeur (span périmé : explication). */
    data class LectureSeule(
        val texte: String,
    ) : ItemAttributs

    /** Le titre d'un groupe de lecture seule (item_attribut_groupe). */
    data class TitreGroupe(
        val titre: String,
    ) : ItemAttributs

    /** Une ligne clé/valeur de lecture seule (item_attribut). */
    data class Attribut(
        val cle: String,
        val valeur: String,
    ) : ItemAttributs

    /** Le volet vide (aucune vue sélectionnée). */
    data object Vide : ItemAttributs

    /**
     * Une ligne de message pleine largeur (vide_volet, texte posé au
     * liage — miroir le `<p class="empty">` de la maquette : « Aucun
     * attribut ne correspond à « … ». », PE11f).
     */
    data class Message(
        val texte: String,
    ) : ItemAttributs

    /**
     * Le bouton large « Afficher les N autres attributs de X »
     * (bouton_autres_attributs — miroir `.linkbtn.wide`, visible en
     * mode Courants quand des attributs sont masqués, PE11f).
     */
    data class BoutonAutres(
        val texte: String,
    ) : ItemAttributs
}
