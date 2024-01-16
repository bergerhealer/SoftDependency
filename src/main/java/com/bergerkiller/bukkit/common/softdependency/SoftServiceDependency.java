package com.bergerkiller.bukkit.common.softdependency;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Automatically tracks when an optional third-party service dependency enables.<br>
 * <br>
 * It's possible that the dependency disables after having previously
 * been enabled, in which case the implementation is reset to the default.
 * Callbacks are provided to be notified when the dependency enables or disables.<br>
 * <br>
 * This class can be safely created in Plugin constructors and will automatically
 * update itself after the plugin enables. It can be enabled while handling
 * <i>onEnable</i> by having the plugin call {@link #detect()} early. There is also
 * a {@link #detectAll(Object)} to automatically call this on all SoftServiceDependency
 * fields declared in a Class.<br>
 * <br>
 * This Dependency is also disabled when the owning plugin is about to disable,
 * ensuring that no logic involved with the dependency is left behind.<br>
 * <br>
 * If any errors occur while enabling the dependency API then the dependency
 * is left disabled.<br>
 * <br>
 * <b>Example usage:</b>
 * <pre>{@code
 * public class MyPlugin extends JavaPlugin {
 *
 *     private final SoftServiceDependency<MyDependencyService> myDependency = new SoftServiceDependency<MyDependencyPlugin>(this, "com.creator.mydep.MyDependencyService") {
 *         @Override
 *         protected MyDependencyService initialize(Object service) {
 *             return MyDependencyService.class.cast(service);
 *         }
 *
 *         @Override
 *         protected void onEnable() {
 *             getLogger().info("Support for MyDependency enabled!");
 *         }
 *
 *         @Override
 *         protected void onDisable() {
 *             getLogger().info("Support for MyDependency disabled!");
 *         }
 *     };
 *
 *     // Can use myDependency.get() anywhere, returns non-null if enabled.
 * }
 * }</pre>
 *
 * @param <T> Dependency API interface
 * @author Irmo van den Berge
 * @version 1.03
 */
public abstract class SoftServiceDependency<T> implements SoftDetectableDependency {
    /** The plugin that owns this Dependency and is informed of its status changes */
    protected final Plugin owningPlugin;
    /** The Service API Class name of this Dependency */
    protected final String dependencyServiceClassName;
    /** The default value {@link #get()} returns when this Dependency is disabled */
    protected final T defaultValue;

    private Object currentService = null;
    private Plugin currentServicePlugin = null;
    private T current;
    private boolean detecting = false;
    private boolean enabled = true;

    // This stuff must be done up-front, as otherwise we could cause a concurrent modification error
    // inside HandlerList.bakeAll()
    static {
        PluginDisableEvent.getHandlerList();
        ServiceRegisterEvent.getHandlerList();
        ServiceUnregisterEvent.getHandlerList();
    }

    /**
     * Builds a new SoftDependency using callback methods
     *
     * @param owningPlugin The plugin that owns this Dependency and is informed of its status changes
     * @param serviceClassName The Service Class Name of this Dependency
     * @return Builder
     * @param <T> Dependency API interface
     */
    public static <T> SoftServiceDependency.Builder<T> build(Plugin owningPlugin, String serviceClassName) {
        return new SoftServiceDependency.Builder<>(owningPlugin, serviceClassName);
    }

    /**
     * Constructs a new SoftServiceDependency
     *
     * @param owningPlugin The plugin that owns this Dependency and is informed of its status changes
     * @param serviceClassName The Service Class Name of this Dependency
     */
    public SoftServiceDependency(Plugin owningPlugin, String serviceClassName) {
        this(owningPlugin, serviceClassName, null);
    }

    /**
     * Constructs a new SoftServiceDependency
     *
     * @param owningPlugin The plugin that owns this Dependency and is informed of its status changes
     * @param serviceClassName The Service Class Name of this Dependency
     * @param defaultValue The default value {@link #get()} returns when this Dependency is disabled
     */
    public SoftServiceDependency(Plugin owningPlugin, String serviceClassName, T defaultValue) {
        this.owningPlugin = owningPlugin;
        this.dependencyServiceClassName = serviceClassName;
        this.defaultValue = defaultValue;
        this.current = defaultValue;
        SoftDependency.whenEnabled(owningPlugin, this::detect);
    }

    /**
     * Called after a Service matching this dependency enables, or after the owning
     * plugin enables and the service dependency is already enabled.<br>
     * <br>
     * Should be implemented to analyze the dependency and construct a suitable
     * dependency API implementation. Is permitted to throw exceptions if this fails,
     * in which case the dependency isn't further enabled.<br>
     * <br>
     * It's allowed to return {@link #defaultValue} if, for example, use of this
     * dependency was disabled in plugin configuration.
     *
     * @param service The service instance that was detected
     * @return Dependency interface implementation
     */
    protected abstract T initialize(Object service) throws Error, Exception;

    /**
     * Sets whether this dependency is enabled at all. If disabled, then the dependency
     * will not be activated if detected. Is by default enabled. Can be called
     * at any time to disable or re-enable a dependency, for example, when configuration
     * is changed.<br>
     * <br>
     * This dependency is automatically disabled <b>before</b> the owning Plugin's
     * <i>onDisable</i> is called. There is no need to disable this dependency yourself.
     *
     * @param enabled Whether this dependency is enabled
     */
    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            if (enabled) {
                detect();
            } else if (currentServicePlugin != null) {
                handleDisable(currentServicePlugin);
            }
        }
    }

    /**
     * Detects whether this dependency is currently enabled. Is called automatically
     * after the owning plugin is enabled, but can be called earlier during plugin
     * <i>onEnable()</i> to load a little sooner.<br>
     * <br>
     * If previously disabled using {@link #setEnabled(boolean) setEnabled(false)}
     * this method does nothing. If the owning plugin is not yet enabled
     * this method does nothing.
     */
    @Override
    public void detect() {
        if (!enabled || !owningPlugin.isEnabled()) {
            return;
        } else if (!detecting) {
            // Detect changes
            detecting = true;
            Bukkit.getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onServiceEnable(ServiceRegisterEvent event) {
                    if (!enabled) {
                        return;
                    }
                    Class<?> serviceClass = tryGetServiceClass();
                    if (serviceClass != null && serviceClass.isAssignableFrom(event.getProvider().getService())) {
                        handleEnable(event.getProvider());
                    }
                }

                @EventHandler
                public void onPluginDisable(PluginDisableEvent event) {
                    if (enabled && event.getPlugin() == owningPlugin) {
                        setEnabled(false);
                    }
                }

                @EventHandler
                public void onServiceDisable(ServiceUnregisterEvent event) {
                    if (enabled && event.getProvider().getProvider() == currentService) {
                        handleDisable(event.getProvider().getPlugin());
                    }
                }
            }, owningPlugin);
        }

        // Detect already enabled
        Class<?> serviceClass = tryGetServiceClass();
        if (serviceClass != null) {
            RegisteredServiceProvider<?> provider = Bukkit.getServer().getServicesManager().getRegistration(serviceClass);
            if (provider != null) {
                handleEnable(provider);
            }
        }
    }

    private Class<?> tryGetServiceClass() {
        try {
            return Class.forName(dependencyServiceClassName);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The plugin that owns this Dependency and is informed of its status changes
     *
     * @return owning plugin
     */
    public Plugin owner() {
        return owningPlugin;
    }

    /**
     * Gets the service Class name of this Dependency. The same name that was specified
     * when creating this SoftServiceDependency.
     *
     * @return dependency service Class Name
     */
    public String name() {
        return dependencyServiceClassName;
    }

    /**
     * Get the current active service dependency API implementation
     *
     * @return Current active service dependency API implementation
     */
    public T get() {
        return current;
    }

    /**
     * Gets the Service instance of this dependency. Returns <i>null</i> if the
     * dependency is not currently enabled.
     *
     * @return Current active dependency Service
     */
    public Object getService() {
        return currentService;
    }

    /**
     * Gets the Plugin instance that provides the current {@link #getService() Service}
     * of this dependency. Returns <i>null</i> if the dependency is not currently enabled.
     *
     * @return Current active dependency service Plugin
     */
    public Plugin getServicePlugin() {
        return currentServicePlugin;
    }

    /**
     * Gets whether this dependency was detected and has enabled.
     *
     * @return True if this dependency is currently enabled
     */
    public boolean isEnabled() {
        return currentService != null;
    }

    /**
     * Callback called on the main thread when this dependency is enabled.
     * The dependency {@link #getService() Service} and {@link #get() API implementation}
     * are both available at this point.
     */
    protected void onEnable() {
    }

    /**
     * Callback called on the main thread right before this dependency is disabled.
     * The dependency {@link #getService() Service} and {@link #get() API implementation}
     * are both still available at this point and can be accessed as this dependency
     * has not yet disabled.
     */
    protected void onDisable() {
    }

    private void handleEnable(RegisteredServiceProvider<?> serviceProvider) {
        Object service = serviceProvider.getProvider();
        Plugin servicePlugin = serviceProvider.getPlugin();
        if (currentService != null && currentService != service) {
            handleDisable(currentServicePlugin);
        }

        T initialized;
        try {
            initialized = initialize(service);
        } catch (Throwable t) {
            owningPlugin.getLogger().log(Level.SEVERE, "An error occurred while initializing use of service dependency "
                    + dependencyServiceClassName + " (" + servicePlugin.getName() + ")", t);
            return;
        }

        current = initialized;
        currentService = service;
        currentServicePlugin = servicePlugin;

        try {
            onEnable();
        } catch (Throwable t) {
            owningPlugin.getLogger().log(Level.SEVERE, "An error occurred while enabling use of service dependency "
                    + dependencyServiceClassName + " (" + servicePlugin.getName() + ")", t);
            current = defaultValue;
            currentService = null;
            currentServicePlugin = null;
        }
    }

    private void handleDisable(Plugin servicePlugin) {
        try {
            onDisable();
        } catch (Throwable t) {
            owningPlugin.getLogger().log(Level.SEVERE, "An error occurred while disabling use of service dependency "
                    + dependencyServiceClassName + " (" + servicePlugin.getName() + ")", t);
        }
        this.current = defaultValue;
        this.currentService = null;
        this.currentServicePlugin = null;
    }

    /**
     * Builds a soft dependency implementation using callbacks provided through
     * this Builder
     *
     * @param <T> Dependency API interface
     */
    public static class Builder<T> {
        @SuppressWarnings("rawtypes")
        private static final Consumer NOOP_CALLBACK = s -> {};
        @SuppressWarnings("unchecked")
        private static <T> Consumer<SoftServiceDependency<T>> noop_callback() {
            return NOOP_CALLBACK;
        }

        private static <T> Consumer<T> chainConsumer(final Consumer<T> prev, final Consumer<T> next) {
            if (next == null) {
                return NOOP_CALLBACK;
            } else if (prev == NOOP_CALLBACK) {
                return next;
            } else {
                return input -> { prev.accept(input); next.accept(input); };
            }
        }

        private final Plugin owningPlugin;
        private final String serviceClassName;
        private T defaultValue = null;
        private Initializer<T> initializer;
        private Consumer<SoftServiceDependency<T>> whenEnable;
        private Consumer<SoftServiceDependency<T>> whenDisable;

        private Builder(Plugin owningPlugin, String serviceClassName) {
            this.owningPlugin = owningPlugin;
            this.serviceClassName = serviceClassName;
            this.initializer = null;
            this.whenEnable = noop_callback();
            this.whenDisable = noop_callback();
        }

        /**
         * Sets the default API interface value to return when this dependency is disabled.
         * Is permitted to change the API interface type of this Builder for easier
         * use.
         *
         * @param defaultValue Default API interface value
         * @return this builder
         * @param <T2> Same or new API interface type
         */
        public <T2> Builder<T2> withDefaultValue(T2 defaultValue) {
            return update(b -> b.defaultValue = defaultValue);
        }

        /**
         * Sets the initializer called when this dependency enables. Should return the
         * API interface value that can be used to use this dependency.
         *
         * @param initializer Initializer
         * @return this builder
         * @param <T2> Same or new API interface type
         */
        public <T2> Builder<T2> withInitializer(Initializer<T2> initializer) {
            return update(b -> b.initializer = initializer);
        }

        /**
         * Sets the initializer called when this dependency enables. Should return the
         * API interface value that can be used to use this dependency.
         *
         * @param initializer Initializer
         * @return this builder
         * @param <T2> Same or new API interface type
         */
        public <T2> Builder<T2> withInitializer(InitializerOnlyService<T2> initializer) {
            return withInitializer((initializer == null) ? null : (s, p) -> initializer.initialize(p));
        }

        /**
         * Sets a callback to be called when this dependency enables. Can be multiple callbacks,
         * which will then be chained.
         *
         * @param callback Callback
         * @return this builder
         */
        public Builder<T> whenEnable(final Consumer<SoftServiceDependency<T>> callback) {
            this.whenEnable = chainConsumer(this.whenEnable, callback);
            return this;
        }

        /**
         * Sets a callback to be called when this dependency enables. Can be multiple callbacks,
         * which will then be chained.
         *
         * @param callback Callback
         * @return this builder
         */
        public Builder<T> whenEnable(Runnable callback) {
            return whenEnable(s -> callback.run());
        }

        /**
         * Sets a callback to be called when this dependency disables. Can be multiple callbacks,
         * which will then be chained.
         *
         * @param callback Callback
         * @return this builder
         */
        public Builder<T> whenDisable(final Consumer<SoftServiceDependency<T>> callback) {
            this.whenDisable = chainConsumer(this.whenDisable, callback);
            return this;
        }

        /**
         * Sets a callback to be called when this dependency disables. Can be multiple callbacks,
         * which will then be chained.
         *
         * @param callback Callback
         * @return this builder
         */
        public Builder<T> whenDisable(Runnable callback) {
            return whenDisable(s -> callback.run());
        }

        /**
         * Creates the SoftServiceDependency using the previously configured callbacks
         *
         * @return SoftServiceDependency
         * @param <T2> Same or new API interface type. Please avoid incorrect typing.
         */
        public <T2> SoftServiceDependency<T2> create() {
            return new CallbackBasedSoftServiceDependency<>(update(b -> {}));
        }

        // Total hack but does the job. Java generics sucks, man!
        @SuppressWarnings("unchecked")
        private <T2> Builder<T2> update(Consumer<Builder<T2>> updator) {
            Builder<T2> newBuilder = (Builder<T2>) this;
            updator.accept(newBuilder);
            return newBuilder;
        }
    }

    /**
     * Initializes a Soft Dependency after the dependency enables
     *
     * @param <T> Dependency API interface
     */
    @FunctionalInterface
    public interface Initializer<T> {
        /**
         * Called after a Service matching this dependency enables, or after the owning
         * plugin enables and the service dependency is already enabled.<br>
         * <br>
         * Should be implemented to analyze the dependency and construct a suitable
         * dependency API implementation. Is permitted to throw exceptions if this fails,
         * in which case the dependency isn't further enabled.<br>
         * <br>
         * It's allowed to return {@link #defaultValue} if, for example, use of this
         * dependency was disabled in plugin configuration.
         *
         * @param softServiceDependency The owning SoftServiceDependency, contains extra information
         * @param service The service instance that was detected
         * @return Dependency interface implementation
         */
        T initialize(SoftServiceDependency<T> softServiceDependency, Object service) throws Error, Exception;
    }

    /**
     * Initializes a Soft Dependency after the dependency enables
     *
     * @param <T> Dependency API interface
     */
    @FunctionalInterface
    public interface InitializerOnlyService<T> {
        /**
         * Called after a Service matching this dependency enables, or after the owning
         * plugin enables and the service dependency is already enabled.<br>
         * <br>
         * Should be implemented to analyze the dependency and construct a suitable
         * dependency API implementation. Is permitted to throw exceptions if this fails,
         * in which case the dependency isn't further enabled.<br>
         * <br>
         * It's allowed to return {@link #defaultValue} if, for example, use of this
         * dependency was disabled in plugin configuration.
         *
         * @param service The service instance that was detected
         * @return Dependency interface implementation
         */
        T initialize(Object service) throws Error, Exception;
    }

    private static class CallbackBasedSoftServiceDependency<T> extends SoftServiceDependency<T> {
        private final T defaultValue;
        private final Initializer<T> initializer;
        private final Consumer<SoftServiceDependency<T>> whenEnable;
        private final Consumer<SoftServiceDependency<T>> whenDisable;

        public CallbackBasedSoftServiceDependency(Builder<T> builder) {
            super(builder.owningPlugin, builder.serviceClassName);
            this.defaultValue = builder.defaultValue;
            this.initializer = (builder.initializer == null)
                    ? (s, p) -> defaultValue
                    : builder.initializer;
            this.whenEnable = builder.whenEnable;
            this.whenDisable = builder.whenDisable;
        }

        @Override
        protected T initialize(Object service) throws Error, Exception {
            return initializer.initialize(this, service);
        }

        @Override
        protected void onEnable() {
            whenEnable.accept(this);
        }

        @Override
        protected void onDisable() {
            whenDisable.accept(this);
        }
    }
}
