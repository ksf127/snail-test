package com.company.service.shortlink.domain;

/**
 * Base62 编解码。
 *
 * <p>为什么固定 7 位而不是变长：
 * <ul>
 *   <li>固定长度让路由正则、缓存 key、日志对齐都简单</li>
 *   <li>62^7 ≈ 3.52e12，按每天 1 亿条也算得过来</li>
 *   <li>左补 '0'（字符 0），解码时前导 0 无副作用</li>
 * </ul>
 */
public final class Base62 {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private static final int[] INDEX = new int[128];

    public static final int CODE_LENGTH = 7;

    public static final long MAX_ID;

    static {
        java.util.Arrays.fill(INDEX, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            INDEX[ALPHABET[i]] = i;
        }
        long max = 1;
        for (int i = 0; i < CODE_LENGTH; i++) {
            max *= ALPHABET.length;
        }
        MAX_ID = max - 1;
    }

    private Base62() {
    }

    public static String encodeFixed(long value) {
        if (value < 0 || value > MAX_ID) {
            throw new IllegalArgumentException("超出 Base62(" + CODE_LENGTH + ") 可表示范围: " + value);
        }
        char[] buffer = new char[CODE_LENGTH];
        long rest = value;
        for (int i = CODE_LENGTH - 1; i >= 0; i--) {
            buffer[i] = ALPHABET[(int) (rest % ALPHABET.length)];
            rest /= ALPHABET.length;
        }
        return new String(buffer);
    }

    public static long decode(String code) {
        if (code == null || code.length() != CODE_LENGTH) {
            throw new IllegalArgumentException("短码长度必须为 " + CODE_LENGTH + ": " + code);
        }
        long value = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            int digit = c < 128 ? INDEX[c] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("短码包含非法字符: " + code);
            }
            value = value * ALPHABET.length + digit;
        }
        return value;
    }

    public static boolean isValid(String code) {
        if (code == null || code.length() != CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c >= 128 || INDEX[c] < 0) {
                return false;
            }
        }
        return true;
    }
}
