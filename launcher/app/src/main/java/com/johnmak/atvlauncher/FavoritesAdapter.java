package com.johnmak.atvlauncher;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Favorites row: one banner tile per favorite, the "all apps" button, then one tile per frequent non-favorite app (the
 * continuation, see {@link FavoritesRowState}). Opened apps carry a small dot under the tile.
 */
final class FavoritesAdapter extends RecyclerView.Adapter<FavoritesAdapter.Holder> {
    static final class Holder extends RecyclerView.ViewHolder {
        final DimImageView image;
        final ShadowView shadow;
        final View dot;

        Holder(View v) {
            super(v);
            image = v.findViewById(R.id.tile_image);
            shadow = v.findViewById(R.id.shadow);
            dot = v.findViewById(R.id.tile_dot);
        }
    }

    /** Height of the favorites row (activity_main.xml); tiles are centred in it. */
    private static final float ROW_HEIGHT_DP = 130;
    private static final float DOT_GAP_DP = 7;

    private final List<AppEntry> items = new ArrayList<>();
    private final FavoritesRowState state = new FavoritesRowState();
    private final Set<String> dotted = new HashSet<>();
    private final float tileRadius;
    private final float density;
    private int selected = -1;

    FavoritesAdapter(float density) {
        this.density = density;
        this.tileRadius = 10 * density;
    }

    /** @param openedPackages packages that get the "opened" dot */
    void setItems(List<AppEntry> favorites, List<AppEntry> frequent, Set<String> openedPackages) {
        items.clear();
        items.addAll(favorites);
        items.addAll(frequent);
        dotted.clear();
        dotted.addAll(openedPackages);
        state.setApps(favorites.size(), frequent.size());
        notifyDataSetChanged();
    }

    /** Tile size and spacing for the current row width and item count; rebinds every tile. */
    void setGeometry(FavoritesRowLayout geometry) {
        state.setGeometry(geometry);
        notifyDataSetChanged();
    }

    /** The tile shown focused; views bound later get this state without animation. */
    void setSelected(int position) {
        selected = position;
    }

    FavoritesRowState.Kind kind(int position) {
        return state.kind(position);
    }

    /** App of a tile; null for the apps button. */
    AppEntry entry(int position) {
        return state.kind(position) == FavoritesRowState.Kind.APPS_BUTTON ? null : items.get(state.entryIndex(position));
    }

    /** Position of the apps button. */
    int buttonIndex() {
        return state.buttonIndex();
    }

    /** Left edge of a tile relative to the content start; needs geometry. */
    int leftOf(int position) {
        return state.geometry().leftOf(position);
    }

    /** See {@link FavoritesRowLayout#scrollFor}; needs geometry. */
    int scrollFor(int position, int current) {
        return state.geometry().scrollFor(position, current);
    }

    /** Tiles that exactly fill the row width: favorites plus the apps button. */
    int fitCount() {
        return state.fitCount();
    }

    /** Tiles the row holds once laid out (focus model size); the adapter itself shows none before geometry. */
    int tileCount() {
        return state.tileCount();
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        Holder h = new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tile, parent, false));
        h.shadow.setRadius(tileRadius);
        return h;
    }

    @Override
    public void onBindViewHolder(Holder h, int position) {
        AppEntry entry = entry(position);
        if (entry != null) {
            Tiles.bindImage(h.image, entry.tile, tileRadius);
        } else {
            h.image.setImageResource(R.drawable.tile_apps);
            Tiles.round(h.image, tileRadius);
        }
        FavoritesRowLayout geometry = state.geometry();
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(geometry.tileWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.rightMargin = geometry.gapAfter(position);
        h.itemView.setLayoutParams(lp);
        FrameLayout.LayoutParams ilp = (FrameLayout.LayoutParams) h.image.getLayoutParams();
        ilp.height = geometry.tileHeight;
        h.image.setLayoutParams(ilp);
        FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) h.shadow.getLayoutParams();
        slp.height = geometry.tileHeight;
        h.shadow.setLayoutParams(slp);
        Tiles.setSelected(h.image, h.shadow, position == selected, false);
        boolean dot = entry != null && dotted.contains(entry.packageName);
        h.dot.setVisibility(dot ? View.VISIBLE : View.GONE);
        if (dot) {
            FrameLayout.LayoutParams dlp = (FrameLayout.LayoutParams) h.dot.getLayoutParams();
            dlp.topMargin = Math.round((ROW_HEIGHT_DP * density + geometry.tileHeight) / 2 + DOT_GAP_DP * density);
            h.dot.setLayoutParams(dlp);
        }
    }

    @Override
    public int getItemCount() {
        return state.visibleCount();
    }
}
