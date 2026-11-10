package pzopt.zombiebuddy;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Uses the native private fade state without adding helpers to a signed game package. */
public final class StartupHooks {
    private StartupHooks() { }
    private record FadeAccess(VarHandle alpha, Method render) { }
    private static final ClassValue<FadeAccess> FADE = new ClassValue<>() {
        @Override protected FadeAccess computeValue(Class<?> type) {
            try {
                VarHandle alpha = MethodHandles.privateLookupIn(type, MethodHandles.lookup()).findVarHandle(type, "fadeAlpha", float.class);
                return new FadeAccess(alpha, type.getMethod("render"));
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("reviewed native fade access changed", error); }
        }
    };

    public static long beforeFade(long start, Object screen) {
        try {
            ClassLoader loader = screen.getClass().getClassLoader();
            Class<?> config = Class.forName("pzopt.Config", true, loader);
            if (!(boolean)config.getField("NO_LOAD_FADE").get(null)) return start;
            Class<?> overrides = Class.forName("pzopt.Overrides", true, loader);
            if (!(boolean)overrides.getMethod("enabled").invoke(null)) return start;
            FadeAccess access = FADE.get(screen.getClass());
            access.alpha.set(screen, 1.0f);
            for (int i = 0; i < 3; i++) access.render.invoke(screen);
            return start - 250L;
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException("native startup performance hook failed", cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("native startup performance hook access changed", error);
        }
    }
}
