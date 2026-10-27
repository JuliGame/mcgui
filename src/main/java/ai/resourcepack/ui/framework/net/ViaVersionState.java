package ai.resourcepack.ui.framework.net;

import java.lang.reflect.Method;

/**
 * Whether a handler in a connection's pipeline is ViaVersion's packet handler ({@code via-encoder}), and whether it
 * changes anything for that connection.
 *
 * <p>For a client on the server's own version, Via marks the connection inactive when the login succeeds (nothing to
 * translate), and its handler then passes every packet on as it came (it only counts it). Map pictures written past
 * it are the bytes it would have passed, so {@link SharedCompression} may write them. For a client Via translates
 * for, the handler rewrites packets and would never see them.
 *
 * <p>Asked afresh each time the pipeline is looked at, through Via's public API only
 * ({@code ViaChannelHandler.connection()}, {@code UserConnection.shouldTransformPacket()}) by reflection, so there
 * is no dependency on Via: a Via where either is missing or throws is {@link #UNKNOWN}, a handler that sees packets.
 */
final class ViaVersionState {
    enum Stand {
        /** Not one of Via's handlers. */
        NOT_VIA,
        /** Via's handler, with nothing to translate on this connection: packets go through it untouched. */
        PASSES,
        /** Via's handler, translating this connection's packets for another game version. */
        TRANSLATES,
        /** Via's handler, which could not be asked. */
        UNKNOWN
    }

    private static final String CHANNEL_HANDLER = "com.viaversion.viaversion.api.platform.ViaChannelHandler";
    /** What a class has no such method as: a {@link ClassValue} may not hold null. */
    private static final Object ABSENT = new Object();
    /** Kept on the classes themselves, so a Via that is reloaded takes its classes and these with it. */
    private static final ClassValue<Object> CONNECTION = new ClassValue<>() {
        @Override
        protected Object computeValue(Class<?> k) {
            return connectionOf(k);
        }
    };
    private static final ClassValue<Object> TRANSFORMS = new ClassValue<>() {
        @Override
        protected Object computeValue(Class<?> k) {
            return transformsOf(k);
        }
    };

    private ViaVersionState() {}

    static Stand stand(Object handler) {
        Object connection = CONNECTION.get(handler.getClass());
        if (connection == ABSENT) return Stand.NOT_VIA;
        try {
            Object user = ((Method) connection).invoke(handler);
            if (user == null) return Stand.UNKNOWN;
            Object transforms = TRANSFORMS.get(user.getClass());
            if (transforms == ABSENT) return Stand.UNKNOWN;
            return (boolean) ((Method) transforms).invoke(user) ? Stand.TRANSLATES : Stand.PASSES;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Stand.UNKNOWN;
        }
    }

    /** [k]'s public {@code connection()} if it is one of Via's channel handlers, else {@link #ABSENT}. */
    private static Object connectionOf(Class<?> k) {
        if (!implementsChannelHandler(k)) return ABSENT;
        try {
            return k.getMethod("connection");
        } catch (NoSuchMethodException e) {
            return ABSENT;
        }
    }

    private static Object transformsOf(Class<?> user) {
        try {
            return user.getMethod("shouldTransformPacket");
        } catch (NoSuchMethodException e) {
            return ABSENT;
        }
    }

    private static boolean implementsChannelHandler(Class<?> k) {
        for (Class<?> c = k; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (i.getName().equals(CHANNEL_HANDLER) || implementsChannelHandler(i)) return true;
            }
        }
        return false;
    }
}
