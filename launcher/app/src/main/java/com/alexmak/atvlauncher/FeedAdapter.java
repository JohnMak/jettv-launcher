package com.alexmak.atvlauncher;

import android.graphics.Bitmap;
import android.os.Handler;
import android.view.View;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/** One horizontal feed row of poster cards; posters load asynchronously through the shared cache. */
final class FeedAdapter extends RecyclerView.Adapter<FeedAdapter.Holder> {
    static final int CARD_HEIGHT_DP = 135;
    /** Poster top inside the card frame (item_card.xml) and the badge's top: the badge is flush with the poster bottom. */
    private static final float POSTER_TOP_DP = 7;
    private static final float BADGE_SIZE_DP = 22;
    private static final float CARD_RADIUS_DP = 12;
    private static final float BADGE_CORNER_DP = 6;

    static final class Holder extends RecyclerView.ViewHolder {
        final DimImageView image;
        final DimImageView badge;
        final ShadowView shadow;
        String boundUri;

        Holder(View v) {
            super(v);
            image = v.findViewById(R.id.card_image);
            badge = v.findViewById(R.id.card_badge);
            shadow = v.findViewById(R.id.shadow);
        }
    }

    private final List<FeedItem> items = new ArrayList<>();
    private final PosterCache posters;
    private final ExecutorService executor;
    private final Handler main;
    private final float density;
    private final Runnable onPosterShown;
    /** Launcher icon of a feed item's source app; null when the app is gone. */
    private final Function<String, Bitmap> appIcon;
    private int selected = -1;

    FeedAdapter(PosterCache posters, ExecutorService executor, Handler main, float density,
                Runnable onPosterShown, Function<String, Bitmap> appIcon) {
        this.onPosterShown = onPosterShown;
        this.appIcon = appIcon;
        this.posters = posters;
        this.executor = executor;
        this.main = main;
        this.density = density;
    }

    void setItems(List<FeedItem> feed) {
        items.clear();
        items.addAll(feed);
        notifyDataSetChanged();
    }

    /** Width of a card (px) as laid out by {@link #onBindViewHolder}. */
    int cardWidthPx(int position) {
        return Math.round(Math.round(CARD_HEIGHT_DP * items.get(position).cardAspect()) * density);
    }

    /** Space between a card and the next one (px). */
    int gapPx() {
        return Math.round(16 * density);
    }

    FeedItem item(int position) {
        return items.get(position);
    }

    void setSelected(int position) {
        selected = position;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        Holder h = new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_card, parent, false));
        Tiles.round(h.image, CARD_RADIUS_DP * density);
        h.shadow.setRadius(CARD_RADIUS_DP * density);
        // bottom-left follows the card's own corner, top-right is the badge's only free rounding
        Tiles.roundCorners(h.badge, 0, BADGE_CORNER_DP * density, 0, CARD_RADIUS_DP * density);
        return h;
    }

    @Override
    public void onBindViewHolder(Holder h, int position) {
        FeedItem item = items.get(position);
        int widthDp = Math.round(CARD_HEIGHT_DP * item.cardAspect());
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                cardWidthPx(position), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.rightMargin = gapPx();
        h.itemView.setLayoutParams(lp);

        h.boundUri = item.posterUri;
        h.image.setImageDrawable(null);
        Bitmap icon = appIcon.apply(item.packageName);
        h.badge.setImageBitmap(icon);
        h.badge.setVisibility(icon == null ? View.GONE : View.VISIBLE);
        // the badge grows around the poster's centre so both scale as one card
        h.badge.setPivotX(widthDp * density / 2);
        h.badge.setPivotY((POSTER_TOP_DP + CARD_HEIGHT_DP / 2f - (POSTER_TOP_DP + CARD_HEIGHT_DP - BADGE_SIZE_DP)) * density);
        Tiles.setCardSelected(h.itemView, position == selected, false);
        if (item.posterUri == null) return;

        Bitmap cached = posters.peek(item.posterUri);
        if (cached != null) {
            show(h, cached);
            return;
        }
        final String uri = item.posterUri;
        final int reqW = Math.round(widthDp * density);
        final int reqH = Math.round(CARD_HEIGHT_DP * density);
        executor.execute(() -> {
            Bitmap bmp = posters.load(uri, reqW, reqH);
            if (bmp == null) return;
            main.post(() -> {
                if (uri.equals(h.boundUri)) {
                    show(h, bmp);
                    onPosterShown.run();
                }
            });
        });
    }

    private static void show(Holder h, Bitmap bmp) {
        h.image.setImageBitmap(bmp);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
