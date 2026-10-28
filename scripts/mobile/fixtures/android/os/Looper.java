package android.os;
/** JVM-only scheduler stub; not evidence of Android Main/IO behavior. */
public class Looper {
  private static final Looper MAIN = new Looper();
  private static final ThreadLocal<Boolean> IS_MAIN = ThreadLocal.withInitial(() -> false);
  public static void setMainForTest(boolean value) { IS_MAIN.set(value); }
  public static Looper myLooper() { return IS_MAIN.get() ? MAIN : null; }
  public static Looper getMainLooper() { return MAIN; }
}
