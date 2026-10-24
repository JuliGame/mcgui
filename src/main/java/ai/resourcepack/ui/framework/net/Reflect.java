package ai.resourcepack.ui.framework.net;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Finding the server's members whether or not they are public. Paper widens many of the game's fields and methods
 * to public; Spigot leaves them as Mojang wrote them, so a member found with {@code getField} on Paper is private on
 * Spigot. The public one first, as before, then the class and each superclass's own, opened.
 */
final class Reflect {
    private Reflect() {}

    static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        try {
            return owner.getField(name);
        } catch (NoSuchFieldException e) {
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                try {
                    return open(c.getDeclaredField(name));
                } catch (NoSuchFieldException ignored) {
                    // up a class
                }
            }
            throw e;
        }
    }

    static Method method(Class<?> owner, String name, Class<?>... parameters) throws NoSuchMethodException {
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                try {
                    return open(c.getDeclaredMethod(name, parameters));
                } catch (NoSuchMethodException ignored) {
                    // up a class
                }
            }
            throw e;
        }
    }

    /** A constructor of [owner] taking [type]'s parameters, as {@code findConstructor} would find a public one. */
    static MethodHandle constructor(MethodHandles.Lookup lookup, Class<?> owner, MethodType type) throws NoSuchMethodException, IllegalAccessException {
        try {
            return lookup.findConstructor(owner, type);
        } catch (IllegalAccessException e) {
            Constructor<?> c = open(owner.getDeclaredConstructor(type.parameterArray()));
            return lookup.unreflectConstructor(c);
        }
    }

    private static <T extends AccessibleObject> T open(T member) {
        member.setAccessible(true);
        return member;
    }
}
