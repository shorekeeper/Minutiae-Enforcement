/*
 * Cross-reference registry, search feed, and auto-linkers for the ALAM
 * reference.
 *
 * Every target is relative to the docs/alam/ root. basePrefix() converts that
 * root-relative target into a URL valid from the current page. Entity pages are
 * one directory below the root, while the hub and guide pages are at the root.
 *
 * Inline <code> elements are linked after DOMContentLoaded. Prism tokens are
 * linked through the wrap hook. Authors must not hand-write links around entity
 * names in code markup.
 */
'use strict';

var ALAM_LINKS = Object.create(null);
var ALAM_SEARCH = [];

(function () {
    function add(label, target, category, searchable) {
        ALAM_LINKS[label] = target;
        if (searchable !== false) {
            ALAM_SEARCH.push({
                label: label,
                target: target,
                category: category || 'ALAM'
            });
        }
    }

    function addFiles(category, directory, names) {
        names.forEach(function (name) {
            add(name, directory + '/' + name + '.html', category);
        });
    }

    function addAnchors(category, page, names) {
        names.forEach(function (name) {
            add(name, page + '#' + name.split('::').pop(), category);
        });
    }

    /* Keywords and declaration forms. */
    addFiles('Keyword', 'keyword', [
        'rule', 'template', 'automaton', 'const', 'schema', 'matrix',
        'transform', 'expand', 'match', 'with', 'use', 'override'
    ]);

    add('condition', 'keyword/rule.html#condition', 'Rule clause');
    add('trigger', 'keyword/rule.html#trigger', 'Rule clause');
    add('guard', 'keyword/rule.html#guard', 'Rule clause');
    add('verdict', 'keyword/rule.html#verdict', 'Rule clause');
    add('where', 'keyword/template.html#where', 'Template clause');
    add('row', 'keyword/matrix.html#row', 'Matrix clause');

    /* Primitive, structural, and shape types. */
    addFiles('Type', 'type', [
        'Bool', 'Int', 'Real', 'Text', 'Duration', 'Rule', 'Account',
        'Step', 'list', 'function'
    ]);

    /*
     * Event is both the distinguished event supertype and the base event
     * descriptor. The canonical spelling links to the event page. The search
     * alias exposes the type-oriented entry without making inline `Event`
     * ambiguous.
     */
    add('Event', 'event/Event.html', 'Event');
    add('Event type', 'type/Event.html', 'Type');

    /* Sum types. */
    addFiles('Sum type', 'sum', [
        'Measure', 'Trigger', 'Escalation', 'Annotation',
        'Attribution', 'DryRun', 'GroupBy', 'DurationTerm'
    ]);

    /*
     * Duration is the primitive finite-span type. DurationTerm is the
     * documentation name of the sum whose value-namespace root is Duration.
     */
    add('Duration term', 'sum/DurationTerm.html', 'Sum type');

    /* Record descriptors. */
    addFiles('Record', 'rec', ['Sanction', 'Layout']);

    /* Event descriptors. */
    addFiles('Event', 'event', ['Chat', 'Break', 'Login', 'Evasion']);

    /* Constructors with fields or independent runtime mechanics. */
    [
        ['Trigger::Atomic', 'ctor/Trigger.Atomic.html'],
        ['Trigger::Repeated', 'ctor/Trigger.Repeated.html'],
        ['Trigger::Sequence', 'ctor/Trigger.Sequence.html'],
        ['Escalation::Steps', 'ctor/Escalation.Steps.html'],
        ['GroupBy::Field', 'ctor/GroupBy.Field.html'],
        ['Duration::Fixed', 'ctor/Duration.Fixed.html'],
        ['Annotation::Notify', 'ctor/Annotation.Notify.html'],
        ['Annotation::WarnFirst', 'ctor/Annotation.WarnFirst.html'],
        ['Annotation::Decay', 'ctor/Annotation.Decay.html'],
        ['Annotation::Stay', 'ctor/Annotation.Stay.html'],
        ['Annotation::Tariff', 'ctor/Annotation.Tariff.html'],
        ['Annotation::Probation', 'ctor/Annotation.Probation.html'],
        ['Annotation::Reason', 'ctor/Annotation.Reason.html'],
        ['Annotation::Link', 'ctor/Annotation.Link.html'],
        ['Attribution::Staff', 'ctor/Attribution.Staff.html']
    ].forEach(function (entry) {
        add(entry[0], entry[1], 'Constructor');
    });

    /* Nullary Measure constructors remain sections of the sum page. */
    addAnchors('Constructor', 'sum/Measure.html', [
        'Measure::WARN',
        'Measure::CENSURE',
        'Measure::MUTE',
        'Measure::KICK',
        'Measure::QUARANTINE',
        'Measure::SUSPENSION',
        'Measure::CUSTODY'
    ]);

    /* Other nullary constructors remain sections of their sum pages. */
    addAnchors('Constructor', 'sum/Escalation.html', [
        'Escalation::None'
    ]);
    addAnchors('Constructor', 'sum/DurationTerm.html', [
        'Duration::Permanent'
    ]);
    addAnchors('Constructor', 'sum/Attribution.html', [
        'Attribution::System'
    ]);
    addAnchors('Constructor', 'sum/DryRun.html', [
        'DryRun::InheritSafety',
        'DryRun::Forced'
    ]);
    addAnchors('Constructor', 'sum/GroupBy.html', [
        'GroupBy::Subject',
        'GroupBy::Global'
    ]);
    addAnchors('Constructor', 'sum/Annotation.html', [
        'Annotation::Evidence',
        'Annotation::Escalate',
        'Annotation::Silent',
        'Annotation::Shadow',
        'Annotation::Ghost',
        'Annotation::Rubberband',
        'Annotation::Transcript'
    ]);

    /* Built-in functions and distinguished guard value. */
    addFiles('Function', 'fn', [
        'precedent', 'fingerprint_score', 'is_online', 'now'
    ]);
    add('Guard::Always', 'fn/Guard.Always.html', 'Built-in guard');

    /* Guides are included in search but are not language entities. */
    add('Lexical structure', 'guide/lexical.html', 'Guide');
    add('Compilation pipeline', 'guide/pipeline.html', 'Guide');
    add('Effects', 'guide/effects.html', 'Guide');
    add('Runtime model', 'guide/runtime.html', 'Guide');

    /*
     * The public diagnostic space is stable even where the current compiler
     * does not emit a code. Reserved pages exist so links never change when a
     * code is activated by a later compiler version.
     */
    function diagnostics(prefix, first, last, except) {
        for (var i = first; i <= last; i++) {
            var code = prefix + String(i).padStart(3, '0');
            if (except && except.indexOf(code) >= 0) {
                continue;
            }
            add(code, 'diag/' + code + '.html', 'Diagnostic');
        }
    }

    diagnostics('L', 1, 8);
    diagnostics('P', 1, 11);
    add('P013', 'diag/P013.html', 'Diagnostic');
    diagnostics('E', 1, 37);
    diagnostics('V', 1, 17);
    diagnostics('R', 1, 5);
    add('W001', 'diag/W001.html', 'Diagnostic');
    add('W003', 'diag/W003.html', 'Diagnostic');


    /* -------------------------------------------------------------- */
    /* Contextual reference taxonomy                                  */
    /* -------------------------------------------------------------- */

    /*
     * ALAM_CATEGORIES drives the contextual navigator shown above the global
     * documentation sidebar. Targets are relative to docs/alam/.
     *
     * The category list answers:
     *   - which reference category contains the current entity;
     *   - which sibling entities exist in that category;
     *   - which sibling is currently open.
     */
    window.ALAM_CATEGORIES = {
        keyword: {
            label: 'Keywords',
            href: 'index.html#keywords',
            items: [
                { label: 'rule', target: 'keyword/rule.html' },
                { label: 'template', target: 'keyword/template.html' },
                { label: 'automaton', target: 'keyword/automaton.html' },
                { label: 'const', target: 'keyword/const.html' },
                { label: 'schema', target: 'keyword/schema.html' },
                { label: 'matrix', target: 'keyword/matrix.html' },
                { label: 'transform', target: 'keyword/transform.html' },
                { label: 'expand', target: 'keyword/expand.html' },
                { label: 'match', target: 'keyword/match.html' },
                { label: 'with', target: 'keyword/with.html' },
                { label: 'use', target: 'keyword/use.html' },
                { label: 'override', target: 'keyword/override.html' }
            ]
        },

        type: {
            label: 'Types',
            href: 'index.html#types',
            items: [
                { label: 'Bool', target: 'type/Bool.html' },
                { label: 'Int', target: 'type/Int.html' },
                { label: 'Real', target: 'type/Real.html' },
                { label: 'Text', target: 'type/Text.html' },
                { label: 'Duration', target: 'type/Duration.html' },
                { label: 'Rule', target: 'type/Rule.html' },
                { label: 'Account', target: 'type/Account.html' },
                { label: 'Step', target: 'type/Step.html' },
                { label: 'Event type', target: 'type/Event.html' },
                { label: 'List type', target: 'type/list.html' },
                { label: 'Function type', target: 'type/function.html' }
            ]
        },

        sum: {
            label: 'Sum types',
            href: 'index.html#sums',
            items: [
                { label: 'Measure', target: 'sum/Measure.html' },
                { label: 'Trigger', target: 'sum/Trigger.html' },
                { label: 'Escalation', target: 'sum/Escalation.html' },
                { label: 'Annotation', target: 'sum/Annotation.html' },
                { label: 'Attribution', target: 'sum/Attribution.html' },
                { label: 'DryRun', target: 'sum/DryRun.html' },
                { label: 'GroupBy', target: 'sum/GroupBy.html' },
                { label: 'Duration term', target: 'sum/DurationTerm.html' }
            ]
        },

        rec: {
            label: 'Records',
            href: 'index.html#records',
            items: [
                { label: 'Sanction', target: 'rec/Sanction.html' },
                { label: 'Layout', target: 'rec/Layout.html' }
            ]
        },

        event: {
            label: 'Events',
            href: 'index.html#events',
            items: [
                { label: 'Event', target: 'event/Event.html' },
                { label: 'Chat', target: 'event/Chat.html' },
                { label: 'Break', target: 'event/Break.html' },
                { label: 'Login', target: 'event/Login.html' },
                { label: 'Evasion', target: 'event/Evasion.html' }
            ]
        },

        fn: {
            label: 'Built-in functions',
            href: 'index.html#functions',
            items: [
                { label: 'precedent', target: 'fn/precedent.html' },
                { label: 'fingerprint_score', target: 'fn/fingerprint_score.html' },
                { label: 'is_online', target: 'fn/is_online.html' },
                { label: 'now', target: 'fn/now.html' },
                { label: 'Guard::Always', target: 'fn/Guard.Always.html' }
            ]
        },

        guide: {
            label: 'Guides',
            href: 'index.html#guides',
            items: [
                { label: 'Lexical structure', target: 'guide/lexical.html' },
                { label: 'Compilation pipeline', target: 'guide/pipeline.html' },
                { label: 'Effects', target: 'guide/effects.html' },
                { label: 'Runtime model', target: 'guide/runtime.html' }
            ]
        }
    };

    /*
     * Member groups provide one additional hierarchy level. A sum page lists
     * its constructors below the category list. A constructor page uses its
     * owning sum as the local sibling group instead of presenting one flat list
     * of every constructor in the language.
     */
    window.ALAM_MEMBER_GROUPS = [
        {
            owner: 'sum/Measure.html',
            label: 'Constructors of Measure',
            categoryLabel: 'Constructors',
            categoryHref: 'index.html#constructors',
            items: [
                { label: 'Measure::WARN', target: 'sum/Measure.html#WARN' },
                { label: 'Measure::CENSURE', target: 'sum/Measure.html#CENSURE' },
                { label: 'Measure::MUTE', target: 'sum/Measure.html#MUTE' },
                { label: 'Measure::KICK', target: 'sum/Measure.html#KICK' },
                { label: 'Measure::QUARANTINE', target: 'sum/Measure.html#QUARANTINE' },
                { label: 'Measure::SUSPENSION', target: 'sum/Measure.html#SUSPENSION' },
                { label: 'Measure::CUSTODY', target: 'sum/Measure.html#CUSTODY' }
            ]
        },
        {
            owner: 'sum/Trigger.html',
            label: 'Constructors of Trigger',
            categoryLabel: 'Constructors',
            categoryHref: 'index.html#constructors',
            items: [
                { label: 'Trigger::Atomic', target: 'ctor/Trigger.Atomic.html' },
                { label: 'Trigger::Repeated', target: 'ctor/Trigger.Repeated.html' },
                { label: 'Trigger::Sequence', target: 'ctor/Trigger.Sequence.html' }
            ]
        },
        {
            owner: 'sum/Escalation.html',
            label: 'Constructors of Escalation',
            categoryLabel: 'Constructors',
            categoryHref: 'index.html#constructors',
            items: [
                { label: 'Escalation::None', target: 'sum/Escalation.html#None' },
                { label: 'Escalation::Steps', target: 'ctor/Escalation.Steps.html' }
            ]
        },
        {
            owner: 'sum/Annotation.html',
            label: 'Constructors of Annotation',
            categoryLabel: 'Constructors',
            categoryHref: 'index.html#constructors',
            items: [
                { label: 'Annotation::Notify', target: 'ctor/Annotation.Notify.html' },
                { label: 'Annotation::Evidence', target: 'sum/Annotation.html#Evidence' },
                { label: 'Annotation::Escalate', target: 'sum/Annotation.html#Escalate' },
                { label: 'Annotation::Silent', target: 'sum/Annotation.html#Silent' },
                { label: 'Annotation::Shadow', target: 'sum/Annotation.html#Shadow' },
                { label: 'Annotation::Ghost', target: 'sum/Annotation.html#Ghost' },
                { label: 'Annotation::Rubberband', target: 'sum/Annotation.html#Rubberband' },
                { label: 'Annotation::Transcript', target: 'sum/Annotation.html#Transcript' },
                { label: 'Annotation::Reason', target: 'ctor/Annotation.Reason.html' },
                { label: 'Annotation::Link', target: 'ctor/Annotation.Link.html' },
                { label: 'Annotation::WarnFirst', target: 'ctor/Annotation.WarnFirst.html' },
                { label: 'Annotation::Decay', target: 'ctor/Annotation.Decay.html' },
                { label: 'Annotation::Stay', target: 'ctor/Annotation.Stay.html' },
                { label: 'Annotation::Tariff', target: 'ctor/Annotation.Tariff.html' },
                { label: 'Annotation::Probation', target: 'ctor/Annotation.Probation.html' }
            ]
        },
        {
            owner: 'sum/Attribution.html',
            label: 'Constructors of Attribution',
            categoryLabel: 'Constructors',
            categoryHref: 'index.html#constructors',
            items: [
                { label: 'Attribution::System', target: 'sum/Attribution.html#System' },
                { label: 'Attribution::Staff', target: 'ctor/Attribution.Staff.html' }
            ]
        },
        {
            owner: 'sum/DryRun.html',
            label: 'Constructors of DryRun',
            categoryLabel: 'Constructors',
            categoryHref: 'index.html#constructors',
            items: [
                { label: 'DryRun::InheritSafety', target: 'sum/DryRun.html#InheritSafety' },
                { label: 'DryRun::Forced', target: 'sum/DryRun.html#Forced' }
            ]
        },
        {
            owner: 'sum/GroupBy.html',
            label: 'Constructors of GroupBy',
            categoryLabel: 'Constructors',
            categoryHref: 'index.html#constructors',
            items: [
                { label: 'GroupBy::Subject', target: 'sum/GroupBy.html#Subject' },
                { label: 'GroupBy::Global', target: 'sum/GroupBy.html#Global' },
                { label: 'GroupBy::Field', target: 'ctor/GroupBy.Field.html' }
            ]
        },
        {
            owner: 'sum/DurationTerm.html',
            label: 'Constructors of Duration term',
            categoryLabel: 'Constructors',
            categoryHref: 'index.html#constructors',
            items: [
                { label: 'Duration::Fixed', target: 'ctor/Duration.Fixed.html' },
                { label: 'Duration::Permanent', target: 'sum/DurationTerm.html#Permanent' }
            ]
        },

        /*
         * Clause groups make parent-owned keywords visible without pretending
         * that they have independent HTML pages.
         */
        {
            owner: 'keyword/rule.html',
            label: 'Clauses of rule',
            categoryLabel: 'Keywords',
            categoryHref: 'index.html#keywords',
            items: [
                { label: 'condition', target: 'keyword/rule.html#condition' },
                { label: 'trigger', target: 'keyword/rule.html#trigger' },
                { label: 'guard', target: 'keyword/rule.html#guard' },
                { label: 'verdict', target: 'keyword/rule.html#verdict' }
            ]
        },
        {
            owner: 'keyword/template.html',
            label: 'Clauses of template',
            categoryLabel: 'Keywords',
            categoryHref: 'index.html#keywords',
            items: [
                { label: 'where', target: 'keyword/template.html#where' }
            ]
        },
        {
            owner: 'keyword/matrix.html',
            label: 'Clauses of matrix',
            categoryLabel: 'Keywords',
            categoryHref: 'index.html#keywords',
            items: [
                { label: 'row', target: 'keyword/matrix.html#row' }
            ]
        }
    ];

    /* -------------------------------------------------------------- */
    /* Diagnostic family taxonomy                                     */
    /* -------------------------------------------------------------- */

    function diagnosticItems(prefix, first, last, omitted) {
        var items = [];

        for (var number = first; number <= last; number++) {
            var code = prefix + String(number).padStart(3, '0');

            if (omitted && omitted.indexOf(code) >= 0) {
                continue;
            }

            items.push({
                label: code,
                target: 'diag/' + code + '.html'
            });
        }

        return items;
    }

    window.ALAM_DIAGNOSTIC_CATEGORIES = {
        L: {
            label: 'Lexical diagnostics',
            href: 'index.html#diagnostics',
            items: diagnosticItems('L', 1, 8)
        },
        P: {
            label: 'Parsing diagnostics',
            href: 'index.html#diagnostics',
            items: diagnosticItems('P', 1, 13, ['P012'])
        },
        E: {
            label: 'Elaboration diagnostics',
            href: 'index.html#diagnostics',
            items: diagnosticItems('E', 1, 37)
        },
        V: {
            label: 'Evaluation diagnostics',
            href: 'index.html#diagnostics',
            items: diagnosticItems('V', 1, 17)
        },
        R: {
            label: 'Verification diagnostics',
            href: 'index.html#diagnostics',
            items: diagnosticItems('R', 1, 5)
        },
        W: {
            label: 'Warnings',
            href: 'index.html#diagnostics',
            items: [
                { label: 'W001', target: 'diag/W001.html' },
                { label: 'W003', target: 'diag/W003.html' }
            ]
        }
    };

    /*
     * Resolves a target relative to docs/alam/.
     *
     * Examples:
     *   /en/docs/alam/                         -> ""
     *   /en/docs/alam/index.html               -> ""
     *   /en/docs/alam/ctor/X.html              -> "../"
     *   /en/docs/alam/keyword/X.html           -> "../"
     *   /en/docs/annotations.html              -> "alam/"
     */
    function basePrefix() {
        var pathname = location.pathname.replace(/\\/g, '/');
        var marker = '/alam/';
        var at = pathname.indexOf(marker);

        if (at < 0) {
            return 'alam/';
        }

        var tail = pathname.slice(at + marker.length);
        if (tail === '' || tail.charAt(tail.length - 1) === '/') {
            return '';
        }

        var parts = tail.split('/');
        var directoryDepth = Math.max(0, parts.length - 1);
        return '../'.repeat(directoryDepth);
    }

    function targetOf(text) {
        var key = String(text || '').trim();

        if (key.length > 2 && key.slice(-2) === '()') {
            key = key.slice(0, -2);
        }

        if (Object.prototype.hasOwnProperty.call(ALAM_LINKS, key)) {
            return basePrefix() + ALAM_LINKS[key];
        }

        if (/^[LPEVRW]\d{3}$/.test(key)) {
            return basePrefix() + 'diag/' + key + '.html';
        }

        return null;
    }

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('article code').forEach(function (code) {
            if (code.closest('pre') || code.closest('a')) {
                return;
            }

            var target = targetOf(code.textContent);
            if (!target) {
                return;
            }

            var link = document.createElement('a');
            link.className = 'term-link';
            link.href = target;
            code.parentNode.insertBefore(link, code);
            link.appendChild(code);
        });
    });

    if (typeof Prism !== 'undefined') {
        Prism.hooks.add('wrap', function (env) {
            if (env.language !== 'alam') {
                return;
            }
            if (env.type !== 'path'
                    && env.type !== 'class-name'
                    && env.type !== 'builtin'
                    && env.type !== 'rule-id') {
                return;
            }

            var target = targetOf(env.content);
            if (!target) {
                return;
            }

            env.tag = 'a';
            env.attributes.href = target;
            env.classes.push('tok-link');
        });
    }
})();