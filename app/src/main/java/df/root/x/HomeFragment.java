package df.root;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;

public class HomeFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Real bold (wght 700) One UI Sans on the headline; the variable font's
        // regular cut is too light to read as a title.
        TextView headline = view.findViewById(R.id.headline);
        Typeface base = ResourcesCompat.getFont(requireContext(), R.font.inter_vf);
        if (base != null) {
            headline.setTypeface(Typeface.create(base, 700, false));
        }
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).onHomeViewReady(view);
        }
    }
}
