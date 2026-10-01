package com.t4usend;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.NfcEvent;
import android.nfc.NfcManager;
import android.nfc.Tag;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * T4U 发送 —— 单 Activity，两种模式。
 *
 * 模式 1「发送物品」：选一个 4U 物品文件（jpg 或裸 bin），分两次贴卡发给 4U/4U+。
 *     第 1 贴：推 258 字节握手载荷（res/raw/send_start.bin，原样）。
 *              4U 会反向推一条 NDEF 回来，载荷 96..111 = 16 字节设备 ID，138..139 = 2 字节生日。
 *     第 2 贴：推物品载荷（256 字节模板 + 8 字节前缀 + TAMAGO 记录 + 校验 + 对齐）。
 *     动作都由安卓 Beam（setNdefPushMessageCallback）代劳，我们只负责给字节。
 *     为了照顾「NFC 老断、要试半天」的机器（Nexus 6），这里刻意做成可反复重贴：
 *     贴坏了直接再贴，或按「重贴一次」退回第 1 步；还可以「跳过握手」直接发物品。
 *
 * 模式 2「碰触点写卡」：往一张 NFC 卡里写 48 字节触点记录；另有只读「读卡诊断」。
 *     卡型硬要求：必须是 Type 2 的 NFC 卡（NTAG213/215/216 或 Mifare Ultralight），
 *     M1 卡（Mifare Classic）不行。详见 TouchSpot 类头注释。
 *     未在实机上验证过（手头没有能用的 Type 2 卡）。
 *
 * 语言：中英双语，默认跟随系统（系统中文 => 中文，否则英文），右上角按钮可手动切
 *       跟随系统 -> 中文 -> English，选择存在 SharedPreferences 里，改完全局 recreate 生效。
 *
 * 不联网：没有 INTERNET 权限。
 */
public class MainActivity extends Activity {

    /** 4U 官方 app 用的 MIME 类型，原样照抄。 */
    private static final String T4U_MIME = "application/jp.co.bandai.tamagotchiapp";
    private static final int REQ_PICK = 1;
    private static final int MAX_LOG_LINES = 14;

    private NfcAdapter nfc;

    private Button btnLang;
    private TextView tvNfc;
    private LinearLayout panelSend;
    private LinearLayout panelSpot;
    private TextView tvFile;
    private TextView tvStep;
    private TextView tvLog;
    private EditText etType;
    private TextView tvSpotLog;

    /** 选中的物品记录。 */
    private byte[] record;
    /** 拼好的物品载荷。 */
    private byte[] itemPayload;
    /** 握手载荷（启动时读一次）。 */
    private byte[] handshake;

    /** 是否正在发送（决定 Beam 推送是否激活）。 */
    private boolean sending;
    /** 0 = 未开始，1 = 等第 1 贴（握手），2 = 等第 2 贴（物品）。 */
    private int step;

    private int mode = 1;                 // 1 = 发送物品，2 = 碰触点写卡
    private boolean readerOn;             // enableReaderMode 是否已开
    /** 读卡回调要做的事：1 = 写卡，2 = 只读诊断。 */
    private int readerAction = 1;

    private final Deque<String> logLines = new ArrayDeque<String>();

    // ------------------------------------------------------------------ 生命周期

    /** 在 setContentView 之前把语言覆盖装进本 Activity 的资源，模式 = 跟随系统时不覆盖。 */
    @Override
    protected void attachBaseContext(Context newBase) {
        String mode = LocaleHelper.getMode(newBase);
        Configuration override = LocaleHelper.overrideFor(newBase, mode);
        if (override != null) {
            applyOverrideConfiguration(override);
        }
        super.attachBaseContext(newBase);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnLang = (Button) findViewById(R.id.btnLang);
        tvNfc = (TextView) findViewById(R.id.tvNfc);
        panelSend = (LinearLayout) findViewById(R.id.panelSend);
        panelSpot = (LinearLayout) findViewById(R.id.panelSpot);
        tvFile = (TextView) findViewById(R.id.tvFile);
        tvStep = (TextView) findViewById(R.id.tvStep);
        tvLog = (TextView) findViewById(R.id.tvLog);
        etType = (EditText) findViewById(R.id.etType);
        tvSpotLog = (TextView) findViewById(R.id.tvSpotLog);

        applyPayloadStrings();

        NfcManager mgr = (NfcManager) getSystemService(NFC_SERVICE);
        nfc = mgr == null ? null : mgr.getDefaultAdapter();

        try {
            InputStream in = getResources().openRawResource(R.raw.send_start);
            handshake = T4uPayload.readAll(in);
            log(getString(R.string.log_handshake_loaded, handshake.length));
        } catch (Exception e) {
            log(getString(R.string.log_handshake_fail, String.valueOf(e)));
        }

        btnLang.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String mode = LocaleHelper.next(LocaleHelper.getMode(MainActivity.this));
                LocaleHelper.setMode(MainActivity.this, mode);
                recreate();          // 重走 attachBaseContext，界面立刻换语言
            }
        });
        findViewById(R.id.btnModeSend).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                switchMode(1);
            }
        });
        findViewById(R.id.btnModeSpot).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                switchMode(2);
            }
        });
        findViewById(R.id.btnPick).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickFile();
            }
        });
        findViewById(R.id.btnStart).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startSend();
            }
        });
        findViewById(R.id.btnRedo).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                step = 1;
                setStepText(getString(R.string.step_tap1));
                log(getString(R.string.log_back_step1));
            }
        });
        findViewById(R.id.btnSkip).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                step = 2;
                setStepText(getString(R.string.step_tap2_skip));
                log(getString(R.string.log_skip_step2));
            }
        });
        findViewById(R.id.btnStop).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sending = false;
                step = 0;
                applyPushState();
                setStepText(getString(R.string.step_stopped));
            }
        });
        findViewById(R.id.btnWrite).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startSpotWrite();
            }
        });
        findViewById(R.id.btnProbe).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startSpotProbe();
            }
        });
        tvNfc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
                } catch (ActivityNotFoundException e) {
                    toast(getString(R.string.toast_no_nfc_settings));
                }
            }
        });

        switchMode(1);
        if (nfc == null) {
            tvNfc.setText(getString(R.string.nfc_missing));
        }
        // 从 NDEF 意图冷启动时也要把回包读出来
        handleNdefIntent(getIntent());
    }

    /** 把中/英两份文案模板灌进 T4uPayload.T（该类不依赖 android.*，所以由这里注入）。 */
    private void applyPayloadStrings() {
        T4uPayload.T.tooSmall = getString(R.string.bad_too_small);
        T4uPayload.T.noRecord = getString(R.string.bad_no_record);
        T4uPayload.T.payload = getString(R.string.desc_payload);
        T4uPayload.T.payloadEmpty = getString(R.string.desc_payload_empty);
        T4uPayload.T.record = getString(R.string.desc_record);
        T4uPayload.T.offset = getString(R.string.desc_offset);
        T4uPayload.T.name = getString(R.string.desc_name);
        T4uPayload.T.slot = getString(R.string.desc_slot);
        T4uPayload.T.typeCat = getString(R.string.desc_type_cat);
        TouchSpot.T.noTag = getString(R.string.spot_no_tag);
        TouchSpot.T.badLen = getString(R.string.spot_bad_len);
        TouchSpot.T.notNfcA = getString(R.string.spot_not_nfca);
        TouchSpot.T.m1Card = getString(R.string.spot_m1_card);
        TouchSpot.T.m1Hint = getString(R.string.spot_m1_hint);
        TouchSpot.T.pageFail = getString(R.string.spot_page_fail);
        TouchSpot.T.readBackFail = getString(R.string.spot_readback_fail);
        TouchSpot.T.doneSame = getString(R.string.spot_done_same);
        TouchSpot.T.doneDiff = getString(R.string.spot_done_diff);
        TouchSpot.T.ioFail = getString(R.string.spot_io_fail);
        TouchSpot.T.probeNoTag = getString(R.string.probe_no_tag);
        TouchSpot.T.probeTech = getString(R.string.probe_tech);
        TouchSpot.T.probeUid = getString(R.string.probe_uid);
        TouchSpot.T.probeNotNfcA = getString(R.string.probe_not_nfca);
        TouchSpot.T.probeAtqaSak = getString(R.string.probe_atqa_sak);
        TouchSpot.T.probePages = getString(R.string.probe_pages);
        TouchSpot.T.probeChip = getString(R.string.probe_chip);
        TouchSpot.T.probeNoVersion = getString(R.string.probe_no_version);
        TouchSpot.T.probeVerdictOk = getString(R.string.probe_verdict_ok);
        TouchSpot.T.probeIoFail = getString(R.string.probe_io_fail);
        spotNeedPrepare = getString(R.string.spot_need_prepare);
        if (btnLang != null) {
            btnLang.setText(langButtonText(LocaleHelper.getMode(this)));
        }
    }

    /** 语言按钮上的字：跟随系统时显示当前跟随到的语言名。 */
    private String langButtonText(String mode) {
        if (LocaleHelper.ZH.equals(mode)) {
            return getString(R.string.lang_zh);
        }
        if (LocaleHelper.EN.equals(mode)) {
            return getString(R.string.lang_en);
        }
        return getString(R.string.lang_auto);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNfcWarning();
        applyPushState();
        if (readerOn && nfc != null) {
            startReader();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfc != null) {
            nfc.setNdefPushMessageCallback(null, this);
            nfc.setOnNdefPushCompleteCallback(null, this);
        }
        stopReader();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNdefIntent(intent);
    }

    // ------------------------------------------------------------------ 模式切换

    private void switchMode(int m) {
        mode = m;
        panelSend.setVisibility(m == 1 ? View.VISIBLE : View.GONE);
        panelSpot.setVisibility(m == 2 ? View.VISIBLE : View.GONE);
        if (m == 2) {
            readerAction = 1;                 // 进这个页默认为「写卡」，要诊断得自己点按钮
            stopReader();
            startReader();
        } else {
            stopReader();
        }
        applyPushState();
    }

    // ------------------------------------------------------------------ NFC 推送

    private void updateNfcWarning() {
        if (nfc == null) {
            tvNfc.setText(getString(R.string.nfc_missing));
        } else if (!nfc.isEnabled()) {
            tvNfc.setText(getString(R.string.nfc_off));
        } else {
            tvNfc.setText(getString(R.string.nfc_on));
        }
    }

    private void applyPushState() {
        if (nfc == null) {
            return;
        }
        boolean on = sending && mode == 1 && nfc.isEnabled();
        if (on) {
            nfc.setNdefPushMessageCallback(ndefCb, this);
            nfc.setOnNdefPushCompleteCallback(doneCb, this);
        } else {
            nfc.setNdefPushMessageCallback(null, this);
            nfc.setOnNdefPushCompleteCallback(null, this);
        }
    }

    private final NfcAdapter.CreateNdefMessageCallback ndefCb =
            new NfcAdapter.CreateNdefMessageCallback() {
                public NdefMessage createNdefMessage(NfcEvent event) {
                    byte[] payload;
                    String what;
                    if (step <= 1) {
                        payload = handshake;
                        what = getString(R.string.what_handshake);
                    } else {
                        payload = itemPayload;
                        what = getString(R.string.what_item);
                    }
                    if (payload == null) {
                        log(getString(R.string.log_payload_empty, what));
                        return null;
                    }
                    log(getString(R.string.log_supply_requested, what, payload.length));
                    NdefRecord r = NdefRecord.createMime(T4U_MIME, payload);
                    // 载荷都 > 255 字节，Android 会自动用长格式（0xC2 + 4 字节长度）封装。
                    return new NdefMessage(new NdefRecord[]{r});
                }
            };

    private final NfcAdapter.OnNdefPushCompleteCallback doneCb =
            new NfcAdapter.OnNdefPushCompleteCallback() {
                public void onNdefPushComplete(NfcEvent event) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (step <= 1) {
                                step = 2;
                                log(getString(R.string.log_handshake_delivered));
                                setStepText(getString(R.string.step_tap2));
                            } else {
                                log(getString(R.string.log_item_delivered));
                                setStepText(getString(R.string.step_done));
                            }
                        }
                    });
                }
            };

    // ------------------------------------------------------------------ 收 4U 回包

    /** 4U 会反向推一条 NDEF 回来：载荷 96..111 = 设备 ID（16B），138..139 = 生日（2B）。 */
    private void handleNdefIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        if (!NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())) {
            return;
        }
        android.os.Parcelable[] raw =
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
        if (raw == null || raw.length == 0) {
            log(getString(R.string.log_no_ndef));
            return;
        }
        NdefMessage msg = (NdefMessage) raw[0];
        log(getString(R.string.log_reply_records, msg.getRecords().length));
        for (NdefRecord r : msg.getRecords()) {
            byte[] p = r.getPayload();
            log(getString(R.string.log_record, p.length,
                    hexHead(p, 8) + (p.length > 8 ? " ..." : "")));
            if (p.length >= 140) {
                log(getString(R.string.log_dev_id, hexRange(p, 96, 16)));
                log(getString(R.string.log_birthday, p[138] & 0xFF, p[139] & 0xFF));
            }
        }
        if (step <= 1) {
            step = 2;
            setStepText(getString(R.string.step_reply_got));
        }
    }

    // ------------------------------------------------------------------ 选文件 / 发送

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.toast_no_picker));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            byte[] file = T4uPayload.readAll(getContentResolver().openInputStream(uri));
            T4uPayload.Item it = T4uPayload.extract(file);
            InputStream tmpl = getResources().openRawResource(R.raw.send_download);
            itemPayload = T4uPayload.buildDownload(T4uPayload.readAll(tmpl), it.record);
            record = it.record;
            tvFile.setText(displayName(uri) + "\n" + it.desc + "\n"
                    + T4uPayload.describePayload(itemPayload));
            log(getString(R.string.log_item_loaded, it.length));
        } catch (T4uPayload.BadItem e) {
            record = null;
            itemPayload = null;
            tvFile.setText(getString(R.string.file_bad, String.valueOf(e.getMessage())));
            log(getString(R.string.log_rejected, String.valueOf(e.getMessage())));
        } catch (Exception e) {
            record = null;
            itemPayload = null;
            tvFile.setText(getString(R.string.file_read_fail, String.valueOf(e)));
            log(getString(R.string.log_read_fail, String.valueOf(e)));
        }
    }

    private void startSend() {
        if (nfc == null) {
            toast(getString(R.string.toast_no_nfc));
            return;
        }
        if (!nfc.isEnabled()) {
            toast(getString(R.string.toast_nfc_off));
            return;
        }
        if (itemPayload == null) {
            toast(getString(R.string.toast_pick_first));
            return;
        }
        sending = true;
        step = 1;
        logReset();
        applyPushState();
        setStepText(getString(R.string.step_tap1));
        log(getString(R.string.log_send_start));
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return c.getString(idx);
                }
            }
        } catch (Exception ignored) {
            // 拿不到名字就用 URI 末段
        } finally {
            if (c != null) {
                c.close();
            }
        }
        String s = uri.getLastPathSegment();
        return s == null ? uri.toString() : s;
    }

    // ------------------------------------------------------------------ 碰触点写卡 / 读卡诊断

    private void startReader() {
        if (nfc == null || readerOn) {
            return;
        }
        try {
            nfc.enableReaderMode(this, readerCb, NfcAdapter.FLAG_READER_NFC_A, null);
            readerOn = true;
            spotLog(getString(R.string.spot_reader_on));
        } catch (Exception e) {
            spotLog(getString(R.string.spot_reader_fail, String.valueOf(e)));
        }
    }

    private void stopReader() {
        if (nfc != null && readerOn) {
            try {
                nfc.disableReaderMode(this);
            } catch (Exception ignored) {
                // 停不掉不影响功能
            }
        }
        readerOn = false;
    }

    private final NfcAdapter.ReaderCallback readerCb = new NfcAdapter.ReaderCallback() {
        public void onTagDiscovered(Tag tag) {
            final int action = readerAction;
            final byte[] rec = spotRecord;
            final String msg;
            if (action == 2) {
                msg = TouchSpot.probe(tag);              // 只读诊断，不改卡
            } else if (rec == null) {
                msg = spotNeedPrepare;                   // 没先点「开始写卡」
            } else {
                msg = TouchSpot.write(tag, rec);
            }
            runOnUiThread(new Runnable() {
                public void run() {
                    spotLog(msg);
                }
            });
            // 写卡一次就够，贴完先收工，免得误重写；诊断模式保持开着，可以连着贴好几张卡。
            if (action != 2) {
                stopReaderAndReport();
            }
        }
    };

    private byte[] spotRecord;
    /** 「没先点开始写卡」的提示语，提前取好，避免在读卡线程里碰资源。 */
    private String spotNeedPrepare;

    private void startSpotWrite() {
        if (nfc == null || !nfc.isEnabled()) {
            toast(getString(R.string.toast_nfc_off));
            return;
        }
        int t;
        try {
            t = Integer.parseInt(etType.getText().toString().trim());
        } catch (Exception e) {
            t = 1;
        }
        if (t < 1 || t > 10) {                            // 固件只认 1..10，越界就夹回来并说明
            t = 1;
            toast(getString(R.string.spot_event_clamped));
        }
        etType.setText(String.valueOf(t));
        readerAction = 1;
        spotRecord = TouchSpot.buildRecord(t);
        spotLog(getString(R.string.spot_event, t, TouchSpot.hex(spotRecord)));
        startReader();
    }

    /** 只读诊断：不改卡，用来验证读卡链路 + 判卡型。 */
    private void startSpotProbe() {
        if (nfc == null || !nfc.isEnabled()) {
            toast(getString(R.string.toast_nfc_off));
            return;
        }
        readerAction = 2;
        spotRecord = null;
        spotLog(getString(R.string.spot_probe_started));
        startReader();
    }

    private void stopReaderAndReport() {
        runOnUiThread(new Runnable() {
            public void run() {
                stopReader();
            }
        });
    }

    // ------------------------------------------------------------------ 杂项

    private void setStepText(String s) {
        tvStep.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void log(String s) {
        logLines.addLast(s);
        while (logLines.size() > MAX_LOG_LINES) {
            logLines.removeFirst();
        }
        StringBuilder sb = new StringBuilder();
        for (String l : logLines) {
            sb.append(l).append('\n');
        }
        tvLog.setText(sb.toString());
    }

    /** 清空日志区（每次「开始发送」时调用，避免上一次的旧内容混在里面）。 */
    private void logReset() {
        logLines.clear();
        tvLog.setText("");
    }

    private void spotLog(String s) {
        tvSpotLog.setText(s);
    }

    private static String hexHead(byte[] b, int n) {
        return hexRange(b, 0, Math.min(n, b.length));
    }

    private static String hexRange(byte[] b, int off, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + n && i < b.length; i++) {
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }
}
