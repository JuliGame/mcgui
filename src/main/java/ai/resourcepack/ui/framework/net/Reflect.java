package ai.resourcepack.ui.framework.net;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Finding the server's members whether or not they are public. Paper widens many of the game's fields and methods
 * to public; Spigot leaves them as Mojang wrote them, so a member found with {@code getField} on Paper is private on
 * Spigot. The public one first, as before, then the class and each superclass's own, opened.
 *
 * <p>Names are Mojang's. On a Spigot server older than 26.1, which runs other names, each class looked in has its
 * own name for the member found through {@link Names}, and every class above it, interfaces too, is looked in.
 */
public final class Reflect {
    private Reflect() {}

    /** The game's class Mojang calls [name]. */
    public static Class<?> cls(String name) throws ClassNotFoundException {
        return Names.cls(name);
    }

    public static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        if (Names.translated()) {
            for (Class<?> c : above(owner)) {
                try {
                    return open(c.getDeclaredField(Names.field(c, name)));
                } catch (NoSuchFieldException ignored) {
                    // the next one up
                }
            }
            throw new NoSuchFieldException(owner.getName() + "." + name);
        }
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

    public static Method method(Class<?> owner, String name, Class<?>... parameters) throws NoSuchMethodException {
        if (Names.translated()) {
            for (Class<?> c : above(owner)) {
                try {
                    return open(c.getDeclaredMethod(Names.method(c, name, parameters), parameters));
                } catch (NoSuchMethodException ignored) {
                    // the next one up
                }
            }
            throw new NoSuchMethodException(owner.getName() + "." + name + java.util.Arrays.toString(parameters));
        }
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
    public static MethodHandle constructor(MethodHandles.Lookup lookup, Class<?> owner, MethodType type) throws NoSuchMethodException, IllegalAccessException {
        try {
            return lookup.findConstructor(owner, type);
        } catch (IllegalAccessException e) {
            Constructor<?> c = open(owner.getDeclaredConstructor(type.parameterArray()));
            return lookup.unreflectConstructor(c);
        }
    }

    /** [owner], its superclasses and every interface any of them has, nearest first. */
    private static Set<Class<?>> above(Class<?> owner) {
        Set<Class<?>> out = new LinkedHashSet<>();
        Deque<Class<?>> todo = new ArrayDeque<>();
        todo.add(owner);
        while (!todo.isEmpty()) {
            Class<?> c = todo.poll();
            if (!out.add(c)) continue;
            if (c.getSuperclass() != null) todo.add(c.getSuperclass());
            for (Class<?> i : c.getInterfaces()) todo.add(i);
        }
        return out;
    }

    private static <T extends AccessibleObject> T open(T member) {
        member.setAccessible(true);
        return member;
    }
}
