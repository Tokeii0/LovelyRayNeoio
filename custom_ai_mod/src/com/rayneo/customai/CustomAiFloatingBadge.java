package com.rayneo.customai;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * Resource-free draggable edge-snapping badge attached directly to Activity content view
 * so no SYSTEM_ALERT_WINDOW overlay permission is needed on HyperOS / Xiaomi 17 Ultra.
 */
public final class CustomAiFloatingBadge {

    private static final String BADGE_TAG = "rayneo_custom_ai_badge_view";
    private static final String PREF_Y_RATIO = "badgeYRatio";
    private static final String PREF_RIGHT_SIDE = "badgeRightSide";
    private static volatile WeakReference<Activity> lastActivityRef;

    static {
        CustomAiCallLog.setUiRefreshCallback(new Runnable() {
            @Override
            public void run() {
                refreshActiveBadge();
            }
        });
    }

    private CustomAiFloatingBadge() {}

    public static void refreshActiveBadge() {
        WeakReference<Activity> ref = lastActivityRef;
        Activity act = ref != null ? ref.get() : null;
        if (act != null && !act.isFinishing() && !act.isDestroyed()) {
            attachIfEnabled(act);
        }
    }

    public static void attachIfEnabled(final Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        lastActivityRef = new WeakReference<>(activity);
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    View content = activity.findViewById(android.R.id.content);
                    if (!(content instanceof ViewGroup)) return;
                    final ViewGroup root = (ViewGroup) content;

                    CustomAiConfig cfg = CustomAiConfig.load(activity);
                    View existing = root.findViewWithTag(BADGE_TAG);
                    if (!cfg.showFloatingButton) {
                        if (existing != null) {
                            root.removeView(existing);
                        }
                        return;
                    }

                    final TextView badge;
                    if (existing instanceof TextView) {
                        badge = (TextView) existing;
                    } else {
                        badge = createBadgeView(activity);
                        SharedPreferences sp = activity.getSharedPreferences(CustomAiConfig.PREF_NAME, Context.MODE_PRIVATE);
                        boolean right = sp.getBoolean(PREF_RIGHT_SIDE, true);
                        float yRatio = sp.getFloat(PREF_Y_RATIO, 0.22f);

                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                        );
                        lp.gravity = Gravity.TOP | Gravity.START;
                        root.addView(badge, lp);

                        attachDragBehavior(activity, root, badge, right, yRatio);
                    }
                    styleBadge(activity, badge, cfg);
                    badge.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                int rw = root.getWidth();
                                int bw = badge.getWidth();
                                if (rw <= 0 || bw <= 0) return;
                                SharedPreferences sp = activity.getSharedPreferences(CustomAiConfig.PREF_NAME, Context.MODE_PRIVATE);
                                boolean snapRight = sp.getBoolean(PREF_RIGHT_SIDE, true);
                                int margin = dp(activity, 10);
                                if (snapRight) {
                                    badge.setX(Math.max(margin, rw - bw - margin));
                                } else {
                                    badge.setX(Math.min(badge.getX(), Math.max(margin, rw - bw - margin)));
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                    badge.bringToFront();
                } catch (Throwable ignored) {}
            }
        });
    }

    private static TextView createBadgeView(Activity activity) {
        TextView tv = new TextView(activity);
        tv.setTag(BADGE_TAG);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        int hPad = dp(activity, 10);
        int vPad = dp(activity, 6);
        tv.setPadding(hPad, vPad, hPad, vPad);
        tv.setElevation(dp(activity, 8));
        tv.setClickable(true);
        return tv;
    }

    private static void styleBadge(Context ctx, TextView badge, CustomAiConfig cfg) {
        int bgColor;
        int strokeColor;
        String label;
        String live = cfg.enabled ? CustomAiCallLog.getLiveBadgeLabel() : null;
        if (live != null) {
            bgColor = Color.argb(235, 30, 58, 138);
            strokeColor = Color.rgb(56, 189, 248);
            label = live;
        } else if (!cfg.enabled) {
            bgColor = Color.argb(215, 55, 65, 81);
            strokeColor = Color.rgb(156, 163, 175);
            label = "⚡官方AI";
        } else if (!cfg.hasApiKey()) {
            bgColor = Color.argb(225, 120, 53, 15);
            strokeColor = Color.rgb(251, 191, 36);
            label = "⚡配Key";
        } else {
            bgColor = Color.argb(225, 15, 23, 42);
            strokeColor = Color.rgb(52, 211, 153);
            label = OpenAiApiClient.isResponsesProtocol(cfg.apiProtocol) ? "⚡Resp" : "⚡LLM";
        }

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(ctx, 18));
        gd.setStroke(dp(ctx, 1.5f), strokeColor);
        badge.setBackground(gd);
        badge.setTextColor(Color.WHITE);
        badge.setText(label);
    }

    private static void attachDragBehavior(
            final Activity activity,
            final ViewGroup root,
            final TextView badge,
            final boolean initRight,
            final float initYRatio
    ) {
        root.post(new Runnable() {
            @Override
            public void run() {
                int rw = root.getWidth();
                int rh = root.getHeight();
                int bw = badge.getWidth();
                int bh = badge.getHeight();
                if (rw <= 0 || bw <= 0) return;
                int margin = dp(activity, 10);
                float x = initRight ? Math.max(margin, rw - bw - margin) : margin;
                float y = Math.max(dp(activity, 48), Math.min(rh - bh - dp(activity, 80), rh * initYRatio));
                badge.setX(x);
                badge.setY(y);
            }
        });

        badge.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY, startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = v.getX();
                        startY = v.getY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dx) > dp(activity, 6) || Math.abs(dy) > dp(activity, 6)) {
                            moved = true;
                        }
                        float maxX = Math.max(0, root.getWidth() - v.getWidth());
                        float maxY = Math.max(0, root.getHeight() - v.getHeight());
                        v.setX(Math.max(0f, Math.min(maxX, startX + dx)));
                        v.setY(Math.max(0f, Math.min(maxY, startY + dy)));
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            CustomAiSettingsDialog.show(activity);
                            return true;
                        }
                        int rw = root.getWidth();
                        int rh = Math.max(1, root.getHeight());
                        int margin = dp(activity, 10);
                        boolean snapRight = (v.getX() + v.getWidth() / 2f) >= (rw / 2f);
                        float targetX = snapRight ? Math.max(margin, rw - v.getWidth() - margin) : margin;
                        v.animate().x(targetX).setDuration(160).start();
                        float yRatio = Math.max(0.08f, Math.min(0.85f, v.getY() / rh));
                        activity.getSharedPreferences(CustomAiConfig.PREF_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean(PREF_RIGHT_SIDE, snapRight)
                                .putFloat(PREF_Y_RATIO, yRatio)
                                .apply();
                        return true;
                }
                return false;
            }
        });
    }

    private static int dp(Context ctx, float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }
}
