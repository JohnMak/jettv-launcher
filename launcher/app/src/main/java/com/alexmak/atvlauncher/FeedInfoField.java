package com.alexmak.atvlauncher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Optional;

/**
 * Info field under a feed row, built from separate views so spacing and vertical centring are plain layout.
 * Line 1: title (extra bold) [season / episode] [app rating icon + number] [КП n] [IMDb n]; below it the Kinopoisk
 * description (up to 3 lines). It always occupies its fixed height; visibility is only its alpha, so showing,
 * hiding or late data never shifts the layout.
 */
public final class FeedInfoField extends LinearLayout {
    private static final long FADE_MS = 180;

    private TextView title;
    private TextView season;
    private View rating;
    private ImageView ratingIcon;
    private TextView ratingText;
    private View kp;
    private TextView kpText;
    private View imdb;
    private TextView imdbText;
    private TextView description;

    public FeedInfoField(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        title = findViewById(R.id.info_title);
        season = findViewById(R.id.info_season);
        rating = findViewById(R.id.info_rating);
        ratingIcon = findViewById(R.id.info_rating_icon);
        ratingText = findViewById(R.id.info_rating_text);
        kp = findViewById(R.id.info_kp);
        kpText = findViewById(R.id.info_kp_text);
        imdb = findViewById(R.id.info_imdb);
        imdbText = findViewById(R.id.info_imdb_text);
        description = findViewById(R.id.info_description);
        setAlpha(0f);
    }

    /** Replaces the content; takes effect immediately, visibility is separate ({@link #setShown}). */
    void setContent(String titleText, String seasonText, Optional<Rating> ratingValue, KinopoiskInfo external) {
        title.setText(titleText);
        title.setVisibility(titleText.isEmpty() ? GONE : VISIBLE);
        season.setText(seasonText);
        season.setVisibility(seasonText.isEmpty() ? GONE : VISIBLE);
        rating.setVisibility(ratingValue.isPresent() ? VISIBLE : GONE);
        ratingValue.ifPresent(r -> {
            ratingIcon.setImageResource(r.top ? R.drawable.ic_laurel_wreath : R.drawable.ic_star);
            ratingText.setText(r.text);
        });
        kpText.setText(external.kp);
        kp.setVisibility(external.kp.isEmpty() ? GONE : VISIBLE);
        imdbText.setText(external.imdb);
        imdb.setVisibility(external.imdb.isEmpty() ? GONE : VISIBLE);
        description.setText(external.description);
    }

    void setShown(boolean shown) {
        animate().alpha(shown ? 1f : 0f).setDuration(FADE_MS).start();
    }
}
