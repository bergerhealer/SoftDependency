# SoftDependency Library
This simple single-class library can be shaded in to provide the **SoftDependency**: A class that allows for automatic detection and enabling of soft dependencies of your plugin. Is included at the same class path in [BKCommonLib](https://www.spigotmc.org/resources/bkcommonlib.39590/), but does not require it.

What makes this unique is that it does not require anyone to call _enable()_, so your plugin's _onEnable()_ will stay nice and clean. Everything is done for you fully automatically. Soft dependencies are automatically enabled after the owning plugin itself has enabled. The _initialize(plugin)_ method can be implemented to detect what version of the dependency is enabled and to then return a version-specific API implementation. Or you can just return the plugin's interface class directly. **Before** your plugin disables, all dependencies are first disabled. All of this makes sure that you do not have to do any special handling during onEnable or onDisable. All dependency logic happens outside of it.

The API is robust and has internal error handling. If any errors are thrown the dependency is simply not enabled.

Where this API shines is that multi-version dependency wrappers can encapsulate all the version detection without the programmer having to do anything to use it.

## Maven Dependency
```xml
<repositories>
    <repository>
        <id>mg-dev repo</id>
        <url>https://ci.mg-dev.eu/plugin/repository/everything</url>
    </repository>
</repositories>
<dependencies>
    <dependency>
        <groupId>com.bergerkiller.bukkit.softdependency</groupId>
        <artifactId>SoftDependency</artifactId>
        <version>1.0</version>
        <optional>true</optional>
    </dependency>
</dependencies>
```

Include _com.bergerkiller.bukkit.softdependency_ in the maven shade plugin to shade it in

## Usage Example
```java
public class MyPlugin extends JavaPlugin {

    private final SoftDependency<MyDependencyPlugin> myDependency = new SoftDependency<MyDependencyPlugin>(this, "my_dependency") {
        @Override
        protected MyDependencyPlugin initialize(Plugin plugin) {
            return MyDependencyPlugin.class.cast(plugin);
        }

        @Override
        protected void onEnable() {
            getLogger().info("Support for MyDependency enabled!");
        } 

        @Override
        protected void onDisable() {
            getLogger().info("Support for MyDependency disabled!");
        }
   };

   // Can use myDependency.get() anywhere, returns non-null if enabled.
}
```

