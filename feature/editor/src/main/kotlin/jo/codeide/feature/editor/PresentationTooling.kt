package jo.codeide.feature.editor

import android.content.Context
import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.StatutBuild

/**
 * Libellé localisable produit par [PresentationTooling] : un identifiant de
 * ressource (avec ses arguments de format), un texte brut (message d'échec
 * du serveur) ou une COMPOSITION de libellés (v0.46.0 — console flux brut :
 * une ligne d'étape de sync est le libellé de l'étape suivi de son suffixe de
 * conclusion). Le présentateur reste PUR — il ne touche ni `Context` ni
 * vue ; la résolution a lieu au dernier moment par l'appelant.
 */
internal sealed interface TexteTooling {
    /** Libellé d'une ressource de chaîne (identifiant + arguments). */
    data class Ressource(val id: Int, val args: List<String> = emptyList()) : TexteTooling

    /** Libellé brut (message technique déjà formulé). */
    data class Brut(val texte: String) : TexteTooling

    /** Libellé composé : chaque partie résolue puis concaténée. */
    data class Compose(val parties: List<TexteTooling>) : TexteTooling
}

/**
 * Résout un [TexteTooling] en chaîne localisée.
 *
 * Exemption detekt ciblée (règle 16, même précédent que
 * `BuildHandler.forTasks`) : `Context.getString` n'expose ses arguments de
 * format qu'en vararg (aucune surcharge `Iterable<String>`) — l'éclatement
 * de la liste est l'unique option.
 */
@Suppress("SpreadOperator")
internal fun TexteTooling.resoudre(contexte: Context): String = when (this) {
    is TexteTooling.Ressource -> {
        if (args.isEmpty()) {
            contexte.getString(id)
        } else {
            contexte.getString(id, *args.toTypedArray())
        }
    }

    is TexteTooling.Brut -> {
        texte
    }

    is TexteTooling.Compose -> {
        parties.joinToString(separator = "") { partie -> partie.resoudre(contexte) }
    }
}

/**
 * Statut VISUEL de l'en-tête tooling (v4, §3.3 ; INC-15 : ANNULE) : pilote
 * la pastille — spinner sur couleur de canal en vol, coche verte de
 * succès, croix rouge d'échec, carré d'arrêt sur couleur d'avertissement
 * pour un build annulé. L'état reste pur, la couleur se résout au rendu.
 */
internal enum class StatutEntete {
    /** Activité en vol : couleur de canal + spinner. */
    EN_VOL,

    /** Dernier résultat réussi : vert + coche. */
    SUCCES,

    /** Dernier résultat échoué : rouge + croix. */
    ECHOUE,

    /** Dernier résultat ANNULÉ par l'utilisateur (INC-15) : avertissement
     *  + carré d'arrêt — distinct de l'échec, partagé avec la notification
     *  qui porte déjà l'indicateur `annulé`. */
    ANNULE,

    /** Aucun résultat tranchant : couleur de canal + icône de canal. */
    NEUTRE,
}

/**
 * Libellé localisé d'une étape AFFICHÉE de sync (v4 ; v5 — le plan de
 * l'APERÇU, sept étapes : la fusion « Dépendances et modèle IDE » rend
 * compte des deux phases qui se produisent pendant la même résolution) —
 * partagé par le SOUS-TITRE de l'en-tête (§3.3) et l'arbre de la console :
 * une seule table de correspondance étape → ressource (l'état reste pur,
 * la chaîne se résout au rendu).
 */
internal object LibellesEtapesSync {
    /** Ressource du libellé de l'étape affichée [etape]. */
    fun libelle(etape: EtapeConsoleSync): Int = when (etape) {
        EtapeConsoleSync.OUTILS -> R.string.editor_console_etape_outils
        EtapeConsoleSync.DISTRIBUTION -> R.string.editor_console_etape_distribution
        EtapeConsoleSync.DAEMON -> R.string.editor_console_etape_daemon
        EtapeConsoleSync.CONFIGURATION -> R.string.editor_console_etape_configuration
        EtapeConsoleSync.MODELE_TACHES -> R.string.editor_console_etape_modele_taches
        EtapeConsoleSync.DEPENDANCES_MODELE -> R.string.editor_console_etape_dependances_modele
        EtapeConsoleSync.CLASSPATHS -> R.string.editor_console_etape_classpaths
    }
}

/**
 * État de rendu de l'EN-TÊTE du panneau tooling (v4, §3.2/§3.3) — sorti du
 * présentateur pur : un seul code décide du titre (numéroté par étape
 * pendant la sync), du sous-titre (étape courante + détail), de la
 * progression déterminée (0..1) ou indéterminée, de la couleur de canal,
 * du statut visuel de la pastille, du chrono (en vol ou figé) et du bouton
 * Arrêt. L'activité n'est plus que de la colle.
 *
 * @property titre titre principal (numéroté « étape n/N » en vol de sync,
 *           sinon l'activité courante ou le résultat).
 * @property libelleEtape ressource du libellé de la phase COURANTE (vol de
n *           sync) — le sous-titre la combine au détail.
 * @property sousTitre détail brut de l'étape courante (« 42 Mo · 3
 *           éléments ») ou texte formulé au repos (succès) — `null` si
 *           sans objet.
 * @property progression 0..1 quand une progression DÉTERMINÉE existe
 *           (octets reçus/total), 1 au succès, `null` pour l'indéterminé.
 * @property couleur couleur de canal (ressource core:ui).
 * @property statut statut visuel de la pastille (§3.3).
 * @property chronoMs départ du chrono en vol (ms horloge) — `null` sinon.
 * @property dureeFigeeMs durée du dernier résultat quand le chrono est figé.
 * @property arret le bouton Arrêter se montre-t-il (build en vol) ?
 */
internal data class EtatEnteteTooling(
    val titre: TexteTooling,
    val libelleEtape: Int? = null,
    val sousTitre: TexteTooling? = null,
    val progression: Float? = null,
    val couleur: Int,
    val statut: StatutEntete = StatutEntete.NEUTRE,
    val chronoMs: Long? = null,
    val dureeFigeeMs: Long? = null,
    val arret: Boolean = false,
)

/**
 * Détails de progression d'une étape de sync (v4, §3.2 — alimentation du
 * SOUS-TITRE de l'en-tête et de la progression déterminée) : un objet à
 * part, pur, la même discipline de test que le présentateur.
 */
internal object DetailsEtapesSync {
    /** Libellé de l'étape courante (sous-titre de l'en-tête) — la phase
     *  câble se projette sur le plan d'AFFICHAGE (v5 : la fusion « Dépendances
     *  et modèle IDE » porte le libellé des deux phases). */
    fun libelleEtape(etat: EtatGradle): Int? = etat.etapeCourante
        ?.takeIf { etat.synchronisationEnCours && !it.terminee }
        ?.let { LibellesEtapesSync.libelle(EtapeConsoleSync.dePhase(it.etape)) }

    /** Sous-titre : détail brut de l'étape courante (« 42 Mo · 3 · artefact »). */
    fun sousTitre(etat: EtatGradle): TexteTooling? = etat.etapeCourante
        ?.takeIf { etat.synchronisationEnCours && !it.terminee }
        ?.let(::detail)
        ?.let { TexteTooling.Brut(it) }

    /** Progression déterminée : octets reçus / total quand les deux sont connus. */
    fun progression(etat: EtatGradle): Float? = etat.etapeCourante
        ?.takeIf { etat.synchronisationEnCours }
        ?.takeIf { courante -> courante.octetsRecus > 0 && (courante.octetsTotal ?: 0L) > 0 }
        ?.let { courante -> courante.octetsRecus.toFloat() / courante.octetsTotal!! }
        ?.coerceIn(0f, 1f)

    /** Détail lisible d'une étape : octets reçus (Mo), compteur, élément. */
    private fun detail(etape: EtapeSyncAffichee): String? {
        val parties = mutableListOf<String>()
        if (etape.octetsRecus > 0) {
            parties += "${etape.octetsRecus / OCTETS_PAR_MO} Mo"
        }
        if (etape.compteur != null && etape.compteur > 0) {
            parties += "${etape.compteur} élément(s)"
        }
        etape.element?.take(TAILLE_ELEMENT_MAX)?.let { element -> parties += element }
        return if (parties.isEmpty()) null else parties.joinToString(" · ")
    }

    private const val OCTETS_PAR_MO = 1_024L * 1_024L

    private const val TAILLE_ELEMENT_MAX = 28
}

/**
 * Présentateur UNIQUE du tooling (correctif n°11 du prompt « tooling Gradle
 * professionnel » ; INC-02, volet C du prompt « retard d'affichage des
 * sorties de build ») : deux cascades dupliquées traduisaient [EtatGradle] en
 * libellés — [EditorActivity.libelleActiviteTooling] pour l'en-tête du
 * panneau et [PanneauConsoleFragment.libelleStatutTooling] pour le statut
 * de l'onglet Sortie. Elles vivent désormais ici, pures et testées
 * (FR/EN, échec, annulé, sync, build, listage).
 *
 * INC-02 : les cascades locales du fragment (chip, icône de canal) sont
 * SUPPRIMÉES — tout écran console dérive du MÊME [canalAffiche] ; le
 * statut de la console suit la MÊME cascade que l'en-tête
 * ([libelleActivite] du canal affiché, INC-22) — la contradiction
 * « Synchronisé » côté console vs échec côté en-tête d'une sync PARTIELLE
 * disparaît : un seul verdict par canal.
 *
 * Les durées passent par [DureesLisibles] (correctif n°11 : la copie
 * privée de `EditorActivity` est supprimée — un seul formateur).
 *
 * Exemption detekt ciblée (règle 16, même précédent que `GradleService`) :
 * TooManyFunctions — les fonctions sont le CONTRAT de la vue (les deux
 * cascades historiques en-tête/statut + la composition v4 de l'en-tête
 * enrichi), chacune couverte par [PresentationToolingTest] et
 * [PresentationToolingLocalisationTest].
 */
@Suppress("TooManyFunctions")
internal object PresentationTooling {
    /**
     * État d'en-tête complet (v4, §3.2/§3.3) : le présentateur décide,
     * l'activité rend — titre NUMÉROTÉ par étape pendant la sync,
     * sous-titre = étape courante avec son détail (octets, compteur),
     * progression déterminée quand les octets totaux sont connus (pleine
     * au succès), statut visuel de la pastille, chrono en vol ou durée
     * figée du dernier résultat.
     */
    fun etatEntete(etat: EtatGradle): EtatEnteteTooling {
        val canal = etat.canalActif ?: etat.canalDernierResultat
        val enVol = etat.canalActif != null
        val statut = statutVisuel(enVol, canal, etat)
        return EtatEnteteTooling(
            titre = titreEntete(etat, canal),
            libelleEtape = DetailsEtapesSync.libelleEtape(etat),
            sousTitre = sousTitre(etat, canal),
            progression =
            when {
                enVol -> DetailsEtapesSync.progression(etat)
                statut == StatutEntete.SUCCES -> 1f
                else -> null
            },
            couleur = canal?.couleur ?: jo.codeide.core.ui.R.color.codeide_canal_sync,
            statut = statut,
            chronoMs =
            when (etat.canalActif) {
                CanalTooling.SYNC -> etat.debutSyncMs
                CanalTooling.BUILD -> etat.debutBuildMs
                CanalTooling.TACHES -> etat.debutTachesMs
                null -> null
            },
            dureeFigeeMs = dureeFigee(etat, canal),
            arret = etat.canalActif == CanalTooling.BUILD,
        )
    }

    /** Titre de l'en-tête (§3.3) : NUMÉROTÉ « étape n/N » pendant la sync
     *  (dès qu'une étape est connue), sinon l'activité du canal ou le
     *  libellé vide. */
    private fun titreEntete(etat: EtatGradle, canal: CanalTooling?): TexteTooling = when {
        etat.synchronisationEnCours && etat.numeroEtape > 0 -> {
            ressource(
                R.string.editor_tooling_entete_sync_etape,
                "${etat.numeroEtape}",
                "${etat.totalEtapes}",
            )
        }

        else -> {
            canal?.let { libelleActivite(etat, it) }
                ?: TexteTooling.Ressource(R.string.editor_sortie_vide)
        }
    }

    /** Statut visuel de la pastille (§3.3 ; INC-15 : ANNULE distinct de
     *  l'échec) : en vol, puis le verdict du dernier résultat du canal —
     *  l'échec de sync prime le succès PARTIEL (même cascade que le
     *  titre : l'en-tête ne mente jamais). */
    private fun statutVisuel(enVol: Boolean, canal: CanalTooling?, etat: EtatGradle): StatutEntete = when {
        enVol -> {
            StatutEntete.EN_VOL
        }

        canal == CanalTooling.SYNC -> {
            when {
                etat.messageEchecSync != null -> StatutEntete.ECHOUE
                etat.synchronisationReussie?.reussie == true -> StatutEntete.SUCCES
                else -> StatutEntete.NEUTRE
            }
        }

        canal == CanalTooling.BUILD -> {
            when (etat.statutBuild) {
                StatutBuild.REUSSI -> StatutEntete.SUCCES
                StatutBuild.ECHOUE -> StatutEntete.ECHOUE
                StatutBuild.ANNULE -> StatutEntete.ANNULE
                else -> StatutEntete.NEUTRE
            }
        }

        else -> {
            StatutEntete.NEUTRE
        }
    }

    /** Sous-titre (§3.3 ; v5 — aperçu ; INC-15 : PAR CANAL — l'ancienne
     *  version sans canal mélangeait le détail de succès de la sync sous
     *  un titre de build) : en vol de sync, détail de l'étape courante ;
     *  au repos, la CAUSE COURTE du verdict du canal (message du serveur
     *  pour un échec) ou le compte « modules · tâches · … » du succès de
     *  sync — `null` sinon. Le message COMPLET vit dans le bandeau de la
     *  console et dans le flux (ADR 0078 : jamais aux deux). */
    private fun sousTitre(etat: EtatGradle, canal: CanalTooling?): TexteTooling? {
        if (etat.synchronisationEnCours) {
            return DetailsEtapesSync.sousTitre(etat)
        }
        return when (canal) {
            null -> null

            CanalTooling.SYNC -> {
                val reussie = etat.synchronisationReussie
                val taches = etat.tachesDisponibles
                when {
                    etat.messageEchecSync != null -> TexteTooling.Brut(etat.messageEchecSync)

                    reussie?.reussie == true && taches?.isNotEmpty() == true -> {
                        sousTitreSucces(etat, taches.size)
                    }

                    else -> null
                }
            }

            CanalTooling.BUILD -> {
                etat.messageEchecBuild?.let { TexteTooling.Brut(it) }
            }

            CanalTooling.TACHES -> null
        }
    }

    /** Sous-titre de succès (v5, aperçu) : « modules · tâches · … » quand
     *  le compte des modules est connu (compteur final de CLASSPATHS),
     *  sinon le compte des tâches seul — repli défensif, la sync réussie
     *  conclut toujours CLASSPATHS avec son compteur. */
    private fun sousTitreSucces(etat: EtatGradle, nbTaches: Int): TexteTooling {
        val modules =
            etat.etapesAffichees
                .firstOrNull { it.etape == EtapeSync.CLASSPATHS }
                ?.compteur
        return if (modules != null) {
            val sansTelechargement = etat.etapesAffichees.none { it.octetsRecus > 0 }
            ressource(
                if (sansTelechargement) {
                    R.string.editor_tooling_entete_succes_detail_a_jour
                } else {
                    R.string.editor_tooling_entete_succes_detail_classpaths
                },
                "$modules",
                "$nbTaches",
            )
        } else {
            ressource(R.string.editor_tooling_entete_succes_taches, "$nbTaches")
        }
    }

    /** Durée figée du dernier résultat (chrono arrêté — INC-15 : TOUT
     *  résultat terminal porte sa durée, un échec de sync autant qu'un
     *  échec de build ; [EtatGradle.dureeSyncMs] est posé à chaque
     *  conclusion, réussie ou échouée). Le repli sur la durée du résultat
     *  réussi couvre les états construits sans le champ figé. */
    private fun dureeFigee(etat: EtatGradle, canal: CanalTooling?): Long? = when (canal) {
        CanalTooling.SYNC -> etat.dureeSyncMs ?: etat.synchronisationReussie?.dureeMs
        CanalTooling.BUILD -> etat.dureeBuildMs
        else -> null
    }

    /**
     * Message d'échec du BANDEAU de la console (INC-21, volet C) : celui
     * du canal AFFICHÉ — `null` quand ce canal n'a pas échoué (l'échec de
     * l'AUTRE canal est signalé par le point d'actualité du sélecteur
     * INC-20 et par l'en-tête). Un échec de sync a enfin un message
     * tranchant, pas seulement une ligne de statut ; le message vide
     * replie sur le verdict localisé du canal.
     */
    fun messageEchecCanal(etat: EtatGradle, canal: CanalTooling?): TexteTooling? = when (canal) {
        CanalTooling.SYNC ->
            etat.messageEchecSync?.let { echec ->
                if (echec.isNotBlank()) {
                    TexteTooling.Brut(echec)
                } else {
                    TexteTooling.Ressource(R.string.editor_console_echec_sync_defaut)
                }
            }

        CanalTooling.BUILD ->
            when (etat.statutBuild) {
                StatutBuild.ECHOUE ->
                    etat.messageEchecBuild?.let { TexteTooling.Brut(it) }
                        ?: TexteTooling.Ressource(R.string.editor_console_echec_defaut)

                else -> null
            }

        CanalTooling.TACHES, null -> null
    }

    /**
     * Le « Réessayer » du bandeau est-il actionnable (INC-21) ? La sync
     * se relance toujours ; un build seulement si des tâches existent.
     */
    fun reessaiPossible(etat: EtatGradle, canal: CanalTooling?): Boolean = when (canal) {
        CanalTooling.SYNC -> true
        else -> etat.taches.isNotEmpty()
    }

    /**
     * Canal affiché par les écrans console (INC-02, volet C) : chip,
     * icône de canal, ligne de statut et console VISIBLE dérivent de cette
     * SEULE dérivation — plus jamais d'une copie de cascade locale.
     *
     * Règle : le choix d'affichage ([FiltreCanalConsole] — poussé par
     * l'action qui démarre, INC-20 : choisi par l'utilisateur ensuite)
     * désigne la console montrée ; la dérivation rend `null` quand NI la
     * sync NI le build n'ont jamais vécu (état vierge — rien à
     * sélectionner, le chip et l'icône disparaissent, le statut replie sur
     * son état vide). Le canal [CanalTooling.TACHES] n'est JAMAIS renvoyé
     * : transitoire de l'EN-TÊTE uniquement (INC-03 — le listage se
     * conclut par son sélecteur, pas par une console).
     *
     * L'en-tête du panneau, lui, suit l'ACTIVITÉ
     * (`canalActif ?: canalDernierResultat`) : pendant que l'utilisateur
     * consulte la console Sync, un build lancé entre-temps reste annoncé
     * par l'en-tête — les deux rôles ne se mélangent pas.
     */
    fun canalAffiche(etat: EtatGradle, choix: FiltreCanalConsole): CanalTooling? {
        val canalVecu =
            etat.canalActif == CanalTooling.SYNC ||
                etat.canalActif == CanalTooling.BUILD ||
                etat.canalDernierResultat != null
        return if (canalVecu) choix.canal else null
    }

    /** Activité courante de l'en-tête, sur SON canal. */
    fun libelleActivite(etat: EtatGradle, canal: CanalTooling): TexteTooling = when (canal) {
        CanalTooling.SYNC -> {
            libelleCanalSyncEntete(etat)
        }

        CanalTooling.BUILD -> {
            libelleCanalBuildEntete(etat)
        }

        CanalTooling.TACHES -> {
            // Indicateur de vol : le sélecteur est le résultat du canal.
            TexteTooling.Ressource(R.string.editor_tooling_taches_en_cours)
        }
    }

    /**
     * Statut de la ligne de la console (INC-22, finale avec l'INC-10 :
     * l'activité et le verdict vivent dans l'EN-TÊTE du sheet — la ligne
     * enrichie sur Console replié, les indicateurs compacts partout
     * ailleurs — ADR 0078 : jamais aux deux). Elle ne porte plus que
     * l'état VIERGE : orchestrateur déconnecté, sinon sortie vide. La
     * vue ne la montre d'ailleurs que déconnectée (l'état vide a déjà sa
     * vue dédiée dans le corps de la console).
     */
    fun libelleStatut(etat: EtatGradle): TexteTooling = when {
        etat.connexion == EtatConnexion.ECHOUEE -> {
            TexteTooling.Ressource(R.string.editor_outil_deconnecte)
        }

        else -> {
            TexteTooling.Ressource(R.string.editor_sortie_vide)
        }
    }

    // ---- Cascades de l'en-tête (par canal) ------------------------------

    private fun libelleCanalSyncEntete(etat: EtatGradle): TexteTooling = when {
        etat.synchronisationEnCours -> {
            TexteTooling.Ressource(R.string.editor_tooling_sync_en_cours)
        }

        etat.messageEchecSync != null -> {
            // INC-15 : verdict LOCALISÉ ET COURT — le message brut du
            // serveur (souvent multi-lignes, technique) va dans le
            // SOUS-TITRE, le bandeau et la console, pas dans un titre
            // singleLine tronqué.
            TexteTooling.Ressource(R.string.editor_tooling_sync_echouee)
        }

        etat.synchronisationReussie != null -> {
            reussieSync(etat)
        }

        else -> {
            // INC-04 : cette branche mentait (« Synchronisation du projet… »
            // alors que rien ne tourne). Inatteignable par les états publiés
            // par le service (un canal SYNC y implique un verdict sync) —
            // mais l'INC-20 rend le canal Sync consultable sans qu'aucune
            // sync n'ait jamais tourné : le libellé honnête est alors
            // « Aucune synchronisation lancée », pas une activité fantôme.
            TexteTooling.Ressource(R.string.editor_tooling_sync_jamais)
        }
    }

    private fun libelleCanalBuildEntete(etat: EtatGradle): TexteTooling = when {
        etat.statutBuild == StatutBuild.EN_COURS -> {
            if (etat.taches.isEmpty()) {
                TexteTooling.Ressource(R.string.editor_tooling_build_en_cours)
            } else {
                ressource(
                    R.string.editor_tooling_build_taches,
                    etat.taches.joinToString(", "),
                )
            }
        }

        etat.statutBuild == StatutBuild.REUSSI -> {
            reussiBuild(etat)
        }

        etat.statutBuild == StatutBuild.ECHOUE -> {
            // INC-15 : verdict court localisé — le message du serveur vit
            // dans le sous-titre, le bandeau et la console.
            TexteTooling.Ressource(R.string.editor_sortie_build_echoue)
        }

        etat.statutBuild == StatutBuild.ANNULE -> {
            TexteTooling.Ressource(R.string.editor_sortie_build_annule)
        }

        else -> {
            TexteTooling.Ressource(R.string.editor_sortie_vide)
        }
    }

    // ---- Libellés partagés par les deux cascades ------------------------

    /** « Synchronisé en X » (durée formatée par [DureesLisibles]). */
    private fun reussieSync(etat: EtatGradle): TexteTooling = ressource(
        R.string.editor_sortie_sync_reussie,
        DureesLisibles.formater(etat.synchronisationReussie?.dureeMs ?: 0L),
    )

    /** « Build réussi en X ». */
    private fun reussiBuild(etat: EtatGradle): TexteTooling = ressource(
        R.string.editor_sortie_build_reussi,
        DureesLisibles.formater(etat.dureeBuildMs ?: 0L),
    )

    /** Ressource avec arguments de format (chaînes). */
    private fun ressource(id: Int, vararg args: String): TexteTooling = TexteTooling.Ressource(id, args.toList())
}
