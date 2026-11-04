package com.company.service.shortlink.domain;

/**
 * Base62 编解码：把号段发出来的十进制 ID 变成 7 位短码。
 *
 * <p>【解决什么问题】短链对外只暴露一个短码，内部却需要一个"全局唯一且趋势递增"的数值 ID。
 * 这个类就是两者之间的唯一转换层：写入时 {@link #encodeFixed(long)}，读取时
 * {@link #decode(String)}。去掉它，号段发号器产出的数字没法变成用户能点的 URL。
 *
 * <p>【为什么这样设计】固定 7 位而不是变长：
 * <ul>
 *   <li>固定长度让路由正则（{@code [0-9A-Za-z]{7}}）、缓存 key、日志对齐都简单，
 *       非法短码在 Spring MVC 路由层就被挡掉，不会进入业务逻辑；</li>
 *   <li>62^7 ≈ 3.52e12，按每天 1 亿条也能用近 100 年；</li>
 *   <li>编码时在左侧补字符 '0'，解码时前导 0 只是数值 0，天然无副作用。</li>
 * </ul>
 * 用 62 进制而不是 64 进制，是因为要去掉 URL 里需要转义的 {@code +} 和 {@code /}，
 * 让短码可以直接拼进路径、不需要任何编码。
 *
 * <p>【怎么读这段代码】先看静态块（初始化字符表、反查表、上限 {@code MAX_ID}），
 * 再看 {@code encodeFixed} 的"从低位往高位填、填完正好 7 位"，最后看 {@code decode}
 * 的"从左往右累乘 62"。所有方法都是纯函数、无共享可变状态，因此天然线程安全。
 */
public final class Base62 {

    /**
     * 字符表顺序就是数值 0~61 的映射：'0'-'9'、'A'-'Z'、'a'-'z'。
     * 顺序一旦改动，历史短码会全部解析成别的 ID，所以它是线上数据格式的一部分。
     */
    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    /**
     * 反查表：字符的 ASCII 码直接当下标，值是该字符代表的数字，-1 表示非法字符。
     * 用数组而不是 HashMap，是为了让解码路径不产生任何对象分配。
     */
    private static final int[] INDEX = new int[128];

    /**
     * 短码固定长度。路由正则、{@code decode} 的长度校验都依赖这个常量。
     */
    public static final int CODE_LENGTH = 7;

    /**
     * 7 位 Base62 能表示的最大 ID，即 62^7 - 1。
     * 号段来源在这里封顶，超过就无法编码成 7 位短码。
     */
    public static final long MAX_ID;

    static {
        // 先全部填 -1（非法），再把合法字符依次登记成它对应的数值
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

    /**
     * 把 ID 编码成固定 7 位的短码。
     *
     * <p>算法：反复对 62 取模得到最低位，再整除 62 继续算高位，从数组末尾往前填。
     * 7 次循环后如果还有剩余，说明数值超出范围 —— 但入口已经校验过，这里不会发生。
     *
     * @param value 号段生成的数字 ID，必须落在 {@code [0, MAX_ID]}
     * @return 定长 7 位短码，不足位左侧补 '0'
     * @throws IllegalArgumentException 数值为负或超过 {@link #MAX_ID}
     */
    public static String encodeFixed(long value) {
        if (value < 0 || value > MAX_ID) {
            throw new IllegalArgumentException("超出 Base62(" + CODE_LENGTH + ") 可表示范围: " + value);
        }
        char[] buffer = new char[CODE_LENGTH];
        long rest = value;
        // 从最低位开始填，所以倒着写数组；填满 7 位时高位自然剩下 0，就是左补 '0'
        for (int i = CODE_LENGTH - 1; i >= 0; i--) {
            buffer[i] = ALPHABET[(int) (rest % ALPHABET.length)];
            rest /= ALPHABET.length;
        }
        return new String(buffer);
    }

    /**
     * 把短码解码回数字 ID。
     *
     * <p>算法：从左到右做 {@code value = value * 62 + digit}，即标准的按位累乘。
     * 这是 {@link #encodeFixed(long)} 的逆运算，两者必须同时改，否则历史数据全部错位。
     *
     * @param code 待解码的短码
     * @return 对应的数字 ID
     * @throws IllegalArgumentException 短码为 null、长度不是 7，或含非 Base62 字符
     */
    public static long decode(String code) {
        if (code == null || code.length() != CODE_LENGTH) {
            throw new IllegalArgumentException("短码长度必须为 " + CODE_LENGTH + ": " + code);
        }
        long value = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            // c >= 128 时不能去查长度为 128 的 INDEX，直接判非法（中文、emoji 都会走到这里）
            int digit = c < 128 ? INDEX[c] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("短码包含非法字符: " + code);
            }
            value = value * ALPHABET.length + digit;
        }
        return value;
    }

    /**
     * 只做格式校验、不做解码，用于在跳转入口快速挡掉非法短码。
     *
     * <p>它和 {@code decode} 的区别是：非法输入返回 false 而不是抛异常。
     * 热路径上异常栈的代价远高于一次布尔判断，所以校验和解析要分开。
     *
     * @param code 待校验的短码，允许 null
     * @return 长度与字符集都合法时为 true
     */
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
