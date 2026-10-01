package {{packageName}};

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import {{packageName}}.databinding.FragmentAccueilBinding;

/**
 * {{t:fragment.kdoc}}
 */
public class FragmentAccueil extends Fragment {
    public static final String ESPACE_ACCUEIL = "accueil";
    public static final String ESPACE_APROPOS = "apropos";
    private static final String CLE_ESPACE = "espace";

    /** Fabrique un fragment pour l'espace demandé (accueil ou à propos). */
    public static FragmentAccueil nouveau(String espace) {
        FragmentAccueil fragment = new FragmentAccueil();
        Bundle arguments = new Bundle();
        arguments.putString(CLE_ESPACE, espace);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        FragmentAccueilBinding liaison = FragmentAccueilBinding.inflate(inflater, container, false);
        String espace = ESPACE_ACCUEIL;
        if (getArguments() != null) {
            espace = getArguments().getString(CLE_ESPACE, ESPACE_ACCUEIL);
        }
        if (ESPACE_APROPOS.equals(espace)) {
            liaison.texteBienvenue.setText(getString(R.string.apropos_texte));
        } else {
            Greeter greeter = new Greeter("{{t:app.greeting}}");
            liaison.texteBienvenue.setText(greeter.greet(getString(R.string.app_name)));
        }
        return liaison.getRoot();
    }
}
