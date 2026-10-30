package local.cmi.missiles;

/** Pure mode policy: ownership is intentionally not an input. */
public final class GuidanceMode {
    private GuidanceMode() {}

    public static boolean managed(boolean marker, boolean designated, boolean interceptor) {
        return marker || designated || interceptor;
    }

    /** Persist the opt-in before removing an obsolete designation or interceptor setting. */
    public static boolean latchOnLoss(boolean marker, boolean designated, boolean interceptor) {
        return managed(marker, designated, interceptor);
    }
}
