package pzopt.zombiebuddy;

/** Reports the early-load result during regular mod loading; never installs late. */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        System.err.println("[pzopt/ZombieBuddy] " + PayloadRuntime.status());
        if (!PayloadRuntime.active()) {
            System.err.println("[pzopt/ZombieBuddy] No optimization payload is active. Approve this mod for preload, "
                    + "save the normal mod selection, fully exit and restart. See the preceding refusal reason.");
        }
    }
}
