package com.bergerkiller.bukkit.common.softdependency;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Automatically tracks when an optional third-party plugin dependency enables.<br>
 * <br>
 * It's possible that the dependency disables after having previously
 * been enabled, in which case the implementation is reset to the default.
 * Callbacks are provided to be notified when the dependency enables or disables.<br>
 * <br>
 * This class can be safely created in Plugin constructors and will automatically
 * update itself after the plugin enables. It can be enabled while handling
 * <i>onEnable</i> by having the plugin call {@link #detect()} early. There is also
 * a {@link #detectAll(Object)} to automatically call this on all SoftDependency
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
 *     private final SoftDependency<MyDependencyPlugin> myDependency = new SoftDependency<MyDependencyPlugin>(this, "my_dependency") {
 *         @Override
 *         protected MyDependencyPlugin initialize(Plugin plugin) {
 *             return MyDependencyPlugin.class.cast(plugin);
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
 * @version 1.05
 */
public abstract class SoftDependency<T> implements SoftDetectableDependency {
    /** The plugin that owns this Dependency and is informed of its status changes */
    protected final Plugin owningPlugin;
    /** The plugin name of this Dependency */
    protected final String dependencyName;
    /** The default value {@link #get()} returns when this Dependency is disabled */
    protected final T defaultValue;

    private Plugin currentPlugin = null;
    private T current;
    private boolean detecting = false;
    private boolean enabled = true;

    // This stuff must be done up-front, as otherwise we could cause a concurrent modification error
    // inside HandlerList.bakeAll()
    static {
        PluginEnableEvent.getHandlerList();
        PluginDisableEvent.getHandlerList();
    }

    /**
     * Builds a new SoftDependency using callback methods
     *
     * @param owningPlugin The plugin that owns this Dependency and is informed of its status changes
     * @param dependencyName The plugin name of this Dependency
     * @return Builder
     * @param <T> Dependency API interface
     */
    public static <T> Builder<T> build(Plugin owningPlugin, String dependencyName) {
        return new Builder<>(owningPlugin, dependencyName);
    }

    /**
     * Constructs a new SoftDependency
     *
     * @param owningPlugin The plugin that owns this Dependency and is informed of its status changes
     * @param dependencyName The plugin name of this Dependency
     */
    public SoftDependency(Plugin owningPlugin, String dependencyName) {
        this(owningPlugin, dependencyName, null);
    }

    /**
     * Constructs a new SoftDependency
     *
     * @param owningPlugin The plugin that owns this Dependency and is informed of its status changes
     * @param dependencyName The plugin name of this Dependency
     * @param defaultValue The default value {@link #get()} returns when this Dependency is disabled
     */
    public SoftDependency(Plugin owningPlugin, String dependencyName, T defaultValue) {
        this.owningPlugin = owningPlugin;
        this.dependencyName = dependencyName;
        this.defaultValue = defaultValue;
        this.current = defaultValue;
        whenEnabled(owningPlugin, this::detect);
    }

    /**
     * Checks whether a particular enabled plugin matches this dependency. If multiple
     * plugins exist with the same name, this method can be overridden to detect if it
     * is the right one. If false is returned, {@link #initialize(Plugin)} will not be
     * called.
     *
     * @param plugin Plugin that enabled
     * @return True if the plugin matches this dependency. False to ignore it.
     */
    protected boolean identify(Plugin plugin) {
        return true;
    }

    /**
     * Called after a Plugin matching this dependency enables, or after the owning
     * plugin enables and the dependency is already enabled.<br>
     * <br>
     * Should be implemented to analyze the dependency and construct a suitable
     * dependency API implementation. Is permitted to throw exceptions if this fails,
     * in which case the dependency isn't further enabled.<br>
     * <br>
     * It's allowed to return {@link #defaultValue} if, for example, use of this
     * dependency was disabled in plugin configuration.
     *
     * @param plugin The plugin that matches the dependency name
     * @return Dependency interface implementation
     */
    protected abstract T initialize(Plugin plugin) throws Error, Exception;

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
            } else if (currentPlugin != null) {
                handleDisable(currentPlugin);
            }
        }
    }

    /**
     * Looks up all the static fields in a Class and/or local member fields declared in
     * an Object value and if they are SoftDetectableDependency fields, calls
     * {@link #detect()} on them. Can be used to perform detection of all soft dependencies
     * at an earlier point in time during plugin enabling.
     *
     * @param fieldContainer Object or Class containing fields
     * @see #detect()
     */
    public static void detectAll(Object fieldContainer) {
        // Just here as it otherwise breaks API compatibility
        SoftDetectableDependency.detectAll(fieldContainer);
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
                public void onPluginEnabled(PluginEnableEvent event) {
                    if (!enabled) {
                        return;
                    }
                    Plugin plugin = Bukkit.getPluginManager().getPlugin(dependencyName);
                    if (plugin != null && event.getPlugin() == plugin && plugin.isEnabled() && handleIdentify(plugin)) {
                        handleEnable(plugin);
                    }
                }

                @EventHandler
                public void onPluginDisable(PluginDisableEvent event) {
                    if (!enabled) {
                        return;
                    }
                    if (event.getPlugin() == owningPlugin) {
                        setEnabled(false);
                        return;
                    }
                    if (event.getPlugin() == currentPlugin) {
                        handleDisable(event.getPlugin());
                    }
                }
            }, owningPlugin);
        }

        // Detect already enabled
        Plugin plugin = Bukkit.getPluginManager().getPlugin(dependencyName);
        if (plugin != null && plugin.isEnabled() && handleIdentify(plugin)) {
            handleEnable(plugin);
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
     * Gets the plugin name of this Dependency. The same name that was specified
     * when creating this SoftDependency. In the case of plugin Provides, the
     * input name to this dependency is returned, not the actual name of the plugin.
     *
     * @return dependency name
     */
    public String name() {
        return dependencyName;
    }

    /**
     * Get the current active dependency API implementation
     *
     * @return Current active dependency API implementation
     */
    public T get() {
        return current;
    }

    /**
     * Gets the Plugin instance of this dependency. Returns <i>null</i> if the
     * dependency is not currently enabled.
     *
     * @return Current active dependency Plugin
     */
    public Plugin getPlugin() {
        return currentPlugin;
    }

    /**
     * Gets whether this dependency was detected and has enabled.
     *
     * @return True if this dependency is currently enabled
     */
    public boolean isEnabled() {
        return currentPlugin != null;
    }

    /**
     * Callback called on the main thread when this dependency is enabled.
     * The dependency {@link #getPlugin() Plugin} and {@link #get() API implementation}
     * are both available at this point.
     */
    protected void onEnable() {
    }

    /**
     * Callback called on the main thread right before this dependency is disabled.
     * The dependency {@link #getPlugin() Plugin} and {@link #get() API implementation}
     * are both still available at this point and can be accessed as this dependency
     * has not yet disabled.
     */
    protected void onDisable() {
    }

    private boolean handleIdentify(Plugin plugin) {
        try {
            return this.identify(plugin);
        } catch (Throwable t) {
            owningPlugin.getLogger().log(Level.SEVERE, "An error occurred while identifying dependency " + plugin.getName(), t);
            failDependencyEnable();
            return false;
        }
    }

    private void handleEnable(Plugin plugin) {
        if (currentPlugin != null && currentPlugin != plugin) {
            handleDisable(currentPlugin);
        }

        T initialized;
        try {
            initialized = initialize(plugin);
        } catch (Throwable t) {
            owningPlugin.getLogger().log(Level.SEVERE, "An error occurred while initializing use of dependency " + plugin.getName(), t);
            failDependencyEnable();
            return;
        }

        // If initialized returns the default value (null) assume initialization failed for some reason
        if (initialized == defaultValue) {
            return;
        }

        current = initialized;
        currentPlugin = plugin;

        try {
            onEnable();
        } catch (Throwable t) {
            owningPlugin.getLogger().log(Level.SEVERE, "An error occurred while enabling use of dependency " + plugin.getName(), t);
            failDependencyEnable();
            current = defaultValue;
            currentPlugin = null;
        }
    }

    private void handleDisable(Plugin plugin) {
        try {
            onDisable();
        } catch (Throwable t) {
            owningPlugin.getLogger().log(Level.SEVERE, "An error occurred while disabling use of dependency " + plugin.getName(), t);
        }
        this.current = defaultValue;
        this.currentPlugin = null;
    }

    private void failDependencyEnable() {
        owningPlugin.getLogger().log(Level.SEVERE, "Integrated support is not enabled for this plugin!");
    }

    /**
     * Calls the callback after the plugin specified enables. Can be called at any time,
     * even when the plugin in question has not enabled yet. If the plugin never enables
     * and is thrown out of the plugin manager, the callback will be discarded.<br>
     * <br>
     * If the plugin is already enabled the callback is called right away.
     *
     * @param plugin Plugin instance
     * @param callback Callback to call when the plugin instance enables
     */
    public static void whenEnabled(Plugin plugin, Runnable callback) {
        EnableEntry e = new EnableEntry(plugin, callback);
        if (e.plugin.isEnabled()) {
            e.run(); // Avoid synchronized()
        } else {
            AfterPluginEnableHook.INSTANCE.schedule(e);
        }
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
        private static <T> Consumer<SoftDependency<T>> noop_callback() {
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
        private final String dependencyName;
        private T defaultValue = null;
        private Initializer<T> initializer;
        private Predicate<Plugin> identify;
        private Consumer<SoftDependency<T>> whenEnable;
        private Consumer<SoftDependency<T>> whenDisable;

        private Builder(Plugin owningPlugin, String dependencyName) {
            this.owningPlugin = owningPlugin;
            this.dependencyName = dependencyName;
            this.initializer = null;
            this.identify = p -> true;
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
         * Adds an identification filter. Only plugins matching the predicate result in this
         * dependency being enabled. By default, there is no filter, and all plugin that
         * match the dependency name enable the dependency.
         *
         * @param identify Plugin identification predicate
         * @return this builder
         */
        public Builder<T> withIdentify(Predicate<Plugin> identify) {
            if (identify == null) {
                throw new IllegalArgumentException("Identify predicate cannot be null");
            }
            this.identify = identify;
            return this;
        }

        /**
         * Sets the initializer called when this dependency enables. Should return the
         * API interface value that can be used to use this dependency. Specify a null
         * initializer to return the default value (typically null) instead of
         * making an API interface/class available.
         *
         * @param initializer Initializer, null for a no-op initializer
         * @return this builder
         * @param <T2> Same or new API interface type
         */
        public <T2> Builder<T2> withInitializer(Initializer<T2> initializer) {
            return update(b -> b.initializer = initializer);
        }

        /**
         * Sets the initializer called when this dependency enables. Should return the
         * API interface value that can be used to use this dependency. Specify a null
         * initializer to return the default value (typically null) instead of
         * making an API interface/class available.
         *
         * @param initializer Initializer, null for a no-op initializer
         * @return this builder
         * @param <T2> Same or new API interface type
         */
        public <T2> Builder<T2> withInitializer(InitializerOnlyPlugin<T2> initializer) {
            return withInitializer((initializer == null) ? null : (s, p) -> initializer.initialize(p));
        }

        /**
         * Sets a callback to be called when this dependency enables. Can be multiple callbacks,
         * which will then be chained.
         *
         * @param callback Callback
         * @return this builder
         */
        public Builder<T> whenEnable(final Consumer<SoftDependency<T>> callback) {
            if (callback == null) {
                throw new IllegalArgumentException("Enable callback cannot be null");
            }
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
            if (callback == null) {
                throw new IllegalArgumentException("Enable callback cannot be null");
            }
            return whenEnable(s -> callback.run());
        }

        /**
         * Sets a callback to be called when this dependency disables. Can be multiple callbacks,
         * which will then be chained.
         *
         * @param callback Callback
         * @return this builder
         */
        public Builder<T> whenDisable(final Consumer<SoftDependency<T>> callback) {
            if (callback == null) {
                throw new IllegalArgumentException("Disable callback cannot be null");
            }
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
            if (callback == null) {
                throw new IllegalArgumentException("Disable callback cannot be null");
            }
            return whenDisable(s -> callback.run());
        }

        /**
         * Creates the SoftDependency using the previously configured callbacks
         *
         * @return SoftDependency
         * @param <T2> Same or new API interface type. Please avoid incorrect typing.
         */
        public <T2> SoftDependency<T2> create() {
            return new CallbackBasedSoftDependency<>(update(b -> {}));
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
         * Called after a Plugin matching this dependency enables, or after the owning
         * plugin enables and the dependency is already enabled.<br>
         * <br>
         * Should be implemented to analyze the dependency and construct a suitable
         * dependency API implementation. Is permitted to throw exceptions if this fails,
         * in which case the dependency isn't further enabled.<br>
         * <br>
         * It's allowed to return {@link #defaultValue} if, for example, use of this
         * dependency was disabled in plugin configuration.
         *
         * @param softDependency The owning SoftDependency, contains extra information
         * @param plugin The plugin that matches the dependency name
         * @return Dependency interface implementation
         */
        T initialize(SoftDependency<T> softDependency, Plugin plugin) throws Error, Exception;
    }

    /**
     * Initializes a Soft Dependency after the dependency enables
     *
     * @param <T> Dependency API interface
     */
    @FunctionalInterface
    public interface InitializerOnlyPlugin<T> {
        /**
         * Called after a Plugin matching this dependency enables, or after the owning
         * plugin enables and the dependency is already enabled.<br>
         * <br>
         * Should be implemented to analyze the dependency and construct a suitable
         * dependency API implementation. Is permitted to throw exceptions if this fails,
         * in which case the dependency isn't further enabled.<br>
         * <br>
         * It's allowed to return {@link #defaultValue} if, for example, use of this
         * dependency was disabled in plugin configuration.
         *
         * @param plugin The plugin that matches the dependency name
         * @return Dependency interface implementation
         */
        T initialize(Plugin plugin) throws Error, Exception;
    }

    private static class CallbackBasedSoftDependency<T> extends SoftDependency<T> {
        private final T defaultValue;
        private final Initializer<T> initializer;
        private final Predicate<Plugin> identify;
        private final Consumer<SoftDependency<T>> whenEnable;
        private final Consumer<SoftDependency<T>> whenDisable;

        public CallbackBasedSoftDependency(Builder<T> builder) {
            super(builder.owningPlugin, builder.dependencyName);
            this.defaultValue = builder.defaultValue;
            this.identify = builder.identify;
            this.initializer = (builder.initializer == null)
                    ? (s, p) -> CallbackBasedSoftDependency.this.defaultValue
                    : builder.initializer;
            this.whenEnable = builder.whenEnable;
            this.whenDisable = builder.whenDisable;
        }

        @Override
        protected boolean identify(Plugin plugin) {
            return identify.test(plugin);
        }

        @Override
        protected T initialize(Plugin plugin) throws Error, Exception {
            return initializer.initialize(this, plugin);
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

    private static class AfterPluginEnableHook extends HandlerList {
        public static final AfterPluginEnableHook INSTANCE = new AfterPluginEnableHook();
        private final ArrayList<EnableEntry> pending = new ArrayList<>();

        public synchronized void schedule(EnableEntry entry) {
            if (entry.plugin.isEnabled()) {
                entry.run();
            } else {
                pending.add(entry);
            }
        }

        @Override
        public synchronized void unregister(Plugin plugin) {
            super.unregister(plugin); // Call base impl, don't want broken state to occur.

            // Delete any entries that reference this same plugin instance
            // This prevents potential memory leaks
            for (Iterator<EnableEntry> iter = pending.iterator(); iter.hasNext();) {
                if (iter.next().plugin == plugin) {
                    iter.remove();
                }
            }
        }

        @Override
        public synchronized void bake() {
            super.bake(); // Call base impl, don't want broken state to occur.

            for (Iterator<EnableEntry> iter = pending.iterator(); iter.hasNext();) {
                EnableEntry e = iter.next();
                if (e.plugin.isEnabled()) {
                    iter.remove();
                    e.run();
                }
            }
        }
    }

    private static class EnableEntry {
        public final Plugin plugin;
        public final Runnable callback;

        public EnableEntry(Plugin plugin, Runnable callback) {
            this.plugin = plugin;
            this.callback = callback;
        }

        public void run() {
            try {
                callback.run();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Failed to run post-enable task", t);
            }
        }
    }
}
