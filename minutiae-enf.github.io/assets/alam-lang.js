/*
 * Prism grammars for the ALAM definition language and the EBNF fragments used
 * by the language reference.
 *
 * The ALAM grammar mirrors the Java lexer. The alam-ebnf grammar is deliberately
 * separate: grammar productions are documentation syntax rather than executable
 * ALAM and should not be coloured as keywords, types, or runtime expressions.
 *
 * Requires Prism core to be loaded first.
 */
'use strict';

(function () {
    if (typeof Prism === 'undefined') {
        return;
    }

    Prism.languages.alam = {
        'comment': [
            {
                pattern: /\/\*[\s\S]*?(?:\*\/|$)/,
                greedy: true
            },
            {
                pattern: /\/\/.*/,
                greedy: true
            }
        ],

        'string': {
            pattern: /"(?:\\.|[^"\\])*"/,
            greedy: true
        },

        /*
         * Rule identifier: uppercase P followed immediately by one or more
         * dot-decimal segments.
         */
        'rule-id': {
            pattern: /\bP(?:\.\d+)+/,
            alias: 'symbol'
        },

        /*
         * Strict-alternation duration literal. It must precede ordinary
         * numbers, otherwise Prism would split the digit runs from their units.
         */
        'duration': {
            pattern: /\b\d+[smhdw](?:\d+[smhdw])*\b/,
            alias: 'number'
        },

        'keyword': /\b(?:rule|condition|trigger|guard|verdict|template|where|automaton|override|use|const|schema|matrix|row|transform|expand|match|with)\b/,

        'boolean': /\b(?:true|false)\b/,

        'wildcard': {
            pattern: /(^|[^\w])_(?!\w)/,
            lookbehind: true,
            alias: 'keyword'
        },

        /*
         * Consume a complete path before class-name matching. This allows the
         * Prism wrap hook to link Trigger::Repeated as one entity.
         */
        'path': {
            pattern: /\b[A-Za-z_]\w*(?:::[A-Za-z_]\w*)+/,
            alias: 'class-name'
        },

        'builtin': /\b(?:precedent|fingerprint_score|is_online|now)(?=\s*\()/,

        'class-name': /\b(?:Bool|Int|Real|Text|Duration|Rule|Account|Measure|Sanction|Layout|Step|Trigger|Escalation|Annotation|Attribution|DryRun|GroupBy|Event|Chat|Break|Login|Evasion)\b/,

        /*
         * Record field access and initializers. A real literal is consumed by
         * the number rule below as one token because this pattern requires a
         * name after the dot.
         */
        'property': {
            pattern: /\.(?!\.)[A-Za-z_]\w*/,
            inside: {
                'punctuation': /^\./
            }
        },

        'number': [
            /\b\d+\.\d+\b/,
            /\b\d+\b/
        ],

        'operator': /::|->|=>|==|!=|<=|>=|&&|\|\||\.\.|[~<>=!+\-*\/]/,

        'punctuation': /[{}()[\],;]/
    };

    /*
     * EBNF grammar used by <pre class="gram"> blocks.
     *
     * Production names are recognized before an equals sign. Quoted terminals
     * retain string colouring. Brackets and braces are grammar operators here,
     * not ALAM list or record punctuation.
     */
    Prism.languages['alam-ebnf'] = {
        'ebnf-comment': {
            pattern: /\(\*[\s\S]*?\*\)/,
            greedy: true,
            alias: 'comment'
        },

        'ebnf-string': {
            pattern: /"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/,
            greedy: true,
            alias: 'string'
        },

        'ebnf-rule': {
            pattern: /^[ \t]*[A-Za-z][A-Za-z0-9-]*(?=\s*=)/m,
            alias: 'property'
        },

        'ebnf-special': {
            pattern: /\b(?:EOF|IDENT|INT|REAL|TEXT|DURATION|RULE-ID)\b/,
            alias: 'symbol'
        },

        'ebnf-operator': {
            pattern: /=|\||-|\+|\*/,
            alias: 'operator'
        },

        'ebnf-punctuation': {
            pattern: /[()[\]{},;]/,
            alias: 'punctuation'
        }
    };
})();