# Stable releases: the verbose, debug and info logs are removed from the bytecode (a beta keeps
# them, so a user's log report says something).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
