import com.t4usend.T4uPayload;

import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * PayloadCheck —— 离线对拍工具（不进 APK，只在 app/t4usend/test/ 下）。
 *
 * T4uPayload 只用 java.io，不依赖任何安卓类，所以可以用系统的 JDK 直接跑，
 * 把「抽记录 + 拼载荷」这两步的结果落盘，方便逐字节核对。
 *
 * 用法：java PayloadCheck <物品jpg|bin> <256字节模板> <输出载荷路径>
 */
public class PayloadCheck {
    public static void main(String[] args) throws Exception {
        byte[] file = T4uPayload.readAll(new FileInputStream(args[0]));
        T4uPayload.Item it = T4uPayload.extract(file);
        System.out.println("record_len=" + it.length);
        System.out.println("offset=" + it.offset);
        System.out.println("desc=" + it.desc);

        byte[] tmpl = T4uPayload.readAll(new FileInputStream(args[1]));
        byte[] payload = T4uPayload.buildDownload(tmpl, it.record);
        System.out.println("payload_len=" + payload.length);
        System.out.println("payload_desc=" + T4uPayload.describePayload(payload));

        FileOutputStream fo = new FileOutputStream(args[2]);
        fo.write(payload);
        fo.close();
    }
}
