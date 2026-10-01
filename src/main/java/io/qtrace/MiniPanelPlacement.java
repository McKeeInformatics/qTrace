package io.qtrace;

/**
 * Where the mini-panel column sits over the viewers, along one axis: its position is kept as a
 * fraction of the free space (0 = near edge, 1 = far edge), so it stays in place — and inside
 * the viewers — whatever their size.
 */
final class MiniPanelPlacement {

    private MiniPanelPlacement() {}

    /** Pixel offset of a {@code size}-long column in a {@code container}-long viewer region. */
    static double position(double fraction, double container, double size, double margin) {
        double free = Math.max(0, container - size - 2 * margin);
        return margin + clamp(fraction) * free;
    }

    /** The fraction a dragged-to {@code position} stands for; {@code current} when there is no room to move. */
    static double fraction(double position, double container, double size, double margin, double current) {
        double free = container - size - 2 * margin;
        if (free <= 0) return current;
        return clamp((position - margin) / free);
    }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
}
