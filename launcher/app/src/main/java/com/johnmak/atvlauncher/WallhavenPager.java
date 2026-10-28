package com.johnmak.atvlauncher;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The pure streaming-pagination state machine of the Wallhaven slideshow (plan B5) — the successor to the one-shot
 * {@code queue/count/cursor} + modulo cycle, which could not express Wallhaven's 24-results/page seed-based random
 * pagination without fetching every page up front. No Android types, no I/O, no threads: it is driven entirely from
 * the main thread by {@link ArtScreensaverView.Slideshow} and unit-tested offline.
 *
 * <p><b>Bounded 2-page window.</b> At most two pages' descriptors are buffered at once: the {@code cur} page being
 * consumed and one prefetched {@code next} page. When {@code cur} is exhausted it is DISCARDED and {@code next} is
 * promoted — the window never grows past two pages (≤48 descriptors), so memory stays bounded regardless of how many
 * pages the seed has.
 *
 * <p><b>Per-seed no-repeat.</b> Every descriptor id delivered for the CURRENT seed is remembered; a later page that
 * repeats an id has that descriptor dropped. Repeats are therefore impossible WITHIN one seeded pass. When the seed's
 * result set is exhausted ({@code curPage ≥ lastPage} and {@code cur} fully consumed) a FRESH seed is requested (page
 * 1) and the seen-id set is reset — so repeats are possible only across seed boundaries, the explicit endless-stream
 * contract.
 *
 * <p><b>Refill is one-ahead only.</b> {@link #needsFetch()} asks for the {@code next} page while {@code cur} still has
 * images (prefetch) — never all pages. A valid-but-empty page (zero usable descriptors, e.g. all-duplicates after
 * dedup) simply promotes to empty and the next {@link #needsFetch()} advances to the following page. Page-FETCH
 * failures and retry/backoff are NOT modelled here — they are the {@link ArtScreensaverView.Slideshow}'s concern
 * (it withholds {@link #onPage} until a page actually arrives); this machine only ever sees delivered pages.
 */
final class WallhavenPager {
    /** The next fetch the pager wants: a page under the current {@code seed}, or a FRESH seed (page 1, {@code seed=""}). */
    static final class Fetch {
        final String seed; // "" ⇒ let the server mint a fresh seed (end-of-results restart)
        final int page;
        final boolean fresh;

        Fetch(String seed, int page, boolean fresh) {
            this.seed = seed;
            this.page = page;
            this.fresh = fresh;
        }
    }

    private String seed = "";
    private int lastPage = 0;
    private int curPage = 0;
    private List<WallhavenImage> cur = new ArrayList<>();
    private int curIdx = 0;
    private List<WallhavenImage> next = null; // prefetched one-ahead page (null ⇒ slot empty)
    private int nextPage = 0;
    private final Set<String> seen = new HashSet<>();

    /** (Re)initialises the window from a fresh page-1 result (a server-minted seed); resets the seen-id set. */
    void start(WallhavenSearch.Page page1) {
        applyFresh(page1);
    }

    /** The descriptor currently due for decode, or null when the window is momentarily empty (a refill is needed). */
    WallhavenImage current() {
        promoteIfNeeded();
        return curIdx < cur.size() ? cur.get(curIdx) : null;
    }

    /** Marks the current descriptor consumed (decoded or rejected — plan: both consume the entry) and advances,
     * promoting the prefetched next page once the current page is fully consumed. */
    void consume() {
        if (curIdx < cur.size()) curIdx++;
        promoteIfNeeded();
    }

    /** Whether a page fetch should be issued now: no {@code next} page buffered AND more is reachable — the next page
     * under the current seed while it exists (prefetch, even while {@code cur} still has images), or a fresh seed once
     * the current seed is fully shown. False while a {@code next} page is already buffered (one-ahead cap). */
    boolean needsFetch() {
        promoteIfNeeded();
        if (next != null) return false;
        if (curPage < lastPage) return true;            // a further page exists under this seed → prefetch it
        return curIdx >= cur.size();                    // seed exhausted AND nothing left to show → fresh seed
    }

    /** The fetch {@link #needsFetch()} is asking for. Only meaningful when {@link #needsFetch()} is true. */
    Fetch plannedFetch() {
        promoteIfNeeded();
        if (curPage < lastPage) return new Fetch(seed, curPage + 1, false);
        return new Fetch("", 1, true);                  // end-of-results → fresh random seed
    }

    /** Delivers a fetched page and returns how many USABLE (post-dedup) descriptors it contributed. A {@code fresh}
     * page replaces the whole window under a new seed (seen-id reset); a non-fresh page fills the one-ahead {@code next}
     * slot (deduped against the current seed's seen-id set). A return of 0 means the page is valid-but-empty (no
     * descriptors, or all duplicates) — the caller must treat that as an error, NOT a silent success. */
    int onPage(WallhavenSearch.Page page, boolean fresh) {
        if (fresh) {
            return applyFresh(page);
        }
        next = dedup(page.images);
        nextPage = curPage + 1;                         // a non-fresh fetch is always curPage+1 at plan time
        return next.size();
    }

    private int applyFresh(WallhavenSearch.Page page) {
        seed = page.seed;
        lastPage = page.lastPage;
        seen.clear();
        cur = dedup(page.images);
        curIdx = 0;
        curPage = Math.max(1, page.page);
        next = null;
        nextPage = 0;
        return cur.size();
    }

    private void promoteIfNeeded() {
        if (curIdx >= cur.size() && next != null) {
            cur = next;
            curIdx = 0;
            curPage = nextPage;
            next = null;
            nextPage = 0;
        }
    }

    /** Filters out ids already seen for the current seed and records the survivors (per-seed no-repeat). */
    private List<WallhavenImage> dedup(List<WallhavenImage> images) {
        List<WallhavenImage> out = new ArrayList<>();
        for (WallhavenImage img : images) {
            if (seen.add(img.id)) out.add(img); // add() is true only for a NOT-yet-seen id
        }
        return out;
    }

    // ---- test observability ----
    String seed() { return seed; }
    int curPage() { return curPage; }
    int seenCount() { return seen.size(); }
    boolean hasNext() { return next != null; }
}
