package com.t4usend;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * 界面语言：中英双语可切换，默认跟随系统。
 *
 * 三种模式（存 SharedPreferences，键 lang）：
 *   auto  跟随系统 —— 系统是中文就用中文（走 res/values-zh），否则用英文（走 res/values）。
 *   zh    强制中文
 *   en    强制英文
 *
 * 生效方式：MainActivity.attachBaseContext 里按当前模式调 applyOverrideConfiguration
 * 把 locale 覆盖上去，然后 super.attachBaseContext —— 这样本次创建的 Activity 直接用
 * 目标语言的资源。切换时调 Activity.recreate() 让它重走一遍。
 *
 * 不用任何 androidx / 第三方库（app 保持零依赖）。applyOverrideConfiguration 需要 API 17+，
 * 本 app minSdk 19，安全。
 */
public final class LocaleHelper {

    public static final String PREF = "t4usend";
    public static final String KEY_LANG = "lang";

    public static final String AUTO = "auto";
    public static final String ZH = "zh";
    public static final String EN = "en";

    private LocaleHelper() {
    }

    /** 读当前模式；没存过 = 跟随系统。 */
    public static String getMode(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String m = sp.getString(KEY_LANG, AUTO);
        // 容错：存进去的值不认识时退回跟随系统
        if (!ZH.equals(m) && !EN.equals(m)) {
            return AUTO;
        }
        return m;
    }

    /** 写当前模式。 */
    public static void setMode(Context c, String mode) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_LANG, mode).apply();
    }

    /** 循环顺序：跟随系统 -> 中文 -> English -> 跟随系统。 */
    public static String next(String mode) {
        if (AUTO.equals(mode)) {
            return ZH;
        }
        if (ZH.equals(mode)) {
            return EN;
        }
        return AUTO;
    }

    /** 模式 -> 具体 locale。auto 返回 null（表示不要覆盖）。 */
    public static Locale localeOf(String mode) {
        if (ZH.equals(mode)) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        if (EN.equals(mode)) {
            return Locale.ENGLISH;
        }
        return null;
    }

    /**
     * 算出要覆盖到配置里的 Configuration；auto 返回 null（不覆盖）。
     * 调用方把它交给 Activity.applyOverrideConfiguration。
     *
     * 不管哪种模式都会顺手把进程默认 locale 摆正：auto 时还原成系统值，
     * 免得之前手动选过英文后 Locale.getDefault() 一直残留英文（影响数字/大小写格式化）。
     */
    public static Configuration overrideFor(Context base, String mode) {
        Locale loc = localeOf(mode);
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        if (loc == null) {
            Locale.setDefault(cfg.locale);
            return null;
        }
        Locale.setDefault(loc);
        cfg.locale = loc;
        return cfg;
    }
}
