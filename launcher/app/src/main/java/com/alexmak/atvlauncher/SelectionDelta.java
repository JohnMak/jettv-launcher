package com.alexmak.atvlauncher;

import java.util.HashSet;
import java.util.Set;

/**
 * Which views change their visual focus state when the selection moves from one state to another.
 * Only the previously focused and the newly focused view change; the first application (previous == null) changes all.
 */
public final class SelectionDelta {
    public enum Row { TOP, FAVORITES, WATCH_NEXT, MIXED, WEATHER }

    /** A view in a row, by index. */
    public static final class ViewRef {
        public final Row row;
        public final int index;

        public ViewRef(Row row, int index) {
            this.row = row;
            this.index = index;
        }

        @Override public boolean equals(Object o) {
            return o instanceof ViewRef && ((ViewRef) o).row == row && ((ViewRef) o).index == index;
        }

        @Override public int hashCode() {
            return row.hashCode() * 31 + index;
        }

        @Override public String toString() {
            return row + "[" + index + "]";
        }
    }

    /** Visual selection: the active row (zone), the selected index of every row and the size of every row (indexed by Row.ordinal()). */
    public static final class Selection {
        public final Row zone;
        public final int[] index;
        public final int[] size;

        public Selection(Row zone, int[] index, int[] size) {
            this.zone = zone;
            this.index = index;
            this.size = size;
        }
    }

    public final Set<ViewRef> changedViews;
    /** The focused row changed: the info field must fade. */
    public final boolean rowChanged;
    /** The info text must be replaced. */
    public final boolean textChanged;

    private SelectionDelta(Set<ViewRef> changedViews, boolean rowChanged, boolean textChanged) {
        this.changedViews = changedViews;
        this.rowChanged = rowChanged;
        this.textChanged = textChanged;
    }

    /** previous == null on the first application: every view gets its initial state. */
    public static SelectionDelta between(Selection previous, Selection next) {
        Set<ViewRef> changed = new HashSet<>();
        if (previous == null) {
            for (Row row : Row.values()) {
                for (int i = 0; i < next.size[row.ordinal()]; i++) {
                    changed.add(new ViewRef(row, i));
                }
            }
            return new SelectionDelta(changed, true, true);
        }
        ViewRef was = focused(previous);
        ViewRef now = focused(next);
        if (!was.equals(now)) {
            changed.add(was);
            changed.add(now);
        }
        boolean rowChanged = previous.zone != next.zone;
        boolean textChanged = rowChanged || !was.equals(now);
        return new SelectionDelta(changed, rowChanged, textChanged);
    }

    /** The one view that is visually focused in a selection. */
    public static ViewRef focused(Selection s) {
        return new ViewRef(s.zone, s.index[s.zone.ordinal()]);
    }
}
