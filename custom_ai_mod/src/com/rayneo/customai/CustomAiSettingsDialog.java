package com.rayneo.customai;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Theme-independent dark UI settings & call-history dialog built purely with
 * Android SDK Views + GradientDrawables (immune to FlutterActivity legacy themes on HyperOS).
 */
public final class CustomAiSettingsDialog {

    private static final int COL_BG_MODAL = Color.rgb(15, 23, 42);      // #0F172A
    private static final int COL_BG_FOOTER = Color.rgb(11, 17, 32);     // #0B1120
    private static final int COL_BG_CARD = Color.rgb(30, 41, 59);       // #1E293B
    private static final int COL_BG_INPUT = Color.rgb(15, 23, 42);      // #0F172A
    private static final int COL_BORDER = Color.rgb(51, 65, 85);        // #334155
    private static final int COL_BORDER_INPUT = Color.rgb(71, 85, 105); // #475569
    private static final int COL_TEXT_PRIMARY = Color.rgb(248, 250, 252);
    private static final int COL_TEXT_SECONDARY = Color.rgb(148, 163, 184);
    private static final int COL_TEXT_LABEL = Color.rgb(147, 197, 253); // #93C5FD
    private static final int COL_BLUE = Color.rgb(37, 99, 235);         // #2563EB
    private static final int COL_SKY = Color.rgb(2, 132, 199);          // #0284C7
    private static final int COL_EMERALD = Color.rgb(5, 150, 105);      // #059669
    private static final int COL_EMERALD_BRIGHT = Color.rgb(16, 185, 129);
    private static final int COL_SLATE_BTN = Color.rgb(51, 65, 85);     // #334155
    private static final int COL_RED = Color.rgb(153, 27, 27);          // #991B1B

    private static volatile Dialog activeDialog;

    private CustomAiSettingsDialog() {}

    public static void show(final Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (activeDialog != null && activeDialog.isShowing()) {
                        activeDialog.dismiss();
                    }
                    buildAndShow(activity);
                } catch (Throwable t) {
                    Toast.makeText(activity, "打开自定义 AI 设置失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private static final class DialogFrame {
        final Dialog dialog;
        final LinearLayout body;
        final LinearLayout footer;

        DialogFrame(Dialog dialog, LinearLayout body, LinearLayout footer) {
            this.dialog = dialog;
            this.body = body;
            this.footer = footer;
        }
    }

    /**
     * Theme-independent custom pill switch built with FrameLayout + GradientDrawable,
     * never touching android.widget.Switch system drawables.
     */
    private static final class ToggleRow extends LinearLayout {
        private final FrameLayout track;
        private final GradientDrawable trackBg;
        private final View thumb;
        private final float thumbTravelPx;
        private boolean checked;
        private Runnable onChangeListener;

        ToggleRow(Context ctx, String title, String desc, boolean initialChecked) {
            super(ctx);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, dp(ctx, 9), 0, dp(ctx, 9));
            setMinimumHeight(dp(ctx, 46));
            setClickable(true);
            setFocusable(true);

            LinearLayout textCol = new LinearLayout(ctx);
            textCol.setOrientation(VERTICAL);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textLp.rightMargin = dp(ctx, 12);

            TextView tvTitle = new TextView(ctx);
            tvTitle.setText(title);
            tvTitle.setTextColor(COL_TEXT_PRIMARY);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
            textCol.addView(tvTitle);

            if (desc != null && !desc.isEmpty()) {
                TextView tvDesc = new TextView(ctx);
                tvDesc.setText(desc);
                tvDesc.setTextColor(COL_TEXT_SECONDARY);
                tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dLp.topMargin = dp(ctx, 2);
                textCol.addView(tvDesc, dLp);
            }
            addView(textCol, textLp);

            int trackW = dp(ctx, 48);
            int trackH = dp(ctx, 26);
            int thumbSize = dp(ctx, 20);
            int margin = dp(ctx, 3);
            this.thumbTravelPx = trackW - thumbSize - margin * 2;

            track = new FrameLayout(ctx);
            trackBg = new GradientDrawable();
            trackBg.setCornerRadius(trackH / 2f);
            track.setBackground(trackBg);

            thumb = new View(ctx);
            GradientDrawable thumbBg = new GradientDrawable();
            thumbBg.setShape(GradientDrawable.OVAL);
            thumbBg.setColor(Color.WHITE);
            thumbBg.setStroke(dp(ctx, 0.5f), Color.rgb(226, 232, 240));
            thumb.setBackground(thumbBg);
            thumb.setElevation(dp(ctx, 2));

            FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(
                    thumbSize, thumbSize, Gravity.CENTER_VERTICAL | Gravity.LEFT);
            thumbLp.leftMargin = margin;
            track.addView(thumb, thumbLp);

            addView(track, new LinearLayout.LayoutParams(trackW, trackH));
            setChecked(initialChecked, false);

            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    setChecked(!checked, true);
                    if (onChangeListener != null) {
                        onChangeListener.run();
                    }
                }
            });
        }

        boolean isChecked() {
            return checked;
        }

        void setChecked(boolean value, boolean animate) {
            this.checked = value;
            if (value) {
                trackBg.setColor(COL_EMERALD_BRIGHT);
                trackBg.setStroke(dp(getContext(), 1), Color.rgb(52, 211, 153));
            } else {
                trackBg.setColor(COL_SLATE_BTN);
                trackBg.setStroke(dp(getContext(), 1), Color.rgb(100, 116, 139));
            }
            float targetX = value ? thumbTravelPx : 0f;
            if (animate) {
                thumb.animate().cancel();
                thumb.animate().translationX(targetX).setDuration(130).start();
            } else {
                thumb.setTranslationX(targetX);
            }
        }

        void setOnChangeListener(Runnable r) {
            this.onChangeListener = r;
        }
    }

    /**
     * Theme-independent segmented pill selector replacing RadioGroup and Spinner.
     */
    private static final class SegmentSelector extends LinearLayout {
        private final List<TextView> items = new ArrayList<>();
        private int selectedIndex = 0;
        private Runnable onChanged;

        SegmentSelector(final Context ctx, String[] labels, float textSp) {
            super(ctx);
            setOrientation(HORIZONTAL);
            GradientDrawable outer = new GradientDrawable();
            outer.setColor(COL_BG_INPUT);
            outer.setCornerRadius(dp(ctx, 10));
            outer.setStroke(dp(ctx, 1), COL_BORDER);
            setBackground(outer);
            int pad = dp(ctx, 3);
            setPadding(pad, pad, pad, pad);

            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                TextView tv = new TextView(ctx);
                tv.setText(labels[i]);
                tv.setGravity(Gravity.CENTER);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setPadding(dp(ctx, 6), dp(ctx, 7), dp(ctx, 6), dp(ctx, 7));
                tv.setClickable(true);
                tv.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (selectedIndex != idx) {
                            setSelectedIndex(idx);
                            if (onChanged != null) onChanged.run();
                        }
                    }
                });
                items.add(tv);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i > 0) lp.leftMargin = dp(ctx, 3);
                addView(tv, lp);
            }
            updateVisuals();
        }

        int getSelectedIndex() {
            return selectedIndex;
        }

        void setSelectedIndex(int idx) {
            if (idx < 0 || idx >= items.size()) idx = 0;
            selectedIndex = idx;
            updateVisuals();
        }

        void setOnChangedListener(Runnable r) {
            this.onChanged = r;
        }

        private void updateVisuals() {
            Context ctx = getContext();
            for (int i = 0; i < items.size(); i++) {
                TextView tv = items.get(i);
                if (i == selectedIndex) {
                    GradientDrawable selBg = new GradientDrawable();
                    selBg.setColor(COL_BLUE);
                    selBg.setCornerRadius(dp(ctx, 7));
                    tv.setBackground(selBg);
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(Typeface.DEFAULT_BOLD);
                } else {
                    tv.setBackground(null);
                    tv.setTextColor(COL_TEXT_SECONDARY);
                    tv.setTypeface(Typeface.DEFAULT);
                }
            }
        }
    }

    private static DialogFrame createBaseDialog(
            final Activity activity,
            String titleText,
            String subtitleText,
            final float maxScrollScreenFraction
    ) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout shell = new LinearLayout(activity);
        shell.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shellBg = new GradientDrawable();
        shellBg.setColor(COL_BG_MODAL);
        shellBg.setCornerRadius(dp(activity, 16));
        shellBg.setStroke(dp(activity, 1), COL_BORDER);
        shell.setBackground(shellBg);
        shell.setElevation(dp(activity, 12));

        // Sticky Header
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 14), dp(activity, 12));

        LinearLayout titleCol = new LinearLayout(activity);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView tvTitle = new TextView(activity);
        tvTitle.setText(titleText);
        tvTitle.setTextColor(COL_TEXT_PRIMARY);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(tvTitle);

        if (subtitleText != null && !subtitleText.isEmpty()) {
            TextView tvSub = new TextView(activity);
            tvSub.setText(subtitleText);
            tvSub.setTextColor(COL_TEXT_SECONDARY);
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.topMargin = dp(activity, 2);
            titleCol.addView(tvSub, sLp);
        }
        header.addView(titleCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnClose = new TextView(activity);
        btnClose.setText("✕");
        btnClose.setTextColor(Color.rgb(203, 213, 225));
        btnClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btnClose.setTypeface(Typeface.DEFAULT_BOLD);
        btnClose.setGravity(Gravity.CENTER);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setShape(GradientDrawable.OVAL);
        closeBg.setColor(COL_BG_CARD);
        closeBg.setStroke(dp(activity, 1), COL_BORDER);
        btnClose.setBackground(closeBg);
        btnClose.setClickable(true);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(activity, 30), dp(activity, 30));
        closeLp.leftMargin = dp(activity, 10);
        header.addView(btnClose, closeLp);
        shell.addView(header);

        addHairlineDivider(activity, shell, 0);

        // Bounded-height ScrollView so header and footer stay pinned even on tall forms or IME resize
        ScrollView scroll = new ScrollView(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                int capPx = (int) (dm.heightPixels * maxScrollScreenFraction);
                int mode = MeasureSpec.getMode(heightMeasureSpec);
                int size = MeasureSpec.getSize(heightMeasureSpec);
                int maxH = (mode == MeasureSpec.UNSPECIFIED) ? capPx : Math.min(size, capPx);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(activity, 13), dp(activity, 12), dp(activity, 13), dp(activity, 12));
        scroll.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addHairlineDivider(activity, shell, 0);

        // Sticky Footer
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable footBg = new GradientDrawable();
        footBg.setColor(COL_BG_FOOTER);
        float r = dp(activity, 16);
        footBg.setCornerRadii(new float[]{0f, 0f, 0f, 0f, r, r, r, r});
        footer.setBackground(footBg);
        footer.setPadding(dp(activity, 13), dp(activity, 10), dp(activity, 13), dp(activity, 12));
        shell.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(shell);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int dialogWidth = (int) (dm.widthPixels * 0.93f);
            win.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return new DialogFrame(dialog, body, footer);
    }

    private static void buildAndShow(final Activity activity) {
        final CustomAiConfig cfg = CustomAiConfig.load(activity);
        final DialogFrame frame = createBaseDialog(
                activity,
                "⚡ 雷鸟 io 自定义 AI 配置",
                "仅替换 LLM 推理；原版 ASR / 本地指令 / 原版 TTS 不变（连按 3 次音量+ 呼出）",
                0.66f
        );
        LinearLayout root = frame.body;

        // ==================== Card 1: Call History & Monitor ====================
        LinearLayout cardMonitor = addCardSection(activity, root, "📜 实时调用监控与历史");

        final Button btnHistory = makePillBtn(activity, "", COL_EMERALD, COL_EMERALD_BRIGHT, 13f);
        btnHistory.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
        LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hbLp.bottomMargin = dp(activity, 8);
        cardMonitor.addView(btnHistory, hbLp);

        final TextView tvLatestSummary = new TextView(activity);
        tvLatestSummary.setTextColor(Color.rgb(226, 232, 240));
        tvLatestSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvLatestSummary.setLineSpacing(dp(activity, 2), 1f);
        tvLatestSummary.setPadding(dp(activity, 10), dp(activity, 9), dp(activity, 10), dp(activity, 9));
        GradientDrawable sumBg = new GradientDrawable();
        sumBg.setColor(COL_BG_INPUT);
        sumBg.setCornerRadius(dp(activity, 8));
        sumBg.setStroke(dp(activity, 1), COL_BORDER);
        tvLatestSummary.setBackground(sumBg);
        cardMonitor.addView(tvLatestSummary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final Runnable refreshHistoryPreview = new Runnable() {
            @Override
            public void run() {
                int cnt = CustomAiCallLog.getCount(activity);
                btnHistory.setText("📜 查看调用历史与返回结果（已记录 " + cnt + " 次）");
                CustomAiCallLog.Entry latest = CustomAiCallLog.getLatest(activity);
                if (latest == null) {
                    tvLatestSummary.setText("暂无自定义 API 调用记录\n使用眼镜语音、手机 AskAI 或下方「测试连接」后将在此显示最近一次调用详情");
                } else {
                    tvLatestSummary.setText("最近一次：\n" + CustomAiCallLog.formatEntrySummary(latest) + "\n(点击查看完整历史与返回内容 ›)");
                }
            }
        };
        refreshHistoryPreview.run();

        View.OnClickListener openHistClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCallHistoryDialog(activity, refreshHistoryPreview);
            }
        };
        btnHistory.setOnClickListener(openHistClick);
        tvLatestSummary.setOnClickListener(openHistClick);

        // ==================== Card 2: Switches ====================
        LinearLayout cardSwitches = addCardSection(activity, root, "🎛️ 核心接管与行为开关");

        final ToggleRow swEnabled = new ToggleRow(
                activity, "启用自定义 AI 接口", "总开关：关闭后完全恢复雷鸟官方大模型", cfg.enabled);
        final ToggleRow swGlasses = new ToggleRow(
                activity, "接管眼镜语音助手 (Assistant)", "原版 ASR 识别后改走自定义 LLM，结果回显眼镜并原版 TTS 播报", cfg.interceptGlassesVoice);
        final ToggleRow swPhoneAsk = new ToggleRow(
                activity, "接管手机端 Ask AI 页面", "手机 App 内 AI 对话同样走自定义 LLM", cfg.interceptPhoneAskAi);
        final ToggleRow swKeepLocal = new ToggleRow(
                activity, "保留本地硬件控制指令", "音量 / 亮度 / 拍照 / 退出等离线指令继续由眼镜原生执行", cfg.keepLocalHardwareControl);
        final ToggleRow swSkills = new ToggleRow(
                activity, "官方功能指令交给原版执行", "提醒/倒计时/电话/音乐/拍照/导航/识图等由雷鸟云端识别并原版执行，闲聊才走自定义模型", cfg.passThroughOfficialSkills);
        final ToggleRow swFloating = new ToggleRow(
                activity, "显示悬浮 ⚡LLM 按钮", "长按可拖动位置；隐藏后可连按 3 次音量+ 打开本面板", cfg.showFloatingButton);
        final ToggleRow swToast = new ToggleRow(
                activity, "调用时弹出 Toast 提示", "每次触发自定义接口时短暂提示渠道与模型名", cfg.showCallToast);
        final ToggleRow swAutoNext = new ToggleRow(
                activity, "播报完自动开启下一轮拾音", "默认关闭，避免回答刚显示就被下一轮拾音冲掉", cfg.autoContinueAfterTts);

        ToggleRow[] allRows = new ToggleRow[]{
                swEnabled, swGlasses, swPhoneAsk, swKeepLocal, swSkills, swFloating, swToast, swAutoNext
        };
        for (int i = 0; i < allRows.length; i++) {
            cardSwitches.addView(allRows[i], new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < allRows.length - 1) {
                addHairlineDivider(activity, cardSwitches, 0);
            }
        }

        // ==================== Card 3: Endpoint & Model ====================
        LinearLayout cardApi = addCardSection(activity, root, "🌐 接口协议与模型配置");

        addFieldLabel(activity, cardApi, "快捷预设（一键填充常用服务地址与模型）", false);
        LinearLayout presetRow1 = new LinearLayout(activity);
        presetRow1.setOrientation(LinearLayout.HORIZONTAL);
        cardApi.addView(presetRow1);
        LinearLayout presetRow2 = new LinearLayout(activity);
        presetRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pr2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pr2Lp.topMargin = dp(activity, 6);
        cardApi.addView(presetRow2, pr2Lp);

        addFieldLabel(activity, cardApi, "API 协议模式", true);
        final SegmentSelector segProto = new SegmentSelector(
                activity,
                new String[]{"Chat (/chat/completions)", "Responses (/responses)"},
                11.5f
        );
        segProto.setSelectedIndex(OpenAiApiClient.isResponsesProtocol(cfg.apiProtocol) ? 1 : 0);
        cardApi.addView(segProto, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addFieldLabel(activity, cardApi, "API Base URL（Android 要求 HTTPS；填域名或 .../v1）", true);
        final EditText etBaseUrl = makeEdit(
                activity, "https://api.openai.com/v1", cfg.baseUrl, InputType.TYPE_TEXT_VARIATION_URI, true);
        cardApi.addView(etBaseUrl);

        final TextView tvResolvedUrl = new TextView(activity);
        tvResolvedUrl.setTextColor(Color.rgb(52, 211, 153));
        tvResolvedUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvResolvedUrl.setPadding(dp(activity, 2), dp(activity, 4), dp(activity, 2), dp(activity, 2));
        cardApi.addView(tvResolvedUrl);

        addFieldLabel(activity, cardApi, "API Key（Bearer Token）", true);
        LinearLayout keyRow = new LinearLayout(activity);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText etApiKey = makeEdit(
                activity, "sk-...", cfg.apiKey,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, true);
        LinearLayout.LayoutParams keyInputLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        keyInputLp.rightMargin = dp(activity, 8);
        keyRow.addView(etApiKey, keyInputLp);

        final boolean[] keyVisible = new boolean[]{false};
        final Button btnShowKey = makePillBtn(activity, "👁 显示", COL_SLATE_BTN, COL_BORDER_INPUT, 11.5f);
        btnShowKey.setPadding(dp(activity, 10), dp(activity, 9), dp(activity, 10), dp(activity, 9));
        btnShowKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                keyVisible[0] = !keyVisible[0];
                int pos = etApiKey.getSelectionStart();
                etApiKey.setInputType(keyVisible[0]
                        ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                        : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
                if (pos >= 0 && pos <= etApiKey.length()) {
                    etApiKey.setSelection(pos);
                }
                btnShowKey.setText(keyVisible[0] ? "🙈 隐藏" : "👁 显示");
            }
        });
        keyRow.addView(btnShowKey, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cardApi.addView(keyRow);

        addFieldLabel(activity, cardApi, "模型名称（Model）", true);
        final EditText etModel = makeEdit(
                activity, "gpt-6-luna / deepseek-flash / gemini-4-hydrogen", cfg.model, InputType.TYPE_CLASS_TEXT, true);
        cardApi.addView(etModel);

        addFieldLabel(activity, cardApi, "思考强度（Reasoning Effort，思考模型可设为 Low/Medium）", true);
        final String[] effortValues = new String[]{"", "low", "medium", "high"};
        final SegmentSelector segEffort = new SegmentSelector(
                activity,
                new String[]{"关闭", "Low", "Medium", "High"},
                11.5f
        );
        int initEffortIdx = 0;
        for (int i = 0; i < effortValues.length; i++) {
            if (effortValues[i].equalsIgnoreCase(cfg.reasoningEffort)) {
                initEffortIdx = i;
                break;
            }
        }
        segEffort.setSelectedIndex(initEffortIdx);
        cardApi.addView(segEffort, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final Runnable updateUrlPreview = new Runnable() {
            @Override
            public void run() {
                String proto = segProto.getSelectedIndex() == 1
                        ? OpenAiApiClient.PROTO_RESPONSES
                        : OpenAiApiClient.PROTO_CHAT_COMPLETIONS;
                tvResolvedUrl.setText("实际请求地址: " + OpenAiApiClient.resolveEndpointUrl(
                        etBaseUrl.getText().toString(), proto));
            }
        };
        updateUrlPreview.run();
        segProto.setOnChangedListener(updateUrlPreview);
        etBaseUrl.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                updateUrlPreview.run();
            }
        });

        addPresetChip(activity, presetRow1, "OpenAI Resp", new Runnable() {
            @Override public void run() {
                segProto.setSelectedIndex(1);
                etBaseUrl.setText("https://api.openai.com/v1");
                etModel.setText("gpt-6-luna");
                updateUrlPreview.run();
            }
        });
        addPresetChip(activity, presetRow1, "Gemini 4", new Runnable() {
            @Override public void run() {
                segProto.setSelectedIndex(0);
                etBaseUrl.setText("https://generativelanguage.googleapis.com/v1beta/openai");
                etModel.setText("gemini-4-hydrogen");
                updateUrlPreview.run();
            }
        });
        addPresetChip(activity, presetRow1, "DeepSeek", new Runnable() {
            @Override public void run() {
                segProto.setSelectedIndex(0);
                etBaseUrl.setText("https://api.deepseek.com/v1");
                etModel.setText("deepseek-flash");
                updateUrlPreview.run();
            }
        });
        addPresetChip(activity, presetRow2, "阿里百炼", new Runnable() {
            @Override public void run() {
                segProto.setSelectedIndex(0);
                etBaseUrl.setText("https://dashscope.aliyuncs.com/compatible-mode/v1");
                etModel.setText("qwen3.8-flash");
                updateUrlPreview.run();
            }
        });
        addPresetChip(activity, presetRow2, "Kimi K3.1", new Runnable() {
            @Override public void run() {
                segProto.setSelectedIndex(0);
                etBaseUrl.setText("https://api.moonshot.cn/v1");
                etModel.setText("kimi-k3.1-pro-highspeed");
                updateUrlPreview.run();
            }
        });
        addPresetChip(activity, presetRow2, "OpenRouter", new Runnable() {
            @Override public void run() {
                segProto.setSelectedIndex(0);
                etBaseUrl.setText("https://openrouter.ai/api/v1");
                etModel.setText("google/gemini-4-hydrogen");
                updateUrlPreview.run();
            }
        });

        // ==================== Card 4: Generation Params & Diagnostics ====================
        LinearLayout cardParams = addCardSection(activity, root, "⚙️ 生成参数与诊断工具");

        addFieldLabel(activity, cardParams, "系统提示词（System Prompt）", false);
        final EditText etSysPrompt = makeEdit(
                activity, "自定义人设/回答风格", cfg.systemPrompt,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, false);
        etSysPrompt.setMinLines(2);
        etSysPrompt.setMaxLines(4);
        cardParams.addView(etSysPrompt);

        LinearLayout gridRow1 = new LinearLayout(activity);
        gridRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams g1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        g1Lp.topMargin = dp(activity, 8);
        cardParams.addView(gridRow1, g1Lp);
        final EditText etTemp = addGridInput(activity, gridRow1, "Temperature", String.valueOf(cfg.temperature), false);
        final EditText etMaxTok = addGridInput(activity, gridRow1, "Max Tokens", String.valueOf(cfg.maxTokens), true);

        LinearLayout gridRow2 = new LinearLayout(activity);
        gridRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams g2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        g2Lp.topMargin = dp(activity, 6);
        cardParams.addView(gridRow2, g2Lp);
        final EditText etHist = addGridInput(activity, gridRow2, "上下文记忆轮数", String.valueOf(cfg.maxHistoryTurns), false);
        final EditText etTimeout = addGridInput(activity, gridRow2, "超时时间（秒）", String.valueOf(cfg.timeoutSeconds), true);

        LinearLayout gridRow3 = new LinearLayout(activity);
        gridRow3.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams g3Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        g3Lp.topMargin = dp(activity, 6);
        cardParams.addView(gridRow3, g3Lp);
        final EditText etRouteMs = addGridInput(activity, gridRow3, "功能分流等待（毫秒）", String.valueOf(cfg.skillRouteTimeoutMs), false);
        TextView tvRouteHint = new TextView(activity);
        tvRouteHint.setText("等待雷鸟云端判断是否为官方功能指令的上限，超时按闲聊走自定义模型（建议 1500~2500）");
        tvRouteHint.setTextColor(COL_TEXT_SECONDARY);
        tvRouteHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tvRouteHint.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        hintLp.leftMargin = dp(activity, 5);
        gridRow3.addView(tvRouteHint, hintLp);

        addHairlineDivider(activity, cardParams, 8);
        final ToggleRow swStream = new ToggleRow(
                activity, "启用 SSE 流式响应 (Stream)", "边生成边推送眼镜字幕，降低首字延迟", cfg.streamEnabled);
        cardParams.addView(swStream);
        addHairlineDivider(activity, cardParams, 2);

        addFieldLabel(activity, cardParams, "自定义请求头 JSON（可选，默认 {}）", true);
        final EditText etHeaders = makeEdit(
                activity, "{\"X-Title\":\"RayNeo\"}", cfg.customHeadersJson, InputType.TYPE_CLASS_TEXT, true);
        cardParams.addView(etHeaders);

        final TextView tvTestStatus = new TextView(activity);
        tvTestStatus.setTextColor(COL_TEXT_PRIMARY);
        tvTestStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvTestStatus.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));
        tvTestStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tsLp.topMargin = dp(activity, 10);
        cardParams.addView(tvTestStatus, tsLp);

        Button btnTest = makePillBtn(activity, "🔌 发送测试请求（检验连通性与返回结果）", COL_SKY, Color.rgb(56, 189, 248), 12.5f);
        btnTest.setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        testLp.topMargin = dp(activity, 10);
        cardParams.addView(btnTest, testLp);

        LinearLayout utilRow = new LinearLayout(activity);
        utilRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams urLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        urLp.topMargin = dp(activity, 8);
        cardParams.addView(utilRow, urLp);

        Button btnExport = makePillBtn(activity, "📋 导出配置", COL_SLATE_BTN, COL_BORDER_INPUT, 11.5f);
        Button btnImport = makePillBtn(activity, "📥 导入配置", COL_SLATE_BTN, COL_BORDER_INPUT, 11.5f);
        Button btnClear = makePillBtn(activity, "🗑️ 清空记忆", COL_RED, Color.rgb(220, 38, 38), 11.5f);
        LinearLayout.LayoutParams u1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        u1.rightMargin = dp(activity, 6);
        LinearLayout.LayoutParams u2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        u2.rightMargin = dp(activity, 6);
        LinearLayout.LayoutParams u3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        utilRow.addView(btnExport, u1);
        utilRow.addView(btnImport, u2);
        utilRow.addView(btnClear, u3);

        final Runnable collectToConfig = new Runnable() {
            @Override
            public void run() {
                cfg.enabled = swEnabled.isChecked();
                cfg.interceptGlassesVoice = swGlasses.isChecked();
                cfg.interceptPhoneAskAi = swPhoneAsk.isChecked();
                cfg.keepLocalHardwareControl = swKeepLocal.isChecked();
                cfg.passThroughOfficialSkills = swSkills.isChecked();
                cfg.skillRouteTimeoutMs = parseIntSafe(etRouteMs.getText().toString(), 2000);
                cfg.showFloatingButton = swFloating.isChecked();
                cfg.showCallToast = swToast.isChecked();
                cfg.autoContinueAfterTts = swAutoNext.isChecked();
                cfg.apiProtocol = segProto.getSelectedIndex() == 1
                        ? OpenAiApiClient.PROTO_RESPONSES : OpenAiApiClient.PROTO_CHAT_COMPLETIONS;
                cfg.baseUrl = etBaseUrl.getText().toString().trim();
                cfg.apiKey = etApiKey.getText().toString().trim();
                cfg.model = etModel.getText().toString().trim();
                int idx = segEffort.getSelectedIndex();
                cfg.reasoningEffort = (idx >= 0 && idx < effortValues.length) ? effortValues[idx] : "";
                cfg.systemPrompt = etSysPrompt.getText().toString();
                cfg.temperature = parseDoubleSafe(etTemp.getText().toString(), 0.7);
                cfg.maxTokens = parseIntSafe(etMaxTok.getText().toString(), 1024);
                cfg.maxHistoryTurns = parseIntSafe(etHist.getText().toString(), 6);
                cfg.timeoutSeconds = parseIntSafe(etTimeout.getText().toString(), 45);
                cfg.streamEnabled = swStream.isChecked();
                cfg.customHeadersJson = etHeaders.getText().toString().trim();
            }
        };

        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collectToConfig.run();
                setStatusBanner(activity, tvTestStatus,
                        Color.rgb(30, 58, 138), Color.rgb(59, 130, 246),
                        "⏳ 正在测试连接 (" + OpenAiApiClient.resolveEndpointUrl(cfg.baseUrl, cfg.apiProtocol) + ")...");
                final OpenAiApiClient.EndpointConfig ep = cfg.toEndpointConfig();
                final long logId = CustomAiCallLog.startCall(
                        activity, "🔌 连通性测试", cfg, "Ping", "PhoneTest", false, false);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        long t0 = System.currentTimeMillis();
                        try {
                            final String reply = OpenAiApiClient.testConnection(ep);
                            final long cost = System.currentTimeMillis() - t0;
                            CustomAiCallLog.onTextDelta(logId, reply);
                            CustomAiCallLog.finishSuccess(activity, logId, reply);
                            activity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    setStatusBanner(activity, tvTestStatus,
                                            Color.rgb(6, 78, 59), COL_EMERALD_BRIGHT,
                                            "✅ 连接成功 (" + cost + " ms) | 模型返回: " + reply);
                                    refreshHistoryPreview.run();
                                }
                            });
                        } catch (final Throwable e) {
                            CustomAiCallLog.finishError(activity, logId, e.getMessage());
                            activity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    setStatusBanner(activity, tvTestStatus,
                                            Color.rgb(127, 29, 29), Color.rgb(239, 68, 68),
                                            "❌ 连接失败: " + e.getMessage());
                                    refreshHistoryPreview.run();
                                }
                            });
                        }
                    }
                }, "CustomAiTest").start();
            }
        });

        btnExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collectToConfig.run();
                copyText(activity, "RayNeoCustomAI", cfg.toJsonString(), "已复制 JSON 配置到剪贴板");
            }
        });

        btnImport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) {
                        Toast.makeText(activity, "剪贴板为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(activity);
                    CustomAiConfig imported = CustomAiConfig.fromJsonString(cs.toString());
                    swEnabled.setChecked(imported.enabled, true);
                    swGlasses.setChecked(imported.interceptGlassesVoice, true);
                    swPhoneAsk.setChecked(imported.interceptPhoneAskAi, true);
                    swKeepLocal.setChecked(imported.keepLocalHardwareControl, true);
                    swSkills.setChecked(imported.passThroughOfficialSkills, true);
                    etRouteMs.setText(String.valueOf(imported.skillRouteTimeoutMs));
                    swFloating.setChecked(imported.showFloatingButton, true);
                    swToast.setChecked(imported.showCallToast, true);
                    swAutoNext.setChecked(imported.autoContinueAfterTts, true);
                    segProto.setSelectedIndex(OpenAiApiClient.isResponsesProtocol(imported.apiProtocol) ? 1 : 0);
                    etBaseUrl.setText(imported.baseUrl);
                    etApiKey.setText(imported.apiKey);
                    etModel.setText(imported.model);
                    etSysPrompt.setText(imported.systemPrompt);
                    etTemp.setText(String.valueOf(imported.temperature));
                    etMaxTok.setText(String.valueOf(imported.maxTokens));
                    etHist.setText(String.valueOf(imported.maxHistoryTurns));
                    etTimeout.setText(String.valueOf(imported.timeoutSeconds));
                    swStream.setChecked(imported.streamEnabled, true);
                    etHeaders.setText(imported.customHeadersJson);
                    int selIdx = 0;
                    for (int i = 0; i < effortValues.length; i++) {
                        if (effortValues[i].equalsIgnoreCase(imported.reasoningEffort)) {
                            selIdx = i;
                            break;
                        }
                    }
                    segEffort.setSelectedIndex(selIdx);
                    updateUrlPreview.run();
                    Toast.makeText(activity, "已从剪贴板导入，请点击保存", Toast.LENGTH_SHORT).show();
                } catch (Throwable e) {
                    Toast.makeText(activity, "JSON 格式无效: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConversationMemory.clearAll();
                Toast.makeText(activity, "已清空多轮对话记忆", Toast.LENGTH_SHORT).show();
            }
        });

        // Sticky Footer Actions
        Button btnCancel = makePillBtn(activity, "取消", COL_SLATE_BTN, COL_BORDER_INPUT, 13.5f);
        btnCancel.setPadding(dp(activity, 8), dp(activity, 11), dp(activity, 8), dp(activity, 11));
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                frame.dialog.dismiss();
            }
        });

        Button btnSave = makePillBtn(activity, "💾 保存并立即生效", COL_BLUE, Color.rgb(96, 165, 250), 13.5f);
        btnSave.setPadding(dp(activity, 8), dp(activity, 11), dp(activity, 8), dp(activity, 11));
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collectToConfig.run();
                cfg.save(activity);
                CustomAiFloatingBadge.attachIfEnabled(activity);
                frame.dialog.dismiss();
                Toast.makeText(activity,
                        cfg.enabled ? "已保存：" + cfg.model + " (" + cfg.apiProtocol + ")" : "已关闭自定义 AI，恢复官方接口",
                        Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cancelLp.rightMargin = dp(activity, 10);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.8f);
        frame.footer.addView(btnCancel, cancelLp);
        frame.footer.addView(btnSave, saveLp);

        activeDialog = frame.dialog;
        frame.dialog.show();
    }

    private static void showCallHistoryDialog(final Activity activity, final Runnable onChanged) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        final DialogFrame frame = createBaseDialog(
                activity,
                "📜 自定义 API 调用历史与返回结果",
                "点击任意记录可查看完整返回文本、请求端点及毫秒级生命周期时间线",
                0.62f
        );

        final LinearLayout listContainer = frame.body;

        final Runnable renderList = new Runnable() {
            @Override
            public void run() {
                listContainer.removeAllViews();
                List<CustomAiCallLog.Entry> entries = CustomAiCallLog.getEntries(activity);
                if (entries.isEmpty()) {
                    LinearLayout emptyCard = new LinearLayout(activity);
                    emptyCard.setOrientation(LinearLayout.VERTICAL);
                    emptyCard.setGravity(Gravity.CENTER);
                    emptyCard.setPadding(dp(activity, 16), dp(activity, 28), dp(activity, 16), dp(activity, 28));
                    GradientDrawable gd = new GradientDrawable();
                    gd.setColor(COL_BG_CARD);
                    gd.setCornerRadius(dp(activity, 10));
                    gd.setStroke(dp(activity, 1), COL_BORDER);
                    emptyCard.setBackground(gd);

                    TextView empty = new TextView(activity);
                    empty.setText("暂无自定义 API 调用记录\n使用眼镜语音、手机 AskAI 或「测试连接」后会立即出现在这里");
                    empty.setGravity(Gravity.CENTER);
                    empty.setTextColor(COL_TEXT_SECONDARY);
                    empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                    emptyCard.addView(empty);
                    listContainer.addView(emptyCard);
                    return;
                }
                for (final CustomAiCallLog.Entry e : entries) {
                    LinearLayout card = new LinearLayout(activity);
                    card.setOrientation(LinearLayout.VERTICAL);
                    int cPad = dp(activity, 11);
                    card.setPadding(cPad, cPad, cPad, cPad);
                    GradientDrawable gd = new GradientDrawable();
                    gd.setColor(COL_BG_CARD);
                    gd.setCornerRadius(dp(activity, 10));
                    int stroke = CustomAiCallLog.STATUS_SUCCESS.equals(e.status)
                            ? COL_EMERALD_BRIGHT
                            : (CustomAiCallLog.STATUS_ERROR.equals(e.status)
                                ? Color.rgb(239, 68, 68)
                                : (CustomAiCallLog.STATUS_INTERRUPTED.equals(e.status)
                                    ? Color.rgb(245, 158, 11)
                                    : Color.rgb(56, 189, 248)));
                    gd.setStroke(dp(activity, 1), stroke);
                    card.setBackground(gd);

                    TextView tvTop = new TextView(activity);
                    tvTop.setText(CustomAiCallLog.statusBadge(e.status) + "  " + e.channel + "  ·  " + e.timeStr);
                    tvTop.setTextColor(COL_TEXT_PRIMARY);
                    tvTop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                    tvTop.setTypeface(Typeface.DEFAULT_BOLD);
                    card.addView(tvTop);

                    String firstStr = e.firstTokenMs >= 0 ? (e.firstTokenMs + "ms") : "-";
                    TextView tvMeta = new TextView(activity);
                    tvMeta.setText(e.model + " | " + e.protocol + " | 首字: " + firstStr
                            + " / 总: " + e.totalMs + "ms | 分片: " + e.chunkCount
                            + " | 设备: " + (e.deviceModel.isEmpty() ? "-" : e.deviceModel)
                            + " (TTS=" + e.enableTts + ")");
                    tvMeta.setTextColor(COL_TEXT_LABEL);
                    tvMeta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                    tvMeta.setPadding(0, dp(activity, 3), 0, dp(activity, 4));
                    card.addView(tvMeta);

                    TextView tvQa = new TextView(activity);
                    String ansPreview = (!e.errorMsg.isEmpty() && !CustomAiCallLog.STATUS_SUCCESS.equals(e.status))
                            ? ("[原因: " + CustomAiCallLog.previewSingleLine(e.errorMsg, 70) + "] "
                                + CustomAiCallLog.previewSingleLine(e.response, 70))
                            : CustomAiCallLog.previewSingleLine(e.response, 90);
                    if (ansPreview.trim().isEmpty()) ansPreview = "(无返回文本)";
                    tvQa.setText("问: " + CustomAiCallLog.previewSingleLine(e.query, 64) + "\n答: " + ansPreview);
                    tvQa.setTextColor(Color.rgb(226, 232, 240));
                    tvQa.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                    card.addView(tvQa);

                    TextView tvHint = new TextView(activity);
                    tvHint.setText("点击查看完整返回内容与毫秒事件 ›");
                    tvHint.setTextColor(COL_TEXT_SECONDARY);
                    tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
                    tvHint.setPadding(0, dp(activity, 4), 0, 0);
                    card.addView(tvHint);

                    card.setClickable(true);
                    card.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            showEntryDetailDialog(activity, e);
                        }
                    });

                    LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    cLp.bottomMargin = dp(activity, 8);
                    listContainer.addView(card, cLp);
                }
            }
        };
        renderList.run();

        Button btnRefresh = makePillBtn(activity, "🔄 刷新", COL_BLUE, Color.rgb(96, 165, 250), 11.5f);
        Button btnCopyAll = makePillBtn(activity, "📋 复制全部", COL_SLATE_BTN, COL_BORDER_INPUT, 11.5f);
        Button btnClearAll = makePillBtn(activity, "🗑️ 清空", COL_RED, Color.rgb(220, 38, 38), 11.5f);
        Button btnClose = makePillBtn(activity, "关闭", COL_SLATE_BTN, COL_BORDER_INPUT, 11.5f);

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                renderList.run();
                if (onChanged != null) onChanged.run();
                Toast.makeText(activity, "已刷新调用历史", Toast.LENGTH_SHORT).show();
            }
        });
        btnCopyAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyText(activity, "RayNeoCustomAI_AllLogs", CustomAiCallLog.formatAllEntries(activity),
                        "已复制全部调用记录到剪贴板");
            }
        });
        btnClearAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CustomAiCallLog.clearAll(activity);
                renderList.run();
                if (onChanged != null) onChanged.run();
                Toast.makeText(activity, "已清空调用历史", Toast.LENGTH_SHORT).show();
            }
        });
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                frame.dialog.dismiss();
            }
        });

        LinearLayout.LayoutParams f1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        f1.rightMargin = dp(activity, 6);
        LinearLayout.LayoutParams f2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        f2.rightMargin = dp(activity, 6);
        LinearLayout.LayoutParams f3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f);
        f3.rightMargin = dp(activity, 6);
        LinearLayout.LayoutParams f4 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f);

        frame.footer.addView(btnRefresh, f1);
        frame.footer.addView(btnCopyAll, f2);
        frame.footer.addView(btnClearAll, f3);
        frame.footer.addView(btnClose, f4);

        frame.dialog.show();
    }

    private static void showEntryDetailDialog(final Activity activity, final CustomAiCallLog.Entry entry) {
        if (activity == null || entry == null || activity.isFinishing() || activity.isDestroyed()) return;

        final String detail = CustomAiCallLog.formatEntryDetail(entry);
        final DialogFrame frame = createBaseDialog(
                activity,
                "🔍 调用诊断详情 #" + entry.id,
                entry.channel + "  ·  " + CustomAiCallLog.statusBadge(entry.status),
                0.62f
        );

        LinearLayout codeCard = new LinearLayout(activity);
        codeCard.setOrientation(LinearLayout.VERTICAL);
        codeCard.setPadding(dp(activity, 11), dp(activity, 11), dp(activity, 11), dp(activity, 11));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(COL_BG_CARD);
        gd.setCornerRadius(dp(activity, 10));
        gd.setStroke(dp(activity, 1), COL_BORDER);
        codeCard.setBackground(gd);

        TextView tv = new TextView(activity);
        tv.setText(detail);
        tv.setTextColor(Color.rgb(241, 245, 249));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setLineSpacing(dp(activity, 2), 1f);
        tv.setTextIsSelectable(true);
        codeCard.addView(tv);
        frame.body.addView(codeCard);

        Button btnCopy = makePillBtn(activity, "📋 复制此条详情", COL_BLUE, Color.rgb(96, 165, 250), 12.5f);
        btnCopy.setPadding(dp(activity, 8), dp(activity, 10), dp(activity, 8), dp(activity, 10));
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyText(activity, "RayNeoCustomAI_Log", detail, "已复制该条诊断详情");
            }
        });

        Button btnClose = makePillBtn(activity, "关闭", COL_SLATE_BTN, COL_BORDER_INPUT, 12.5f);
        btnClose.setPadding(dp(activity, 8), dp(activity, 10), dp(activity, 8), dp(activity, 10));
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                frame.dialog.dismiss();
            }
        });

        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f);
        c1.rightMargin = dp(activity, 8);
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        frame.footer.addView(btnCopy, c1);
        frame.footer.addView(btnClose, c2);

        frame.dialog.show();
    }

    // ==================== Reusable Styled Primitives ====================

    private static LinearLayout addCardSection(Context ctx, LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(ctx, 13), dp(ctx, 12), dp(ctx, 13), dp(ctx, 12));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(COL_BG_CARD);
        gd.setCornerRadius(dp(ctx, 12));
        gd.setStroke(dp(ctx, 1), COL_BORDER);
        card.setBackground(gd);

        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.bottomMargin = dp(ctx, 11);
        parent.addView(card, cLp);

        TextView tvHeader = new TextView(ctx);
        tvHeader.setText(title);
        tvHeader.setTextColor(Color.rgb(56, 189, 248));
        tvHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvHeader.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = dp(ctx, 8);
        card.addView(tvHeader, hLp);
        return card;
    }

    private static void addHairlineDivider(Context ctx, LinearLayout parent, int verticalMarginDp) {
        View v = new View(ctx);
        v.setBackgroundColor(COL_BORDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.8f)));
        if (verticalMarginDp > 0) {
            int m = dp(ctx, verticalMarginDp);
            lp.topMargin = m;
            lp.bottomMargin = m;
        }
        parent.addView(v, lp);
    }

    private static void addFieldLabel(Context ctx, LinearLayout parent, String text, boolean topSpace) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(COL_TEXT_LABEL);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topSpace ? dp(ctx, 9) : 0;
        lp.bottomMargin = dp(ctx, 4);
        parent.addView(tv, lp);
    }

    private static EditText makeEdit(Context ctx, String hint, String val, int inputType, boolean singleLine) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(Color.rgb(100, 116, 139));
        et.setText(val == null ? "" : val);
        et.setTextColor(COL_TEXT_PRIMARY);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        et.setInputType(inputType);
        et.setSingleLine(singleLine);
        et.setPadding(dp(ctx, 11), dp(ctx, 9), dp(ctx, 11), dp(ctx, 9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COL_BG_INPUT);
        bg.setCornerRadius(dp(ctx, 8));
        bg.setStroke(dp(ctx, 1), COL_BORDER_INPUT);
        et.setBackground(bg);
        return et;
    }

    private static EditText addGridInput(
            Context ctx, LinearLayout row, String label, String val, boolean isRightCol) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(COL_TEXT_LABEL);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lblLp.bottomMargin = dp(ctx, 3);
        col.addView(tv, lblLp);

        EditText et = makeEdit(ctx, "", val, InputType.TYPE_CLASS_TEXT, true);
        col.addView(et);

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (isRightCol) {
            colLp.leftMargin = dp(ctx, 5);
        } else {
            colLp.rightMargin = dp(ctx, 5);
        }
        row.addView(col, colLp);
        return et;
    }

    private static void addPresetChip(Context ctx, LinearLayout row, String text, final Runnable action) {
        TextView chip = new TextView(ctx);
        chip.setText(text);
        chip.setTextColor(Color.rgb(191, 219, 254));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setPadding(dp(ctx, 8), dp(ctx, 7), dp(ctx, 8), dp(ctx, 7));

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(COL_BG_INPUT);
        gd.setCornerRadius(dp(ctx, 14));
        gd.setStroke(dp(ctx, 1), Color.rgb(59, 130, 246));
        chip.setBackground(gd);

        chip.setClickable(true);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (row.getChildCount() > 0) lp.leftMargin = dp(ctx, 6);
        row.addView(chip, lp);
    }

    private static Button makePillBtn(Context ctx, String text, int fillColor, int strokeColor, float sp) {
        Button b = new Button(ctx);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setCornerRadius(dp(ctx, 9));
        bg.setStroke(dp(ctx, 1), strokeColor);
        b.setBackground(bg);
        b.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
        return b;
    }

    private static void setStatusBanner(Context ctx, TextView tv, int fillColor, int strokeColor, String msg) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(ctx, 8));
        gd.setStroke(dp(ctx, 1), strokeColor);
        tv.setBackground(gd);
        tv.setVisibility(View.VISIBLE);
        tv.setText(msg);
    }

    private static double parseDoubleSafe(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static void copyText(Context ctx, String label, String text, String toastMsg) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
                Toast.makeText(ctx, toastMsg, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {}
    }

    private static int dp(Context ctx, float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }
}
