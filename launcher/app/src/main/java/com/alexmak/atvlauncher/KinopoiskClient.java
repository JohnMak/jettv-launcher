package com.alexmak.atvlauncher;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Two-call Kinopoisk lookup (search by keyword, then the film by id) through kinopoiskapiunofficial.tech.
 * Blocking: call from a worker thread. HTTP 429 starts a back-off during which every lookup fails at once;
 * errors are returned, never turned into "not found".
 */
final class KinopoiskClient {
    private static final long BACKOFF_MS = 60_000;

    private final Map<String, String> headers;
    private volatile long backoffUntil;

    KinopoiskClient(String token) {
        this.headers = Collections.singletonMap("X-API-KEY", token);
    }

    /** ok(NONE) = Kinopoisk has no such title; err = try again later (nothing may be cached). */
    FetchResult<KinopoiskInfo> lookup(String title, int year, TitleMatch.Kind kind) {
        String keyword = TitleMatch.clean(title);
        if (keyword.isEmpty()) return FetchResult.ok(KinopoiskInfo.NONE);
        FetchResult<String> search = get(KinopoiskModel.searchUrl(keyword));
        if (!search.isOk()) return FetchResult.err(search.error());
        FetchResult<List<TitleMatch.Candidate>> candidates = KinopoiskModel.parseSearch(search.value());
        if (!candidates.isOk()) return FetchResult.err(candidates.error());
        Optional<TitleMatch.Candidate> match = TitleMatch.pick(title, year, kind, candidates.value());
        if (!match.isPresent()) return FetchResult.ok(KinopoiskInfo.NONE);
        FetchResult<String> film = get(KinopoiskModel.filmUrl(match.get().id));
        if (!film.isOk()) return FetchResult.err(film.error());
        return KinopoiskModel.parseFilm(film.value(), year);
    }

    private FetchResult<String> get(String url) {
        if (System.currentTimeMillis() < backoffUntil) return FetchResult.err("kinopoisk: backing off after HTTP 429");
        FetchResult<String> body = HttpText.get(url, headers);
        if (!body.isOk() && body.error().equals(HttpText.RATE_LIMITED)) {
            backoffUntil = System.currentTimeMillis() + BACKOFF_MS;
        }
        return body;
    }
}
