package local.cmi.core;

/** Conservative integer block bounds for a segment already transformed into plot coordinates. */
public record PlotGeometryBounds(int x0, int x1, int y0, int y1, int z0, int z1) {
    public static int subdivisions(double ax, double ay, double az, double bx, double by, double bz) {
        double span = Math.max(Math.abs(bx - ax), Math.max(Math.abs(by - ay), Math.abs(bz - az)));
        if (!Double.isFinite(span) || span > 16384) throw new IllegalArgumentException();
        return Math.max(1, (int)Math.ceil(span / 4.0));
    }
    /** Only an explicitly authorized enemy may be penetrated; all other solids block. */
    public static boolean blocks(boolean authorisedEnemy, boolean intersects) {
        return intersects && !authorisedEnemy;
    }
    public static PlotGeometryBounds around(double ax, double ay, double az, double bx, double by, double bz, double radius) {
        if (!Double.isFinite(ax) || !Double.isFinite(ay) || !Double.isFinite(az) ||
            !Double.isFinite(bx) || !Double.isFinite(by) || !Double.isFinite(bz) ||
            !Double.isFinite(radius) || radius < 0 || radius > 64) throw new IllegalArgumentException();
        int margin = (int) Math.ceil(radius + 1.5);
        for (double coordinate : new double[]{ax, ay, az, bx, by, bz}) {
            if (coordinate < Integer.MIN_VALUE + margin || coordinate > Integer.MAX_VALUE - margin - 1.0) throw new IllegalArgumentException();
        }
        return new PlotGeometryBounds((int)Math.floor(Math.min(ax, bx)) - margin, (int)Math.floor(Math.max(ax, bx)) + margin,
                (int)Math.floor(Math.min(ay, by)) - margin, (int)Math.floor(Math.max(ay, by)) + margin,
                (int)Math.floor(Math.min(az, bz)) - margin, (int)Math.floor(Math.max(az, bz)) + margin);
    }
}
