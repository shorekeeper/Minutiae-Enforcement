package org.synergyst.minutiae.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;
import org.synergyst.minutiae.execute.LiftKind;
import org.synergyst.minutiae.execute.SanctionLift;
import org.synergyst.minutiae.lifecycle.LifecycleComponent;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.rule.RuleRegistry;
import org.synergyst.minutiae.storage.Storage;
import org.synergyst.minutiae.web.hive.Hive;
import org.synergyst.minutiae.web.hive.HiveKey;
import org.synergyst.minutiae.web.hive.HivePath;
import org.synergyst.minutiae.web.hive.HiveValue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP transport for the web panel.
 *
 * <p>The server binds the configured interface and serves three surfaces: the
 * embedded single-page application and its static assets; a read API that
 * resolves Hive keys and performs search; and a write API that dispatches
 * actions through the sanction lift and appeal paths. Every request is gated by a
 * session token mapped to a role; navigation requires the {@code read} role and
 * actions require {@code write}. Handlers run on a small dedicated pool, off the
 * server main thread, and may join storage futures directly; write actions are
 * marshalled to the main thread by the underlying lift and executor.
 *
 * <p>A write action is dispatched through a synthetic sender whose permission
 * predicate admits only the scoped {@link #WRITE_GRANTS} set. Nodes conferring
 * moderator impersonation, aggravating commutation, and amendment are withheld,
 * so a compromised write token cannot exceed a bounded moderation surface.
 *
 * <p>The server holds the sole reference to the JDK {@link HttpServer} and its
 * executor, both released on shutdown.
 */
public final class WebServer implements LifecycleComponent {

    /**
     * Permission nodes conferred on the write role. The set omits nodes that
     * permit moderator impersonation ({@code as}), aggravating commutation, and
     * amendment of existing sanctions, bounding the authority of a write token.
     */
    private static final Set<String> WRITE_GRANTS = Set.of(
            "minutiae.annotation.measure",
            "minutiae.annotation.count",
            "minutiae.annotation.silent",
            "minutiae.annotation.evidence",
            "minutiae.annotation.notify",
            "minutiae.annotation.warn-first",
            "minutiae.annotation.escalate",
            "minutiae.annotation.appeal",
            "minutiae.annotation.decay",
            "minutiae.annotation.ghost",
            "minutiae.annotation.rubberband",
            "minutiae.annotation.shadow",
            "minutiae.annotation.now",
            "minutiae.annotation.dry-run",
            "minutiae.annotation.link",
            "minutiae.annotation.reason",
            "minutiae.annotation.stay",
            "minutiae.annotation.commute",
            "minutiae.annotation.tariff",
            "minutiae.annotation.waive",
            "minutiae.annotation.probation",
            "minutiae.annotation.transcript",
            "minutiae.annotation.weave",
            "minutiae.waive",
            "minutiae.lift.foreign",
            "minutiae.lift.blocking",
            "minutiae.commute.mitigate");

    private final KernelLogger log;
    private final JavaPlugin plugin;
    private final WebConfig config;
    private final Hive hive;
    private final Storage storage;
    private final RuleRegistry rules;
    private final SanctionLift lift;
    private final org.synergyst.minutiae.resolve.SanctionResolver resolver;
    private final org.synergyst.minutiae.execute.SanctionExecutor executor;

    private HttpServer http;
    private ExecutorService pool;
    private byte[] indexHtml;
    private byte[] appJs;
    private byte[] appCss;

    public WebServer(final KernelLogger log,
                     final JavaPlugin plugin,
                     final WebConfig config,
                     final Hive hive,
                     final Storage storage,
                     final RuleRegistry rules,
                     final SanctionLift lift,
                     final org.synergyst.minutiae.resolve.SanctionResolver resolver,
                     final org.synergyst.minutiae.execute.SanctionExecutor executor) {
        this.log = log;
        this.plugin = plugin;
        this.config = config;
        this.hive = hive;
        this.storage = storage;
        this.rules = rules;
        this.lift = lift;
        this.resolver = resolver;
        this.executor = executor;
    }

    @Override
    public String tag() {
        return "web";
    }

    @Override
    public void boot() throws Exception {
        if (!config.enabled()) {
            log.info("web", "panel disabled");
            return;
        }
        indexHtml = asset("/web/index.html");
        appJs = asset("/web/app.js");
        appCss = asset("/web/app.css");

        final AtomicLong seq = new AtomicLong();
        this.pool = Executors.newFixedThreadPool(4, r -> {
            final Thread t = new Thread(r, "minutiae-web-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        });

        this.http = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 0);
        http.setExecutor(pool);
        http.createContext("/", this::dispatch);
        http.start();
        log.info("web", "panel online at http://%s:%d/ (%d token(s))",
                config.bind(), config.port(), config.tokens().size());
    }

    @Override
    public void shutdown() {
        if (http != null) {
            http.stop(0);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    // ----------------------------------------------------------------------
    // Dispatch
    // ----------------------------------------------------------------------

    private void dispatch(final HttpExchange ex) throws IOException {
        try {
            final String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/", "/index.html" -> serve(ex, 200, "text/html; charset=utf-8", indexHtml);
                case "/app.js" -> serve(ex, 200, "application/javascript; charset=utf-8", appJs);
                case "/app.css" -> serve(ex, 200, "text/css; charset=utf-8", appCss);
                case "/api/login" -> handleLogin(ex);
                case "/api/key" -> handleKey(ex);
                case "/api/search" -> handleSearch(ex);
                case "/api/action" -> handleAction(ex);
                default -> json(ex, 404, "{\"error\":\"not found\"}");
            }
        } catch (final Exception e) {
            log.warn("web", "request failed: %s", e.getMessage());
            json(ex, 500, "{\"error\":\"" + Json.esc(e.getMessage()) + "\"}");
        } finally {
            ex.close();
        }
    }

    private void handleLogin(final HttpExchange ex) throws IOException {
        final Map<String, String> body = Json.parseFlat(readBody(ex));
        final String token = body.get("token");
        final String role = config.roleOf(token);
        if (role == null) {
            json(ex, 401, "{\"error\":\"invalid token\"}");
            return;
        }
        // The Secure attribute is omitted only when bound to loopback, where the
        // absence of TLS is not exploitable; any non-loopback bind demands it.
        final boolean loopback = config.bind().equals("127.0.0.1")
                || config.bind().equals("::1")
                || config.bind().equalsIgnoreCase("localhost");
        ex.getResponseHeaders().add("Set-Cookie",
                "me_session=" + token + "; HttpOnly; SameSite=Strict; Path=/"
                        + (loopback ? "" : "; Secure"));
        json(ex, 200, "{\"role\":\"" + Json.esc(role) + "\"}");
    }

    private void handleKey(final HttpExchange ex) throws IOException {
        if (roleOf(ex) == null) {
            json(ex, 401, "{\"error\":\"unauthenticated\"}");
            return;
        }
        final String raw = query(ex).getOrDefault("path", "/");
        final HiveKey key = hive.resolve(HivePath.parse(raw)).join();
        if (key == null) {
            json(ex, 404, "{\"error\":\"no such key\"}");
            return;
        }
        json(ex, 200, keyToJson(key));
    }

    private void handleSearch(final HttpExchange ex) throws IOException {
        if (roleOf(ex) == null) {
            json(ex, 401, "{\"error\":\"unauthenticated\"}");
            return;
        }
        final String q = query(ex).getOrDefault("q", "").trim();
        final StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        if (!q.isEmpty()) {
            // The length bound keeps an all-digit query within the parsable range
            // of a signed 64-bit integer, so the numeric branch cannot throw.
            if (q.length() <= 18 && q.chars().allMatch(Character::isDigit)) {
                final var v = storage.findSanction(Long.parseLong(q)).join();
                if (v != null) {
                    final String path = "/HKEY_CASES/Players/" + v.uuid()
                            + "/Sanctions/" + v.measure() + "/" + v.staff() + "/" + v.id();
                    first = result(sb, first, path, "#" + v.id() + " " + v.measure(), "sanction");
                }
            } else {
                final String byName = storage.resolveUuidByName(q).join();
                if (byName != null) {
                    first = result(sb, first, "/HKEY_CASES/Players/" + byName, q, "subject");
                } else {
                    final var op = plugin.getServer().getOfflinePlayerIfCached(q);
                    if (op != null) {
                        first = result(sb, first, "/HKEY_CASES/Players/" + op.getUniqueId(),
                                q, "subject");
                    }
                }
                first = result(sb, first, "/HKEY_APPEALS/Players/" + q, q, "appellant");
            }
        }
        sb.append("]");
        json(ex, 200, sb.toString());
    }

    private void handleAction(final HttpExchange ex) throws IOException {
        final String role = roleOf(ex);
        if (!"write".equals(role)) {
            json(ex, 403, "{\"error\":\"write role required\"}");
            return;
        }
        final Map<String, String> body = Json.parseFlat(readBody(ex));
        final String type = body.getOrDefault("type", "");
        final long id = safeLong(body.get("id"));
        final String reason = body.get("reason");

        // The synthetic sender's authority is the scoped write-grant set, not an
        // unconditional grant, so a panel action cannot exceed a manual command
        // by a moderator holding that set.
        final WebCommandSender actor =
                new WebCommandSender(plugin, "web:" + role, WRITE_GRANTS::contains);

        switch (type) {
            case "lift" -> {
                final LiftKind kind = LiftKind.parse(body.get("kind"));
                // An absent or unrecognised kind degrades to vacate, the
                // historical default of the panel's lift button.
                lift.lift(actor, id, kind != null ? kind : LiftKind.VACATE, reason);
            }
            case "appeal-accept" -> storage.findAppeal(id).thenAccept(a -> {
                if (a != null && "PENDING".equals(a.status())) {
                    storage.decideAppeal(id, "ACCEPTED", reason, actor.getName(),
                            System.currentTimeMillis()).thenAccept(rows -> {
                        if (rows > 0) {
                            lift.lift(actor, a.banId(), reason != null ? reason : "appeal accepted");
                        }
                    });
                }
            });
            case "appeal-deny" -> storage.decideAppeal(id, "DENIED", reason, actor.getName(),
                    System.currentTimeMillis());
            case "enforce" -> {
                final String spec = body.getOrDefault("spec", "").trim();
                if (spec.isEmpty()) {
                    json(ex, 400, "{\"error\":\"empty spec\"}");
                    return;
                }
                try {
                    final org.synergyst.minutiae.command.parse.ParsedCommand parsed =
                            org.synergyst.minutiae.command.parse.CommandParser.parse(
                                    org.synergyst.minutiae.command.parse.CommandTokenizer.tokenize(spec));
                    final org.synergyst.minutiae.resolve.ResolvedSanction resolved =
                            resolver.resolve(parsed, actor::hasPermission);
                    executor.execute(resolved, actor);
                    json(ex, 202, "{\"queued\":true}");
                } catch (final org.synergyst.minutiae.command.parse.CommandParseException
                               | org.synergyst.minutiae.resolve.ResolveException e) {
                    json(ex, 400, "{\"error\":\"" + Json.esc(rootMsg(e)) + "\"}");
                }
                return;
            }
            default -> {
                json(ex, 400, "{\"error\":\"unknown action\"}");
                return;
            }
        }
        json(ex, 202, "{\"queued\":true}");
    }

    // ----------------------------------------------------------------------
    // Serialisation
    // ----------------------------------------------------------------------

    private static String rootMsg(final Throwable t) {
        final String m = t.getMessage();
        return m != null ? m : t.getClass().getSimpleName();
    }

    private String keyToJson(final HiveKey key) {
        final StringBuilder sb = new StringBuilder(512);
        sb.append("{\"path\":\"").append(Json.esc(key.path().toString()))
                .append("\",\"name\":\"").append(Json.esc(key.name()))
                .append("\",\"children\":[");
        boolean first = true;
        for (final org.synergyst.minutiae.web.hive.HiveChild c : key.children()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"name\":\"").append(Json.esc(c.name()))
                    .append("\",\"label\":\"").append(Json.esc(c.display()))
                    .append("\",\"hasChildren\":").append(c.hasChildren()).append('}');
        }
        sb.append("],\"values\":[");
        first = true;
        for (final HiveValue vv : key.values()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"name\":\"").append(Json.esc(vv.name()))
                    .append("\",\"type\":\"").append(vv.type().name())
                    .append("\",\"display\":\"").append(Json.esc(vv.display()))
                    .append("\",\"raw\":\"").append(Json.esc(vv.raw()))
                    .append("\",\"link\":").append(vv.link() == null ? "null"
                            : "\"" + Json.esc(vv.link()) + "\"")
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private boolean result(final StringBuilder sb, final boolean first, final String path,
                           final String label, final String kind) {
        if (!first) {
            sb.append(',');
        }
        sb.append("{\"path\":\"").append(Json.esc(path))
                .append("\",\"label\":\"").append(Json.esc(label))
                .append("\",\"kind\":\"").append(Json.esc(kind)).append("\"}");
        return false;
    }

    // ----------------------------------------------------------------------
    // Transport helpers
    // ----------------------------------------------------------------------

    private String roleOf(final HttpExchange ex) {
        final List<String> cookies = ex.getRequestHeaders().getOrDefault("Cookie", List.of());
        for (final String header : cookies) {
            for (final String part : header.split(";")) {
                final String p = part.trim();
                if (p.startsWith("me_session=")) {
                    return config.roleOf(p.substring("me_session=".length()));
                }
            }
        }
        return null;
    }

    private Map<String, String> query(final HttpExchange ex) {
        final Map<String, String> out = new HashMap<>();
        final String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) {
            return out;
        }
        for (final String pair : raw.split("&")) {
            final int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private String readBody(final HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void json(final HttpExchange ex, final int code, final String body) throws IOException {
        serve(ex, code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void serve(final HttpExchange ex, final int code, final String type, final byte[] body)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, body.length);
        try (final OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private byte[] asset(final String resource) throws IOException {
        try (final var in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing embedded asset " + resource);
            }
            return in.readAllBytes();
        }
    }

    private static long safeLong(final String s) {
        try {
            return s == null ? -1L : Long.parseLong(s.trim());
        } catch (final NumberFormatException e) {
            return -1L;
        }
    }
}