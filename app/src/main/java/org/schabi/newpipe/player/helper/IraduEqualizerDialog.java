package org.schabi.newpipe.player.helper;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.schabi.newpipe.R;
import org.schabi.newpipe.player.Player;

public final class IraduEqualizerDialog {
    private static final int COLOR_BACKGROUND_TOP = 0xFF16051F;
    private static final int COLOR_BACKGROUND_BOTTOM = 0xFF04080F;
    private static final int COLOR_TEXT = 0xFFF7EEFF;
    private static final int COLOR_TEXT_MUTED = 0xFFB6A9C7;
    private static final int COLOR_PINK = 0xFFFF0A7A;
    private static final int COLOR_BLUE = 0xFF58D8FF;
    private static final int COLOR_PURPLE = 0xFF9B5CFF;
    private static final int COLOR_GREEN = 0xFF2DFFB3;

    private IraduEqualizerDialog() {
    }

    public static void show(@NonNull final Context context, @NonNull final Player player) {
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackground(createPanelBackground(context));
        final int padding = dp(context, 22);
        content.setPadding(padding, dp(context, 18), padding, dp(context, 12));

        final TextView title = new TextView(context);
        title.setText(R.string.iradu_equalizer);
        title.setTextSize(24);
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        content.addView(title);

        final TextView subtitle = new TextView(context);
        subtitle.setText("Som turbinado para o Iradu Music");
        subtitle.setTextSize(13);
        subtitle.setTextColor(COLOR_TEXT_MUTED);
        subtitle.setPadding(0, dp(context, 2), 0, dp(context, 12));
        content.addView(subtitle);

        final SoundBarsView soundBarsView = new SoundBarsView(context);
        content.addView(soundBarsView);

        final TextView turbo = new TextView(context);
        turbo.setText(R.string.iradu_equalizer_turbo);
        turbo.setTextSize(15);
        turbo.setTextColor(COLOR_PINK);
        turbo.setTypeface(turbo.getTypeface(), android.graphics.Typeface.BOLD);
        turbo.setPadding(0, dp(context, 14), 0, dp(context, 10));
        content.addView(turbo);

        final EqualizerRow volume = addRow(context, content, R.string.iradu_equalizer_volume,
                1, 200, player.getIraduVolumePercent(), COLOR_PINK);
        final EqualizerRow bass = addRow(context, content, R.string.iradu_equalizer_bass,
                -100, 100, player.getIraduBassLevel(), COLOR_BLUE);
        final EqualizerRow treble = addRow(context, content, R.string.iradu_equalizer_treble,
                -100, 100, player.getIraduTrebleLevel(), COLOR_GREEN);
        final EqualizerRow distortion = addRow(context, content,
                R.string.iradu_equalizer_distortion, -100, 100,
                player.getIraduDistortionLevel(), COLOR_PURPLE);
        final EqualizerRow noise = addRow(context, content, R.string.iradu_equalizer_noise,
                -100, 100, player.getIraduNoiseLevel(), 0xFFFFC857);

        soundBarsView.setEqualizerLevels(player.getIraduBassLevel(), player.getIraduTrebleLevel(),
                player.getIraduVolumePercent());

        volume.seekBar.setOnSeekBarChangeListener(listener(volume, value -> {
            player.setIraduVolumePercent(value);
            soundBarsView.setEqualizerLevels(player.getIraduBassLevel(),
                    player.getIraduTrebleLevel(), value);
        }));
        bass.seekBar.setOnSeekBarChangeListener(listener(bass, value -> {
            player.setIraduBassLevel(value);
            soundBarsView.setEqualizerLevels(value, player.getIraduTrebleLevel(),
                    player.getIraduVolumePercent());
        }));
        treble.seekBar.setOnSeekBarChangeListener(listener(treble, value -> {
            player.setIraduTrebleLevel(value);
            soundBarsView.setEqualizerLevels(player.getIraduBassLevel(), value,
                    player.getIraduVolumePercent());
        }));
        distortion.seekBar.setOnSeekBarChangeListener(listener(distortion, value ->
                player.setIraduDistortionLevel(value)));
        noise.seekBar.setOnSeekBarChangeListener(listener(noise, value ->
                player.setIraduNoiseLevel(value)));

        turbo.setOnClickListener(v -> {
            player.setIraduVolumePercent(160);
            player.setIraduBassLevel(70);
            player.setIraduTrebleLevel(45);
            player.setIraduDistortionLevel(30);
            player.setIraduNoiseLevel(-35);
            volume.setValue(player.getIraduVolumePercent());
            bass.setValue(player.getIraduBassLevel());
            treble.setValue(player.getIraduTrebleLevel());
            distortion.setValue(player.getIraduDistortionLevel());
            noise.setValue(player.getIraduNoiseLevel());
            soundBarsView.setEqualizerLevels(player.getIraduBassLevel(),
                    player.getIraduTrebleLevel(), player.getIraduVolumePercent());
        });

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        final Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        final Button okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (okButton != null) {
            okButton.setTextColor(COLOR_BLUE);
        }
        dialog.setOnDismissListener(ignored -> soundBarsView.stopAnimation());
    }

    @NonNull
    private static EqualizerRow addRow(@NonNull final Context context,
                                       @NonNull final LinearLayout content,
                                       final int labelRes,
                                       final int min,
                                       final int max,
                                       final int value,
                                       final int accentColor) {
        final EqualizerRow row = new EqualizerRow(context, labelRes, min, max, accentColor);
        content.addView(row.label);
        content.addView(row.seekBar);
        row.setValue(value);
        return row;
    }

    @NonNull
    private static SeekBar.OnSeekBarChangeListener listener(
            @NonNull final EqualizerRow row,
            @NonNull final ValueConsumer consumer) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(final SeekBar seekBar,
                                          final int progress,
                                          final boolean fromUser) {
                if (fromUser) {
                    final int value = progress + row.min;
                    row.setValue(value);
                    consumer.accept(value);
                }
            }

            @Override
            public void onStartTrackingTouch(final SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(final SeekBar seekBar) {
            }
        };
    }

    private static int dp(@NonNull final Context context, final int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @NonNull
    private static GradientDrawable createPanelBackground(@NonNull final Context context) {
        final GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {COLOR_BACKGROUND_TOP, COLOR_BACKGROUND_BOTTOM});
        background.setCornerRadius(dp(context, 24));
        background.setStroke(dp(context, 1), 0x55FF0A7A);
        return background;
    }

    private interface ValueConsumer {
        void accept(int value);
    }

    private static final class SoundBarsView extends android.view.View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator animator;
        private final int[] colors = {
                COLOR_PINK, COLOR_BLUE, COLOR_PURPLE, COLOR_GREEN,
                0xFFFFC857, COLOR_PINK, COLOR_BLUE
        };
        private final float[] heights = {0.42f, 0.84f, 0.62f, 1f, 0.55f, 0.74f, 0.36f};
        private float phase;
        private float bassBoost = 0.5f;
        private float trebleBoost = 0.5f;
        private float volumeBoost = 0.5f;

        private SoundBarsView(@NonNull final Context context) {
            super(context);
            setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 74)));
            animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
            animator.setDuration(900);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(animation -> {
                phase = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        private void setEqualizerLevels(final int bass, final int treble, final int volume) {
            bassBoost = normalizeSigned(bass);
            trebleBoost = normalizeSigned(treble);
            volumeBoost = Math.max(0.08f, Math.min(1f, volume / 200f));
            invalidate();
        }

        private float normalizeSigned(final int value) {
            return (Math.max(-100, Math.min(100, value)) + 100) / 200f;
        }

        private void stopAnimation() {
            animator.cancel();
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            super.onDraw(canvas);
            final float width = getWidth();
            final float bottom = getHeight() - dp(getContext(), 8);
            final float barWidth = width / 11f;
            final float gap = barWidth * 0.45f;
            float left = barWidth;
            for (int i = 0; i < heights.length; i++) {
                paint.setColor(colors[i]);
                paint.setShadowLayer(dp(getContext(), 8), 0, 0, colors[i]);
                final float bassWeight = i < 3 ? bassBoost : 0.45f;
                final float trebleWeight = i > 3 ? trebleBoost : 0.45f;
                final float wave = (float) Math.sin(phase + i * 0.95f);
                final float reactiveHeight = heights[i] * (0.42f + volumeBoost)
                        + wave * 0.18f + bassWeight * 0.2f + trebleWeight * 0.18f;
                final float clampedHeight = Math.max(0.18f, Math.min(1f, reactiveHeight));
                final float top = bottom - (getHeight() - dp(getContext(), 16)) * clampedHeight;
                canvas.drawRoundRect(left, top, left + barWidth, bottom,
                        dp(getContext(), 8), dp(getContext(), 8), paint);
                left += barWidth + gap;
            }
        }
    }

    private static final class EqualizerRow {
        private final Context context;
        private final int labelRes;
        private final int min;
        private final TextView label;
        private final SeekBar seekBar;

        private EqualizerRow(@NonNull final Context context,
                             final int labelRes,
                             final int min,
                             final int max,
                             final int accentColor) {
            this.context = context;
            this.labelRes = labelRes;
            this.min = min;
            label = new TextView(context);
            label.setTextColor(COLOR_TEXT);
            label.setTextSize(15);
            label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
            seekBar = new SeekBar(context);
            seekBar.setMax(max - min);
            seekBar.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 34)));
            seekBar.setProgressTintList(ColorStateList.valueOf(accentColor));
            seekBar.setThumbTintList(ColorStateList.valueOf(accentColor));
            seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(0x44FFFFFF));
        }

        private void setValue(final int value) {
            label.setText(context.getString(labelRes, value));
            seekBar.setProgress(value - min);
        }
    }
}
