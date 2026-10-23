package com.alexmak.atvlauncher;

/** Either a value or an error message (optionally with a typed error kind for the UI); never both. */
final class FetchResult<T> {
    private final T value;
    private final String error;
    private final Object errorKind;

    private FetchResult(T value, String error, Object errorKind) {
        this.value = value;
        this.error = error;
        this.errorKind = errorKind;
    }

    static <T> FetchResult<T> ok(T value) {
        if (value == null) throw new IllegalArgumentException("value");
        return new FetchResult<>(value, null, null);
    }

    static <T> FetchResult<T> err(String error) {
        if (error == null) throw new IllegalArgumentException("error");
        return new FetchResult<>(null, error, null);
    }

    /** Error carrying a typed kind; read it back with {@link #errorKind(Class)}. */
    static <T> FetchResult<T> err(String error, Object kind) {
        if (error == null) throw new IllegalArgumentException("error");
        if (kind == null) throw new IllegalArgumentException("kind");
        return new FetchResult<>(null, error, kind);
    }

    boolean isOk() { return error == null; }

    /** Only valid when {@link #isOk()}. */
    T value() {
        if (error != null) throw new IllegalStateException(error);
        return value;
    }

    /** Only valid when not {@link #isOk()}. */
    String error() {
        if (error == null) throw new IllegalStateException("ok");
        return error;
    }

    /** The typed error kind if this is an error carrying one of {@code type}; otherwise null. */
    <K> K errorKind(Class<K> type) {
        return type.isInstance(errorKind) ? type.cast(errorKind) : null;
    }
}
