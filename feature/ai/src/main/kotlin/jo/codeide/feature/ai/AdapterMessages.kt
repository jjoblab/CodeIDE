package jo.codeide.feature.ai

import android.animation.ObjectAnimator
import android.text.method.LinkMovementMethod
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

    /** Liste : vue textuelle réutilisée, puces préposées. */
    private fun rendreListe(bloc: BlocMarkdown.Liste, index: Int) {
        val brut = bloc.elements.joinToString("\n") { "•  $it" }
        blocTexte(TYPE_BLOC_LISTE, index) {
            TextView(conteneur.context).apply {
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                )
                setPadding(0, espaceVerticalPx, 0, 0)
            }
        }.poserTexte(brut) { SpansMarkdown.de(brut) }
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
            // TalkBack : chaque message est annoncé avec son rôle.
            liaison.root.contentDescription =
                liaison.root.context.getString(R.string.ia_message_cd_utilisateur, message.texte)
        }
    }

    /** Titulaire du message assistant (raisonnement repliable, blocs Markdown, erreur inline). */
    public class TitulaireAssistant(private val liaison: LigneMessageAssistantBinding) :
        RecyclerView.ViewHolder(liaison.root) {

        /** Repli du raisonnement — état de LA ligne, survit aux rebinds partiels. */
        private var raisonnementDeplie = false

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
         * Raisonnement (v0.195.0) : en-tête repliable + corps italique
         * discret. Pendant la phase de pensée la ligne titre annonce
         * « Raisonnement en cours… » et le corps coule au fil des
         * fragments ; dès que la réponse démarre ou à la fin, le titre
         * repasse à « Raisonnement » — repliable au clic, la ligne
         * seule reste visible (style Claude : jamais une 2e bulle).
         */
        private fun rendreRaisonnement(message: MessageChatUi) {
            val visible = message.raisonnement.isNotBlank()
            liaison.ligneRaisonnement.isVisible = visible
            liaison.texteRaisonnement.isVisible = visible && raisonnementDeplie
            if (!visible) {
                return
            }
            liaison.titreRaisonnement.setText(
                if (message.generationEnCours && message.texte.isBlank()) {
                    R.string.ia_raisonnement_en_cours
                } else {
                    R.string.ia_raisonnement_titre
                },
            )
            liaison.texteRaisonnement.text = message.raisonnement
            liaison.chevronRaisonnement.rotation = if (raisonnementDeplie) ROTATION_DEPLIE else ROTATION_REPLIE
            if (!liaison.ligneRaisonnement.hasOnClickListeners()) {
                liaison.ligneRaisonnement.setOnClickListener {
                    raisonnementDeplie = !raisonnementDeplie
                    liaison.texteRaisonnement.isVisible = raisonnementDeplie
                    liaison.chevronRaisonnement.rotation =
                        if (raisonnementDeplie) ROTATION_DEPLIE else ROTATION_REPLIE
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
