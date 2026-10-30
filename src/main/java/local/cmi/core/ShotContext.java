package local.cmi.core;
import java.util.ArrayDeque;
/** Scoped only around the native server firing method; never inferred by proximity. */
public final class ShotContext {
    private ShotContext(){}
    private static final ThreadLocal<ArrayDeque<Actor>> ACTORS=ThreadLocal.withInitial(ArrayDeque::new);
    public static void push(Actor actor){ACTORS.get().push(actor);}
    public static void pop(){var stack=ACTORS.get();if(!stack.isEmpty())stack.pop();if(stack.isEmpty())ACTORS.remove();}
    public static Actor current(){return ACTORS.get().peek();}
    private static final ThreadLocal<Integer> MANAGED=ThreadLocal.withInitial(() -> 0);
    public static boolean managed(){return MANAGED.get()>0;}
    public static void runManaged(Runnable action){
        int depth=MANAGED.get();MANAGED.set(depth+1);
        try{action.run();}finally{if(depth==0)MANAGED.remove();else MANAGED.set(depth);}
    }
}
