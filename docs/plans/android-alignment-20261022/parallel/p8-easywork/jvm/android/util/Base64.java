package android.util;

/** Host-only replacement for Android's byte encoding API. No key or protocol logic. */
public final class Base64 {
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int URL_SAFE = 8;

    public static String encodeToString(byte[] bytes, int flags) {
        if (flags != (URL_SAFE | NO_PADDING | NO_WRAP)) {
            throw new IllegalArgumentException("Unexpected fixture Base64 flags");
        }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] decode(String text, int flags) {
        if (flags != (URL_SAFE | NO_WRAP)) {
            throw new IllegalArgumentException("Unexpected fixture Base64 flags");
        }
        return java.util.Base64.getUrlDecoder().decode(text);
    }
}
