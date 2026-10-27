package jo.codeide.feature.ai

import android.animation.ObjectAnimator
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.feature.ai.databinding.LigneBlocCodeBinding
import jo.codeide.feature.ai.databinding.LigneMessageAssistantBinding
import jo.codeide.feature.ai.databinding.LigneMessageUtilisateurBinding

/**
 * Rendu Markdown INCRÉMENTAL (v0.195.0, retour propriétaire) : le
 * message en génération n'est plus détruit/regonflé à chaque fragment
 * reçu — les blocs déjà affichés gardent leur vue (le TEXTE seul est
 * remplacé), les blocs nouveaux sont AJOUTÉS, les blocs en trop RETIRÉS
 * (changement de type : un paragraphe devient liste…). C'est ce qui
 * rend la réponse « coulante » — l'ancien `removeAllViews` + regonflement
 * de TOUT clignotait et saccadait.
 *
 * GARDE ANTI-RENDU (v0.196.0, ANR) : à chaque passage le texte brut
 * porté par la vue est comparé au texte brut du bloc — un bloc
 * INCHANGÉ ne repasse NI par `setText` NI par la fabrication des spans
 * (un `setText` identique invalide quand même mesure/disposition).
 * Pendant le flux, seul le DERNIER bloc grandit : les autres deviennent
 * des pures lectures O(1), ce qui borne le coût de chaque image au
 * rendu du bloc qui coule.
 *
 * Les rappels d'actions des blocs de code (Copier / Insérer / Appliquer)
 * sont capturés au bind complet de la ligne : un rebind partiel qui fait
 * apparaître un NOUVEAU bloc de code rebranche les mêmes rappels sans
 * repasser par l'adaptateur.
 */
// Exemption detekt ciblée (règle 16) : TooManyFunctions — une fonction
// de rendu par TYPE de bloc Markdown (titre, paragraphe, liste, code,
// citation, séparateur, tableau — C3, retour propriétaire) : la surface
// EST la couverture des constructes, chacun reste fermé et testé.
@Suppress("TooManyFunctions")
internal class RenduBlocsMarkdown(private val conteneur: ViewGroup, private val espaceVerticalPx: Int) {
    var copierBloc: (String) -> Unit = {}
    var insererBloc: (String) -> Unit = {}
    var appliquerBloc: (String) -> Unit = {}

    /** Re-rend les blocs du texte — incrémental, sans destruction. */
    fun rendre(texte: String) {
        val blocs = MarkdownIa.parser(texte)
        // Retrait des blocs en trop (le re-parse peut fusionner).
        while (conteneur.childCount > blocs.size) {
            conteneur.removeViewAt(conteneur.childCount - 1)
        }
        for (index in blocs.indices) {
            when (val bloc = blocs[index]) {
                is BlocMarkdown.Titre -> rendreTitre(bloc, index)
                is BlocMarkdown.Paragraphe -> rendreParagraphe(bloc, index)
                is BlocMarkdown.Liste -> rendreListe(bloc, index)
                is BlocMarkdown.BlocCode -> rendreBlocCode(bloc, index)
                is BlocMarkdown.Citation -> rendreCitation(bloc, index)
                BlocMarkdown.Separateur -> rendreSeparateur(index)
                is BlocMarkdown.Tableau -> rendreTableau(bloc, index)
            }
        }
    }

    /** Titre : vue textuelle réutilisée, style TitleSmall. */
    private fun rendreTitre(bloc: BlocMarkdown.Titre, index: Int) {
        blocTexte(TYPE_BLOC_TITRE, index) {
            TextView(conteneur.context).apply {
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_TitleSmall,
                )
                setPadding(0, espaceVerticalPx, 0, 0)
            }
        }.poserTexte(bloc.texte) { SpansMarkdown.de(bloc.texte) }
    }

    /** Paragraphe : vue textuelle réutilisée, liens cliquables. */
    private fun rendreParagraphe(bloc: BlocMarkdown.Paragraphe, index: Int) {
        blocTexte(TYPE_BLOC_PARAGRAPHE, index) {
            TextView(conteneur.context).apply {
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                )
                movementMethod = LinkMovementMethod.getInstance()
                setPadding(0, espaceVerticalPx, 0, 0)
            }
        }.poserTexte(bloc.texte) { SpansMarkdown.de(bloc.texte) }
    }

    /** Liste : vue textuelle réutilisée — puce, numéro ou case par
     * élément, indentation par niveau (C3, retour propriétaire :
     * l'imbrication et les cases à cocher deviennent VISIBLES, les
     * listes ordonnées gardent LEUR numérotation). */
    private fun rendreListe(bloc: BlocMarkdown.Liste, index: Int) {
        var numero = 1
        val brut =
            bloc.elements.joinToString("\n") { element ->
                val prefixe =
                    when {
                        element.coche != null -> if (element.coche == true) "☑" else "☐"
                        bloc.ordonee -> "${numero++}."
                        element.niveau == 0 -> "•"
                        element.niveau == 1 -> " ◦"
                        else -> "  ‧"
                    }
                "${"  ".repeat(element.niveau)}$prefixe  ${element.texte}"
            }
        blocTexte(TYPE_BLOC_LISTE, index) {
            TextView(conteneur.context).apply {
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                )
                setPadding(0, espaceVerticalPx, 0, 0)
            }
        }.poserTexte(brut) { SpansMarkdown.de(brut) }
    }

    /** Citation : bloc renfoncé à liseré primaire, corps discret
     * (C3, retour propriétaire — l'attribution est VISUELLE, pas
     * seulement textuelle). */
    private fun rendreCitation(bloc: BlocMarkdown.Citation, index: Int) {
        val espacement = conteneur.resources
        blocTexte(TYPE_BLOC_CITATION, index) {
            TextView(conteneur.context).apply {
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                )
                setBackgroundResource(R.drawable.fond_citation_ia)
                setPaddingRelative(
                    espacement.getDimensionPixelSize(jo.codeide.core.ui.R.dimen.spacing_md),
                    espacement.getDimensionPixelSize(jo.codeide.core.ui.R.dimen.spacing_xs),
                    espacement.getDimensionPixelSize(jo.codeide.core.ui.R.dimen.spacing_sm),
                    espacement.getDimensionPixelSize(jo.codeide.core.ui.R.dimen.spacing_xs),
                )
            }
        }.poserTexte(bloc.texte) { SpansMarkdown.de(bloc.texte) }
    }

    /** Séparateur horizontal : filet discret pleine largeur (C3,
     * retour propriétaire — les coupes de section deviennent
     * VISIBLES, pas un paragraphe de tirets). */
    private fun rendreSeparateur(index: Int) {
        val existante = vueAuRang(index)
        if (existante?.tag != TYPE_BLOC_SEPARATEUR) {
            existante?.let { conteneur.removeView(it) }
            val filet =
                View(conteneur.context).apply {
                    tag = TYPE_BLOC_SEPARATEUR
                    setBackgroundResource(R.drawable.ligne_separateur_ia)
                    layoutParams =
                        ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            conteneur.resources.getDimensionPixelSize(
                                jo.codeide.core.ui.R.dimen.hauteur_separateur_ia,
                            ),
                        ).apply {
                            topMargin = espaceVerticalPx
                            bottomMargin = espaceVerticalPx
                        }
                }
            conteneur.addView(filet, index)
        }
    }

    /**
     * Tableau (C3, retour propriétaire) : grille MONOSPACE aux
     * colonnes PADDÉES — l'en-tête en gras thématique, un filet sous
     * l'en-tête, l'alignement déclaré (`--:` `:-:`) suivi par
     * colonne. Le monospace rend l'alignement honnête ; le texte
     * passe à la ligne au-delà de l'écran (TextView non éditable,
     * jamais tronqué) — pas de défilement horizontal caché.
     */
    private fun rendreTableau(bloc: BlocMarkdown.Tableau, index: Int) {
        val colonnes = bloc.entetes.size.coerceAtLeast(1)
        val largeurs =
            IntArray(colonnes) { colonne ->
                (bloc.entetes.getOrNull(colonne)?.length ?: 0).coerceAtLeast(
                    bloc.lignes.maxOfOrNull { it.getOrNull(colonne)?.length ?: 0 } ?: 0,
                )
            }

        fun ligne(cells: List<String>): String = (0 until colonnes).joinToString("  ") { colonne ->
            val texte = cells.getOrNull(colonne) ?: ""
            val largeur = largeurs[colonne]
            when (bloc.alignements.getOrNull(colonne)) {
                AlignementColonne.DROITE -> texte.padStart(largeur)
                AlignementColonne.CENTRE -> texte.padStart((largeur + texte.length) / 2).padEnd(largeur)
                else -> texte.padEnd(largeur)
            }
        }

        val filet = (0 until colonnes).joinToString("  ") { "─".repeat(largeurs[it]) }
        val brut = buildString {
            append(ligne(bloc.entetes))
            append('\n')
            append(filet)
            for (rangee in bloc.lignes) {
                append('\n')
                append(ligne(rangee))
            }
        }
        blocTexte(TYPE_BLOC_TABLEAU, index) {
            TextView(conteneur.context).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                )
                setPadding(0, espaceVerticalPx, 0, 0)
            }
        }.poserTexte(brut) { spanTableau(brut) }
    }

    /** Spanne l'EN-TÊTE du tableau (première ligne) en gras primaire —
     * le corps reste tel quel (monospace + padding = la structure). */
    private fun spanTableau(brut: String): CharSequence {
        val constructeur = SpannableStringBuilder(brut)
        val finEntetes = brut.indexOf('\n')
        if (finEntetes > 0) {
            constructeur.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0,
                finEntetes,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            constructeur.setSpan(
                ForegroundColorSpan(couleurTheme(androidx.appcompat.R.attr.colorPrimary)),
                0,
                finEntetes,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return constructeur
    }

    /** Couleur d'attribut de thème résolue (le clair/sombre suit le thème). */
    private fun couleurTheme(attribut: Int): Int {
        val valeur = android.util.TypedValue()
        conteneur.context.theme.resolveAttribute(attribut, valeur, true)
        return valeur.data
    }

    /** Bloc de code : carte dédiée (langue · Copier · Insérer · Appliquer). */
    private fun rendreBlocCode(bloc: BlocMarkdown.BlocCode, index: Int) {
        if (vueAuRang(index)?.tag != TYPE_BLOC_CODE) {
            vueAuRang(index)?.let { conteneur.removeView(it) }
            val vueBloc =
                LigneBlocCodeBinding.inflate(
                    LayoutInflater.from(conteneur.context),
                    conteneur,
                    false,
                )
            vueBloc.root.tag = TYPE_BLOC_CODE
            conteneur.addView(vueBloc.root, index)
        }
        val vueBloc = LigneBlocCodeBinding.bind(conteneur.getChildAt(index))
        vueBloc.langueBloc.text = bloc.langue ?: ""
        vueBloc.langueBloc.isVisible = !bloc.langue.isNullOrBlank()
        // Garde anti-rendu (v0.196.0) : un bloc de code inchangé (tous
        // sauf le dernier pendant le flux) ne repasse ni par setText ni
        // par les écouteurs — mesure/disposition épargnées.
        if (vueBloc.codeBloc.text.toString() == bloc.code) {
            return
        }
        vueBloc.codeBloc.text = bloc.code
        vueBloc.boutonCopierBloc.setOnClickListener { copierBloc(bloc.code) }
        vueBloc.boutonInsererBloc.setOnClickListener { insererBloc(bloc.code) }
        vueBloc.boutonAppliquerBloc.setOnClickListener { appliquerBloc(bloc.code) }
    }

    /**
     * Vue textuelle du rang [index] : RÉUTILISÉE si elle est du même
     * type de bloc (réutilisation incrémentale — le texte seul est
     * remplacé), REMPLACÉE sinon (un paragraphe devient liste…).
     */
    private inline fun blocTexte(type: String, index: Int, fabrication: () -> TextView): TextView {
        val existante = vueAuRang(index)
        val reutilisable = (existante as? TextView)?.takeIf { it.tag == type }
        if (reutilisable != null) {
            return reutilisable
        }
        existante?.let { conteneur.removeView(it) }
        val vue = fabrication()
        vue.tag = type
        conteneur.addView(vue, index)
        return vue
    }

    private fun vueAuRang(index: Int): View? = conteneur.getChildAt(index)

    /**
     * Pose le rendu [fabrication] SEULEMENT si le texte BRUT a changé
     * (garde anti-rendu, v0.196.0) : la vue porte le brut courant, la
     * comparaison est O(taille du bloc) en lecture pure — sans elle,
     * chaque image du flux remesurait TOUS les blocs du message.
     */
    private inline fun TextView.poserTexte(brut: String, fabrication: () -> CharSequence) {
        if (text.toString() != brut) {
            text = fabrication()
        }
    }

    private companion object {
        const val TYPE_BLOC_TITRE = "titre"
        const val TYPE_BLOC_PARAGRAPHE = "paragraphe"
        const val TYPE_BLOC_LISTE = "liste"
        const val TYPE_BLOC_CODE = "code"
        const val TYPE_BLOC_CITATION = "citation"
        const val TYPE_BLOC_SEPARATEUR = "separateur"
        const val TYPE_BLOC_TABLEAU = "tableau"
    }
}

/**
 * Adaptateur des messages du chat (mission IA, lot IA-3) : deux types
 * (utilisateur = bulle droite, assistant = pleine largeur rendue en
 * blocs Markdown), DiffUtil sur l'identifiant.
 *
 * FLUIDITÉ DU FLUX (v0.195.0, retour propriétaire) : le message en
 * génération est re-rendu par PAIEMENTS ([getChangePayload] — texte,
 * raisonnement, erreur, fin) ; le rendu Markdown incrémental vit dans
 * [RenduBlocsMarkdown].
 *
 * Les actions des blocs de code (Copier / Insérer au curseur), la
 * copie d'un message, la RÉGÉNÉRATION de la dernière réponse et
 * l'EXPORT MARKDOWN d'une réponse (rapport IA, lot 3 — d'après la
 * maquette) et l'ACTION DE REMÉDIATION d'une carte d'erreur (rapport
 * IA, Lot 5, point 5) remontent aux actions du ViewModel.
 */
// Exemption detekt ciblée (règle 16) : LongParameterList — un rappel
// par action de la ligne (copie, copie/insertion/application de bloc,
// régénération, export, remédiation d'erreur), miroir des actions du
// ViewModel — un objet de rappels masquerait la DESTINATION de chaque
// action (rapport IA, lot 3).
@Suppress("LongParameterList")
public class AdapterMessages(
    private val onCopierMessage: (String) -> Unit,
    private val onCopierBloc: (String) -> Unit,
    private val onInsererBloc: (String) -> Unit,
    private val onAppliquerBloc: (String) -> Unit,
    private val onRegenererMessage: () -> Unit,
    private val onExporterMessage: (String) -> Unit,
    private val onActionErreur: (MessageChatUi, ActionErreurIa) -> Unit,
) : ListAdapter<MessageChatUi, RecyclerView.ViewHolder>(ComparateurMessages) {
    public override fun getItemViewType(position: Int): Int =
        if (getItem(position).utilisateur) TYPE_UTILISATEUR else TYPE_ASSISTANT

    public override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_UTILISATEUR) {
            TitulaireUtilisateur(
                LigneMessageUtilisateurBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            )
        } else {
            TitulaireAssistant(LigneMessageAssistantBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    public override fun onBindViewHolder(titulaire: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        if (titulaire is TitulaireUtilisateur) {
            titulaire.lier(message)
        } else if (titulaire is TitulaireAssistant) {
            titulaire.lier(
                message,
                estDernier = position == itemCount - 1,
                onCopierMessage = onCopierMessage,
                onCopierBloc = onCopierBloc,
                onInsererBloc = onInsererBloc,
                onAppliquerBloc = onAppliquerBloc,
                onRegenererMessage = onRegenererMessage,
                onExporterMessage = onExporterMessage,
                onActionErreur = onActionErreur,
            )
        }
    }

    /**
     * Paiements du rebind partiel (v0.195.0) : pendant le flux, la
     * DiffUtil re-rend SEULEMENT ce qui a changé — le texte qui coule,
     * le raisonnement qui s'écrit, la carte d'erreur ou la fin — sans
     * jamais regonfler la ligne entière.
     *
     * B1 (rapport IA, lot 1) : le payload de [ComparateurMessages] est
     * un `Set<ClePayload>` — il est DÉPLIÉ ici (`flatMap` sur chaque
     * élément, Set ou clé nue). L'ancienne lecture `mapNotNull {
     * it as? ClePayload }` rejetait le `Set` entier : liste VIDE,
     * `lierPartiellement` sans effet, réponse INVISIBLE pendant le
     * flux. Un dépliage qui ne reconnaît RIEN replie sur le bind
     * COMPLET (jamais un rendu muet).
     */
    public override fun onBindViewHolder(
        titulaire: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.isEmpty() || titulaire !is TitulaireAssistant) {
            super.onBindViewHolder(titulaire, position, payloads)
            return
        }
        val cles =
            payloads.flatMap { (it as? Set<*>)?.toList() ?: listOf(it) }.filterIsInstance<ClePayload>()
        if (cles.isEmpty()) {
            super.onBindViewHolder(titulaire, position, payloads)
            return
        }
        titulaire.lierPartiellement(getItem(position), cles)
    }

    /** Le titulaire recyclé ne doit PAS garder son curseur en animation. */
    public override fun onViewRecycled(titulaire: RecyclerView.ViewHolder) {
        (titulaire as? TitulaireAssistant)?.liberer()
        super.onViewRecycled(titulaire)
    }

    /** Titulaire du message utilisateur (bulle). */
    public class TitulaireUtilisateur(private val liaison: LigneMessageUtilisateurBinding) :
        RecyclerView.ViewHolder(liaison.root) {
        public fun lier(message: MessageChatUi) {
            liaison.texteMessage.text = message.texte
            // C4 (retour propriétaire) : l'heure sous la bulle — lue
            // au fil de la conversation comme dans un client de chat.
            val heure = heureCourte(liaison.root.context, message.horodatageMs)
            liaison.heureMessage.isVisible = heure.isNotEmpty()
            liaison.heureMessage.text = heure
            // TalkBack : chaque message est annoncé avec son rôle.
            liaison.root.contentDescription =
                liaison.root.context.getString(R.string.ia_message_cd_utilisateur, message.texte)
        }
    }

    /** Titulaire du message assistant (raisonnement repliable, blocs Markdown, erreur inline). */
    public class TitulaireAssistant(private val liaison: LigneMessageAssistantBinding) :
        RecyclerView.ViewHolder(liaison.root) {

        /** Repli du raisonnement — état de LA ligne, survit aux rebinds
         * partiels. `null` = AUTO (C3, retour propriétaire) : déplié
         * pendant la phase de PENSÉE (l'utilisateur VOIT le flux de
         * pensée couler — l'ancien comportement le masquait derrière
         * un clic qu'on ne devine pas), replié dès que la réponse
         * démarre ; le clic de l'utilisateur tranche ensuite
         * (Boolean explicite qui survit à la fin du flux). */
        private var replieRaisonnement: Boolean? = null

        /** Curseur de flux : animation alpha clignotante, annulée au recyclage. */
        private var clignotement: ObjectAnimator? = null

        /** Rendu Markdown incrémental des blocs (v0.195.0). */
        private val renduBlocs =
            RenduBlocsMarkdown(
                conteneur = liaison.conteneurBlocs,
                espaceVerticalPx =
                liaison.root.resources.getDimensionPixelSize(jo.codeide.core.ui.R.dimen.spacing_xs),
            )

        /** Rappel de l'action d'erreur capturé au bind COMPLET. */
        private var actionErreur: (MessageChatUi, ActionErreurIa) -> Unit = { _, _ -> Unit }

        /** Rappels des actions par réponse (rapport IA, lot 3) — capturés au bind complet. */
        private var onCopierMessageLie: (String) -> Unit = { _ -> Unit }

        private var onRegenererMessageLie: () -> Unit = { }

        private var onExporterMessageLie: (String) -> Unit = { _ -> Unit }

        /** `true` si la ligne est le DERNIER message (Régénérer, rapport IA lot 3). */
        private var estDerniere = false

        @Suppress("LongParameterList") // un rappel par action de la ligne, miroir de l'adaptateur.
        public fun lier(
            message: MessageChatUi,
            estDernier: Boolean,
            onCopierMessage: (String) -> Unit,
            onCopierBloc: (String) -> Unit,
            onInsererBloc: (String) -> Unit,
            onAppliquerBloc: (String) -> Unit,
            onRegenererMessage: () -> Unit,
            onExporterMessage: (String) -> Unit,
            onActionErreur: (MessageChatUi, ActionErreurIa) -> Unit,
        ) {
            renduBlocs.copierBloc = onCopierBloc
            renduBlocs.insererBloc = onInsererBloc
            renduBlocs.appliquerBloc = onAppliquerBloc
            actionErreur = onActionErreur
            onCopierMessageLie = onCopierMessage
            onRegenererMessageLie = onRegenererMessage
            onExporterMessageLie = onExporterMessage
            estDerniere = estDernier

            // C3 : un NOUVEAU message repart en repli AUTOMATIQUE (la
            // préférence explicite appartient au message précédent, le
            // titulaire peut être recyclé pour un autre message).
            replieRaisonnement = null

            // Le raisonnement suit son propre rendu (masqué quand vide).
            rendreRaisonnement(message)

            renduBlocs.rendre(message.texte)
            rendreCurseur(message.generationEnCours)

            val contexte = liaison.root.context
            liaison.root.contentDescription =
                contexte.getString(
                    R.string.ia_message_cd_assistant,
                    message.texte.ifBlank { contexte.getString(R.string.ia_chat_vide_titre) },
                )

            rendreErreur(message)

            rendreActionsReponse(message, onCopierMessage, onRegenererMessage, onExporterMessage)
        }

        /**
         * Actions par RÉPONSE (rapport IA, lot 3 — d'après la maquette) :
         * Copier sur toute réponse complète, ↻ Régénérer sur la
         * DERNIÈRE réponse (relance le dernier échange — jamais une
         * réponse ancienne), Exporter en Markdown sur toute réponse
         * complète (le fragment porte le sélecteur SAF). Pendant le
         * flux, AUCUNE action (la réponse n'est pas encore une
         * réponse).
         */
        private fun rendreActionsReponse(
            message: MessageChatUi,
            onCopierMessage: (String) -> Unit,
            onRegenererMessage: () -> Unit,
            onExporterMessage: (String) -> Unit,
        ) {
            val complete = message.texte.isNotBlank() && !message.generationEnCours
            liaison.boutonCopierMessage.isVisible = complete
            liaison.boutonRegenererMessage.isVisible = complete && estDerniere
            liaison.boutonExporterMessage.isVisible = complete
            // C4 (retour propriétaire) : l'heure de la réponse joint
            // la ligne d'actions — le moment où elle a été rédigée se
            // lit AUSSI dans une conversation longue.
            val heure = heureCourte(liaison.root.context, message.horodatageMs)
            liaison.heureMessage.isVisible = complete && heure.isNotEmpty()
            liaison.heureMessage.text = heure
            if (complete) {
                liaison.boutonCopierMessage.setOnClickListener { onCopierMessage(message.identifiant) }
            }
            if (complete && estDerniere) {
                liaison.boutonRegenererMessage.setOnClickListener { onRegenererMessage() }
            }
            if (complete) {
                liaison.boutonExporterMessage.setOnClickListener { onExporterMessage(message.identifiant) }
            }
        }

        /** Rebind PARTIEL (v0.195.0) : seules les sections payées sont re-rendues. */
        internal fun lierPartiellement(message: MessageChatUi, cles: List<ClePayload>) {
            for (cle in cles) {
                when (cle) {
                    ClePayload.TEXTE -> renduBlocs.rendre(message.texte)

                    ClePayload.RAISONNEMENT -> rendreRaisonnement(message)

                    ClePayload.ERREUR -> rendreErreur(message)

                    ClePayload.FIN -> {
                        rendreCurseur(message.generationEnCours)
                        rendreRaisonnement(message)
                        rendreActionsReponse(message, onCopierMessageLie, onRegenererMessageLie, onExporterMessageLie)
                    }
                }
            }
        }

        /** Arrête l'animation du curseur (titulaire recyclé). */
        internal fun liberer() {
            clignotement?.cancel()
            clignotement = null
        }

        /**
         * Raisonnement (v0.195.0 ; C3, retour propriétaire) : en-tête
         * repliable + corps italique discret. Pendant la phase de
         * pensée la ligne titre annonce « Raisonnement en cours… » et
         * le corps COULE VISIBLE (auto-déplié — on regarde l'IA
         * réfléchir, plus un clic à deviner) ; dès que la réponse
         * démarre ou à la fin, le corps se REPLIE (l'utilisateur peut
         * le rouvrir) — style Claude : jamais une 2e bulle.
         */
        private fun rendreRaisonnement(message: MessageChatUi) {
            val visible = message.raisonnement.isNotBlank()
            liaison.ligneRaisonnement.isVisible = visible
            val phasePensee = message.generationEnCours && message.texte.isBlank()
            val deplie = replieRaisonnement ?: phasePensee
            liaison.texteRaisonnement.isVisible = visible && deplie
            if (!visible) {
                return
            }
            liaison.titreRaisonnement.setText(
                if (phasePensee) {
                    R.string.ia_raisonnement_en_cours
                } else {
                    R.string.ia_raisonnement_titre
                },
            )
            liaison.texteRaisonnement.text = message.raisonnement
            liaison.chevronRaisonnement.rotation = if (deplie) ROTATION_DEPLIE else ROTATION_REPLIE
            if (!liaison.ligneRaisonnement.hasOnClickListeners()) {
                liaison.ligneRaisonnement.setOnClickListener {
                    // Bascule l'état EFFECTIF (AUTO compris) : la pensée
                    // en cours est dépliée SANS clic — le premier appui
                    // la REPLIE, le suivant la ROUVRE.
                    val deplieMaintenant = liaison.texteRaisonnement.isVisible
                    replieRaisonnement = !deplieMaintenant
                    liaison.texteRaisonnement.isVisible = !deplieMaintenant
                    liaison.chevronRaisonnement.rotation =
                        if (!deplieMaintenant) ROTATION_DEPLIE else ROTATION_REPLIE
                }
            }
        }

        /**
         * Curseur de flux (v0.195.0) : barre clignotante sous le dernier
         * bloc pendant la génération — signe visible de la progression ;
         * arrêtée et masquée à la fin (l'animation est annulée : le
         * titulaire peut être recyclé).
         */
        private fun rendreCurseur(enCours: Boolean) {
            liaison.vueCurseurFlux.isVisible = enCours
            if (enCours) {
                if (clignotement == null) {
                    clignotement =
                        ObjectAnimator.ofFloat(liaison.vueCurseurFlux, View.ALPHA, 1f, ALPHA_CURSEUR_FAIBLE).apply {
                            duration = DUREE_CLIGNOTEMENT_MS
                            repeatMode = ObjectAnimator.REVERSE
                            repeatCount = ObjectAnimator.INFINITE
                            interpolator = LinearInterpolator()
                            start()
                        }
                }
            } else {
                liberer()
                liaison.vueCurseurFlux.alpha = 1f
            }
        }

        /** Carte d'erreur + action de remédiation (Lot 5, point 5) + indice tronqué. */
        private fun rendreErreur(message: MessageChatUi) {
            val contexte = liaison.root.context
            liaison.texteTronque.isVisible = message.tronque && message.erreur == null
            liaison.erreurMessage.isVisible = message.erreur != null
            if (message.erreur != null) {
                val presentation = PresentationErreursIa.message(message.erreur)
                liaison.erreurMessage.text =
                    contexte.getString(presentation.ressource, presentation.argument ?: "")
                // Lot 5, point 5 : la carte d'erreur propose SON action —
                // jamais une carte muette.
                val action = PresentationErreursIa.action(message.erreur)
                liaison.boutonActionErreur.isVisible = true
                liaison.boutonActionErreur.setText(action.libelleRes)
                liaison.boutonActionErreur.setOnClickListener { actionErreur(message, action) }
            } else {
                liaison.boutonActionErreur.isVisible = false
            }
        }

        private companion object {
            /** Rotation du chevron replié (droite) / déplié (bas). */
            const val ROTATION_REPLIE = 0f
            const val ROTATION_DEPLIE = 90f

            /** Alpha bas du clignotement du curseur de flux. */
            const val ALPHA_CURSEUR_FAIBLE = 0.15f

            /** Demi-période du clignotement du curseur de flux. */
            const val DUREE_CLIGNOTEMENT_MS = 450L
        }
    }

    private companion object {
        const val TYPE_UTILISATEUR = 0
        const val TYPE_ASSISTANT = 1
    }
}

/**
 * Heure locale courte d'un message (C4, retour propriétaire — « ajoute
 * l'heure dans les conversations ») : le FORMAT suit le réglage 12/24 h
 * du SYSTÈME ([android.text.format.DateFormat.getTimeFormat] — jamais
 * un motif en dur) ; chaîne vide si l'horodatage est inconnu (0 —
 * jamais une heure inventée).
 */
internal fun heureCourte(contexte: android.content.Context, horodatageMs: Long): String = if (horodatageMs <= 0L) {
    ""
} else {
    android.text.format.DateFormat
        .getTimeFormat(contexte)
        .format(java.util.Date(horodatageMs))
}
