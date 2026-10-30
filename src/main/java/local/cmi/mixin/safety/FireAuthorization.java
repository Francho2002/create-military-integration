package local.cmi.mixin.safety;

/** Scoped to the synchronous, already checked fire call. */
public final class FireAuthorization {
    private FireAuthorization() {}
    private static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();
    public static boolean permits(Object wfc) { return CURRENT.get() == wfc; }
    public static void run(Object wfc, Runnable fire) {
        Object previous = CURRENT.get();
        CURRENT.set(wfc);
        try { fire.run(); } finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
}
