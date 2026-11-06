package com.ludomadagascar;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Lightweight static Home composition.
 *
 * Gameplay owns the real GLES3/PBR board, pawns and dice. Home deliberately uses
 * one down-sampled RGB565 reference image: no GLSurfaceView, terrain GLB, baobab
 * GLBs, shadow map, cubemap or gameplay renderer is created on this screen.
 */
public final class PremiumHeroViewV2 extends FrameLayout {
    private static final int[] HOME_STAKES = {500, 1000, 2000, 5000, 10000, 20000};
    // Logical 1280x720 coordinates shared by the 16:9 Home reference image.
    private static final float[][] HOME_STAKE_BUTTON_BOUNDS = {
            {136f, 590f, 184f, 622f},
            {189f, 590f, 237f, 622f},
            {242f, 590f, 290f, 622f},
            {295f, 590f, 343f, 622f},
            {181f, 630f, 248f, 660f},
            {255f, 630f, 322f, 660f}
    };
    private static final String HOME_BASE = "LudoMada Premium Home";
    private final HomeImageView homeImage;
    private final View jouerAccessibilityTarget;
    private final View[] stakeAccessibilityTargets = new View[HOME_STAKES.length];

    public PremiumHeroViewV2(Context context) {
        super(context);
        setWillNotDraw(true);
        setClipChildren(true);
        setClipToPadding(true);
        setBackgroundColor(Color.rgb(5, 14, 35));
        setContentDescription(HOME_BASE);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        homeImage = new HomeImageView(context);
        homeImage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        addView(homeImage, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        ));

        // The reference JPG contains old, baked-in stake labels. Transparent native
        // targets keep the visual overlay clickable and accessible on API 27+.
        for (int i = 0; i < HOME_STAKES.length; i++) {
            final int stake = HOME_STAKES[i];
            View target = new View(context);
            target.setBackgroundColor(Color.TRANSPARENT);
            target.setContentDescription("HOME_STAKE_" + stake);
            target.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            target.setFocusable(true);
            target.setClickable(true);
            target.setOnClickListener(v -> selectHomeStake(stake));
            stakeAccessibilityTargets[i] = target;
            addView(target, new FrameLayout.LayoutParams(
                    1, 1, android.view.Gravity.TOP | android.view.Gravity.LEFT
            ));
        }

        // Expose the primary visual hitbox as a real, invisible accessibility
        // target. This keeps touch coordinates and assistive/cloud automation
        // aligned with the exact same bitmap destination rectangle.
        jouerAccessibilityTarget = new View(context);
        jouerAccessibilityTarget.setContentDescription("HOME_ACTION_JOUER");
        jouerAccessibilityTarget.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        jouerAccessibilityTarget.setFocusable(true);
        jouerAccessibilityTarget.setClickable(true);
        jouerAccessibilityTarget.setOnClickListener(v -> call("gameModeDialog"));
        addView(jouerAccessibilityTarget, new FrameLayout.LayoutParams(
                1, 1, android.view.Gravity.TOP | android.view.Gravity.LEFT
        ));
    }

    public View getHomeImageView() {
        return homeImage;
    }

    public View getJouerAccessibilityTarget() {
        return jouerAccessibilityTarget;
    }

    public View getStakeAccessibilityTarget(int amount) {
        for (int i = 0; i < HOME_STAKES.length; i++) {
            if (HOME_STAKES[i] == amount) return stakeAccessibilityTargets[i];
        }
        return null;
    }

    private void selectHomeStake(int amount) {
        call("selectHomeStake", amount);
        homeImage.invalidate();
        updateStakeAccessibilityTargets();
    }

    private void updateStakeAccessibilityTargets() {
        int selected = homeImage.getSelectedStake();
        for (int i = 0; i < HOME_STAKES.length; i++) {
            RectF bounds = homeImage.getStakeButtonBounds(i);
            int left = Math.max(0, Math.round(bounds.left));
            int top = Math.max(0, Math.round(bounds.top));
            int right = Math.min(getWidth(), Math.round(bounds.right));
            int bottom = Math.min(getHeight(), Math.round(bounds.bottom));
            View target = stakeAccessibilityTargets[i];
            if (right <= left || bottom <= top) {
                target.setVisibility(View.INVISIBLE);
                target.layout(0, 0, 0, 0);
                continue;
            }
            target.setVisibility(View.VISIBLE);
            target.layout(left, top, right, bottom);
            target.setSelected(selected == HOME_STAKES[i]);
            target.setContentDescription("HOME_STAKE_" + HOME_STAKES[i]);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        RectF bounds = homeImage.getGameModeHitboxBounds();
        int targetLeft = Math.max(0, Math.round(bounds.left));
        int targetTop = Math.max(0, Math.round(bounds.top));
        int targetRight = Math.min(getWidth(), Math.round(bounds.right));
        int targetBottom = Math.min(getHeight(), Math.round(bounds.bottom));
        if (targetRight <= targetLeft || targetBottom <= targetTop) {
            jouerAccessibilityTarget.setVisibility(View.INVISIBLE);
            jouerAccessibilityTarget.layout(0, 0, 0, 0);
            return;
        }
        jouerAccessibilityTarget.setVisibility(View.VISIBLE);
        jouerAccessibilityTarget.layout(targetLeft, targetTop, targetRight, targetBottom);
        updateStakeAccessibilityTargets();
    }

    private Activity activityContext() {
        Context current = getContext();
        while (current instanceof ContextWrapper && !(current instanceof Activity)) {
            current = ((ContextWrapper) current).getBaseContext();
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    private void call(String methodName) {
        Activity activity = activityContext();
        if (activity == null) {
            Log.w("LudoMadaHome", "HOME_ACTION_NO_ACTIVITY name=" + methodName);
            return;
        }
        try {
            Method method = activity.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(activity);
        } catch (Throwable error) {
            Log.e("LudoMadaHome", "HOME_ACTION_FAILED name=" + methodName, error);
        }
    }

    private void call(String methodName, int argument) {
        Activity activity = activityContext();
        if (activity == null) {
            Log.w("LudoMadaHome", "HOME_ACTION_NO_ACTIVITY name=" + methodName);
            return;
        }
        try {
            Method method = activity.getClass().getDeclaredMethod(methodName, int.class);
            method.setAccessible(true);
            method.invoke(activity, argument);
        } catch (Throwable error) {
            Log.e("LudoMadaHome", "HOME_ACTION_FAILED name=" + methodName, error);
        }
    }

    private String readString(String fieldName) {
        Activity activity = activityContext();
        if (activity == null) return "";
        try {
            java.lang.reflect.Field field = activity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(activity);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private long readLong(String fieldName) {
        Activity activity = activityContext();
        if (activity == null) return 0L;
        try {
            java.lang.reflect.Field field = activity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(activity);
            return value instanceof Number ? ((Number) value).longValue() : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private boolean readBoolean(String methodName) {
        Activity activity = activityContext();
        if (activity == null) return false;
        try {
            Method method = activity.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return Boolean.TRUE.equals(method.invoke(activity));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private final class HomeImageView extends View {
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap reference;
        private Rect sourceRect;
        private final RectF destination = new RectF();
        private String readinessMarker = "HOME_IMAGE_MISSING";
        private boolean firstFramePublished;

        HomeImageView(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            loadReference();
        }

        private void loadReference() {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(getResources(),
                    R.drawable.ludomada_lobby_reference_4k, bounds);

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                reference = null;
                readinessMarker = "HOME_IMAGE_MISSING";
                return;
            }

            // Keep the decoded image at or below roughly 2.5K × 1.4K.
            // RGB565 halves decoded memory compared with ARGB8888.
            int sample = 1;
            while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 1440) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inDither = true;
            options.inScaled = false;
            try {
                reference = BitmapFactory.decodeResource(getResources(),
                        R.drawable.ludomada_lobby_reference_4k, options);
            } catch (OutOfMemoryError error) {
                Log.e("LudoMadaHome", "HOME_IMAGE_DECODE_OOM sample=" + sample, error);
                reference = null;
            }

            if (reference == null || reference.isRecycled()) {
                readinessMarker = "HOME_IMAGE_MISSING";
                reference = null;
                return;
            }

            int width = reference.getWidth();
            int height = reference.getHeight();
            sourceRect = new Rect(
                    Math.round(width * 0.005f),
                    Math.round(height * 0.040f),
                    Math.round(width * 0.982f),
                    Math.round(height * 0.975f)
            );
            readinessMarker = "HOME_IMAGE_FRAME_COMPLETE"
                    + " | IMAGE_SOURCE=LOBBY_REFERENCE_4K"
                    + " | IMAGE_WIDTH=" + width
                    + " | IMAGE_HEIGHT=" + height
                    + " | IMAGE_CONFIG=RGB_565";
            firstFramePublished = false;
            invalidate();
        }

        int getSelectedStake() {
            long value = PremiumHeroViewV2.this.readLong("entryFee");
            for (int stake : HOME_STAKES) {
                if (stake == value) return stake;
            }
            return HOME_STAKES[0];
        }

        RectF getStakeButtonBounds(int index) {
            updateDestination();
            if (index < 0 || index >= HOME_STAKE_BUTTON_BOUNDS.length) return new RectF();
            float scaleX = destination.width() / 1280f;
            float scaleY = destination.height() / 720f;
            float[] logical = HOME_STAKE_BUTTON_BOUNDS[index];
            return new RectF(
                    destination.left + logical[0] * scaleX,
                    destination.top + logical[1] * scaleY,
                    destination.left + logical[2] * scaleX,
                    destination.top + logical[3] * scaleY
            );
        }

        RectF getGameModeHitboxBounds() {
            updateDestination();
            float scaleX = destination.width() / 1280f;
            float scaleY = destination.height() / 720f;
            return new RectF(
                    destination.left + 975f * scaleX,
                    destination.top + 345f * scaleY,
                    destination.left + 1245f * scaleX,
                    destination.top + 485f * scaleY
            );
        }

        private void updateDestination() {
            if (reference == null || sourceRect == null || getWidth() <= 0 || getHeight() <= 0) {
                destination.set(0f, 0f, getWidth(), getHeight());
                return;
            }
            float scale = Math.max(
                    getWidth() / (float) sourceRect.width(),
                    getHeight() / (float) sourceRect.height()
            );
            float drawnWidth = sourceRect.width() * scale;
            float drawnHeight = sourceRect.height() * scale;
            float left = (getWidth() - drawnWidth) * 0.5f;
            float top = (getHeight() - drawnHeight) * 0.5f;
            destination.set(left, top, left + drawnWidth, top + drawnHeight);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (reference == null || sourceRect == null || reference.isRecycled()) {
                canvas.drawColor(Color.rgb(5, 14, 35));
                overlayPaint.setColor(Color.WHITE);
                overlayPaint.setTextSize(24f * getResources().getDisplayMetrics().density);
                canvas.drawText("LudoMada", 24f, 48f, overlayPaint);
                if (!firstFramePublished) {
                    firstFramePublished = true;
                    final String missing = HOME_BASE + " | HOME_IMAGE_MISSING";
                    setContentDescription(missing);
                    PremiumHeroViewV2.this.setContentDescription(missing);
                    Log.e("LudoMadaHome", "HOME_IMAGE_MISSING");
                }
                return;
            }

            updateDestination();
            canvas.drawBitmap(reference, sourceRect, destination, imagePaint);

            // Only live identity and account balance are redrawn over the reference.
            // Decorative layers, board art and the Madagascar background stay in the
            // optimized source bitmap and cost no GL draw calls.
            canvas.save();
            canvas.translate(destination.left, destination.top);
            canvas.scale(destination.width() / 1280f, destination.height() / 720f);
            drawLiveAccountFields(canvas);
            drawHomeStakeSelector(canvas);
            canvas.restore();

            if (!firstFramePublished) {
                firstFramePublished = true;
                Log.i("LudoMadaHome", readinessMarker);
            }
            String accessibleState = readinessMarker + " | HOME_SELECTED_STAKE=" + getSelectedStake();
            setContentDescription(accessibleState);
            PremiumHeroViewV2.this.setContentDescription(HOME_BASE + " | " + accessibleState);
        }

        private void drawLiveAccountFields(Canvas canvas) {
            String username = readString("username").trim();
            if (username.isEmpty()) username = "Vahiny";
            if (username.length() > 18) username = username.substring(0, 18) + "…";
            long balance = readLong("accountBalanceMGA");

            overlayPaint.setStyle(Paint.Style.FILL);
            overlayPaint.setShader(new android.graphics.LinearGradient(
                    0f, 38f, 0f, 102f,
                    0xFF102A4D, 0xE613315C, android.graphics.Shader.TileMode.CLAMP
            ));
            canvas.drawRoundRect(105f, 43f, 520f, 104f, 18f, 18f, overlayPaint);
            overlayPaint.setShader(null);
            overlayPaint.setStyle(Paint.Style.STROKE);
            overlayPaint.setStrokeWidth(1.2f);
            overlayPaint.setColor(0x66FFFFFF);
            canvas.drawRoundRect(105f, 43f, 520f, 104f, 18f, 18f, overlayPaint);
            overlayPaint.setStyle(Paint.Style.FILL);
            drawText(canvas, username, 165f, 74f, 17f, Color.WHITE, true);
            drawText(canvas, "LudoMada • Joue • Gagne • Grandis",
                    165f, 94f, 9.5f, 0xD7FFFFFF, false);

            overlayPaint.setShader(new android.graphics.LinearGradient(
                    0f, 44f, 0f, 104f,
                    0xFF112B50, 0xE616315E, android.graphics.Shader.TileMode.CLAMP
            ));
            canvas.drawRoundRect(920f, 45f, 1103f, 102f, 16f, 16f, overlayPaint);
            overlayPaint.setShader(null);
            drawText(canvas, "SOLDE", 944f, 67f, 8.5f, 0xFFFFE07A, true);
            drawText(canvas, formatBalance(balance) + " Ar",
                    944f, 88f, 13.5f, Color.WHITE, true);
            drawText(canvas, readBoolean("musicEnabledFromHome") ? "♫" : "♪",
                    1193f, 86f, 17f, Color.WHITE, true);
        }

        private void drawHomeStakeSelector(Canvas canvas) {
            int selected = getSelectedStake();

            // Replace only the stale stake selector embedded in the reference JPG.
            overlayPaint.setShader(null);
            overlayPaint.setStyle(Paint.Style.FILL);
            overlayPaint.setColor(0xF20B2040);
            canvas.drawRoundRect(118f, 544f, 358f, 668f, 17f, 17f, overlayPaint);
            overlayPaint.setStyle(Paint.Style.STROKE);
            overlayPaint.setStrokeWidth(1.2f);
            overlayPaint.setColor(0x66FFFFFF);
            canvas.drawRoundRect(118f, 544f, 358f, 668f, 17f, 17f, overlayPaint);
            overlayPaint.setStyle(Paint.Style.FILL);
            drawCenteredText(canvas, "MISE", 238f, 574f, 12f, 0xFFFFE39A, true);

            String[] labels = {"500", "1K", "2K", "5K", "10K", "20K"};
            for (int i = 0; i < HOME_STAKES.length; i++) {
                float[] bounds = HOME_STAKE_BUTTON_BOUNDS[i];
                boolean active = selected == HOME_STAKES[i];
                RectF chip = new RectF(bounds[0], bounds[1], bounds[2], bounds[3]);
                overlayPaint.setStyle(Paint.Style.FILL);
                overlayPaint.setColor(active ? 0xFFF0B72D : 0xFF1C4776);
                canvas.drawRoundRect(chip, 8f, 8f, overlayPaint);
                overlayPaint.setStyle(Paint.Style.STROKE);
                overlayPaint.setStrokeWidth(1f);
                overlayPaint.setColor(active ? 0xFFFFE08A : 0xFF7194B8);
                canvas.drawRoundRect(chip, 8f, 8f, overlayPaint);
                drawCenteredText(
                        canvas, labels[i], (bounds[0] + bounds[2]) * 0.5f,
                        bounds[1] + 21f, 11.5f,
                        active ? 0xFF142846 : Color.WHITE, true
                );
            }

            // The old "Mise actuelle" value is baked into the JPG too. Cover that
            // small region and redraw the live value from MainActivity.entryFee.
            overlayPaint.setStyle(Paint.Style.FILL);
            overlayPaint.setColor(0xF20D2445);
            canvas.drawRoundRect(1005f, 297f, 1167f, 371f, 11f, 11f, overlayPaint);
            drawCenteredText(canvas, "Mise actuelle", 1086f, 324f, 10.5f, 0xFFE7EEF8, false);
            drawCenteredText(
                    canvas, formatBalance(selected) + " Ar", 1086f, 353f,
                    17f, 0xFFFFDA71, true
            );
        }

        private void drawCenteredText(Canvas canvas, String value, float centerX, float baselineY,
                                      float size, int color, boolean bold) {
            overlayPaint.setShader(null);
            overlayPaint.setStyle(Paint.Style.FILL);
            overlayPaint.setColor(color);
            overlayPaint.setTextSize(size);
            overlayPaint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            canvas.drawText(value, centerX - overlayPaint.measureText(value) * 0.5f,
                    baselineY, overlayPaint);
        }

        private void drawText(Canvas canvas, String value, float x, float y,
                              float size, int color, boolean bold) {
            overlayPaint.setShader(null);
            overlayPaint.setStyle(Paint.Style.FILL);
            overlayPaint.setColor(color);
            overlayPaint.setTextSize(size);
            overlayPaint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            canvas.drawText(value, x, y, overlayPaint);
        }

        private String formatBalance(long value) {
            return String.format(Locale.getDefault(), "%,d", value).replace(',', ' ');
        }

        private String actionFor(float x, float y) {
            if (x >= 975f && x <= 1245f && y >= 345f && y <= 485f) return "gameModeDialog";
            if (x >= 1125f && x <= 1255f && y >= 30f && y <= 135f) return "toggleMusicFromHome";
            if (x >= 55f && x <= 340f && y >= 300f && y <= 435f) return "wallet";
            if (x >= 75f && x <= 455f && y >= 150f && y <= 285f) return "joinDialog";
            if (x >= 75f && x <= 455f && y >= 455f && y <= 590f) return "profile";
            if (y >= 630f && y <= 720f) {
                if (x < 300f) return "home";
                if (x < 560f) return "gameModeDialog";
                if (x < 820f) return "events";
                if (x < 1050f) return "social";
                return "shop";
            }
            return null;
        }

        private float[] logicalPoint(float x, float y) {
            float width = Math.max(1f, getWidth());
            float height = Math.max(1f, getHeight());
            if (width >= height) {
                updateDestination();
                return new float[]{
                        (x - destination.left) / Math.max(1f, destination.width()) * 1280f,
                        (y - destination.top) / Math.max(1f, destination.height()) * 720f
                };
            }
            float nx = x / width;
            float ny = y / height;
            float[] clockwise = new float[]{(1f - ny) * 1280f, nx * 720f};
            if (actionFor(clockwise[0], clockwise[1]) != null) return clockwise;
            return new float[]{ny * 1280f, (1f - nx) * 720f};
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float[] point = logicalPoint(event.getX(), event.getY());
                String action = actionFor(point[0], point[1]);
                if (action != null) {
                    performClick();
                    call(action);
                }
                return true;
            }
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (reference != null && !reference.isRecycled()) reference.recycle();
            reference = null;
            sourceRect = null;
            firstFramePublished = false;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (reference == null) loadReference();
        }
    }
}
