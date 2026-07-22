package org.synergyst.minutiae.lang.sem;

import org.junit.jupiter.api.Test;
import org.synergyst.minutiae.lang.ast.Decl;
import org.synergyst.minutiae.lang.diag.Diagnostics;
import org.synergyst.minutiae.lang.lex.Lexer;
import org.synergyst.minutiae.lang.parse.Parser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.synergyst.minutiae.lang.TestHarness.sanction;

/**
 * Type checking, effect checking, exhaustiveness analysis, and declaration
 * validation of the elaborator.
 */
final class ElaboratorTest {

    private static List<String> elab(final String src) {
        final Diagnostics diags = new Diagnostics();
        final List<Decl> decls = Parser.parse(Lexer.lex(src, diags), diags);
        assertFalse(diags.hasErrors(), () -> "parse failed: " + diags.all());
        Elaborator.run(decls, diags);
        return diags.all().stream().map(d -> d.code()).toList();
    }

    @Test
    void wellTypedRuleElaboratesCleanly() {
        assertEquals(List.of(), elab("""
                rule<Chat> r {
                    condition {
                        trigger = Trigger::Atomic {};
                        guard = (e) -> e.length > 120 && precedent(e.subject, P.2.1) >= 1;
                    }
                    => verdict = (e) -> %s;
                }
                """.formatted(sanction("Measure::MUTE", "Duration::Fixed(3h)"))));
    }

    @Test
    void noImplicitCoercion() {
        assertTrue(elab("const Int x = \"s\";").contains("E003"));
        assertTrue(elab("const Bool x = 1h > 5;").contains("E019"));
    }

    @Test
    void mandatoryFieldsAreEnforced() {
        final String src = """
                rule<Chat> r {
                    condition {
                        trigger = Trigger::Atomic {};
                        guard = Guard::Always;
                    }
                    => verdict = (e) -> Sanction {
                        .target = e.subject,
                        .cite = {},
                        .measure = Measure::MUTE,
                        .duration = Duration::Fixed(1h),
                        .escalation = Escalation::None,
                        .annotations = {},
                        .attribution = Attribution::System,
                    };
                }
                """;
        assertTrue(elab(src).contains("E005"));
    }

    @Test
    void unknownFieldIsRejected() {
        assertTrue(elab("""
                rule<Chat> r {
                    condition {
                        trigger = Trigger::Atomic {};
                        guard = Guard::Always;
                    }
                    => verdict = (e) -> Sanction { .bogus = 1 };
                }
                """).contains("E004"));
    }

    @Test
    void queryBuiltinIsRejectedInPureVerdict() {
        assertTrue(elab("""
                rule<Chat> r {
                    condition {
                        trigger = Trigger::Atomic {};
                        guard = Guard::Always;
                    }
                    => verdict = (e) -> Sanction {
                        .target = e.subject,
                        .cite = {},
                        .measure = Measure::MUTE,
                        .duration = Duration::Fixed(1h),
                        .escalation = Escalation::None,
                        .annotations = { Annotation::WarnFirst(precedent(e.subject, P.1.1)) },
                        .attribution = Attribution::System,
                        .dry_run = DryRun::InheritSafety,
                    };
                }
                """).contains("E007"));
    }

    @Test
    void matchOverIntRequiresWildcard() {
        assertTrue(elab("const Int x = match (3) { 1 => 1, 2 => 2 };").contains("E011"));
    }

    @Test
    void armAfterWildcardIsUnreachable() {
        assertTrue(elab("const Int x = match (3) { _ => 1, 2 => 2 };").contains("E012"));
    }

    @Test
    void duplicateDefinitionIsRejected() {
        assertTrue(elab("const Int x = 1;\nconst Int x = 2;").contains("E014"));
    }

    @Test
    void templateParameterTypesAreRestricted() {
        assertTrue(elab("""
                template<Real X>
                verdict t = (e) -> 0;
                """).contains("E028"));
    }

    @Test
    void overrideEventMustMatchRulePrincipalEvent() {
        assertTrue(elab("""
                rule<Break> br {
                    condition {
                        trigger = Trigger::Atomic {};
                        guard = Guard::Always;
                    }
                    => verdict = (e) -> %s;
                }
                automaton a {
                    override<Chat> use br;
                }
                """.formatted(sanction("Measure::KICK", "Duration::Permanent")))
                .contains("E022"));
    }

    @Test
    void unknownEventIsRejected() {
        assertTrue(elab("""
                rule<Chats> r {
                    condition {
                        trigger = Trigger::Atomic {};
                        guard = Guard::Always;
                    }
                    => verdict = (e) -> e;
                }
                """).contains("E025"));
    }

    @Test
    void transformCompositionTypeChecks() {
        assertEquals(List.of(), elab("""
                schema R { Int x; }
                transform<R -> R> h = (r) -> r with { .x = r.x * 2 };
                transform<R -> R> s = h . h;
                """));
    }

    @Test
    void anonymousRecordRequiresRecordExpectation() {
        assertTrue(elab("const Int x = { .a = 1 };").contains("E006"));
    }
}