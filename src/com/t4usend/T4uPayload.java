package com.t4usend;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 4U 物品载荷的解析与组装。
 *
 * 事实来源：对真实物品文件逐字节验证过。
 *
 * 1) 物品文件 = JPEG(到 FF D9) + 紧贴其后的 TAMAGO 记录（也有裸记录，即文件本身就是记录）。
 *    TAMAGO 记录布局：
 *      +0x00 "TAMAGO"
 *      +0x06 UTF-16BE 内部文件名
 *      +0x30 另一个长度字段：官方物品里它与 +0x48 并不相等，别拿它当长度
 *      +0x34 ASCII 槽 ID
 *      +0x48 BE32 记录总长度（含末尾 2 字节校验），以它为准
 *      +0x4E 类型字节 / +0x50 分类字节
 *      +0x5E 游戏内显示名（设备字模码，2 字节/字）
 *      末尾2字节 BE16 校验和 = sum(前 n-2 字节) & 0xFFFF
 *
 * 2) 发送给 4U 的载荷（256 字节模板来自 res/raw/send_download.bin）：
 *      [0..255]   模板
 *      [256..263] 03 00 00 00 01 00 <len LE16>
 *      [264..]    TAMAGO 记录
 *      [72..73]   整包总长（LE16，含对齐填充）
 *      [132]      0x01 普通物品 / 0x03 注册
 *      [total-2]  LE16 和校验，覆盖 [0, total-2)
 *      末尾补 0 到 4 字节对齐
 *
 * 3) 第 1 贴的握手载荷 = res/raw/send_start.bin（258 字节，原样发，不用组装）。
 *
 * 本类不做任何网络访问，纯字节运算。
 */
public final class T4uPayload {

    /** 长度下限：小于这个长度的 declared 一定不是合法记录。 */
    private static final int MIN_DECLARED = 0x60;
    /** 容错范围：declared 不合法时，允许尾部少 0..16 字节再试。 */
    private static final int MAC_SLACK = 16;

    private T4uPayload() {
    }

    /** 解析结果。 */
    public static final class Item {
        /** 记录起点（有效载荷从这里开始）。 */
        public byte[] record;
        /** 记录总长度（= 记录头 +0x48 处的值）。 */
        public int length;
        /** 记录在源文件里的偏移。 */
        public int offset;
        /** 人类可读的说明，直接显示给用户。 */
        public String desc;
    }

    /** 异常：文件不是合法的 4U 物品。 */
    public static final class BadItem extends Exception {
        public BadItem(String m) {
            super(m);
        }
    }

    /**
     * 文案表（全部是 String.format 模板）。
     * 默认英文，保证本类不依赖 android.*、可以在 host JDK 上跑测试；
     * Android 侧启动时用资源串覆盖，从而支持中英切换。
     */
    public static final class T {
        public static String tooSmall = "File too small (%1$d bytes), not a 4U item record";
        public static String noRecord = "No valid TAMAGO record found (maybe not a 4U item file)";
        public static String payload = "payload %1$d bytes (total field=%2$d, item=%3$d, flag=0x%4$s)";
        public static String payloadEmpty = "(payload is empty)";
        public static String record = "%1$d-byte record";
        public static String offset = " (file offset 0x%1$s)";
        public static String name = ", inner name %1$s";
        public static String slot = ", slot %1$s";
        public static String typeCat = ", type=0x%1$s cat=0x%2$s";
    }

    /**
     * 从文件字节里抽出 TAMAGO 记录。
     * 会遍历所有 "TAMAGO" 出现位置，取第一个 declared + 校验和都成立的，避免误命中 JPEG 数据。
     */
    public static Item extract(byte[] file) throws BadItem {
        if (file == null || file.length < MIN_DECLARED) {
            throw new BadItem(String.format(T.tooSmall, file == null ? 0 : file.length));
        }

        int from = 0;
        while (true) {
            int off = indexOf(file, "TAMAGO", from);
            if (off < 0) {
                break;
            }
            int remaining = file.length - off;
            int len = pickLength(file, off, remaining);
            if (len > 0) {
                byte[] rec = new byte[len];
                System.arraycopy(file, off, rec, 0, len);
                Item it = new Item();
                it.record = rec;
                it.length = len;
                it.offset = off;
                it.desc = describe(rec, off);
                return it;
            }
            from = off + 1;
        }
        throw new BadItem(T.noRecord);
    }

    /** 优先信 +0x48 的 declared，失败再按「尾部少 0..16 字节」逐个试。返回记录长度，0 = 都不成立。 */
    private static int pickLength(byte[] b, int off, int remaining) {
        int declared = 0;
        if (remaining >= 0x4C) {
            int n = be32(b, off + 0x48);
            if (n >= MIN_DECLARED && n <= remaining) {
                declared = n;
            }
        }
        if (declared > 0 && macOk(b, off, declared)) {
            return declared;
        }
        for (int slack = 0; slack <= MAC_SLACK; slack++) {
            int cand = remaining - slack;
            if (cand < MIN_DECLARED || cand < declared) {
                continue;
            }
            if (macOk(b, off, cand)) {
                return cand;
            }
        }
        return 0;
    }

    /** 校验和：末尾两字节 BE16 == sum(前 len-2 字节) & 0xFFFF。 */
    private static boolean macOk(byte[] b, int off, int len) {
        if (len < 3 || off + len > b.length) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < len - 2; i++) {
            sum = (sum + (b[off + i] & 0xFF)) & 0xFFFF;
        }
        int stored = ((b[off + len - 2] & 0xFF) << 8) | (b[off + len - 1] & 0xFF);
        return sum == stored;
    }

    /** 拼出可以直接推给 4U 的物品载荷。 */
    public static byte[] buildDownload(byte[] template256, byte[] record) {
        if (template256 == null || template256.length != 256) {
            throw new IllegalArgumentException("template must be exactly 256 bytes");
        }
        int item = record.length;
        int total = 256 + 8 + item + 2;
        byte[] app = new byte[total + 3];                 // 预留对齐空间
        System.arraycopy(template256, 0, app, 0, 256);

        // 8 字节前缀
        app[256] = 0x03;
        app[257] = 0x00;
        app[258] = 0x00;
        app[259] = 0x00;
        app[260] = 0x01;
        app[261] = 0x00;
        app[262] = (byte) (item & 0xFF);
        app[263] = (byte) ((item >> 8) & 0xFF);

        System.arraycopy(record, 0, app, 264, item);

        app[72] = (byte) (total & 0xFF);
        app[73] = (byte) ((total >> 8) & 0xFF);
        app[132] = 0x01;                                  // 普通物品；注册用 0x03

        int sum = 0;
        for (int i = 0; i < total - 2; i++) {
            sum = (sum + (app[i] & 0xFF)) & 0xFFFF;
        }
        app[total - 2] = (byte) (sum & 0xFF);
        app[total - 1] = (byte) ((sum >> 8) & 0xFF);

        int padded = total;
        while ((padded & 3) != 0) {
            padded++;
        }
        // 必须截到 padded：app 是 total+3 的缓冲，直接返回会把尾部多出的字节一起发出去。
        byte[] out = new byte[padded];
        System.arraycopy(app, 0, out, 0, padded);
        return out;
    }

    /** 把物品载荷拆成人类可读的说明，用于界面显示与日志。文案走 T 模板（可中英切换）。 */
    public static String describePayload(byte[] payload) {
        if (payload == null || payload.length < 264) {
            return T.payloadEmpty;
        }
        int total = ((payload[73] & 0xFF) << 8) | (payload[72] & 0xFF);
        int item = ((payload[263] & 0xFF) << 8) | (payload[262] & 0xFF);
        return String.format(T.payload, payload.length, total, item,
                String.format("%02X", payload[132] & 0xFF));
    }

    private static String describe(byte[] rec, int off) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(T.record, rec.length));
        if (off > 0) {
            sb.append(String.format(T.offset, Integer.toHexString(off).toUpperCase()));
        }
        String name = utf16be(rec, 0x06, 32);
        if (name.length() > 0) {
            sb.append(String.format(T.name, name));
        }
        String slot = ascii(rec, 0x34, 20);
        if (slot.length() > 0) {
            sb.append(String.format(T.slot, slot));
        }
        if (rec.length > 0x50) {
            sb.append(String.format(T.typeCat,
                    String.format("%02X", rec[0x4E] & 0xFF),
                    String.format("%02X", rec[0x50] & 0xFF)));
        }
        return sb.toString();
    }

    private static String utf16be(byte[] b, int off, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i + 1 < off + max && i + 1 < b.length; i += 2) {
            int c = ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
            if (c == 0) {
                break;
            }
            if (c >= 0x20 && c < 0x7F) {
                sb.append((char) c);
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    private static String ascii(byte[] b, int off, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + max && i < b.length; i++) {
            int c = b[i] & 0xFF;
            if (c == 0) {
                break;
            }
            sb.append(c >= 0x20 && c < 0x7F ? (char) c : '?');
        }
        return sb.toString();
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int indexOf(byte[] hay, String needle, int from) {
        byte[] n = needle.getBytes();
        outer:
        for (int i = Math.max(0, from); i + n.length <= hay.length; i++) {
            for (int k = 0; k < n.length; k++) {
                if (hay[i + k] != n[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }
}
