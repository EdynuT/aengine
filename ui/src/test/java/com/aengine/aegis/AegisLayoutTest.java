package com.aengine.aegis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Rows and columns: placing, growing, aligning, fitting content, scrolling and hit-testing. */
class AegisLayoutTest {

    private final AegisLayout layout = new AegisLayout(64);

    @Test
    void childrenFollowEachOtherInsidePaddingWithGaps() {
        int row = layout.row(AegisLayout.NONE);
        layout.setPadding(row, 10.0f);
        layout.setGap(row, 5.0f);
        int a = box(row, 30.0f, 20.0f);
        int b = box(row, 40.0f, 20.0f);

        layout.solve(row, 100.0f, 50.0f, 300.0f, 60.0f);

        assertEquals(110.0f, layout.x(a));
        assertEquals(60.0f,  layout.y(a));
        assertEquals(145.0f, layout.x(b));
        assertEquals(40.0f,  layout.width(b));
    }

    @Test
    void spareSpaceIsSharedByGrowFactor() {
        int row = layout.row(AegisLayout.NONE);
        int a = box(row, 0.0f, 10.0f);
        int b = box(row, 0.0f, 10.0f);
        layout.setGrow(a, 1.0f);
        layout.setGrow(b, 2.0f);

        layout.solve(row, 0.0f, 0.0f, 300.0f, 10.0f);

        assertEquals(100.0f, layout.width(a));
        assertEquals(200.0f, layout.width(b));
    }

    @Test
    void alignmentMovesTheGroupAlongAndEachChildAcross() {
        int row = layout.row(AegisLayout.NONE);
        layout.setAlignX(row, AegisLayout.Align.END);
        layout.setAlignY(row, AegisLayout.Align.STRETCH);
        int a = box(row, 50.0f, 10.0f);

        layout.solve(row, 0.0f, 0.0f, 200.0f, 80.0f);

        assertEquals(150.0f, layout.x(a));
        assertEquals(80.0f,  layout.height(a));
        assertThrows(IllegalArgumentException.class, () -> layout.setAlignX(row, AegisLayout.Align.STRETCH));
    }

    @Test
    void aContainerWithNoSizeTakesItsContents() {
        int column = layout.column(AegisLayout.NONE);
        int row = layout.row(column);
        layout.setPadding(row, 4.0f);
        layout.setGap(row, 6.0f);
        box(row, 30.0f, 20.0f);
        box(row, 40.0f, 35.0f);

        layout.solve(column, 0.0f, 0.0f, 500.0f, 500.0f);

        assertEquals(30.0f + 6.0f + 40.0f + 8.0f, layout.width(row));
        assertEquals(35.0f + 8.0f, layout.height(row));
    }

    @Test
    void anUnchangedTreeIsNotSolvedAgain() {
        int row = layout.row(AegisLayout.NONE);
        int a = box(row, 10.0f, 10.0f);
        layout.solve(row, 0.0f, 0.0f, 100.0f, 100.0f);
        layout.solve(row, 0.0f, 0.0f, 100.0f, 100.0f);
        assertEquals(1, layout.solves());
        assertEquals(1, layout.solveSkips());

        layout.setSize(a, 10.0f, 10.0f);          // the value it has: nothing changed
        layout.solve(row, 0.0f, 0.0f, 100.0f, 100.0f);
        assertEquals(1, layout.solves());

        layout.setSize(a, 20.0f, 10.0f);
        layout.solve(row, 0.0f, 0.0f, 100.0f, 100.0f);
        assertEquals(2, layout.solves());
    }

    @Test
    void attachMovesANodeAndRefusesALoop() {
        int first  = layout.row(AegisLayout.NONE);
        int second = layout.row(AegisLayout.NONE);
        int a = box(first, 10.0f, 10.0f);
        int b = box(first, 10.0f, 10.0f);

        layout.attach(a, second);

        assertEquals(b, layout.firstChild(first));
        assertEquals(a, layout.firstChild(second));
        assertEquals(second, layout.parent(a));
        assertThrows(IllegalArgumentException.class, () -> layout.attach(second, second));
        assertThrows(IllegalArgumentException.class, () -> layout.attach(first, b));
    }

    @Test
    void aScrollingColumnMovesItsChildrenAndKeepsTheOffsetInRange() {
        int column = scrollingColumn();   // five boxes of 50, gap 5: 270 long in 200 less 16 of padding

        layout.solve(column, 0.0f, 0.0f, 100.0f, 200.0f);
        assertEquals(270.0f, layout.scrollContent(column));
        assertEquals(86.0f,  layout.scrollRange(column));
        assertEquals(8.0f,   layout.y(layout.firstChild(column)));

        layout.setScrollOffset(column, 50.0f);
        layout.solve(column, 0.0f, 0.0f, 100.0f, 200.0f);
        assertEquals(-42.0f, layout.y(layout.firstChild(column)));

        layout.setScrollOffset(column, 999.0f);
        layout.solve(column, 0.0f, 0.0f, 100.0f, 200.0f);
        assertEquals(86.0f, layout.scrollOffset(column));

        layout.setScrollOffset(column, -20.0f);
        layout.solve(column, 0.0f, 0.0f, 100.0f, 200.0f);
        assertEquals(0.0f, layout.scrollOffset(column));
    }

    @Test
    void aPointInAScrollingColumnsPaddingIsTheColumns() {
        int column = scrollingColumn();
        layout.setScrollOffset(column, 50.0f);
        layout.solve(column, 0.0f, 0.0f, 100.0f, 200.0f);

        // The first child reaches up into the padding, where it is cut off.
        assertEquals(column, layout.nodeAt(column, 20.0f, 3.0f));
        // 100 down, less 8 of padding, scrolled by 50: 142 into the content, the third box (110 to 160).
        int third = layout.nextSibling(layout.nextSibling(layout.firstChild(column)));
        assertEquals(third, layout.nodeAt(column, 20.0f, 100.0f));
        assertEquals(AegisLayout.NONE, layout.nodeAt(column, 500.0f, 100.0f));
    }

    @Test
    void aScrollingColumnAsksForNoHeightOfItsOwn() {
        int outer = layout.column(AegisLayout.NONE);
        int column = layout.column(outer);
        layout.setScrollable(column, true);
        box(column, 50.0f, 400.0f);

        layout.solve(outer, 0.0f, 0.0f, 100.0f, 200.0f);
        assertEquals(0.0f, layout.height(column));

        layout.setGrow(column, 1.0f);
        layout.solve(outer, 0.0f, 0.0f, 100.0f, 200.0f);
        assertEquals(200.0f, layout.height(column));
        assertEquals(200.0f, layout.scrollRange(column));
    }

    @Test
    void namesAreAsciiLettersAndDigits() {
        int row = layout.row(AegisLayout.NONE);
        layout.setNodeName(row, "toolBar2");
        assertEquals("toolBar2", layout.nodeName(row));
        assertThrows(IllegalArgumentException.class, () -> layout.setNodeName(row, "tool.bar"));
        assertThrows(IllegalArgumentException.class, () -> layout.setNodeName(row, "2bar"));
        assertThrows(IllegalArgumentException.class, () -> layout.setNodeName(row, "tool_bar"));
        assertThrows(IllegalArgumentException.class, () -> layout.setNodeName(row, "barração"));
    }

    private int scrollingColumn() {
        int column = layout.column(AegisLayout.NONE);
        layout.setPadding(column, 8.0f);
        layout.setGap(column, 5.0f);
        layout.setScrollable(column, true);
        for (int i = 0; i < 5; i++) box(column, 50.0f, 50.0f);
        return column;
    }

    private int box(int parent, float width, float height) {
        int box = layout.box(parent);
        layout.setSize(box, width, height);
        return box;
    }
}
