package com.t4usend;

import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.NfcA;

import java.io.IOException;

/**
 * 碰触点（タッチスポット）NFC 卡的诊断与写入。
 *
 * 注意（卡型硬要求，别用错卡）
 * ==========================
 * 碰触点用的卡**必须是 NFC Forum Type 2**，即下面这几款之一：
 *     NTAG213 / NTAG215 / NTAG216 / Mifare Ultralight (C / EV1)
 *
 * **绝对不能用 Mifare Classic（俗称「M1 卡 / M1 白卡」）**：
 *   M1 同样走 ISO14443-3A，同样能被 {@link NfcA#get} 拿到底层句柄、同样答 READ(0x30)，
 *   但它写卡是另一套协议（0xA0 + 密钥认证，按 16 字节块），根本不认 Type 2 的
 *   WRITE(0xA2)。发过去的结果就是超时或 NAK，日志写「第 N 页写入失败」。
 *   => 那不是程序 bug，是卡型不对。用 {@link #probe} 可以当场判出卡型。
 *
 * 记录格式（48 字节，写在卡的页 4..15，即内存偏移 0x10 起）
 * --------------------------------------------------------
 *   +0x00 = 00
 *   +0x01 = 80
 *   +0x02 = 00
 *   +0x03 = 01        触点 id（固定为 1）
 *   +0x04 = 事件类型   1..10
 *   +0x05..0x2D = 00
 *   +0x2E..0x2F = BE16  sum(前 0x2E 字节) & 0xFFFF
 *
 * 用法
 * ----
 *   byte[] rec = TouchSpot.buildRecord(3);
 *   String msg = TouchSpot.write(Tag, rec);   // 需在 enableReaderMode 回调里、拿到 Tag 后调用
 *   String diag = TouchSpot.probe(Tag);       // 只读诊断，不改卡里任何东西
 */
public final class TouchSpot {

    /** 记录总长 = 48 字节 = 12 页 x 4 字节。 */
    public static final int RECORD_LEN = 0x30;
    /** 记录在卡内存里的起始页（= 偏移 0x10 / 4）。 */
    public static final int FIRST_PAGE = 4;
    /** 合法的碰触事件类型（固件只认这几个）。 */
    public static final int[] EVENT_TYPES = {1, 2, 3, 4, 7, 8, 9, 10};

    /**
     * 结果文案（String.format 模板）。默认英文，Android 侧由 MainActivity 用资源串覆盖，
     * 从而跟随界面语言（中/英）。T 与 T4uPayload.T 同一套路，保证本类不依赖 android.*的资源系统。
     */
    public static final class T {
        public static String noTag = "No tag found";
        public static String badLen = "Record length must be %1$d bytes";
        public static String notNfcA = "This card is not an NFC-A tag, cannot write";
        public static String m1Card = "This is a Mifare Classic (M1) card - NOT a Type 2 card, "
                + "it cannot be used as a touch spot and writing will fail. "
                + "Use NTAG213/215/216 or Mifare Ultralight.";
        public static String pageFail = "Write to page %1$d failed (response %2$s)";
        public static String readBackFail = "Read-back of page %1$d failed";
        public static String doneSame = "Write done, read-back matches";
        public static String doneDiff = "Write done, read-back differs (below)";
        public static String ioFail = "Write IO failed: %1$s";

        /** 写失败时额外附一句排查提示（写卡失败十有八九是卡型不对）。 */
        public static String m1Hint = "(hint: the card must be Type 2, i.e. NTAG213/215/216 or "
                + "Mifare Ultralight. An M1 / Mifare Classic card cannot be written. "
                + "Use \"Read card\" to check the card type.)";

        /* ---- 只读诊断 ---- */
        public static String probeNoTag = "No tag found";
        public static String probeTech = "tech: %1$s";
        public static String probeUid = "UID : %1$s  (%2$d bytes)";
        public static String probeNotNfcA = "not an NFC-A tag - cannot be used as a touch spot";
        public static String probeAtqaSak = "ATQA %1$s   SAK %2$s";
        public static String probePages = "page %1$2d-%2$2d : %3$s";
        public static String probeChip = "chip: %1$s (vendor %2$s)";
        public static String probeNoVersion = "GET_VERSION no reply (old Ultralight or a clone; still usable)";
        public static String probeVerdictOk = "=> Type 2 card, usable as a touch spot. Pages 4..15 "
                + "are the 48-byte record area (shown above as page 04-15).";
        public static String probeIoFail = "Read failed: %1$s";
    }

    private TouchSpot() {
    }

    /** 拼 48 字节触点记录。eventType 建议取 EVENT_TYPES 里的值（1..10）。 */
    public static byte[] buildRecord(int eventType) {
        byte[] c = new byte[RECORD_LEN];
        c[0x00] = 0x00;
        c[0x01] = (byte) 0x80;
        c[0x02] = 0x00;
        c[0x03] = 0x01;                       // 触点 id
        c[0x04] = (byte) (eventType & 0xFF);  // 事件类型
        int sum = 0;
        for (int i = 0; i < 0x2E; i++) {
            sum = (sum + (c[i] & 0xFF)) & 0xFFFF;
        }
        c[0x2E] = (byte) ((sum >> 8) & 0xFF);
        c[0x2F] = (byte) (sum & 0xFF);
        return c;
    }

    /** 记录的可读描述（十六进制转储，便于排查对照）。 */
    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            sb.append(String.format("%02X", b[i] & 0xFF));
            if (i % 4 == 3) {
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ 只读诊断

    /**
     * 只看不写：报出这张卡的技术栈、UID、ATQA/SAK、芯片型号，并把页 0..15 的 64 字节
     * 打出来（其中页 4..15 就是碰触点记录区）。
     *
     * 用途：手头即便没有能用的 Type 2 卡，也能拿任意一张 NFC 卡来验证
     * 「reader mode 通不通 / 能不能连上 / 能不能读页」这条链路，并当场判出卡型对不对。
     * 全程只发 READ(0x30) 与 GET_VERSION(0x60)，不做任何写入。
     *
     * @return 多行报告，直接显示给用户。
     */
    public static String probe(Tag tag) {
        if (tag == null) {
            return T.probeNoTag;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(T.probeTech, join(tag.getTechList()))).append('\n');
        byte[] uid = tag.getId();
        sb.append(String.format(T.probeUid, uid == null ? "-" : hex(uid),
                uid == null ? 0 : uid.length)).append('\n');

        NfcA a = NfcA.get(tag);
        if (a == null) {
            sb.append(T.probeNotNfcA);
            return sb.toString();
        }
        try {
            a.connect();
            byte[] atqa = a.getAtqa();
            sb.append(String.format(T.probeAtqaSak,
                    String.format("%02X%02X", atqa[1] & 0xFF, atqa[0] & 0xFF),
                    String.format("%02X", a.getSak() & 0xFF))).append('\n');

            if (MifareClassic.get(tag) != null) {
                // M1 的 READ 地址是「块号」不是「页号」，继续读只会给出误导性的行，所以不读。
                sb.append(T.m1Card);
                return sb.toString();
            }

            // 先读内存（一定成功），最后再试 GET_VERSION（失败会丢 tag，放最后）。
            for (int p = 0; p < 16; p += 4) {
                byte[] r = a.transceive(new byte[]{0x30, (byte) p});
                if (r == null || r.length < 16) {
                    sb.append(String.format(T.probeIoFail, "short read at page " + p));
                    return sb.toString();
                }
                sb.append(String.format(T.probePages, p, p + 3, hex(r))).append('\n');
            }
            sb.append(describeChip(a)).append('\n');
            sb.append(T.probeVerdictOk);
            return sb.toString();
        } catch (IOException e) {
            sb.append(String.format(T.probeIoFail, String.valueOf(e.getMessage())));
            return sb.toString();
        } finally {
            try {
                a.close();
            } catch (IOException ignored) {
                // 关不掉就算了，卡拿开自然会断
            }
        }
    }

    /** 试 GET_VERSION(0x60)，按返回的存储容量码报芯片型号。不答就说明是老 Ultralight 或仿卡。 */
    private static String describeChip(NfcA a) {
        try {
            byte[] v = a.transceive(new byte[]{0x60});
            if (v == null || v.length < 8) {
                return T.probeNoVersion;
            }
            int size = v[6] & 0xFF;
            String name;
            if (size == 0x0F) {
                name = "NTAG213 (144 B)";
            } else if (size == 0x11) {
                name = "NTAG215 (504 B)";
            } else if (size == 0x13) {
                name = "NTAG216 (888 B)";
            } else if (size == 0x06) {
                name = "Mifare Ultralight (48 B)";
            } else {
                name = "Type 2, storage code 0x" + Integer.toHexString(size).toUpperCase();
            }
            return String.format(T.probeChip, name, String.format("%02X", v[1] & 0xFF));
        } catch (IOException e) {
            return T.probeNoVersion;
        }
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 把记录写进卡的页 4..15。
     * 必须在拿到 {@link Tag} 的连接上下文里调用；NfcA.transceive 会自动加/校验 CRC。
     *
     * @return 结果说明（成功或失败原因），直接显示给用户。
     */
    public static String write(Tag tag, byte[] record) {
        if (tag == null) {
            return T.noTag;
        }
        if (record == null || record.length != RECORD_LEN) {
            return String.format(T.badLen, RECORD_LEN);
        }
        NfcA a = NfcA.get(tag);
        if (a == null) {
            return T.notNfcA;
        }
        // 先挡掉 M1：它的写命令完全不同，硬写只会得到一串看不懂的超时，不如直接说清楚。
        if (MifareClassic.get(tag) != null) {
            return T.m1Card;
        }
        try {
            a.connect();
            StringBuilder log = new StringBuilder();
            log.append(String.format(T.probeAtqaSak,
                    String.format("%02X%02X", a.getAtqa()[1] & 0xFF, a.getAtqa()[0] & 0xFF),
                    String.format("%02X", a.getSak() & 0xFF)))
                    .append("  UID ").append(hex(tag.getId())).append('\n');
            int pages = RECORD_LEN / 4;
            for (int p = 0; p < pages; p++) {
                byte[] cmd = new byte[6];
                cmd[0] = (byte) 0xA2;                 // WRITE
                cmd[1] = (byte) (FIRST_PAGE + p);
                System.arraycopy(record, p * 4, cmd, 2, 4);
                byte[] resp = a.transceive(cmd);
                // Type 2 的写应答：4 位 ACK 0x0A
                if (resp == null || resp.length != 1 || (resp[0] & 0x0F) != 0x0A) {
                    return log + String.format(T.pageFail, FIRST_PAGE + p,
                            resp == null ? "null" : hex(resp)) + "\n" + T.m1Hint;
                }
            }
            // 回读校验：一次 READ 拿 16 字节，3 次覆盖 12 页
            byte[] back = new byte[RECORD_LEN];
            for (int p = 0; p < pages / 4; p++) {
                byte[] resp = a.transceive(new byte[]{(byte) 0x30, (byte) (FIRST_PAGE + p * 4)});
                if (resp == null || resp.length < 16) {
                    return log + String.format(T.readBackFail, FIRST_PAGE + p * 4);
                }
                System.arraycopy(resp, 0, back, p * 16, 16);
            }
            boolean same = true;
            for (int i = 0; i < RECORD_LEN; i++) {
                if (back[i] != record[i]) {
                    same = false;
                    break;
                }
            }
            log.append(same ? T.doneSame : T.doneDiff);
            if (!same) {
                log.append('\n').append(hex(back));
            }
            return log.toString();
        } catch (IOException e) {
            return String.format(T.ioFail, String.valueOf(e.getMessage())) + "\n" + T.m1Hint;
        } finally {
            try {
                a.close();
            } catch (IOException ignored) {
                // 关不掉就算了，卡拿开自然会断
            }
        }
    }

    private static String join(String[] a) {
        if (a == null || a.length == 0) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            // 去掉包名前缀，只留 NfcA / MifareClassic 这种短名，日志窄屏放得下
            String s = a[i];
            int dot = s.lastIndexOf('.');
            sb.append(dot >= 0 ? s.substring(dot + 1) : s);
        }
        return sb.toString();
    }
}
