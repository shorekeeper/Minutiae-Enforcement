/*
 * Shared documentation chrome.
 *
 * ME_NAV supplies the sidebar, breadcrumbs, and ordinary page search. The
 * optional ALAM_SEARCH feed supplies entity and diagnostic results without
 * placing every entity in the sidebar.
 *
 * Entity pages may define:
 *
 *   var ME_RELATED = [
 *       { label: 'Trigger', href: '../sum/Trigger.html' }
 *   ];
 *
 * When present, ME_RELATED replaces the heading-derived "On this page" rail.
 */
'use strict';

(function () {
    var path = location.pathname;
    var segs = path.split('/').filter(function (segment) {
        return segment.length > 0;
    });

    var langIdx = -1;
    for (var i = 0; i < segs.length; i++) {
        if (ME_LANGS.indexOf(segs[i]) >= 0) {
            langIdx = i;
            break;
        }
    }

    var lang = langIdx >= 0 ? segs[langIdx] : 'en';
    var base = '/' + segs.slice(0, Math.max(0, langIdx)).join('/');
    if (base !== '/') {
        base += '/';
    }

    var ui = ME_UI[lang] || ME_UI.en;
    var slug = document.documentElement.getAttribute('data-slug') || 'index';

    function splitFragment(value) {
        var at = value.indexOf('#');
        return {
            path: at < 0 ? value : value.slice(0, at),
            fragment: at < 0 ? '' : value.slice(at)
        };
    }

    function href(targetLang, targetSlug) {
        var target = splitFragment(targetSlug);
        var page;

        if (target.path === 'index') {
            page = '';
        } else if (target.path.length > 6
                && target.path.slice(-6) === '/index') {
            page = target.path.slice(0, target.path.length - 5);
        } else {
            page = target.path + '.html';
        }

        return base + targetLang + '/' + page + target.fragment;
    }

    function alamHref(targetLang, target) {
        return base + targetLang + '/docs/alam/' + target;
    }

    function slugPath(value) {
        return splitFragment(value).path;
    }

    function storedTheme() {
        try {
            return localStorage.getItem('me-theme');
        } catch (e) {
            return null;
        }
    }

    var theme = storedTheme();
    if (theme === 'dark' || theme === 'light') {
        document.documentElement.setAttribute('data-theme', theme);
    }

    function toggleTheme() {
        var dark = document.documentElement.getAttribute('data-theme') === 'dark'
            || (!document.documentElement.hasAttribute('data-theme')
                && matchMedia('(prefers-color-scheme: dark)').matches);
        var next = dark ? 'light' : 'dark';

        document.documentElement.setAttribute('data-theme', next);
        try {
            localStorage.setItem('me-theme', next);
        } catch (e) {
            /* Storage is optional. */
        }
    }

    function buildHeader() {
        var header = document.querySelector('.site-header');
        if (!header) {
            return;
        }

        var toggle = document.createElement('button');
        toggle.id = 'nav-toggle';
        toggle.textContent = '\u2630';
        toggle.setAttribute('aria-label', ui.menu);
        toggle.onclick = function () {
            var sidebar = document.querySelector('.sidebar');
            if (sidebar) {
                sidebar.classList.toggle('open');
            }
        };
        header.insertBefore(toggle, header.firstChild);

        var tools = header.querySelector('.header-tools');
        if (!tools) {
            return;
        }

        var select = document.createElement('select');
        select.setAttribute('aria-label', 'Language');

        ME_LANGS.forEach(function (code) {
            var option = document.createElement('option');
            option.value = code;
            option.textContent = ME_LANG_NAMES[code];
            option.selected = code === lang;
            select.appendChild(option);
        });

        select.onchange = function () {
            try {
                localStorage.setItem('me-lang', select.value);
            } catch (e) {
                /* Storage is optional. */
            }
            location.href = href(select.value, slug);
        };
        tools.appendChild(select);

        var themeButton = document.createElement('button');
        themeButton.textContent = '\u25D0';
        themeButton.setAttribute('aria-label', ui.theme);
        themeButton.onclick = toggleTheme;
        tools.appendChild(themeButton);
    }

/* ---------------------------------------------------------------- */
    /* ALAM contextual navigation                                       */
    /* ---------------------------------------------------------------- */

    function currentAlamTarget() {
        var prefix = 'docs/alam/';

        if (slug === 'docs/alam/index') {
            return 'index.html';
        }

        if (slug.indexOf(prefix) !== 0) {
            return null;
        }

        return slug.slice(prefix.length) + '.html';
    }

    function targetPath(target) {
        var hash = target.indexOf('#');
        return hash < 0 ? target : target.slice(0, hash);
    }

    function targetHash(target) {
        var hash = target.indexOf('#');
        return hash < 0 ? '' : target.slice(hash);
    }

    function currentEntityLabel() {
        var heading = document.querySelector('article h1');
        return heading ? heading.textContent.trim() : '';
    }

    function categoryForTarget(target) {
        if (!target || !window.ALAM_CATEGORIES) {
            return null;
        }

        var directory = target.split('/')[0];

        if (directory === 'diag') {
            var file = target.split('/').pop();
            var prefix = file ? file.charAt(0) : '';

            return window.ALAM_DIAGNOSTIC_CATEGORIES
                ? window.ALAM_DIAGNOSTIC_CATEGORIES[prefix] || null
                : null;
        }

        /*
         * Constructor pages use their parent member group rather than one flat
         * constructor category. The breadcrumb still identifies Constructors
         * as the broad category.
         */
        if (directory === 'ctor') {
            var group = memberGroupForTarget(target);

            if (!group) {
                return null;
            }

            return {
                label: group.categoryLabel || 'Constructors',
                href: group.categoryHref || 'index.html#constructors',
                items: group.items,
                localLabel: group.label
            };
        }

        return window.ALAM_CATEGORIES[directory] || null;
    }

    function memberGroupForTarget(target) {
        if (!target || !Array.isArray(window.ALAM_MEMBER_GROUPS)) {
            return null;
        }

        var currentPath = targetPath(target);

        for (var i = 0; i < window.ALAM_MEMBER_GROUPS.length; i++) {
            var group = window.ALAM_MEMBER_GROUPS[i];

            if (group.owner === currentPath) {
                return group;
            }

            for (var j = 0; j < group.items.length; j++) {
                if (targetPath(group.items[j].target) === currentPath
                        && targetPath(group.items[j].target) !== group.owner) {
                    return group;
                }
            }
        }

        return null;
    }

    function makeContextLink(item, currentTarget, memberMode) {
        var link = document.createElement('a');
        link.className = 'alam-context-link';
        link.href = alamHref(lang, item.target);
        link.textContent = item.label;
        link.dataset.target = item.target;

        var samePage = targetPath(item.target) === targetPath(currentTarget);
        var itemFragment = targetHash(item.target);
        var currentFragment = location.hash || '';

        var selected = samePage
            && (!itemFragment || itemFragment === currentFragment);

        /*
         * On a sum page with no hash, the entity itself is current; none of its
         * constructor anchors is selected. On a constructor page, the matching
         * constructor file is selected normally.
         */
        if (memberMode && samePage && itemFragment && !currentFragment) {
            selected = false;
        }

        if (selected) {
            link.classList.add('current');
            link.setAttribute('aria-current', 'page');

            var marker = document.createElement('span');
            marker.className = 'alam-context-marker';
            marker.textContent = 'current';
            link.appendChild(marker);
        }

        return link;
    }

    function appendContextSection(container, label, items, currentTarget, memberMode) {
        if (!items || items.length === 0) {
            return;
        }

        var heading = document.createElement('div');
        heading.className = 'alam-context-section-title';
        heading.textContent = label;
        container.appendChild(heading);

        var list = document.createElement('div');
        list.className = 'alam-context-list';

        items.forEach(function (item) {
            list.appendChild(
                makeContextLink(item, currentTarget, memberMode)
            );
        });

        container.appendChild(list);
    }

    function updateContextSelection() {
        var currentTarget = currentAlamTarget();

        if (!currentTarget) {
            return;
        }

        document.querySelectorAll('.alam-context-link').forEach(function (link) {
            var itemTarget = link.dataset.target;
            var samePage = targetPath(itemTarget) === targetPath(currentTarget);
            var itemFragment = targetHash(itemTarget);

            var selected = samePage
                && (!itemFragment || itemFragment === location.hash);

            /*
             * A page-level category entry remains current regardless of a local
             * member hash. Member-anchor entries select only on exact hashes.
             */
            if (!itemFragment && samePage) {
                selected = true;
            }

            link.classList.toggle('current', selected);

            if (selected) {
                link.setAttribute('aria-current', 'page');
            } else {
                link.removeAttribute('aria-current');
            }

            var marker = link.querySelector('.alam-context-marker');

            if (selected && !marker) {
                marker = document.createElement('span');
                marker.className = 'alam-context-marker';
                marker.textContent = 'current';
                link.appendChild(marker);
            } else if (!selected && marker) {
                marker.remove();
            }
        });
    }

    function buildAlamContext() {
        var sidebar = document.querySelector('.sidebar');
        var currentTarget = currentAlamTarget();

        if (!sidebar || !currentTarget || currentTarget === 'index.html') {
            return;
        }

        var category = categoryForTarget(currentTarget);

        if (!category) {
            return;
        }

        var context = document.createElement('section');
        context.className = 'alam-context';
        context.setAttribute('aria-label', 'ALAM reference context');

        var root = document.createElement('a');
        root.className = 'alam-context-root';
        root.href = href(lang, 'docs/alam/index');
        root.textContent = 'ALAM Reference';
        context.appendChild(root);

        var trail = document.createElement('div');
        trail.className = 'alam-context-trail';

        var categoryLink = document.createElement('a');
        categoryLink.href = alamHref(lang, category.href);
        categoryLink.textContent = category.label;
        trail.appendChild(categoryLink);

        var current = document.createElement('strong');
        current.textContent = currentEntityLabel();
        trail.appendChild(current);

        context.appendChild(trail);

        appendContextSection(
            context,
            category.localLabel || 'In this category',
            category.items,
            currentTarget,
            targetPath(currentTarget).indexOf('ctor/') === 0
        );

        /*
         * A sum page also exposes its constructors. Keyword pages may expose
         * clauses such as where, guard, verdict, or row.
         */
        var members = memberGroupForTarget(currentTarget);

        if (members && members.owner === targetPath(currentTarget)
                && targetPath(currentTarget).indexOf('ctor/') !== 0) {
            appendContextSection(
                context,
                members.label,
                members.items,
                currentTarget,
                true
            );
        }

        var separator = document.createElement('div');
        separator.className = 'alam-context-separator';
        context.appendChild(separator);

        sidebar.appendChild(context);

        window.addEventListener('hashchange', updateContextSelection);
    }

    function buildSidebar() {
        var sidebar = document.querySelector('.sidebar');
        if (!sidebar) {
            return;
        }

        buildAlamContext();

        ME_NAV.forEach(function (section) {
            var heading = document.createElement('h5');
            heading.textContent = section.title[lang] || section.title.en;
            sidebar.appendChild(heading);

            section.items.forEach(function (item) {
                var link = document.createElement('a');
                link.href = href(lang, item.slug);
                link.textContent = item.title[lang] || item.title.en;

                if (slugPath(item.slug) === slug) {
                    link.className = 'current';
                }

                sidebar.appendChild(link);
            });
        });
    }

    function currentNavEntry() {
        var found = null;

        ME_NAV.some(function (section) {
            return section.items.some(function (item) {
                if (slugPath(item.slug) !== slug) {
                    return false;
                }
                found = {
                    section: section,
                    item: item
                };
                return true;
            });
        });

        return found;
    }

    function buildBreadcrumb() {
        var breadcrumb = document.querySelector('.breadcrumb');
        if (!breadcrumb || slug === 'index' || slug.slice(-6) === '/index') {
            return;
        }

        var entry = currentNavEntry();
        var home = document.createElement('a');
        home.href = href(lang, 'docs/index');
        home.textContent = ui.docs;
        breadcrumb.appendChild(home);

        if (entry) {
            breadcrumb.appendChild(document.createTextNode(
                ' / '
                + (entry.section.title[lang] || entry.section.title.en)
                + ' / '
                + (entry.item.title[lang] || entry.item.title.en)
            ));
            return;
        }

        if (slug.indexOf('docs/alam/') === 0) {
            var alam = document.createElement('a');
            alam.href = href(lang, 'docs/alam/index');
            alam.textContent = 'ALAM';
            breadcrumb.appendChild(document.createTextNode(' / '));
            breadcrumb.appendChild(alam);

            var title = document.querySelector('article h1');
            if (title) {
                breadcrumb.appendChild(document.createTextNode(
                    ' / ' + title.textContent.trim()
                ));
            }
        }
    }

    function buildRelatedRail(rail) {
        var heading = document.createElement('h5');
        heading.textContent = ui.related;
        rail.classList.add('related-rail');
        rail.appendChild(heading);

        var currentGroup = null;

        window.ME_RELATED.forEach(function (item) {
            if (!item || !item.label || !item.href) {
                return;
            }

            if (item.group && item.group !== currentGroup) {
                currentGroup = item.group;

                var group = document.createElement('div');
                group.className = 'related-group';
                group.textContent = currentGroup;
                rail.appendChild(group);
            }

            var link = document.createElement('a');
            link.href = item.href;
            link.textContent = item.label;

            if (item.depth) {
                link.className = 'depth-' + item.depth;
            }

            rail.appendChild(link);
        });
    }

    function buildHeadingRail(rail, article) {
        var heads = article.querySelectorAll('h2[id], h3[id]');
        if (heads.length === 0) {
            return;
        }

        var heading = document.createElement('h5');
        heading.textContent = ui.onThisPage;
        rail.appendChild(heading);

        var links = [];
        heads.forEach(function (head) {
            var link = document.createElement('a');
            link.href = '#' + head.id;
            link.textContent = head.childNodes[0].textContent.trim();
            link.className = 'depth-' + head.tagName.charAt(1);
            rail.appendChild(link);
            links.push(link);
        });

        if (typeof IntersectionObserver === 'undefined') {
            return;
        }

        var observer = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (!entry.isIntersecting) {
                    return;
                }

                links.forEach(function (link) {
                    link.classList.toggle(
                        'active',
                        link.getAttribute('href') === '#' + entry.target.id
                    );
                });
            });
        }, {
            rootMargin: '-60px 0px -70% 0px'
        });

        heads.forEach(function (head) {
            observer.observe(head);
        });
    }

    function buildRail() {
        var rail = document.querySelector('.rail');
        var article = document.querySelector('article');
        if (!rail || !article) {
            return;
        }

        if (Array.isArray(window.ME_RELATED)) {
            buildRelatedRail(rail);
            return;
        }

        buildHeadingRail(rail, article);
    }

    function buildAnchors() {
        document.querySelectorAll('article h2[id], article h3[id]').forEach(function (head) {
            var anchor = document.createElement('a');
            anchor.className = 'anchor';
            anchor.href = '#' + head.id;
            anchor.textContent = '#';
            head.appendChild(anchor);
        });
    }

    function buildCopyButtons() {
        document.querySelectorAll('article pre').forEach(function (pre) {
            var button = document.createElement('button');
            button.className = 'copy-btn';
            button.textContent = 'copy';

            button.onclick = function () {
                var code = pre.querySelector('code');
                var text = code ? code.textContent : pre.textContent;

                navigator.clipboard.writeText(text).then(function () {
                    button.textContent = 'ok';
                    setTimeout(function () {
                        button.textContent = 'copy';
                    }, 1200);
                });
            };

            pre.appendChild(button);
        });
    }

    function navSearchItems() {
        var items = [];

        ME_NAV.forEach(function (section) {
            section.items.forEach(function (item) {
                items.push({
                    label: item.title[lang] || item.title.en,
                    english: item.title.en,
                    section: section.title[lang] || section.title.en,
                    href: href(lang, item.slug)
                });
            });
        });

        if (Array.isArray(window.ALAM_SEARCH)) {
            window.ALAM_SEARCH.forEach(function (item) {
                items.push({
                    label: item.label,
                    english: item.label,
                    section: item.category || 'ALAM',
                    href: alamHref(lang, item.target)
                });
            });
        }

        var seen = Object.create(null);
        return items.filter(function (item) {
            var key = item.label + '\u0000' + item.href;
            if (seen[key]) {
                return false;
            }
            seen[key] = true;
            return true;
        });
    }

    function buildSearch() {
        var box = document.querySelector('.header-search');
        if (!box) {
            return;
        }

        var input = box.querySelector('input');
        if (!input) {
            return;
        }
        input.placeholder = ui.search;

        var results = document.createElement('div');
        results.className = 'search-results';
        results.style.display = 'none';
        box.appendChild(results);

        var feed = navSearchItems();

        input.oninput = function () {
            var query = input.value.trim().toLowerCase();
            results.innerHTML = '';

            if (query.length === 0) {
                results.style.display = 'none';
                return;
            }

            var matches = feed.filter(function (item) {
                return item.label.toLowerCase().indexOf(query) >= 0
                    || item.english.toLowerCase().indexOf(query) >= 0;
            }).slice(0, 20);

            matches.forEach(function (item) {
                var link = document.createElement('a');
                link.href = item.href;

                var section = document.createElement('span');
                section.className = 'section';
                section.textContent = item.section;
                link.appendChild(section);
                link.appendChild(document.createElement('br'));
                link.appendChild(document.createTextNode(item.label));

                results.appendChild(link);
            });

            if (matches.length === 0) {
                var none = document.createElement('a');
                none.textContent = ui.noResults;
                none.removeAttribute('href');
                results.appendChild(none);
            }

            results.style.display = 'block';
        };

        document.addEventListener('click', function (event) {
            if (!box.contains(event.target)) {
                results.style.display = 'none';
            }
        });

        input.onkeydown = function (event) {
            if (event.key === 'Escape') {
                results.style.display = 'none';
                input.blur();
            }
        };
    }

    /* ---------------------------------------------------------------- */
    /* Grammar highlighting                                             */
    /* ---------------------------------------------------------------- */

    function highlightGrammarBlocks() {
        if (typeof Prism === 'undefined'
                || !Prism.languages
                || !Prism.languages['alam-ebnf']) {
            return;
        }

        document.querySelectorAll('article pre.gram code').forEach(function (code) {
            code.classList.remove('language-alam');
            code.classList.add('language-alam-ebnf');
            Prism.highlightElement(code);
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        buildHeader();
        buildSidebar();
        buildBreadcrumb();
        buildRail();
        buildAnchors();
        highlightGrammarBlocks();
        buildCopyButtons();
        buildSearch();
    });
})();