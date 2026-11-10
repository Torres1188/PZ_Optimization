package pzopt.zombiebuddy;

/** ZombieBuddy's approved early-load entry point. No game classes are referenced here. */
public final class PreMain {
    private PreMain() { }

    public static void premain(String[] args) {
        PayloadRuntime.installFromZombieBuddy();
    }
}
