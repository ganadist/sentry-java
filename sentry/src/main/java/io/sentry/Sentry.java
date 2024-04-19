package io.sentry;

import io.sentry.backpressure.BackpressureMonitor;
import io.sentry.cache.EnvelopeCache;
import io.sentry.cache.IEnvelopeCache;
import io.sentry.config.PropertiesProviderFactory;
import io.sentry.internal.debugmeta.NoOpDebugMetaLoader;
import io.sentry.internal.debugmeta.ResourcesDebugMetaLoader;
import io.sentry.internal.modules.CompositeModulesLoader;
import io.sentry.internal.modules.IModulesLoader;
import io.sentry.internal.modules.ManifestModulesLoader;
import io.sentry.internal.modules.NoOpModulesLoader;
import io.sentry.internal.modules.ResourcesModulesLoader;
import io.sentry.metrics.MetricsApi;
import io.sentry.protocol.SentryId;
import io.sentry.protocol.User;
import io.sentry.transport.NoOpEnvelopeCache;
import io.sentry.util.DebugMetaPropertiesApplier;
import io.sentry.util.FileUtils;
import io.sentry.util.Platform;
import io.sentry.util.thread.IMainThreadChecker;
import io.sentry.util.thread.MainThreadChecker;
import io.sentry.util.thread.NoOpMainThreadChecker;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Sentry SDK main API entry point */
public final class Sentry {

  private Sentry() {}

  private static volatile @NotNull IScopesStorage scopesStorage = new DefaultScopesStorage();

  /** The Main Hub or NoOp if Sentry is disabled. */
  private static volatile @NotNull IScopes mainScopes = NoOpScopes.getInstance();
  // TODO cannot pass options here
  private static volatile @NotNull IScope globalScope = new Scope(new SentryOptions());

  /** Default value for globalHubMode is false */
  private static final boolean GLOBAL_HUB_DEFAULT_MODE = false;

  /** whether to use a single (global) Hub as opposed to one per thread. */
  private static volatile boolean globalHubMode = GLOBAL_HUB_DEFAULT_MODE;

  @ApiStatus.Internal
  public static final @NotNull String APP_START_PROFILING_CONFIG_FILE_NAME =
      "app_start_profiling_config";

  @SuppressWarnings("CharsetObjectCanBeUsed")
  private static final Charset UTF_8 = Charset.forName("UTF-8");

  /** Timestamp used to check old profiles to delete. */
  private static final long classCreationTimestamp = System.currentTimeMillis();

  /**
   * Returns the current (threads) hub, if none, clones the mainScopes and returns it.
   *
   * @return the hub
   */
  @ApiStatus.Internal // exposed for the coroutines integration in SentryContext
  @SuppressWarnings("deprecation")
  @Deprecated
  public static @NotNull IHub getCurrentHub() {
    return new HubScopesWrapper(getCurrentScopes());
  }

  @ApiStatus.Internal // exposed for the coroutines integration in SentryContext
  @SuppressWarnings("deprecation")
  public static @NotNull IScopes getCurrentScopes() {
    if (globalHubMode) {
      return mainScopes;
    }
    IScopes scopes = getScopesStorage().get();
    if (scopes == null || scopes.isNoOp()) {
      // TODO fork instead
      scopes = mainScopes.clone();
      getScopesStorage().set(scopes);
    }
    return scopes;
  }

  private static @NotNull IScopesStorage getScopesStorage() {
    return scopesStorage;
  }

  /**
   * Returns a new hub which is cloned from the mainScopes.
   *
   * @return the hub
   */
  @ApiStatus.Internal
  @ApiStatus.Experimental
  @SuppressWarnings("deprecation")
  public static @NotNull IScopes cloneMainHub() {
    if (globalHubMode) {
      return mainScopes;
    }
    // TODO fork instead
    return mainScopes.clone();
  }

  @ApiStatus.Internal // exposed for the coroutines integration in SentryContext
  @Deprecated
  @SuppressWarnings({"deprecation", "InlineMeSuggester"})
  public static void setCurrentHub(final @NotNull IHub hub) {
    setCurrentScopes(hub);
  }

  @ApiStatus.Internal // exposed for the coroutines integration in SentryContext
  public static @NotNull ISentryLifecycleToken setCurrentScopes(final @NotNull IScopes scopes) {
    return getScopesStorage().set(scopes);
  }

  public static @NotNull IScope getGlobalScope() {
    return globalScope;
  }

  /**
   * Check if the current Hub is enabled/active.
   *
   * @return true if its enabled or false otherwise.
   */
  public static boolean isEnabled() {
    return getCurrentScopes().isEnabled();
  }

  /** Initializes the SDK */
  public static void init() {
    init(options -> options.setEnableExternalConfiguration(true), GLOBAL_HUB_DEFAULT_MODE);
  }

  /**
   * Initializes the SDK
   *
   * @param dsn The Sentry DSN
   */
  public static void init(final @NotNull String dsn) {
    init(options -> options.setDsn(dsn));
  }

  /**
   * Initializes the SDK
   *
   * @param clazz OptionsContainer for SentryOptions
   * @param optionsConfiguration configuration options callback
   * @param <T> class that extends SentryOptions
   * @throws IllegalAccessException the IllegalAccessException
   * @throws InstantiationException the InstantiationException
   * @throws NoSuchMethodException the NoSuchMethodException
   * @throws InvocationTargetException the InvocationTargetException
   */
  public static <T extends SentryOptions> void init(
      final @NotNull OptionsContainer<T> clazz,
      final @NotNull OptionsConfiguration<T> optionsConfiguration)
      throws IllegalAccessException, InstantiationException, NoSuchMethodException,
          InvocationTargetException {
    init(clazz, optionsConfiguration, GLOBAL_HUB_DEFAULT_MODE);
  }

  /**
   * Initializes the SDK
   *
   * @param clazz OptionsContainer for SentryOptions
   * @param optionsConfiguration configuration options callback
   * @param globalHubMode the globalHubMode
   * @param <T> class that extends SentryOptions
   * @throws IllegalAccessException the IllegalAccessException
   * @throws InstantiationException the InstantiationException
   * @throws NoSuchMethodException the NoSuchMethodException
   * @throws InvocationTargetException the InvocationTargetException
   */
  public static <T extends SentryOptions> void init(
      final @NotNull OptionsContainer<T> clazz,
      final @NotNull OptionsConfiguration<T> optionsConfiguration,
      final boolean globalHubMode)
      throws IllegalAccessException, InstantiationException, NoSuchMethodException,
          InvocationTargetException {
    final T options = clazz.createInstance();
    applyOptionsConfiguration(optionsConfiguration, options);
    init(options, globalHubMode);
  }

  /**
   * Initializes the SDK with an optional configuration options callback.
   *
   * @param optionsConfiguration configuration options callback
   */
  public static void init(final @NotNull OptionsConfiguration<SentryOptions> optionsConfiguration) {
    init(optionsConfiguration, GLOBAL_HUB_DEFAULT_MODE);
  }

  /**
   * Initializes the SDK with an optional configuration options callback.
   *
   * @param optionsConfiguration configuration options callback
   * @param globalHubMode the globalHubMode
   */
  public static void init(
      final @NotNull OptionsConfiguration<SentryOptions> optionsConfiguration,
      final boolean globalHubMode) {
    final SentryOptions options = new SentryOptions();
    applyOptionsConfiguration(optionsConfiguration, options);
    init(options, globalHubMode);
  }

  private static <T extends SentryOptions> void applyOptionsConfiguration(
      OptionsConfiguration<T> optionsConfiguration, T options) {
    try {
      optionsConfiguration.configure(options);
    } catch (Throwable t) {
      options
          .getLogger()
          .log(SentryLevel.ERROR, "Error in the 'OptionsConfiguration.configure' callback.", t);
    }
  }

  /**
   * Initializes the SDK with a SentryOptions.
   *
   * @param options options the SentryOptions
   */
  @ApiStatus.Internal
  public static void init(final @NotNull SentryOptions options) {
    init(options, GLOBAL_HUB_DEFAULT_MODE);
  }

  /**
   * Initializes the SDK with a SentryOptions and globalHubMode
   *
   * @param options options the SentryOptions
   * @param globalHubMode the globalHubMode
   */
  @SuppressWarnings("deprecation")
  private static synchronized void init(
      final @NotNull SentryOptions options, final boolean globalHubMode) {
    if (isEnabled()) {
      options
          .getLogger()
          .log(
              SentryLevel.WARNING,
              "Sentry has been already initialized. Previous configuration will be overwritten.");
    }

    if (!initConfigurations(options)) {
      return;
    }

    options.getLogger().log(SentryLevel.INFO, "GlobalHubMode: '%s'", String.valueOf(globalHubMode));
    Sentry.globalHubMode = globalHubMode;

    final IScopes scopes = getCurrentScopes();
    final IScope rootScope = new Scope(options);
    // TODO should use separate isolation scope:
    //    final IScope rootIsolationScope = new Scope(options);
    // TODO should be:
    //    getGlobalScope().bindClient(new SentryClient(options));
    rootScope.bindClient(new SentryClient(options));
    // TODO shouldn't replace global scope
    globalScope = rootScope;
    mainScopes = new Scopes(rootScope, rootScope, options, "Sentry.init");

    getScopesStorage().set(mainScopes);

    scopes.close(true);

    // If the executorService passed in the init is the same that was previously closed, we have to
    // set a new one
    if (options.getExecutorService().isClosed()) {
      options.setExecutorService(new SentryExecutorService());
    }

    // when integrations are registered on Hub ctor and async integrations are fired,
    // it might and actually happened that integrations called captureSomething
    // and hub was still NoOp.
    // Registering integrations here make sure that Hub is already created.
    for (final Integration integration : options.getIntegrations()) {
      integration.register(ScopesAdapter.getInstance(), options);
    }

    notifyOptionsObservers(options);

    finalizePreviousSession(options, ScopesAdapter.getInstance());

    handleAppStartProfilingConfig(options, options.getExecutorService());
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private static void handleAppStartProfilingConfig(
      final @NotNull SentryOptions options,
      final @NotNull ISentryExecutorService sentryExecutorService) {
    try {
      sentryExecutorService.submit(
          () -> {
            final String cacheDirPath = options.getCacheDirPathWithoutDsn();
            if (cacheDirPath != null) {
              final @NotNull File appStartProfilingConfigFile =
                  new File(cacheDirPath, APP_START_PROFILING_CONFIG_FILE_NAME);
              try {
                // We always delete the config file for app start profiling
                FileUtils.deleteRecursively(appStartProfilingConfigFile);
                if (!options.isEnableAppStartProfiling()) {
                  return;
                }
                if (!options.isTracingEnabled()) {
                  options
                      .getLogger()
                      .log(
                          SentryLevel.INFO,
                          "Tracing is disabled and app start profiling will not start.");
                  return;
                }
                if (appStartProfilingConfigFile.createNewFile()) {
                  final @NotNull TracesSamplingDecision appStartSamplingDecision =
                      sampleAppStartProfiling(options);
                  final @NotNull SentryAppStartProfilingOptions appStartProfilingOptions =
                      new SentryAppStartProfilingOptions(options, appStartSamplingDecision);
                  try (final OutputStream outputStream =
                          new FileOutputStream(appStartProfilingConfigFile);
                      final Writer writer =
                          new BufferedWriter(new OutputStreamWriter(outputStream, UTF_8))) {
                    options.getSerializer().serialize(appStartProfilingOptions, writer);
                  }
                }
              } catch (Throwable e) {
                options
                    .getLogger()
                    .log(
                        SentryLevel.ERROR, "Unable to create app start profiling config file. ", e);
              }
            }
          });
    } catch (Throwable e) {
      options
          .getLogger()
          .log(
              SentryLevel.ERROR,
              "Failed to call the executor. App start profiling config will not be changed. Did you call Sentry.close()?",
              e);
    }
  }

  private static @NotNull TracesSamplingDecision sampleAppStartProfiling(
      final @NotNull SentryOptions options) {
    TransactionContext appStartTransactionContext = new TransactionContext("app.launch", "profile");
    appStartTransactionContext.setForNextAppStart(true);
    SamplingContext appStartSamplingContext = new SamplingContext(appStartTransactionContext, null);
    return new TracesSampler(options).sample(appStartSamplingContext);
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private static void finalizePreviousSession(
      final @NotNull SentryOptions options, final @NotNull IScopes scopes) {
    // enqueue a task to finalize previous session. Since the executor
    // is single-threaded, this task will be enqueued sequentially after all integrations that have
    // to modify the previous session have done their work, even if they do that async.
    try {
      options.getExecutorService().submit(new PreviousSessionFinalizer(options, scopes));
    } catch (Throwable e) {
      options.getLogger().log(SentryLevel.DEBUG, "Failed to finalize previous session.", e);
    }
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private static void notifyOptionsObservers(final @NotNull SentryOptions options) {
    // enqueue a task to trigger the static options change for the observers. Since the executor
    // is single-threaded, this task will be enqueued sequentially after all integrations that rely
    // on the observers have done their work, even if they do that async.
    try {
      options
          .getExecutorService()
          .submit(
              () -> {
                // for static things like sentry options we can immediately trigger observers
                for (final IOptionsObserver observer : options.getOptionsObservers()) {
                  observer.setRelease(options.getRelease());
                  observer.setProguardUuid(options.getProguardUuid());
                  observer.setSdkVersion(options.getSdkVersion());
                  observer.setDist(options.getDist());
                  observer.setEnvironment(options.getEnvironment());
                  observer.setTags(options.getTags());
                }
              });
    } catch (Throwable e) {
      options.getLogger().log(SentryLevel.DEBUG, "Failed to notify options observers.", e);
    }
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private static boolean initConfigurations(final @NotNull SentryOptions options) {
    if (options.isEnableExternalConfiguration()) {
      options.merge(ExternalOptions.from(PropertiesProviderFactory.create(), options.getLogger()));
    }

    final String dsn = options.getDsn();

    if (!options.isEnabled() || (dsn != null && dsn.isEmpty())) {
      close();
      return false;
    } else if (dsn == null) {
      throw new IllegalArgumentException(
          "DSN is required. Use empty string or set enabled to false in SentryOptions to disable SDK.");
    }

    @SuppressWarnings("unused")
    final Dsn parsedDsn = new Dsn(dsn);

    ILogger logger = options.getLogger();

    if (options.isDebug() && logger instanceof NoOpLogger) {
      options.setLogger(new SystemOutLogger());
      logger = options.getLogger();
    }
    logger.log(SentryLevel.INFO, "Initializing SDK with DSN: '%s'", options.getDsn());

    // TODO: read values from conf file, Build conf or system envs
    // eg release, distinctId, sentryClientName

    // this should be after setting serializers
    final String outboxPath = options.getOutboxPath();
    if (outboxPath != null) {
      final File outboxDir = new File(outboxPath);
      outboxDir.mkdirs();
    } else {
      logger.log(SentryLevel.INFO, "No outbox dir path is defined in options.");
    }

    final String cacheDirPath = options.getCacheDirPath();
    if (cacheDirPath != null) {
      final File cacheDir = new File(cacheDirPath);
      cacheDir.mkdirs();
      final IEnvelopeCache envelopeCache = options.getEnvelopeDiskCache();
      // only overwrite the cache impl if it's not already set
      if (envelopeCache instanceof NoOpEnvelopeCache) {
        options.setEnvelopeDiskCache(EnvelopeCache.create(options));
      }
    }

    final String profilingTracesDirPath = options.getProfilingTracesDirPath();
    if (options.isProfilingEnabled() && profilingTracesDirPath != null) {

      final File profilingTracesDir = new File(profilingTracesDirPath);
      profilingTracesDir.mkdirs();

      try {
        options
            .getExecutorService()
            .submit(
                () -> {
                  final File[] oldTracesDirContent = profilingTracesDir.listFiles();
                  if (oldTracesDirContent == null) return;
                  // Method trace files are normally deleted at the end of traces, but if that fails
                  // for some reason we try to clear any old files here.
                  for (File f : oldTracesDirContent) {
                    // We delete files 5 minutes older than class creation to account for app
                    // start profiles, as an app start profile could have a lower creation date.
                    if (f.lastModified() < classCreationTimestamp - TimeUnit.MINUTES.toMillis(5)) {
                      FileUtils.deleteRecursively(f);
                    }
                  }
                });
      } catch (RejectedExecutionException e) {
        options
            .getLogger()
            .log(
                SentryLevel.ERROR,
                "Failed to call the executor. Old profiles will not be deleted. Did you call Sentry.close()?",
                e);
      }
    }

    final @NotNull IModulesLoader modulesLoader = options.getModulesLoader();
    if (!options.isSendModules()) {
      options.setModulesLoader(NoOpModulesLoader.getInstance());
    } else if (modulesLoader instanceof NoOpModulesLoader) {
      options.setModulesLoader(
          new CompositeModulesLoader(
              Arrays.asList(
                  new ManifestModulesLoader(options.getLogger()),
                  new ResourcesModulesLoader(options.getLogger())),
              options.getLogger()));
    }

    if (options.getDebugMetaLoader() instanceof NoOpDebugMetaLoader) {
      options.setDebugMetaLoader(new ResourcesDebugMetaLoader(options.getLogger()));
    }
    final @Nullable List<Properties> propertiesList = options.getDebugMetaLoader().loadDebugMeta();
    DebugMetaPropertiesApplier.applyToOptions(options, propertiesList);

    final IMainThreadChecker mainThreadChecker = options.getMainThreadChecker();
    // only override the MainThreadChecker if it's not already set by Android
    if (mainThreadChecker instanceof NoOpMainThreadChecker) {
      options.setMainThreadChecker(MainThreadChecker.getInstance());
    }

    if (options.getPerformanceCollectors().isEmpty()) {
      options.addPerformanceCollector(new JavaMemoryCollector());
    }

    if (options.isEnableBackpressureHandling()) {
      options.setBackpressureMonitor(new BackpressureMonitor(options, ScopesAdapter.getInstance()));
      options.getBackpressureMonitor().start();
    }

    return true;
  }

  /** Close the SDK */
  public static synchronized void close() {
    final IScopes scopes = getCurrentScopes();
    mainScopes = NoOpScopes.getInstance();
    // remove thread local to avoid memory leak
    getScopesStorage().close();
    scopes.close(false);
  }

  /**
   * Captures the event.
   *
   * @param event the event
   * @return The Id (SentryId object) of the event
   */
  public static @NotNull SentryId captureEvent(final @NotNull SentryEvent event) {
    return getCurrentScopes().captureEvent(event);
  }

  /**
   * Captures the event.
   *
   * @param event The event.
   * @param callback The callback to configure the scope for a single invocation.
   * @return The Id (SentryId object) of the event
   */
  public static @NotNull SentryId captureEvent(
      final @NotNull SentryEvent event, final @NotNull ScopeCallback callback) {
    return getCurrentScopes().captureEvent(event, callback);
  }

  /**
   * Captures the event.
   *
   * @param event the event
   * @param hint SDK specific but provides high level information about the origin of the event
   * @return The Id (SentryId object) of the event
   */
  public static @NotNull SentryId captureEvent(
      final @NotNull SentryEvent event, final @Nullable Hint hint) {
    return getCurrentScopes().captureEvent(event, hint);
  }

  /**
   * Captures the event.
   *
   * @param event The event.
   * @param hint SDK specific but provides high level information about the origin of the event
   * @param callback The callback to configure the scope for a single invocation.
   * @return The Id (SentryId object) of the event
   */
  public static @NotNull SentryId captureEvent(
      final @NotNull SentryEvent event,
      final @Nullable Hint hint,
      final @NotNull ScopeCallback callback) {
    return getCurrentScopes().captureEvent(event, hint, callback);
  }

  /**
   * Captures the message.
   *
   * @param message The message to send.
   * @return The Id (SentryId object) of the event
   */
  public static @NotNull SentryId captureMessage(final @NotNull String message) {
    return getCurrentScopes().captureMessage(message);
  }

  /**
   * Captures the message.
   *
   * @param message The message to send.
   * @param callback The callback to configure the scope for a single invocation.
   * @return The Id (SentryId object) of the event
   */
  public static @NotNull SentryId captureMessage(
      final @NotNull String message, final @NotNull ScopeCallback callback) {
    return getCurrentScopes().captureMessage(message, callback);
  }

  /**
   * Captures the message.
   *
   * @param message The message to send.
   * @param level The message level.
   * @return The Id (SentryId object) of the event
   */
  public static @NotNull SentryId captureMessage(
      final @NotNull String message, final @NotNull SentryLevel level) {
    return getCurrentScopes().captureMessage(message, level);
  }

  /**
   * Captures the message.
   *
   * @param message The message to send.
   * @param level The message level.
   * @param callback The callback to configure the scope for a single invocation.
   * @return The Id (SentryId object) of the event
   */
  public static @NotNull SentryId captureMessage(
      final @NotNull String message,
      final @NotNull SentryLevel level,
      final @NotNull ScopeCallback callback) {
    return getCurrentScopes().captureMessage(message, level, callback);
  }

  /**
   * Captures the exception.
   *
   * @param throwable The exception.
   * @return The Id (SentryId object) of the event
   */
  public static @NotNull SentryId captureException(final @NotNull Throwable throwable) {
    return getCurrentScopes().captureException(throwable);
  }

  /**
   * Captures the exception.
   *
   * @param throwable The exception.
   * @param callback The callback to configure the scope for a single invocation.
   * @return The Id (SentryId object) of the event
   */
  public static @NotNull SentryId captureException(
      final @NotNull Throwable throwable, final @NotNull ScopeCallback callback) {
    return getCurrentScopes().captureException(throwable, callback);
  }

  /**
   * Captures the exception.
   *
   * @param throwable The exception.
   * @param hint SDK specific but provides high level information about the origin of the event
   * @return The Id (SentryId object) of the event
   */
  public static @NotNull SentryId captureException(
      final @NotNull Throwable throwable, final @Nullable Hint hint) {
    return getCurrentScopes().captureException(throwable, hint);
  }

  /**
   * Captures the exception.
   *
   * @param throwable The exception.
   * @param hint SDK specific but provides high level information about the origin of the event
   * @param callback The callback to configure the scope for a single invocation.
   * @return The Id (SentryId object) of the event
   */
  public static @NotNull SentryId captureException(
      final @NotNull Throwable throwable,
      final @Nullable Hint hint,
      final @NotNull ScopeCallback callback) {
    return getCurrentScopes().captureException(throwable, hint, callback);
  }

  /**
   * Captures a manually created user feedback and sends it to Sentry.
   *
   * @param userFeedback The user feedback to send to Sentry.
   */
  public static void captureUserFeedback(final @NotNull UserFeedback userFeedback) {
    getCurrentScopes().captureUserFeedback(userFeedback);
  }

  /**
   * Adds a breadcrumb to the current Scope
   *
   * @param breadcrumb the breadcrumb
   * @param hint SDK specific but provides high level information about the origin of the event
   */
  public static void addBreadcrumb(
      final @NotNull Breadcrumb breadcrumb, final @Nullable Hint hint) {
    getCurrentScopes().addBreadcrumb(breadcrumb, hint);
  }

  /**
   * Adds a breadcrumb to the current Scope
   *
   * @param breadcrumb the breadcrumb
   */
  public static void addBreadcrumb(final @NotNull Breadcrumb breadcrumb) {
    getCurrentScopes().addBreadcrumb(breadcrumb);
  }

  /**
   * Adds a breadcrumb to the current Scope
   *
   * @param message rendered as text and the whitespace is preserved.
   */
  public static void addBreadcrumb(final @NotNull String message) {
    getCurrentScopes().addBreadcrumb(message);
  }

  /**
   * Adds a breadcrumb to the current Scope
   *
   * @param message rendered as text and the whitespace is preserved.
   * @param category Categories are dotted strings that indicate what the crumb is or where it comes
   *     from.
   */
  public static void addBreadcrumb(final @NotNull String message, final @NotNull String category) {
    getCurrentScopes().addBreadcrumb(message, category);
  }

  /**
   * Sets the level of all events sent within current Scope
   *
   * @param level the Sentry level
   */
  public static void setLevel(final @Nullable SentryLevel level) {
    getCurrentScopes().setLevel(level);
  }

  /**
   * Sets the name of the current transaction to the current Scope.
   *
   * @param transaction the transaction
   */
  public static void setTransaction(final @Nullable String transaction) {
    getCurrentScopes().setTransaction(transaction);
  }

  /**
   * Shallow merges user configuration (email, username, etc) to the current Scope.
   *
   * @param user the user
   */
  public static void setUser(final @Nullable User user) {
    getCurrentScopes().setUser(user);
  }

  /**
   * Sets the fingerprint to group specific events together to the current Scope.
   *
   * @param fingerprint the fingerprints
   */
  public static void setFingerprint(final @NotNull List<String> fingerprint) {
    getCurrentScopes().setFingerprint(fingerprint);
  }

  /** Deletes current breadcrumbs from the current scope. */
  public static void clearBreadcrumbs() {
    getCurrentScopes().clearBreadcrumbs();
  }

  /**
   * Sets the tag to a string value to the current Scope, overwriting a potential previous value
   *
   * @param key the key
   * @param value the value
   */
  public static void setTag(final @NotNull String key, final @NotNull String value) {
    getCurrentScopes().setTag(key, value);
  }

  /**
   * Removes the tag to a string value to the current Scope
   *
   * @param key the key
   */
  public static void removeTag(final @NotNull String key) {
    getCurrentScopes().removeTag(key);
  }

  /**
   * Sets the extra key to an arbitrary value to the current Scope, overwriting a potential previous
   * value
   *
   * @param key the key
   * @param value the value
   */
  public static void setExtra(final @NotNull String key, final @NotNull String value) {
    getCurrentScopes().setExtra(key, value);
  }

  /**
   * Removes the extra key to an arbitrary value to the current Scope
   *
   * @param key the key
   */
  public static void removeExtra(final @NotNull String key) {
    getCurrentScopes().removeExtra(key);
  }

  /**
   * Last event id recorded in the current scope
   *
   * @return last SentryId
   */
  public static @NotNull SentryId getLastEventId() {
    return getCurrentScopes().getLastEventId();
  }

  /** Pushes a new scope while inheriting the current scope's data. */
  public static void pushScope() {
    // pushScope is no-op in global hub mode
    if (!globalHubMode) {
      // TODO this might have to behave differently from Scopes.pushScope
      getCurrentScopes().pushScope();
    }
  }

  /** Removes the first scope */
  public static void popScope() {
    // popScope is no-op in global hub mode
    if (!globalHubMode) {
      // TODO this might have to behave differently from Scopes.popScope
      getCurrentScopes().popScope();
    }
  }

  /**
   * Runs the callback with a new scope which gets dropped at the end
   *
   * @param callback the callback
   */
  public static void withScope(final @NotNull ScopeCallback callback) {
    // TODO this might have to behave differently from Scopes.withScope
    getCurrentScopes().withScope(callback);
  }

  /**
   * Configures the scope through the callback.
   *
   * @param callback The configure scope callback.
   */
  public static void configureScope(final @NotNull ScopeCallback callback) {
    getCurrentScopes().configureScope(callback);
  }

  /**
   * Binds a different client to the current hub
   *
   * @param client the client.
   */
  public static void bindClient(final @NotNull ISentryClient client) {
    getCurrentScopes().bindClient(client);
  }

  public static boolean isHealthy() {
    return getCurrentScopes().isHealthy();
  }

  /**
   * Flushes events queued up to the current hub. Not implemented yet.
   *
   * @param timeoutMillis time in milliseconds
   */
  public static void flush(final long timeoutMillis) {
    getCurrentScopes().flush(timeoutMillis);
  }

  /** Starts a new session. If there's a running session, it ends it before starting the new one. */
  public static void startSession() {
    getCurrentScopes().startSession();
  }

  /** Ends the current session */
  public static void endSession() {
    getCurrentScopes().endSession();
  }

  /**
   * Creates a Transaction and returns the instance. Started transaction is set on the scope.
   *
   * @param name the transaction name
   * @param operation the operation
   * @return created transaction
   */
  public static @NotNull ITransaction startTransaction(
      final @NotNull String name, final @NotNull String operation) {
    return getCurrentScopes().startTransaction(name, operation);
  }

  /**
   * Creates a Transaction and returns the instance.
   *
   * @param name the transaction name
   * @param operation the operation
   * @param transactionOptions options for the transaction
   * @return created transaction
   */
  public static @NotNull ITransaction startTransaction(
      final @NotNull String name,
      final @NotNull String operation,
      final @NotNull TransactionOptions transactionOptions) {
    return getCurrentScopes().startTransaction(name, operation, transactionOptions);
  }

  /**
   * Creates a Transaction and returns the instance.
   *
   * @param name the transaction name
   * @param operation the operation
   * @param description the description
   * @param transactionOptions options for the transaction
   * @return created transaction
   */
  public static @NotNull ITransaction startTransaction(
      final @NotNull String name,
      final @NotNull String operation,
      final @Nullable String description,
      final @NotNull TransactionOptions transactionOptions) {
    final ITransaction transaction =
        getCurrentScopes().startTransaction(name, operation, transactionOptions);
    transaction.setDescription(description);
    return transaction;
  }

  /**
   * Creates a Transaction and returns the instance.
   *
   * @param transactionContexts the transaction contexts
   * @return created transaction
   */
  public static @NotNull ITransaction startTransaction(
      final @NotNull TransactionContext transactionContexts) {
    return getCurrentScopes().startTransaction(transactionContexts);
  }

  /**
   * Creates a Transaction and returns the instance.
   *
   * @param transactionContext the transaction context
   * @param transactionOptions options for the transaction
   * @return created transaction.
   */
  public static @NotNull ITransaction startTransaction(
      final @NotNull TransactionContext transactionContext,
      final @NotNull TransactionOptions transactionOptions) {
    return getCurrentScopes().startTransaction(transactionContext, transactionOptions);
  }

  /**
   * Returns the "sentry-trace" header that allows tracing across services. Can also be used in
   * &lt;meta&gt; HTML tags. Also see {@link Sentry#getBaggage()}.
   *
   * @deprecated please use {@link Sentry#getTraceparent()} instead.
   * @return sentry trace header or null
   */
  @Deprecated
  @SuppressWarnings("InlineMeSuggester")
  public static @Nullable SentryTraceHeader traceHeaders() {
    return getCurrentScopes().traceHeaders();
  }

  /**
   * Gets the current active transaction or span.
   *
   * @return the active span or null when no active transaction is running. In case of
   *     globalHubMode=true, always the active transaction is returned, rather than the last active
   *     span.
   */
  public static @Nullable ISpan getSpan() {
    if (globalHubMode && Platform.isAndroid()) {
      return getCurrentScopes().getTransaction();
    } else {
      return getCurrentScopes().getSpan();
    }
  }

  /**
   * Returns if the App has crashed (Process has terminated) during the last run. It only returns
   * true or false if offline caching {{@link SentryOptions#getCacheDirPath()} } is set with a valid
   * dir.
   *
   * <p>If the call to this method is early in the App lifecycle and the SDK could not check if the
   * App has crashed in the background, the check is gonna do IO in the calling thread.
   *
   * @return true if App has crashed, false otherwise, and null if not evaluated yet
   */
  public static @Nullable Boolean isCrashedLastRun() {
    return getCurrentScopes().isCrashedLastRun();
  }

  /**
   * Report a screen has been fully loaded. That means all data needed by the UI was loaded. If
   * time-to-full-display tracing {{@link SentryOptions#isEnableTimeToFullDisplayTracing()} } is
   * disabled this call is ignored.
   *
   * <p>This method is safe to be called multiple times. If the time-to-full-display span is already
   * finished, this call will be ignored.
   */
  public static void reportFullyDisplayed() {
    getCurrentScopes().reportFullyDisplayed();
  }

  /**
   * @deprecated See {@link Sentry#reportFullyDisplayed()}.
   */
  @Deprecated
  @SuppressWarnings("InlineMeSuggester")
  public static void reportFullDisplayed() {
    reportFullyDisplayed();
  }

  /** the metrics API for the current hub */
  @NotNull
  @ApiStatus.Experimental
  public static MetricsApi metrics() {
    return getCurrentScopes().metrics();
  }

  /**
   * Configuration options callback
   *
   * @param <T> a class that extends SentryOptions or SentryOptions itself.
   */
  public interface OptionsConfiguration<T extends SentryOptions> {

    /**
     * configure the options
     *
     * @param options the options
     */
    void configure(@NotNull T options);
  }

  /**
   * Continue a trace based on HTTP header values. If no "sentry-trace" header is provided a random
   * trace ID and span ID is created.
   *
   * @param sentryTrace "sentry-trace" header
   * @param baggageHeaders "baggage" headers
   * @return a transaction context for starting a transaction or null if performance is disabled
   */
  // return TransactionContext (if performance enabled) or null (if performance disabled)
  public static @Nullable TransactionContext continueTrace(
      final @Nullable String sentryTrace, final @Nullable List<String> baggageHeaders) {
    return getCurrentScopes().continueTrace(sentryTrace, baggageHeaders);
  }

  /**
   * Returns the "sentry-trace" header that allows tracing across services. Can also be used in
   * &lt;meta&gt; HTML tags. Also see {@link Sentry#getBaggage()}.
   *
   * @return sentry trace header or null
   */
  public static @Nullable SentryTraceHeader getTraceparent() {
    return getCurrentScopes().getTraceparent();
  }

  /**
   * Returns the "baggage" header that allows tracing across services. Can also be used in
   * &lt;meta&gt; HTML tags. Also see {@link Sentry#getTraceparent()}.
   *
   * @return baggage header or null
   */
  public static @Nullable BaggageHeader getBaggage() {
    return getCurrentScopes().getBaggage();
  }

  @ApiStatus.Experimental
  public static @NotNull SentryId captureCheckIn(final @NotNull CheckIn checkIn) {
    return getCurrentScopes().captureCheckIn(checkIn);
  }
}
