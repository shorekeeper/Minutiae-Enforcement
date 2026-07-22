/*
 * Navigation tree shared by every documentation page and language.
 *
 * Entity pages are intentionally absent from ME_NAV. The ALAM sidebar exposes
 * the hub, the four narrative guides, and category anchors on the hub. Entity
 * navigation is supplied by inline links and ME_RELATED.
 */
'use strict';

var ME_LANGS = ['en', 'ru', 'uk'];

var ME_LANG_NAMES = {
    en: 'English',
    ru: 'Русский',
    uk: 'Українська'
};

var ME_UI = {
    en: {
        docs: 'Docs',
        onThisPage: 'On this page',
        related: 'Related',
        search: 'Search docs',
        menu: 'Menu',
        theme: 'Theme',
        home: 'Overview',
        untranslated: 'This page has not been translated yet. You are reading the English version.',
        noResults: 'No results'
    },
    ru: {
        docs: 'Документация',
        onThisPage: 'На этой странице',
        related: 'Связано',
        search: 'Поиск по документации',
        menu: 'Меню',
        theme: 'Тема',
        home: 'Обзор',
        untranslated: 'Эта страница пока не переведена. Вы читаете английскую версию.',
        noResults: 'Ничего не найдено'
    },
    uk: {
        docs: 'Документація',
        onThisPage: 'На цій сторінці',
        related: 'Пов’язане',
        search: 'Пошук у документації',
        menu: 'Меню',
        theme: 'Тема',
        home: 'Огляд',
        untranslated: 'Цю сторінку ще не перекладено. Ви читаєте англійську версію.',
        noResults: 'Нічого не знайдено'
    }
};

var ME_NAV = [
    {
        title: { en: 'Overview', ru: 'Обзор', uk: 'Огляд' },
        items: [
            {
                slug: 'docs/index',
                title: { en: 'Introduction', ru: 'Введение', uk: 'Вступ' }
            },
            {
                slug: 'docs/getting-started',
                title: { en: 'Getting started', ru: 'Начало работы', uk: 'Початок роботи' }
            },
            {
                slug: 'docs/configuration',
                title: { en: 'Configuration', ru: 'Конфигурация', uk: 'Конфігурація' }
            }
        ]
    },
    {
        title: { en: 'Moderation', ru: 'Модерация', uk: 'Модерація' },
        items: [
            {
                slug: 'docs/commands',
                title: { en: 'Commands', ru: 'Команды', uk: 'Команди' }
            },
            {
                slug: 'docs/annotations',
                title: {
                    en: 'Annotations reference',
                    ru: 'Справочник аннотаций',
                    uk: 'Довідник анотацій'
                }
            },
            {
                slug: 'docs/measures',
                title: { en: 'Measures', ru: 'Меры', uk: 'Заходи' }
            },
            {
                slug: 'docs/layouts',
                title: {
                    en: 'Layouts and escalation',
                    ru: 'Шаблоны и эскалация',
                    uk: 'Шаблони та ескалація'
                }
            },
            {
                slug: 'docs/lift',
                title: {
                    en: 'Lifting sanctions',
                    ru: 'Снятие санкций',
                    uk: 'Зняття санкцій'
                }
            }
        ]
    },
    {
        title: { en: 'Administration', ru: 'Администрирование', uk: 'Адміністрування' },
        items: [
            {
                slug: 'docs/permissions',
                title: { en: 'Permissions', ru: 'Права', uk: 'Права' }
            },
            {
                slug: 'docs/localization',
                title: { en: 'Localization', ru: 'Локализация', uk: 'Локалізація' }
            },
            {
                slug: 'docs/web-panel',
                title: { en: 'Web panel', ru: 'Веб-панель', uk: 'Веб-панель' }
            },
            {
                slug: 'docs/fingerprint',
                title: {
                    en: 'Evasion detection',
                    ru: 'Детект обхода банов',
                    uk: 'Виявлення обходу банів'
                }
            }
        ]
    },
    {
        title: { en: 'The ALAM language', ru: 'Язык ALAM', uk: 'Мова ALAM' },
        items: [
            {
                slug: 'docs/alam/index',
                title: { en: 'Language reference', ru: 'Справочник языка', uk: 'Довідник мови' }
            },
            {
                slug: 'docs/alam/guide/lexical',
                title: { en: 'Lexical structure', ru: 'Лексическая структура', uk: 'Лексична структура' }
            },
            {
                slug: 'docs/alam/guide/pipeline',
                title: { en: 'Compilation pipeline', ru: 'Конвейер компиляции', uk: 'Конвеєр компіляції' }
            },
            {
                slug: 'docs/alam/guide/effects',
                title: { en: 'Effects', ru: 'Эффекты', uk: 'Ефекти' }
            },
            {
                slug: 'docs/alam/guide/runtime',
                title: { en: 'Runtime model', ru: 'Модель выполнения', uk: 'Модель виконання' }
            },
            {
                slug: 'docs/alam/index#keywords',
                title: { en: 'Keywords', ru: 'Ключевые слова', uk: 'Ключові слова' }
            },
            {
                slug: 'docs/alam/index#types',
                title: { en: 'Types', ru: 'Типы', uk: 'Типи' }
            },
            {
                slug: 'docs/alam/index#sums',
                title: { en: 'Sum types', ru: 'Суммарные типы', uk: 'Сумарні типи' }
            },
            {
                slug: 'docs/alam/index#constructors',
                title: { en: 'Constructors', ru: 'Конструкторы', uk: 'Конструктори' }
            },
            {
                slug: 'docs/alam/index#records',
                title: { en: 'Records', ru: 'Записи', uk: 'Записи' }
            },
            {
                slug: 'docs/alam/index#events',
                title: { en: 'Events', ru: 'События', uk: 'Події' }
            },
            {
                slug: 'docs/alam/index#functions',
                title: { en: 'Functions', ru: 'Функции', uk: 'Функції' }
            },
            {
                slug: 'docs/alam/index#diagnostics',
                title: { en: 'Diagnostics', ru: 'Диагностика', uk: 'Діагностика' }
            }
        ]
    }
];