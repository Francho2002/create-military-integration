package local.cmi.core;
import java.util.ArrayDeque;
/** Scoped only around the native server firing method; never inferred by proximity. */
public final class ShotContext {
    private ShotContext(){}
    private static final ThreadLocal<ArrayDeque<Actor>> ACTORS=ThreadLocal.withInitial(ArrayDeque::new);
    public static void push(Actor actor){ACTORS.get().push(actor);}
    public static void pop(){var stack=ACTORS.get();if(!stack.isEmpty())stack.pop();if(stack.isEmpty())ACTORS.remove();}
    public static Actor current(){return ACTORS.get().peek();}
}
