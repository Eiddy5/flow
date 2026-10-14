package org.flow.core.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Utility methods for constant-time credential comparison. */
public final class AuthUtils {

    private AuthUtils() {
    }

    /**
     * Compares two strings in constant time, regardless of where they first differ or of a length mismatch.
     *
     * <p>
     * Comparing SHA-256 digests instead of the raw bytes avoids {@link MessageDigest#isEqual} short-circuiting
     * on differing lengths, which would otherwise leak the length of the expected value.
     *
     * @return {@code true} if both strings are non-null and equal, {@code false} otherwise
     */
    public static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(left), sha256(right));
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the JVM spec — this cannot happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
