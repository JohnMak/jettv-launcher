package com.alexmak.atvlauncher;

/**
 * D-pad focus in a grid made of sections (each preceded by a non-focusable header).
 * Up/Down move one row, crossing section borders; the column is remembered across rows.
 * Above the grid sits an optional header row of buttons, right-aligned over the grid columns: Up from the first
 * grid row enters it (the button nearest to the column), Left/Right move inside it, Down returns to the grid.
 */
final class SectionedGridFocus {
    private final int columns;
    private int[] sizes = new int[0];
    private int section;
    private int index;
    private int column;
    private int headerCount;
    private boolean inHeader;
    private int headerIndex;

    SectionedGridFocus(int columns) {
        this.columns = columns;
    }

    /** Sizes of the non-empty sections; the focus is clamped into the new shape. */
    void setSections(int[] newSizes) {
        sizes = newSizes.clone();
        section = Math.max(0, Math.min(sizes.length - 1, section));
        index = sizes.length == 0 ? 0 : Math.max(0, Math.min(sizes[section] - 1, index));
        column = index % columns;
    }

    /** Number of header buttons; focus leaves the header when it becomes empty and is clamped otherwise. */
    void setHeaderCount(int count) {
        headerCount = count;
        if (count == 0) inHeader = false;
        headerIndex = Math.max(0, Math.min(count - 1, headerIndex));
    }

    boolean inHeader() {
        return inHeader;
    }

    int headerIndex() {
        return headerIndex;
    }

    private boolean moveInHeader(FocusModel.Dir dir) {
        switch (dir) {
            case LEFT:
                if (headerIndex == 0) return false;
                headerIndex--;
                break;
            case RIGHT:
                if (headerIndex >= headerCount - 1) return false;
                headerIndex++;
                break;
            case DOWN:
                if (sizes.length == 0) return false;
                inHeader = false;
                section = 0;
                index = Math.min(column, sizes[0] - 1);
                return true;
            default:
                return false;
        }
        column = Math.max(0, Math.min(columns - 1, headerIndex + columns - headerCount));
        return true;
    }

    private void enterHeader() {
        inHeader = true;
        headerIndex = Math.max(0, headerCount - columns + column);
        headerIndex = Math.min(headerIndex, headerCount - 1);
    }

    void select(int newSection, int newIndex) {
        section = newSection;
        index = newIndex;
        column = index % columns;
    }

    int section() {
        return section;
    }

    int index() {
        return index;
    }

    /** Adapter position of the focused item, counting one header per section. */
    int flatPosition() {
        int pos = 0;
        for (int s = 0; s < section; s++) pos += 1 + sizes[s];
        return pos + 1 + index;
    }

    boolean move(FocusModel.Dir dir) {
        if (inHeader) return moveInHeader(dir);
        if (sizes.length == 0) return false;
        int size = sizes[section];
        int row = index / columns;
        switch (dir) {
            case LEFT:
                if (index % columns == 0) return false;
                index--;
                column = index % columns;
                return true;
            case RIGHT:
                if (index % columns == columns - 1 || index + 1 >= size) return false;
                index++;
                column = index % columns;
                return true;
            case UP:
                if (row > 0) {
                    index -= columns;
                } else if (section > 0) {
                    section--;
                    int prev = sizes[section];
                    index = Math.min(((prev - 1) / columns) * columns + column, prev - 1);
                } else if (headerCount > 0) {
                    enterHeader();
                } else {
                    return false;
                }
                return true;
            default:
                if (index + columns < size) {
                    index += columns;
                } else if ((row + 1) * columns < size) {
                    index = size - 1;
                } else if (section < sizes.length - 1) {
                    section++;
                    index = Math.min(column, sizes[section] - 1);
                } else {
                    return false;
                }
                return true;
        }
    }
}
