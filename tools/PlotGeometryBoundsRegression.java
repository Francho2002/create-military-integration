import local.cmi.core.PlotGeometryBounds;

public final class PlotGeometryBoundsRegression {
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
    public static void main(String[] args) {
        var straight = PlotGeometryBounds.around(10.5, 4.5, 2.5, 13.5, 4.5, 2.5, 0.5);
        var reverse = PlotGeometryBounds.around(13.5, 4.5, 2.5, 10.5, 4.5, 2.5, 0.5);
        check(straight.equals(reverse), "reversing a segment changes bounds");
        check(straight.x0() <= 9 && straight.x1() >= 14, "corridor margin");
        // Equivalent plot-space segments obtained after a 90-degree rotation around Y.
        var rotated = PlotGeometryBounds.around(-2.5, 4.5, 10.5, -2.5, 4.5, 13.5, 0.5);
        check(rotated.z1() - rotated.z0() == straight.x1() - straight.x0(), "rotation segment extent");
        var larger = PlotGeometryBounds.around(10.5, 4.5, 2.5, 13.5, 4.5, 2.5, 4);
        check(larger.x0() < straight.x0() && larger.z1() > straight.z1(), "radius expansion");
        check(PlotGeometryBounds.blocks(false, true), "allied or unknown solid must block");
        check(!PlotGeometryBounds.blocks(true, true), "enemy solid may be traversed while checking continues");
        check(!PlotGeometryBounds.blocks(false, false), "non-intersecting shape must not block");
        try { PlotGeometryBounds.around(Double.NaN, 0, 0, 1, 1, 1, 0); throw new AssertionError("NaN accepted"); }
        catch (IllegalArgumentException expected) { }
        int divisions = PlotGeometryBounds.subdivisions(0, 0, 0, 100, 100, 100);
        check(divisions == 25, "diagonal subdivision count");
        long splitVolume = 0;
        double previous = 0;
        for (int i = 0; i < divisions; i++) {
            double start = 100.0 * i / divisions, end = 100.0 * (i + 1) / divisions;
            check(start == previous && end > start, "continuous subdivision");
            var part = PlotGeometryBounds.around(start, start, start, end, end, end, 0.5);
            splitVolume += (long)(part.x1() - part.x0() + 1) * (part.y1() - part.y0() + 1) * (part.z1() - part.z0() + 1);
            previous = end;
        }
        check(previous == 100, "final endpoint");
        var whole = PlotGeometryBounds.around(0, 0, 0, 100, 100, 100, 0.5);
        long wholeVolume = (long)(whole.x1() - whole.x0() + 1) * (whole.y1() - whole.y0() + 1) * (whole.z1() - whole.z0() + 1);
        check(splitVolume < wholeVolume, "subdivision scan cost");
        try { PlotGeometryBounds.subdivisions(0, 0, 0, Double.POSITIVE_INFINITY, 0, 0); throw new AssertionError("infinite segment accepted"); }
        catch (IllegalArgumentException expected) { }
        try { PlotGeometryBounds.around(0, 0, 0, 1, 1, 1, -1); throw new AssertionError("negative radius accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("PlotGeometryBounds regression passed");
    }
}
