package jo.codeide.core.ai

import jo.codeide.core.model.AppResult
import javax.inject.Inject

/**
 * Liste des modèles du fournisseur actif (bouton « Actualiser la liste »,
 * lot IA-2) — enveloppe du port `AiProvider.listModels()` : la logique
 * vit dans `:core:ai`, le ViewModel n'orchestre que des cas d'usage
 * (même patron que [SendMessageUseCase]).
 */
public class ListerModelesIaUseCase
@Inject
constructor(private val provider: AiProvider) {
    /** Liste les modèles disponibles chez le fournisseur actif. */
    public suspend operator fun invoke(): AppResult<List<AiModelInfo>> = provider.listModels()
}
