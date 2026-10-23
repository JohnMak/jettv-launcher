package com.johnmak.atvlauncher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Optional;

/**
 * Info field under a feed row, built from separate views so spacing and vertical centring are plain layout.
 * Line 1: title (extra bold) [season / episode] [app rating icon + number] [year]; below it the app's own
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
    private TextView year;
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
        year = findViewById(R.id.info_year);
        description = findViewById(R.id.info_description);
        setAlpha(0f);
    }

    /** Replaces the content; takes effect immediately, visibility is separate ({@link #setShown}). */
    void setContent(String titleText, String seasonText, Optional<Rating> ratingValue, String yearText) {
        title.setText(titleText);
        title.setVisibility(titleText.isEmpty() ? GONE : VISIBLE);
        season.setText(seasonText);
        season.setVisibility(seasonText.isEmpty() ? GONE : VISIBLE);
        rating.setVisibility(ratingValue.isPresent() ? VISIBLE : GONE);
        ratingValue.ifPresent(r -> {
            ratingIcon.setImageResource(r.top ? R.drawable.ic_laurel_wreath : R.drawable.ic_star);
            ratingText.setText(r.text);
        });
        year.setText(yearText);
        year.setVisibility(yearText.isEmpty() ? GONE : VISIBLE);
    }

    /** The description line under the title (the feed item's own text; empty hides the text). */
    void setDescription(String text) {
        description.setText(text);
    }

    void setShown(boolean shown, boolean animated) {
        animate().cancel();
        if (animated) animate().alpha(shown ? 1f : 0f).setDuration(FADE_MS).start();
        else setAlpha(shown ? 1f : 0f);
    }
}
