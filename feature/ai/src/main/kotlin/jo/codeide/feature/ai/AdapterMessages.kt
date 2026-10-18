package jo.codeide.feature.ai

import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.feature.ai.databinding.LigneBlocCodeBinding
import jo.codeide.feature.ai.databinding.LigneMessageAssistantBinding
import jo.codeide.feature.ai.databinding.LigneMessageUtilisateurBinding

/**
 * Adaptateur des messages du chat (mission IA, lot IA-3) : deux types
 * (utilisateur = bulle droite, assistant = pleine largeur rendue en
 * blocs Markdown), DiffUtil sur l'identifiant — seul le message en
 * cours de génération est re-rendu pendant le flux (rendu incrémental).
 *
 * Les actions des blocs de code (Copier / Insérer au curseur) et la
 * copie d'un message remontent aux actions du ViewModel.
 */
public class AdapterMessages(
    private val onCopierMessage: (String) -> Unit,
    private val onCopierBloc: (String) -> Unit,
    private val onInsererBloc: (String) -> Unit,
    private val onAppliquerBloc: (String) -> Unit,
) : ListAdapter<MessageChatUi, RecyclerView.ViewHolder>(ComparateurMessages) {
    public override fun getItemViewType(position: Int): Int =
        if (getItem(position).utilisateur) TYPE_UTILISATEUR else TYPE_ASSISTANT

    public override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder =
        if (viewType == TYPE_UTILISATEUR) {
            TitulaireUtilisateur(
                LigneMessageUtilisateurBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            )
        } else {
            TitulaireAssistant(LigneMessageAssistantBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    public override fun onBindViewHolder(
        titulaire: RecyclerView.ViewHolder,
        position: Int,
    ) {
        val message = getItem(position)
        if (titulaire is TitulaireUtilisateur) {
            titulaire.lier(message)
        } else if (titulaire is TitulaireAssistant) {
            titulaire.lier(message, onCopierMessage, onCopierBloc, onInsererBloc, onAppliquerBloc)
        }
    }

    /** Titulaire du message utilisateur (bulle). */
    public class TitulaireUtilisateur(
        private val liaison: LigneMessageUtilisateurBinding,
    ) : RecyclerView.ViewHolder(liaison.root) {
        public fun lier(message: MessageChatUi) {
            liaison.texteMessage.text = message.texte
            // TalkBack : chaque message est annoncé avec son rôle.
            liaison.root.contentDescription =
                liaison.root.context.getString(R.string.ia_message_cd_utilisateur, message.texte)
        }
    }

    /** Titulaire du message assistant (blocs Markdown + erreur inline). */
    public class TitulaireAssistant(
        private val liaison: LigneMessageAssistantBinding,
    ) : RecyclerView.ViewHolder(liaison.root) {
        public fun lier(
            message: MessageChatUi,
            onCopierMessage: (String) -> Unit,
            onCopierBloc: (String) -> Unit,
            onInsererBloc: (String) -> Unit,
            onAppliquerBloc: (String) -> Unit,
        ) {
            liaison.conteneurBlocs.removeAllViews()
            for (bloc in MarkdownIa.parser(message.texte)) {
                when (bloc) {
                    is BlocMarkdown.Titre -> {
                        liaison.conteneurBlocs.addView(
                            texte(bloc.texte, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall),
                        )
                    }

                    is BlocMarkdown.Paragraphe -> {
                        liaison.conteneurBlocs.addView(
                            texte(
                                bloc.texte,
                                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                                liens = true,
                            ),
                        )
                    }

                    is BlocMarkdown.Liste -> {
                        liaison.conteneurBlocs.addView(
                            texte(
                                bloc.elements.joinToString("\n") {
                                    "•  $it"
                                },
                                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                            ),
                        )
                    }

                    is BlocMarkdown.BlocCode -> {
                        liaison.conteneurBlocs.addView(
                            gonflerBlocCode(bloc, onCopierBloc, onInsererBloc, onAppliquerBloc),
                        )
                    }
                }
            }

            val contexte = liaison.root.context
            liaison.root.contentDescription =
                contexte.getString(
                    R.string.ia_message_cd_assistant,
                    message.texte.ifBlank { contexte.getString(R.string.ia_chat_vide_titre) },
                )

            liaison.erreurMessage.isVisible = message.erreur != null
            if (message.erreur != null) {
                val presentation = PresentationErreursIa.message(message.erreur)
                liaison.erreurMessage.text =
                    contexte.getString(presentation.ressource, presentation.argument ?: "")
            }

            liaison.boutonCopierMessage.isVisible = message.texte.isNotBlank()
            liaison.boutonCopierMessage.setOnClickListener { onCopierMessage(message.identifiant) }
        }

        /** Bloc de code : barre langue · Copier · Insérer · Appliquer (diff) + contenu défilant. */
        private fun gonflerBlocCode(
            bloc: BlocMarkdown.BlocCode,
            onCopierBloc: (String) -> Unit,
            onInsererBloc: (String) -> Unit,
            onAppliquerBloc: (String) -> Unit,
        ): android.view.View {
            val liaisonBloc =
                LigneBlocCodeBinding.inflate(
                    LayoutInflater.from(liaison.root.context),
                    liaison.conteneurBlocs,
                    false,
                )
            liaisonBloc.langueBloc.text = bloc.langue ?: ""
            liaisonBloc.langueBloc.isVisible = !bloc.langue.isNullOrBlank()
            liaisonBloc.codeBloc.text = bloc.code
            liaisonBloc.boutonCopierBloc.setOnClickListener { onCopierBloc(bloc.code) }
            liaisonBloc.boutonInsererBloc.setOnClickListener { onInsererBloc(bloc.code) }
            liaisonBloc.boutonAppliquerBloc.setOnClickListener { onAppliquerBloc(bloc.code) }
            return liaisonBloc.root
        }

        /** Texte spanné (Markdown en ligne) avec l'apparence du thème. */
        private fun texte(
            contenu: String,
            apparence: Int,
            liens: Boolean = false,
        ): TextView {
            val vue = TextView(liaison.root.context)
            vue.text = SpansMarkdown.de(contenu)
            vue.setTextAppearance(apparence)
            if (liens) {
                vue.movementMethod = LinkMovementMethod.getInstance()
            }
            vue.setPadding(0, espaceVertical(), 0, 0)
            return vue
        }

        private fun espaceVertical(): Int =
            liaison.root.resources.getDimensionPixelSize(jo.codeide.core.ui.R.dimen.spacing_xs)
    }

    private object ComparateurMessages : DiffUtil.ItemCallback<MessageChatUi>() {
        public override fun areItemsTheSame(
            ancien: MessageChatUi,
            nouveau: MessageChatUi,
        ): Boolean = ancien.identifiant == nouveau.identifiant

        public override fun areContentsTheSame(
            ancien: MessageChatUi,
            nouveau: MessageChatUi,
        ): Boolean = ancien == nouveau
    }

    private companion object {
        const val TYPE_UTILISATEUR = 0
        const val TYPE_ASSISTANT = 1
    }
}
