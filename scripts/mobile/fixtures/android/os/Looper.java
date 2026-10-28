package android.os;
/** JVM-only scheduler stub; not evidence of Android Main/IO behavior. */
public class Looper {
  private static final Looper MAIN = new Looper();
  public static Looper myLooper() { return null; }
  public static Looper getMainLooper() { return MAIN; }
}
