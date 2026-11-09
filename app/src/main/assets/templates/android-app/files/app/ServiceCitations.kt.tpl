package {{packageName}}

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET

/**
 * Service d'exemple Retrofit : une API publique de citations est décrite
 * puis construite via le convertisseur Gson — le câblage réel à adapter.
 */
interface ServiceCitations {
    /** Récupère une citation aléatoire (JSON : {content, author}). */
    @GET("quotes/random")
    suspend fun citationAleatoire(): Citation

    data class Citation(val content: String, val author: String)

    companion object {
        /** Construit le service pointé vers l'API d'exemple. */
        fun creer(baseUrl: String = "https://api.quotable.io/"): ServiceCitations =
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ServiceCitations::class.java)
    }
}
