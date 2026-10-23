package com.johnmak.atvlauncher;

/** Pure selection and scrolling window of a checklist popup: at most {@link #VISIBLE} lines show at once. */
final class ChecklistWindow {
    static final int VISIBLE = 8;

    private final int size;
    private int selected;
    private int first;

    ChecklistWindow(int size) {
        this.size = size;
    }

    int selected() {
        return selected;
    }

    /** Index of the first visible line. */
    int first() {
        return first;
    }

    /** One past the last visible line. */
    int end() {
        return Math.min(size, first + VISIBLE);
    }

    boolean isVisible(int index) {
        return index >= first && index < end();
    }

    /** Moves the selection (clamped); returns whether it changed. */
    boolean move(int delta) {
        if (size == 0) return false;
        int next = Math.max(0, Math.min(size - 1, selected + delta));
        if (next == selected) return false;
        selected = next;
        if (selected < first) first = selected;
        else if (selected >= first + VISIBLE) first = selected - VISIBLE + 1;
        return true;
    }
}
