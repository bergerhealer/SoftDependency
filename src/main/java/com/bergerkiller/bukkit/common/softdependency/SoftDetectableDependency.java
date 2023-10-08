package com.bergerkiller.bukkit.common.softdependency;

/**
 * Base interface for a dependency that can be detected
 */
public interface SoftDetectableDependency {
    /**
     * Detects whether this dependency is currently enabled
     */
    void detect();

    /**
     * Looks up all the static fields in a Class and/or local member fields declared in
     * an Object value and if they are SoftDetectableDependency fields, calls
     * {@link #detect()} on them. Can be used to perform detection of all soft dependencies
     * at an earlier point in time during plugin enabling.
     *
     * @param fieldContainer Object or Class containing fields
     * @see #detect()
     */
    static void detectAll(Object fieldContainer) {
        java.lang.reflect.Field[] fields = (fieldContainer instanceof Class)
                ? ((Class<?>) fieldContainer).getDeclaredFields()
                : fieldContainer.getClass().getDeclaredFields();
        for (java.lang.reflect.Field field : fields) {
            if (!SoftDependency.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                SoftDetectableDependency dep;
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    dep = (SoftDetectableDependency) field.get(null);
                } else if (fieldContainer instanceof Class) {
                    continue; // Doesn't work.
                } else {
                    dep = (SoftDetectableDependency) field.get(fieldContainer);
                }
                if (dep != null) {
                    dep.detect();
                }
            } catch (Throwable t) {
                throw new UnsupportedOperationException("Can't detect dependency", t);
            }
        }
    }
}
