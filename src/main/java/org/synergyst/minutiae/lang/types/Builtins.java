package org.synergyst.minutiae.lang.types;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.synergyst.minutiae.lang.types.Type.*;

/**
 * The closed built-in world of the definition language.
 *
 * <p>The class defines, once and immutably: the primitive type namespace, the
 * built-in sum types and their value-namespace path roots, the built-in record
 * descriptors, the event catalogue, and the environment-reading built-in
 * functions with their effect grants. All collections are unmodifiable and
 * safe for concurrent read.
 *
 * <h2>Sum types and path roots</h2>
 * <p>Constructors of a sum type are addressed by a two-segment path whose
 * first segment is the sum's name. Nullary constructors are values; a
 * constructor with fields is applied positionally in call form or by named
 * fields in record form.
 *
 * <ul>
 *   <li>{@code Measure}: {@code WARN}, {@code CENSURE}, {@code MUTE},
 *       {@code KICK}, {@code QUARANTINE}, {@code SUSPENSION},
 *       {@code CUSTODY}.</li>
 *   <li>{@code Duration}: {@code Fixed(value: Duration)},
 *       {@code Permanent}. The path root {@code Duration} addresses this sum
 *       in the value namespace; the type name {@code Duration} in the type
 *       namespace denotes the primitive finite span. The two namespaces are
 *       disjoint by construction.</li>
 *   <li>{@code Escalation}: {@code None}, {@code Steps(steps: Duration-term
 *       list)}.</li>
 *   <li>{@code Annotation}: {@code Notify(channel: Text)}, {@code Evidence},
 *       {@code Escalate}, {@code Silent}, {@code Shadow}, {@code Ghost},
 *       {@code Rubberband}, {@code Transcript}, {@code Reason(text: Text)},
 *       {@code Link(ref: Text)}, {@code WarnFirst(count: Int)},
 *       {@code Decay(window: Duration)}, {@code Stay(window: Duration)},
 *       {@code Tariff(floor: Duration)},
 *       {@code Probation(window: Duration)}.</li>
 *   <li>{@code Attribution}: {@code System}, {@code Staff(name: Text)}.</li>
 *   <li>{@code DryRun}: {@code InheritSafety}, {@code Forced}.</li>
 *   <li>{@code GroupBy}: {@code Subject}, {@code Global},
 *       {@code Field(name: Text)}.</li>
 *   <li>{@code Trigger}: {@code Atomic}, {@code Repeated(count: Int,
 *       within: Duration, group_by: GroupBy)}, {@code Sequence(within:
 *       Duration, group_by: GroupBy, steps: Step list)}.</li>
 * </ul>
 *
 * <h2>Record descriptors</h2>
 * <p>{@code Sanction} and {@code Layout} are total record descriptors: every
 * field is mandatory at construction and absence of effect is expressed by an
 * explicit constructor ({@code Escalation::None}, {@code Duration::Permanent},
 * {@code DryRun::InheritSafety}), never by omission.
 *
 * <h2>Events</h2>
 * <p>Every event carries {@code subject: Account} and
 * {@code subject_name: Text}. The distinguished base event {@code Event}
 * carries exactly those two fields; every concrete event is assignable to it,
 * which is the subtyping axis that lets an event-polymorphic template body
 * access common fields only.
 *
 * <h2>Built-in functions</h2>
 * <p>All built-ins are environment reads of effect {@link Effect#QUERY}:
 * {@code precedent(Account, Rule) -> Int},
 * {@code fingerprint_score(Account) -> Real},
 * {@code is_online(Account) -> Bool}, {@code now() -> Int}.
 */
public final class Builtins {

    /** Name of the distinguished base event type. */
    public static final String ANY_EVENT_NAME = "Event";

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    /** The base event type carrying the common fields only. */
    public static final Event ANY_EVENT = new Event(ANY_EVENT_NAME, List.of(
            new Field("subject", ACCOUNT),
            new Field("subject_name", TEXT)));

    private static final Map<String, Event> EVENTS = Map.of(
            "Chat", new Event("Chat", List.of(
                    new Field("subject", ACCOUNT),
                    new Field("subject_name", TEXT),
                    new Field("message", TEXT),
                    new Field("length", INT))),
            "Break", new Event("Break", List.of(
                    new Field("subject", ACCOUNT),
                    new Field("subject_name", TEXT),
                    new Field("block", TEXT))),
            "Login", new Event("Login", List.of(
                    new Field("subject", ACCOUNT),
                    new Field("subject_name", TEXT),
                    new Field("ip", TEXT))),
            "Evasion", new Event("Evasion", List.of(
                    new Field("subject", ACCOUNT),
                    new Field("subject_name", TEXT),
                    new Field("score", REAL))));

    // ------------------------------------------------------------------
    // Sum types
    // ------------------------------------------------------------------

    /** The enforcement measure sum; constructors mirror the runtime enum. */
    public static final Sum MEASURE = new Sum("Measure", List.of(
            new Ctor("WARN", List.of()), new Ctor("CENSURE", List.of()),
            new Ctor("MUTE", List.of()), new Ctor("KICK", List.of()),
            new Ctor("QUARANTINE", List.of()), new Ctor("SUSPENSION", List.of()),
            new Ctor("CUSTODY", List.of())));

    /** The duration-term sum: a fixed finite span or the permanent term. */
    public static final Sum DURATION_TERM = new Sum("Duration", List.of(
            new Ctor("Fixed", List.of(new Field("value", DURATION))),
            new Ctor("Permanent", List.of())));

    /** The escalation sum: no ladder, or an explicit ladder of terms. */
    public static final Sum ESCALATION = new Sum("Escalation", List.of(
            new Ctor("None", List.of()),
            new Ctor("Steps", List.of(new Field("steps", new ListT(null))))));

    /** The annotation sum. */
    public static final Sum ANNOTATION = new Sum("Annotation", List.of(
            new Ctor("Notify", List.of(new Field("channel", TEXT))),
            new Ctor("Evidence", List.of()),
            new Ctor("Escalate", List.of()),
            new Ctor("Silent", List.of()),
            new Ctor("Shadow", List.of()),
            new Ctor("Ghost", List.of()),
            new Ctor("Rubberband", List.of()),
            new Ctor("Transcript", List.of()),
            new Ctor("Reason", List.of(new Field("text", TEXT))),
            new Ctor("Link", List.of(new Field("ref", TEXT))),
            new Ctor("WarnFirst", List.of(new Field("count", INT))),
            new Ctor("Decay", List.of(new Field("window", DURATION))),
            new Ctor("Stay", List.of(new Field("window", DURATION))),
            new Ctor("Tariff", List.of(new Field("floor", DURATION))),
            new Ctor("Probation", List.of(new Field("window", DURATION)))));

    /** The attribution sum. */
    public static final Sum ATTRIBUTION = new Sum("Attribution", List.of(
            new Ctor("System", List.of()),
            new Ctor("Staff", List.of(new Field("name", TEXT)))));

    /** The dry-run policy sum. */
    public static final Sum DRY_RUN = new Sum("DryRun", List.of(
            new Ctor("InheritSafety", List.of()),
            new Ctor("Forced", List.of())));

    /** The recurrence-partitioning sum. */
    public static final Sum GROUP_BY = new Sum("GroupBy", List.of(
            new Ctor("Subject", List.of()),
            new Ctor("Global", List.of()),
            new Ctor("Field", List.of(new Field("name", TEXT)))));

    /** The trigger sum. */
    public static final Sum TRIGGER;

    static {
        // ESCALATION and TRIGGER carry list fields whose element types are
        // themselves built here; the two forward references are resolved by
        // rebuilding the affected constructors once the elements exist.
        final Type stepAny = new Step(ANY_EVENT);
        TRIGGER = new Sum("Trigger", List.of(
                new Ctor("Atomic", List.of()),
                new Ctor("Repeated", List.of(
                        new Field("count", INT),
                        new Field("within", DURATION),
                        new Field("group_by", GROUP_BY))),
                new Ctor("Sequence", List.of(
                        new Field("within", DURATION),
                        new Field("group_by", GROUP_BY),
                        new Field("steps", new ListT(stepAny))))));
    }

    /** The escalation sum with its ladder element type resolved. */
    public static final Sum ESCALATION_RESOLVED = new Sum("Escalation", List.of(
            new Ctor("None", List.of()),
            new Ctor("Steps", List.of(new Field("steps", new ListT(DURATION_TERM))))));

    // ------------------------------------------------------------------
    // Record descriptors
    // ------------------------------------------------------------------

    /** The total sanction descriptor. */
    public static final Rec SANCTION = new Rec("Sanction", List.of(
            new Field("target", ACCOUNT),
            new Field("cite", new ListT(RULE)),
            new Field("measure", MEASURE),
            new Field("duration", DURATION_TERM),
            new Field("escalation", ESCALATION_RESOLVED),
            new Field("annotations", new ListT(ANNOTATION)),
            new Field("attribution", ATTRIBUTION),
            new Field("dry_run", DRY_RUN)));

    /** The total layout descriptor. */
    public static final Rec LAYOUT = new Rec("Layout", List.of(
            new Field("key", TEXT),
            new Field("rule", RULE),
            new Field("reason", TEXT),
            new Field("measure", MEASURE),
            new Field("duration", DURATION_TERM),
            new Field("escalation", ESCALATION_RESOLVED),
            new Field("annotations", new ListT(ANNOTATION))));

    // ------------------------------------------------------------------
    // Namespaces
    // ------------------------------------------------------------------

    private static final Map<String, Type> TYPE_NAMESPACE;
    private static final Map<String, Sum> SUM_ROOTS;
    private static final Map<String, Func> FUNCTIONS;

    /** Types admissible as template parameters. */
    public static final Set<Type.PrimKind> TEMPLATE_PRIM_PARAMS =
            Set.of(PrimKind.INT, PrimKind.TEXT, PrimKind.DURATION, PrimKind.RULE);

    static {
        final Map<String, Type> types = new LinkedHashMap<>();
        types.put("Bool", BOOL);
        types.put("Int", INT);
        types.put("Real", REAL);
        types.put("Text", TEXT);
        types.put("Duration", DURATION);
        types.put("Rule", RULE);
        types.put("Account", ACCOUNT);
        types.put("Measure", MEASURE);
        types.put("Sanction", SANCTION);
        types.put("Layout", LAYOUT);
        TYPE_NAMESPACE = Map.copyOf(types);

        final Map<String, Sum> roots = new LinkedHashMap<>();
        roots.put("Measure", MEASURE);
        roots.put("Duration", DURATION_TERM);
        roots.put("Escalation", ESCALATION_RESOLVED);
        roots.put("Annotation", ANNOTATION);
        roots.put("Attribution", ATTRIBUTION);
        roots.put("DryRun", DRY_RUN);
        roots.put("GroupBy", GROUP_BY);
        roots.put("Trigger", TRIGGER);
        SUM_ROOTS = Map.copyOf(roots);

        FUNCTIONS = Map.of(
                "precedent", new Func(List.of(ACCOUNT, RULE), INT, Effect.QUERY),
                "fingerprint_score", new Func(List.of(ACCOUNT), REAL, Effect.QUERY),
                "is_online", new Func(List.of(ACCOUNT), BOOL, Effect.QUERY),
                "now", new Func(List.of(), INT, Effect.QUERY));
    }

    private Builtins() {
    }

    /** Resolves a type-namespace name, or null when unknown. */
    public static Type typeNamed(final String name) {
        return TYPE_NAMESPACE.get(name);
    }

    /** Resolves a sum by value-namespace path root, or null when unknown. */
    public static Sum sumByRoot(final String root) {
        return SUM_ROOTS.get(root);
    }

    /** Resolves a concrete event type by name, or null when unknown. */
    public static Event event(final String name) {
        return EVENTS.get(name);
    }

    /** Resolves a built-in function signature by name, or null when unknown. */
    public static Func function(final String name) {
        return FUNCTIONS.get(name);
    }
}