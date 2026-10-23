package com.alexmak.atvlauncher;

/** Either a value or an error message; never both. */
final class FetchResult<T> {
    private final T value;
    private final String error;

    private FetchResult(T value, String error) {
        this.value = value;
        this.error = error;
    }

    static <T> FetchResult<T> ok(T value) {
        if (value == null) throw new IllegalArgumentException("value");
        return new FetchResult<>(value, null);
    }

    static <T> FetchResult<T> err(String error) {
        if (error == null) throw new IllegalArgumentException("error");
        return new FetchResult<>(null, error);
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
}
