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
 * <i>onEnable</i> by having the plugin call {@link #detect()} early.<br>
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
 * @version 1.0
 */
public abstract class SoftDependency<T> {
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

    public SoftDependency(Plugin owningPlugin, String dependencyName) {
        this(owningPlugin, dependencyName, null);
    }

    public SoftDependency(Plugin owningPlugin, String dependencyName, T defaultValue) {
        this.owningPlugin = owningPlugin;
        this.dependencyName = dependencyName;
        this.defaultValue = defaultValue;
        this.current = defaultValue;
        whenEnabled(owningPlugin, this::detect);
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
     * Detects whether this dependency is currently enabled. Is called automatically
     * after the owning plugin is enabled, but can be called earlier during plugin
     * <i>onEnable()</i> to load a little sooner.<br>
     * <br>
     * If previously disabled using {@link #setEnabled(boolean) setEnabled(false)}
     * this method does nothing. If the owning plugin is not yet enabled
     * this method does nothing.
     */
    public void detect() {
        if (!enabled || !owningPlugin.isEnabled()) {
            return;
        } else if (!detecting) {
            // Detect changes
            detecting = true;
            Bukkit.getPluginManager().registerEvents(new Listener() {
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

                @EventHandler
                public void onPluginEnabled(PluginEnableEvent event) {
                    if (!enabled) {
                        return;
                    }
                    Plugin plugin = Bukkit.getPluginManager().getPlugin(dependencyName);
                    if (plugin != null && event.getPlugin() == plugin) {
                        handleEnable(plugin);
                    }
                }
            }, owningPlugin);
        }

        // Detect already enabled
        Plugin plugin = Bukkit.getPluginManager().getPlugin(dependencyName);
        if (plugin != null && plugin.isEnabled()) {
            handleEnable(plugin);
        }
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

    private void handleEnable(Plugin plugin) {
        if (currentPlugin != null && currentPlugin != plugin) {
            handleDisable(currentPlugin);
        }

        T initialized;
        try {
            initialized = initialize(plugin);
        } catch (Throwable t) {
            owningPlugin.getLogger().log(Level.SEVERE, "An error occurred while initializing use of dependency " + plugin.getName(), t);
            return;
        }

        current = initialized;
        currentPlugin = plugin;

        try {
            onEnable();
        } catch (Throwable t) {
            owningPlugin.getLogger().log(Level.SEVERE, "An error occurred while enabling use of dependency " + plugin.getName(), t);
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
        current = defaultValue;
        currentPlugin = null;
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
