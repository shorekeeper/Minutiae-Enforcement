package org.synergyst.minutiae.lang.eval;

import java.util.HashMap;
import java.util.Map;

/**
 * A chained evaluation environment.
 *
 * <p>The root frame holds global bindings and grows monotonically as
 * declarations evaluate; child frames are created per closure application and
 * per template instantiation and are immutable after construction. Under the
 * declaration-before-use discipline, every name a closure captures is bound
 * in its chain before the closure is created, so later growth of the root is
 * invisible to correctness.
 */
public final class Env {

    private final Env parent;
    private final Map<String, Value> bindings;

    private Env(final Env parent, final int capacity) {
        this.parent = parent;
        this.bindings = new HashMap<>(capacity);
    }

    /** Creates the root frame. */
    public static Env root() {
        return new Env(null, 32);
    }

    /** Creates a child frame of this environment. */
    public Env child(final int capacity) {
        return new Env(this, Math.max(2, capacity));
    }

    /** Binds a name in this frame; the name must not already be bound here. */
    public void bind(final String name, final Value value) {
        bindings.put(name, value);
    }

    /**
     * Resolves a name through the chain.
     *
     * @param name the name
     * @return the bound value, or null when unbound
     */
    public Value lookup(final String name) {
        for (Env e = this; e != null; e = e.parent) {
            final Value v = e.bindings.get(name);
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}