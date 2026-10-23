package com.johnmak.atvlauncher;

import java.util.List;

/** Focus cursor over the cleanup list: a row and, for rows with actions, one of its columns. Locked rows are focusable without a column. */
final class CleanupRowFocus {
    enum Dir { LEFT, RIGHT, UP, DOWN }

    private List<CleanupList.Row> rows;
    private int rowIndex;
    private CleanupList.Col col;

    CleanupRowFocus(List<CleanupList.Row> rows) {
        this.rows = rows;
        this.rowIndex = rows.isEmpty() ? -1 : 0;
        this.col = rows.isEmpty() ? null : nearest(rows.get(0), CleanupList.Col.TOGGLE);
    }

    /** -1 when the list is empty. */
    int rowIndex() { return rowIndex; }

    /** Null on a locked row or an empty list. */
    CleanupList.Col col() { return col; }

    CleanupList.Row row() { return rowIndex < 0 ? null : rows.get(rowIndex); }

    /** Moves the cursor; true when it changed. */
    boolean move(Dir dir) {
        if (rowIndex < 0) return false;
        int prevRow = rowIndex;
        CleanupList.Col prevCol = col;
        switch (dir) {
            case LEFT:
            case RIGHT:
                if (col != null) col = step(rows.get(rowIndex), col, dir == Dir.RIGHT ? 1 : -1);
                break;
            default:
                int next = rowIndex + (dir == Dir.DOWN ? 1 : -1);
                if (next >= 0 && next < rows.size()) {
                    rowIndex = next;
                    col = nearest(rows.get(next), col == null ? CleanupList.Col.TOGGLE : col);
                }
        }
        return rowIndex != prevRow || col != prevCol;
    }

    /** Replaces the rows after a refresh, keeping the focused package when it is still listed (else clamping the index). */
    void rebind(List<CleanupList.Row> newRows) {
        String pkg = row() == null ? null : row().pkg;
        CleanupList.Col want = col == null ? CleanupList.Col.TOGGLE : col;
        rows = newRows;
        if (rows.isEmpty()) {
            rowIndex = -1;
            col = null;
            return;
        }
        int found = -1;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).pkg.equals(pkg)) found = i;
        rowIndex = found >= 0 ? found : Math.min(Math.max(rowIndex, 0), rows.size() - 1);
        col = nearest(rows.get(rowIndex), want);
    }

    /** Confirmation key {@code col:pkg}; null when no column is focused. */
    String confirmKey() { return col == null ? null : col.name() + ":" + row().pkg; }

    /** Two-press confirm on the focused action: true when this press confirms. False also when nothing is focusable. */
    boolean press(CleanupConfirm confirm) {
        String key = confirmKey();
        return key != null && confirm.press(key);
    }

    private static CleanupList.Col step(CleanupList.Row row, CleanupList.Col from, int delta) {
        CleanupList.Col[] all = CleanupList.Col.values();
        for (int i = from.ordinal() + delta; i >= 0 && i < all.length; i += delta) {
            if (row.actions.contains(all[i])) return all[i];
        }
        return from;
    }

    /** The row's available column closest to {@code want} (lower on a tie); null for a row without actions. */
    private static CleanupList.Col nearest(CleanupList.Row row, CleanupList.Col want) {
        CleanupList.Col best = null;
        for (CleanupList.Col c : CleanupList.Col.values()) {
            if (!row.actions.contains(c)) continue;
            if (best == null || Math.abs(c.ordinal() - want.ordinal()) < Math.abs(best.ordinal() - want.ordinal())) best = c;
        }
        return best;
    }
}
