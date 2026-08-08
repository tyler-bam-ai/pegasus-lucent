package android.view;

/** Host-JVM compile-only stand-in; never packaged in the Android APK. */
public final class Surface {
    private final boolean valid;
    public Surface(boolean valid) { this.valid = valid; }
    public boolean isValid() { return valid; }
}
