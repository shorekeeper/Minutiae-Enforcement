package org.synergyst.minutiae.boot;

import org.bukkit.plugin.PluginManager;
import org.synergyst.minutiae.MinutiaeEnforcement;
import org.synergyst.minutiae.annotation.AnnotationRegistry;
import org.synergyst.minutiae.async.AsyncScheduler;
import org.synergyst.minutiae.behaviour.BehaviourConfig;
import org.synergyst.minutiae.behaviour.BehaviourEffects;
import org.synergyst.minutiae.behaviour.BehaviourManager;
import org.synergyst.minutiae.behaviour.listener.ChatListener;
import org.synergyst.minutiae.behaviour.listener.CommandListener;
import org.synergyst.minutiae.behaviour.listener.InteractionListener;
import org.synergyst.minutiae.behaviour.listener.MovementListener;
import org.synergyst.minutiae.behaviour.listener.PresenceListener;
import org.synergyst.minutiae.chat.ChatCaptureConfig;
import org.synergyst.minutiae.chat.ChatHistoryService;
import org.synergyst.minutiae.command.AppealCommand;
import org.synergyst.minutiae.command.DiagnosticCommand;
import org.synergyst.minutiae.command.EnforceCommand;
import org.synergyst.minutiae.command.dsl.Dispatcher;
import org.synergyst.minutiae.config.RuntimeConfig;
import org.synergyst.minutiae.engine.LiveGuardEnvironment;
import org.synergyst.minutiae.engine.SafetyConfig;
import org.synergyst.minutiae.dispatch.*;
import org.synergyst.minutiae.execute.*;
import org.synergyst.minutiae.fingerprint.FingerprintConfig;
import org.synergyst.minutiae.fingerprint.FingerprintEngine;
import org.synergyst.minutiae.fingerprint.FingerprintService;
import org.synergyst.minutiae.fingerprint.SessionRegistry;
import org.synergyst.minutiae.layout.LayoutRegistry;
import org.synergyst.minutiae.lifecycle.ReloadManager;
import org.synergyst.minutiae.lifecycle.Reloadable;
import org.synergyst.minutiae.lifecycle.ServiceContainer;
import org.synergyst.minutiae.log.KernelLogger;
import org.synergyst.minutiae.maintenance.MaintenanceService;
import org.synergyst.minutiae.message.Arg;
import org.synergyst.minutiae.message.MessageKey;
import org.synergyst.minutiae.message.MessageService;
import org.synergyst.minutiae.net.*;
import org.synergyst.minutiae.resolve.SanctionResolver;
import org.synergyst.minutiae.rule.RuleRegistry;
import org.synergyst.minutiae.storage.Storage;
import org.synergyst.minutiae.storage.sqlite.SqliteStorage;
import org.synergyst.minutiae.notify.NotificationService;
import org.synergyst.minutiae.notify.NotifyConfig;
import org.synergyst.minutiae.preference.PreferenceService;
import org.synergyst.minutiae.web.WebConfig;
import org.synergyst.minutiae.web.WebServer;
import org.synergyst.minutiae.web.hive.Hive;
import org.synergyst.minutiae.web.hive.HiveProviders;
import org.synergyst.minutiae.command.dsl.Node;
/**
 * Orchestrates the ordered initialisation of all subsystems.
 *
 * <p>The boot sequence is expressed as one method per {@link BootStage}, invoked
 * in canonical order by {@link #run()}. Each stage is self-contained: it emits a
 * boundary diagnostic, constructs the components it owns by resolving their
 * collaborators from the {@link ServiceContainer}, and publishes its own results
 * back to the container. No component reference is threaded through the sequence
 * as a local variable; the container is the single source of resolved services.
 *
 * <p>Resolution via the container is fail-fast: a stage that requests a service
 * not yet published throws {@link IllegalStateException}, surfacing an
 * out-of-order dependency as an immediate, named error rather than a downstream
 * null dereference. Any stage failure aborts the sequence and propagates to the
 * caller, which unwinds already-booted components via the container.
 */
public final class BootSequence {

    private final MinutiaeEnforcement plugin;
    private final KernelLogger log;
    private final ServiceContainer services;

    public BootSequence(final MinutiaeEnforcement plugin,
                        final KernelLogger log,
                        final ServiceContainer services) {
        this.plugin = plugin;
        this.log = log;
        this.services = services;
    }

    /**
     * Executes the full boot sequence in canonical stage order.
     *
     * @throws Exception if any stage fails; the caller must invoke teardown
     */
    public void run() throws Exception {
        banner();
        stageConfig();
        stageAsync();
        stageMessages();
        stageStorage();
        stageRules();
        stageAnnotations();
        stageLang();
        stageLayouts();
        stageFingerprint();
        stageBehaviour();
        stageMaintenance();
        stageNotify();
        stageNetwork();
        stageEnforce();
        stageDispatch();
        stageWeb();
        stageCommands();
        stageReady();
    }

    // ----------------------------------------------------------------------
    // Stages
    // ----------------------------------------------------------------------

    /** Materialises the runtime configuration snapshot and publishes it. */
    private void stageConfig() {
        stage(BootStage.CONFIG);
        plugin.saveDefaultConfig();
        final RuntimeConfig config = RuntimeConfig.from(plugin.getConfig());
        services.register(RuntimeConfig.class, config);
        log.info("config", "storage.driver=%s pool.max=%d rules.file=%s layouts.file=%s",
                config.storage().driver(), config.storage().maxConnections(),
                config.rulesFile(), config.layoutsFile());
    }

    /** Boots the asynchronous execution scheduler. */
    private void stageAsync() throws Exception {
        stage(BootStage.ASYNC);
        services.bootComponent(AsyncScheduler.class, new AsyncScheduler(log));
    }

    /** Boots the localisation message service. */
    private void stageMessages() throws Exception {
        stage(BootStage.MESSAGES);
        services.bootComponent(MessageService.class, new MessageService(
                log, plugin.getDataFolder(), this::saveResource, plugin::getResource,
                plugin.getConfig().getString("messages.default-locale", "en"),
                plugin.getConfig().getBoolean("messages.per-player-locale", true),
                plugin.getConfig().getInt("messages.reason.abbreviate", 32)));
    }

    /** Validates the storage driver selection and boots the backend. */
    private void stageStorage() throws Exception {
        stage(BootStage.STORAGE);
        final RuntimeConfig config = config();
        if (!"SQLITE".equals(config.storage().driver())) {
            throw new IllegalStateException("unsupported storage driver: " + config.storage().driver());
        }
        services.bootComponent(Storage.class, new SqliteStorage(
                log, config.storage(), services.get(AsyncScheduler.class),
                plugin.getDataFolder().toPath()));
    }

    /** Boots the rule registry and reconciles the persisted rule cache. */
    private void stageRules() throws Exception {
        stage(BootStage.RULES);
        services.bootComponent(RuleRegistry.class, new RuleRegistry(
                log, services.get(Storage.class), plugin.getDataFolder(),
                config().rulesFile(), this::saveResource));
    }

    /** Boots the annotation model: catalogue and relationship matrix. */
    private void stageAnnotations() throws Exception {
        stage(BootStage.ANNOTATIONS);
        services.bootComponent(AnnotationRegistry.class, new AnnotationRegistry(
                log, () -> plugin.getConfig().getConfigurationSection("annotations")));
    }

    /** Initialises the fingerprint engine, session tracking, and login listeners. */
    private void stageFingerprint() throws Exception {
        stage(BootStage.FINGERPRINT);
        final org.bukkit.configuration.ConfigurationSection fp =
                plugin.getConfig().getConfigurationSection("fingerprint");

        final FingerprintConfig fpConfig = FingerprintConfig.from(fp, log);
        final org.synergyst.minutiae.fingerprint.EvidenceConfig evidenceConfig =
                org.synergyst.minutiae.fingerprint.EvidenceConfig.from(
                        fp == null ? null : fp.getConfigurationSection("evidence"));
        final org.synergyst.minutiae.fingerprint.SessionCorrelationConfig corrConfig =
                org.synergyst.minutiae.fingerprint.SessionCorrelationConfig.from(
                        fp == null ? null : fp.getConfigurationSection("correlation"));
        final org.synergyst.minutiae.web.hive.ClusterConfig clusterConfig =
                org.synergyst.minutiae.web.hive.ClusterConfig.from(
                        fp == null ? null : fp.getConfigurationSection("clustering"));
        final org.synergyst.minutiae.fingerprint.NetworkClassifier classifier =
                org.synergyst.minutiae.fingerprint.NetworkClassifier.build(
                        fp == null ? null : fp.getConfigurationSection("network"), log);
        final boolean reverseDns = fp != null && fp.getBoolean("reverse-dns", false);

        final SessionRegistry sessions = services.register(SessionRegistry.class,
                new SessionRegistry());

        // The engine loads persisted beliefs and primes the frequency snapshot in
        // its boot method; it is booted as a lifecycle component and registered
        // for reload so its model recompiles on demand.
        final FingerprintEngine engine = services.bootComponent(FingerprintEngine.class,
                new FingerprintEngine(log, services.get(Storage.class),
                        services.get(AsyncScheduler.class), evidenceConfig, corrConfig,
                        clusterConfig, classifier, reverseDns));

        final FingerprintService fingerprint = services.register(FingerprintService.class,
                new FingerprintService(log, services.get(Storage.class), sessions, fpConfig,
                        engine, services.get(MessageService.class), plugin));

        pm().registerEvents(sessions, plugin);
        pm().registerEvents(fingerprint, plugin);
        pm().registerEvents(new org.synergyst.minutiae.fingerprint.SessionAuditListener(
                log, services.get(Storage.class)), plugin);


        log.info("fingerprint", "engine online: enabled=%b flag>=%.2f reverse-dns=%b",
                fpConfig.enabled(), engine.model().flagThreshold(), reverseDns);
    }

    /** Boots the behavioural state manager, effects, chat capture, and enforcement listeners. */
    private void stageBehaviour() throws Exception {
        stage(BootStage.BEHAVIOUR);
        final BehaviourConfig cfg = BehaviourConfig.from(
                plugin.getConfig().getConfigurationSection("behaviour"));
        final BehaviourManager manager = services.bootComponent(
                BehaviourManager.class, new BehaviourManager(log));
        final BehaviourEffects effects = services.register(BehaviourEffects.class,
                new BehaviourEffects(log, plugin, cfg, manager, services.get(MessageService.class)));

        final Storage storage = services.get(Storage.class);
        final MessageService messages = services.get(MessageService.class);
        pm().registerEvents(new ChatListener(manager, messages), plugin);
        pm().registerEvents(new CommandListener(manager, cfg, messages), plugin);
        pm().registerEvents(new MovementListener(manager, cfg, effects), plugin);
        pm().registerEvents(new InteractionListener(manager), plugin);
        pm().registerEvents(new PresenceListener(log, plugin, storage, manager, effects), plugin);

        final ChatCaptureConfig chatCfg = ChatCaptureConfig.from(
                plugin.getConfig().getConfigurationSection("chat-history"));
        final ChatHistoryService chatHistory = services.bootComponent(
                ChatHistoryService.class, new ChatHistoryService(log, storage, chatCfg));
        pm().registerEvents(chatHistory, plugin);

        log.info("behaviour", "manager online; 6 listener(s) registered");
    }

    /** Schedules the periodic maintenance sweeper. */
    private void stageMaintenance() throws Exception {
        stage(BootStage.MAINTENANCE);
        services.bootComponent(MaintenanceService.class, new MaintenanceService(
                log, plugin, services.get(SessionRegistry.class),
                services.get(BehaviourManager.class), services.get(BehaviourEffects.class),
                services.get(org.synergyst.minutiae.fingerprint.FingerprintEngine.class),
                plugin.getConfig().getLong("maintenance.interval", 30L),
                plugin.getConfig().getLong("maintenance.session-grace", 300L),
                plugin.getConfig().getLong("maintenance.frequency-refresh", 600L)));
    }

    /** Boots the notification service and the sanction-review poller. */
    private void stageNotify() throws Exception {
        stage(BootStage.NOTIFY);
        final NotifyConfig notifyConfig = NotifyConfig.from(
                plugin.getConfig().getConfigurationSection("notify"), log);
        // Published for the command layer, whose spec completer suggests
        // channel names for the @notify parameter.
        services.register(NotifyConfig.class, notifyConfig);
        services.bootComponent(NotificationService.class, new NotificationService(
                log, services.get(MessageService.class), services.get(AsyncScheduler.class),
                notifyConfig, plugin));
        services.bootComponent(ReviewService.class, new ReviewService(
                log, plugin, services.get(Storage.class),
                services.get(NotificationService.class)));
    }

    /** Resolves the instance identity and boots the directive bus. */
    private void stageNetwork() throws Exception {
        stage(BootStage.NETWORK);
        final NetworkConfig netConfig = NetworkConfig.from(
                plugin.getConfig().getConfigurationSection("network"));
        final ServerIdentity identity = ServerIdentity.resolve(
                plugin.getDataFolder(), netConfig.configuredId(), log);
        services.register(ServerIdentity.class, identity);

        final DirectiveBus bus = services.bootComponent(DirectiveBus.class, new DirectiveBus(
                log, plugin, netConfig, identity, services.get(Storage.class),
                services.get(MessageService.class), services.get(BehaviourManager.class),
                services.get(BehaviourEffects.class)));
        services.register(NetworkBus.class, bus);

        pm().registerEvents(new ForwardingSentinel(log), plugin);
        if (!plugin.getServer().getOnlineMode() && !netConfig.enabled()) {
            log.warn("network", "online-mode is off (proxy deployment?) while networking"
                    + " is disabled; multi-server effect propagation will not occur");
        }
    }


    /** Publishes the resolver and executor and registers the access guard. */
    private void stageEnforce() {
        stage(BootStage.ENFORCE);
        services.register(SanctionResolver.class, new SanctionResolver(
                services.get(AnnotationRegistry.class), services.get(LayoutRegistry.class),
                services.get(RuleRegistry.class)));

        final PreferenceService preferences = services.register(PreferenceService.class,
                new PreferenceService(log, services.get(Storage.class)));
        pm().registerEvents(preferences, plugin);

        final BroadcastConfig broadcast = BroadcastConfig.from(
                plugin.getConfig().getConfigurationSection("broadcast"));

        final EscalationConfig escalation = EscalationConfig.from(
                plugin.getConfig().getConfigurationSection("escalation"));

        // Operator-defined rank catalogue for issuance attribution; consumed
        // by the executor and the lift path through the {rank} placeholder.
        final org.synergyst.minutiae.rank.RankConfig ranks =
                services.register(org.synergyst.minutiae.rank.RankConfig.class,
                        org.synergyst.minutiae.rank.RankConfig.from(
                                plugin.getConfig().getConfigurationSection("ranks")));

        // Recent-identifier ring backing synchronous tab completion. Warmed
        // asynchronously from the newest persisted sanctions, oldest first so
        // the newest identifier ends up at the ring head; a warm failure
        // degrades to an empty ring and is not fatal.
        final org.synergyst.minutiae.command.RecentIds recents =
                services.register(org.synergyst.minutiae.command.RecentIds.class,
                        new org.synergyst.minutiae.command.RecentIds());
        services.get(Storage.class)
                .listRecent(org.synergyst.minutiae.command.RecentIds.WARM_LIMIT, 0)
                .whenComplete((rows, err) -> {
                    if (err != null) {
                        log.warn("enforce", "recent-id warm failed: %s", err.getMessage());
                        return;
                    }
                    for (int i = rows.size() - 1; i >= 0; i--) {
                        try {
                            recents.recordSanction(rows.get(i).id(),
                                    java.util.UUID.fromString(rows.get(i).uuid()));
                        } catch (final IllegalArgumentException malformedUuid) {
                            recents.recordSanction(rows.get(i).id(), null);
                        }
                    }
                });
        services.get(Storage.class)
                .listPendingAppeals(org.synergyst.minutiae.command.RecentIds.WARM_LIMIT, 0)
                .whenComplete((rows, err) -> {
                    if (err != null) {
                        return;
                    }
                    for (int i = rows.size() - 1; i >= 0; i--) {
                        recents.recordAppeal(rows.get(i).id(), null);
                    }
                });

        services.register(SanctionExecutor.class, new SanctionExecutor(
                log, services.get(Storage.class), services.get(AnnotationRegistry.class),
                services.get(FingerprintService.class), services.get(MessageService.class),
                services.get(BehaviourManager.class), services.get(BehaviourEffects.class),
                services.get(NotificationService.class), preferences, broadcast, escalation,
                services.get(ChatHistoryService.class), services.get(NetworkBus.class),
                services.get(ServerIdentity.class), ranks, recents, plugin));

        services.register(SanctionLift.class, new SanctionLift(
                log, services.get(Storage.class), services.get(MessageService.class),
                services.get(BehaviourManager.class), services.get(BehaviourEffects.class),
                broadcast, ranks, services.get(NetworkBus.class),
                services.get(NotificationService.class), plugin));

        pm().registerEvents(new AccessListener(
                log, services.get(Storage.class), services.get(MessageService.class)), plugin);
        log.info("enforce", "resolver and executor online; access guard registered");
    }

    /** Plans every definition source and publishes plans and stamped layouts. */
    private void stageLang() throws Exception {
        stage(BootStage.LANG);
        services.bootComponent(AlamService.class, new AlamService(
                log, plugin.getDataFolder(), services.get(RuleRegistry.class)));
    }

    /** Boots the layout registry against the rule and annotation models. */
    private void stageLayouts() throws Exception {
        stage(BootStage.LAYOUTS);
        services.bootComponent(LayoutRegistry.class, new LayoutRegistry(
                log, services.get(RuleRegistry.class), services.get(AnnotationRegistry.class),
                plugin.getDataFolder(), config().layoutsFile(), this::saveResource,
                () -> services.get(AlamService.class).stampedLayouts()));
    }

    /** Wires the dispatch engine, guard environment, and event listeners. */
    private void stageDispatch() {
        stage(BootStage.DISPATCH);
        final SafetyConfig safety = SafetyConfig.from(
                plugin.getConfig().getConfigurationSection("alam"));
        final LiveGuardEnvironment env = new LiveGuardEnvironment(
                services.get(Storage.class), services.get(FingerprintService.class), plugin);
        final DispatchEngine engine = services.register(DispatchEngine.class, new DispatchEngine(
                log, services.get(AlamService.class).armedRules(),
                services.get(SanctionResolver.class), services.get(SanctionExecutor.class),
                services.get(MessageService.class), services.get(Storage.class),
                safety, env, () -> plugin.getServer().getConsoleSender()));

        pm().registerEvents(new ChatEventListener(engine), plugin);
        pm().registerEvents(new BreakEventListener(engine), plugin);
        pm().registerEvents(new LoginEventListener(engine), plugin);
        log.info("dispatch", "engine online: %d rule(s) armed, armed=%b throttle=%d/min",
                engine.ruleCount(), safety.armedGlobally(), safety.throttlePerMin());
    }

    /** Assembles the Hive projection and starts the web panel transport. */
    private void stageWeb() throws Exception {
        stage(BootStage.WEB);
        final WebConfig webConfig = WebConfig.from(plugin.getConfig().getConfigurationSection("web"));

        final org.synergyst.minutiae.name.NameService names =
                services.bootComponent(org.synergyst.minutiae.name.NameService.class,
                        new org.synergyst.minutiae.name.NameService(log, services.get(Storage.class)));
        pm().registerEvents(names, plugin);

        final HiveProviders providers = new HiveProviders(
                services.get(Storage.class), services.get(RuleRegistry.class),
                services.get(FingerprintService.class), names, plugin,
                System.currentTimeMillis());

        final Hive hive = services.bootComponent(Hive.class, new Hive(log, providers));

        services.bootComponent(WebServer.class, new WebServer(
                log, plugin, webConfig, hive, services.get(Storage.class),
                services.get(RuleRegistry.class), services.get(SanctionLift.class),
                services.get(SanctionResolver.class), services.get(SanctionExecutor.class)));
    }

    /** Assembles the command trees and arms the dispatcher. */
    private void stageCommands() {
        stage(BootStage.COMMANDS);

        final ReloadManager reloads = services.register(ReloadManager.class, new ReloadManager(log));
        reloads.register((Reloadable) services.get(MessageService.class));
        reloads.register((Reloadable) services.get(RuleRegistry.class));
        reloads.register((Reloadable) services.get(AnnotationRegistry.class));
        reloads.register((Reloadable) services.get(LayoutRegistry.class));
        reloads.register((Reloadable) services.get(FingerprintEngine.class));

        // Route command-framework failures through the localisation service.
        final MessageService messages = services.get(MessageService.class);
        org.synergyst.minutiae.command.dsl.Node.failureReporter((ctx, error) ->
                messages.send(ctx.sender(), org.synergyst.minutiae.message.MessageKey.ERROR_COMMAND,
                        org.synergyst.minutiae.message.Arg.s("error", error)));

        final org.synergyst.minutiae.command.RecentIds recents =
                services.get(org.synergyst.minutiae.command.RecentIds.class);
        final org.synergyst.minutiae.command.SpecCompleter specCompleter =
                new org.synergyst.minutiae.command.SpecCompleter(
                        services.get(LayoutRegistry.class),
                        services.get(AnnotationRegistry.class),
                        services.get(RuleRegistry.class),
                        recents,
                        () -> services.get(org.synergyst.minutiae.notify.NotifyConfig.class)
                                .channels().keySet());

        final Dispatcher dispatcher = new Dispatcher(plugin, log);
        dispatcher.register(new DiagnosticCommand(
                        services.get(Storage.class), services.get(RuleRegistry.class),
                        services.get(LayoutRegistry.class), services.get(AnnotationRegistry.class),
                        services.get(FingerprintService.class), services.get(MessageService.class),
                        services.get(PreferenceService.class), reloads, plugin).tree(),
                "Minutiae Enforcement diagnostics");
        dispatcher.register(new EnforceCommand(
                        services.get(SanctionResolver.class), services.get(SanctionExecutor.class),
                        services.get(SanctionLift.class), services.get(Storage.class),
                        services.get(LayoutRegistry.class), services.get(AnnotationRegistry.class),
                        services.get(MessageService.class), specCompleter, recents).tree(),
                "Minutiae Enforcement sanction issuance");
        dispatcher.register(new AppealCommand(
                        services.get(Storage.class), services.get(MessageService.class),
                        services.get(NotificationService.class), recents, plugin).tree(),
                "Minutiae Enforcement appeal submission");
        dispatcher.register(new AlamCommand(
                        services.get(DispatchEngine.class),
                        services.get(MessageService.class)).tree(),
                "Minutiae Enforcement dispatch inspection and simulation");
        dispatcher.arm();
    }

    /** Emits the readiness marker. */
    private void stageReady() {
        stage(BootStage.READY);
        log.info("ready", "all subsystems online");
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    /**
     * Resolves the published runtime configuration snapshot.
     *
     * @return the runtime configuration
     */
    private RuntimeConfig config() {
        return services.get(RuntimeConfig.class);
    }

    /**
     * Returns the server plugin manager.
     *
     * @return the plugin manager
     */
    private PluginManager pm() {
        return plugin.getServer().getPluginManager();
    }

    /**
     * Provisions a bundled resource to the data folder without overwriting an
     * existing file. Used as the resource-saver callback for components that own
     * default configuration files.
     *
     * @param resourceName the jar-relative resource name
     */
    private void saveResource(final String resourceName) {
        plugin.saveResource(resourceName, false);
    }

    private void stage(final BootStage stage) {
        log.info("boot", "-> stage %d/%d : %s",
                stage.ordinal() + 1, BootStage.values().length, stage.label());
    }

    private void banner() {
        log.info("boot", "Minutiae Enforcement %s", plugin.getPluginMeta().getVersion());
        log.info("boot", "runtime java=%s vm=%s",
                System.getProperty("java.version"), System.getProperty("java.vm.name"));
        log.info("boot", "server=%s", plugin.getServer().getVersion());
        log.info("boot", "initiating boot sequence");
    }
}