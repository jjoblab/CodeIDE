package {{packageName}};

import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.GET;

/**
 * Service d'exemple Retrofit : une API publique de citations est décrite
 * puis construite via le convertisseur Gson — le câblage réel à adapter.
 */
public interface ServiceCitations {
    /** Récupère une citation aléatoire (JSON : {content, author}). */
    @GET("quotes/random")
    Call<Citation> citationAleatoire();

    /** Réponse de l'API d'exemple. */
    class Citation {
        public String content;
        public String author;
    }

    /** Construit le service pointé vers l'API d'exemple. */
    static ServiceCitations creer(String baseUrl) {
        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ServiceCitations.class);
    }
}
