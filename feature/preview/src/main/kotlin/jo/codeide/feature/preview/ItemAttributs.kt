package jo.codeide.feature.preview

import jo.codeide.preview.xml.CatalogueAttributs
import jo.codeide.preview.xml.EditionAttributsXml
import jo.codeide.preview.xml.ProblemesFamilles
import jo.codeide.preview.xml.ProblemesPreview

/**
 * L'ACTION d'une carte de correction (PE11g — miroir `fx` de
 * `quickFixes` : `run` écrit une édition + toast, `go` navigue SANS
 * édition). Portée par l'item, l'adaptateur la rend au volet au tap.
 */
internal sealed interface ActionCorrectif {
    /** « Passer à 48 dp » : fixe < 48 → 48, sinon `minWidth`/`minHeight` — UNE édition. */
    data object ElargirCible : ActionCorrectif

    /** « Extraire en @string » : [ExtractionRessource] existant, valeurs FR et EN. */
    data object ExtraireTexte : ActionCorrectif

    /** « Autoriser 2 lignes » : `android:maxLines="2"` — UNE édition. */
    data object AutoriserDeuxLignes : ActionCorrectif

    /** « Renseigner » : ouvre la section du champ et pose le FOCUS — SANS édition. */
    data object OuvrirContentDescription : ActionCorrectif

    /**
     * PE12f — le correctif d'un problème de FAMILLE : l'édition se pose
     * sur le SPAN du problème (texte vivant), PAS sur la sélection du
     * rendu — le problème survit à un rendu en erreur (boucle § 12) et
     * son correctif doit corriger le TEXTE, pas un span périmé.
     */
    data class CorrigerFamille(
        val probleme: ProblemesFamilles.Probleme,
    ) : ActionCorrectif
}

/**
 * Les items du volet ATTRIBUTS (PE11b — PREVIEW_UI.md § 8, ADR 0132) :
 * titre, barre d'outils épinglée, cartes de correction rapide (PE11g),
 * cartes de section repliables, trait de fin, groupes de LECTURE
 * SEULE — la liste consommée par [AdaptateurAttributs]. Une carte porte
 * SES attributs lus : son corps n'est gonflé qu'à l'ouverture, une
 * carte fermée ne coûte rien.
 */
internal sealed interface ItemAttributs {
    /** Item 0 — le titre « MODIFIER · CLASSE » (entete_editeur). */
    data class Titre(
        val texte: String,
    ) : ItemAttributs

    /** Item 1 — la barre d'outils épinglée (barre_outils_attributs). */
    data object BarreOutils : ItemAttributs

    /**
     * UNE carte de correction rapide (item_correctif, PE11g — miroir
     * `.fix`) : le PROBLÈME de la vue sélectionnée (titre + message
     * localisés) et son ACTION. Un problème sans action (classe
     * introuvable : l'ajout de dépendance n'existe pas) ne produit
     * JAMAIS de carte (brief : « n'afficher le bouton que si l'action
     * existe »).
     */
    data class Correctif(
        val genre: ProblemesPreview.GenreProbleme,
        val titre: String,
        val message: String,
        val bouton: String,
        val action: ActionCorrectif,
    ) : ItemAttributs

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

    /**
     * La SUGGESTION « Ajouter android:xxx comme attribut libre »
     * (PE11h — miroir le `linkbtn` sous le `<p class="empty">` : une
     * recherche qui RESSEMBLE à un nom d'attribut sans résultat la
     * propose). Le tap passe en mode Tous, ouvre la section Autres
     * attributs et préremplit le champ de nom — SANS édition.
     */
    data class SuggestionLibre(
        val nom: String,
    ) : ItemAttributs
}
