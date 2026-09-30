package com.winlator.hikariro;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.Random;

// Pantalla de carga de HikariRO (estilo oscuro moderno): ilustracion de carga
// del propio cliente a la derecha, fondo desenfocado y columna de texto a la
// izquierda con el estado, una barra de progreso fina y un consejo.
public class HikariLoadingView extends FrameLayout {
    public static final int ACCENT = 0xFF9BD7FF;
    private static final int ART_COUNT = 12;
    private static final String[] TIPS = {
        "Mantén el dedo quieto un momento y arrastra para mover objetos y ventanas.",
        "Pellizca la pantalla para acercar o alejar la cámara.",
        "Toca con dos dedos a la vez para hacer clic derecho.",
        "Pulsa el ojo de arriba a la derecha para ocultar los botones.",
        "Escribe /mineffect en el chat para reducir efectos en zonas con muchos monstruos.",
        "Sentarte (icono zZ) recupera HP y SP más rápido.",
        "Alt, Ctrl y Shift se quedan activados hasta que los vuelves a pulsar."
    };

    // Misma ilustracion en el lanzador y en la pantalla del juego (mismo proceso).
    private static int artIndex = -1;

    private final TextView statusView;
    private final View progressBar;
    private final ImageView artView;

    public HikariLoadingView(Context context) {
        super(context);
        if (artIndex < 0) artIndex = new Random().nextInt(ART_COUNT);
        setBackgroundColor(0xFF0B0D12);
        setClickable(true);

        // Fondo: la misma ilustracion ya desenfocada y oscurecida
        ImageView background = new ImageView(context);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        background.setImageBitmap(loadAsset(String.format("hikari/loading/%02d_blur.jpg", artIndex)));
        addView(background, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Ilustracion nitida, a la derecha, con esquinas redondeadas
        artView = new ImageView(context);
        artView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artView.setImageBitmap(loadAsset(String.format("hikari/loading/%02d.jpg", artIndex)));
        final float radius = dp(18);
        artView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        artView.setClipToOutline(true);
        artView.setElevation(dp(12));
        addView(artView);

        // Degradado de izquierda a derecha para que el texto se lea bien
        View scrim = new View(context);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xE60B0D12, 0x990B0D12, 0x000B0D12}));
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Columna de texto
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams columnParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.START);
        columnParams.leftMargin = (int)dp(48);
        addView(column, columnParams);

        TextView eyebrow = text(context, "RAGNAROK ONLINE", 11, 0x99FFFFFF, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.25f);
        column.addView(eyebrow);

        TextView title = text(context, "HikariRO", 40, Color.WHITE, Typeface.BOLD);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setPadding(0, (int)dp(4), 0, (int)dp(20));
        column.addView(title);

        // Fila de estado: circulo animado + texto
        LinearLayout statusRow = new LinearLayout(context);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(statusRow);

        SpinnerView spinner = new SpinnerView(context);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams((int)dp(26), (int)dp(26));
        spinnerParams.rightMargin = (int)dp(12);
        statusRow.addView(spinner, spinnerParams);

        statusView = text(context, "Preparando...", 15, 0xDDFFFFFF, Typeface.NORMAL);
        statusRow.addView(statusView);

        // Barra de progreso indeterminada: un tramo de luz que recorre una linea
        FrameLayout track = new FrameLayout(context);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(0x26FFFFFF);
        trackBg.setCornerRadius(dp(2));
        track.setBackground(trackBg);
        track.setClipChildren(true);
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams((int)dp(260), (int)dp(3));
        trackParams.topMargin = (int)dp(14);
        column.addView(track, trackParams);

        progressBar = new View(context);
        GradientDrawable barBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0x009BD7FF, ACCENT, 0x009BD7FF});
        barBg.setCornerRadius(dp(2));
        progressBar.setBackground(barBg);
        track.addView(progressBar, new FrameLayout.LayoutParams((int)dp(90), FrameLayout.LayoutParams.MATCH_PARENT));

        TextView tip = text(context, TIPS[new Random().nextInt(TIPS.length)], 12, 0x88FFFFFF, Typeface.NORMAL);
        tip.setMaxWidth((int)dp(300));
        tip.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams tipParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tipParams.topMargin = (int)dp(28);
        column.addView(tip, tipParams);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Ilustracion 4:3 a la derecha, 82% del alto
        int artHeight = (int)(h * 0.82f);
        int artWidth = artHeight * 4 / 3;
        LayoutParams params = new LayoutParams(artWidth, artHeight, Gravity.END | Gravity.CENTER_VERTICAL);
        params.rightMargin = (int)((h - artHeight) * 0.5f);
        artView.setLayoutParams(params);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Barra: va y viene con aceleracion suave
        progressBar.post(() -> {
            float travel = dp(260) - dp(90);
            progressBar.setTranslationX(-dp(90));
            progressBar.animate().translationX(travel + dp(90)).setDuration(1400)
                .setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .withEndAction(this::restartProgress).start();
        });
        // Ilustracion: acercamiento muy lento (efecto Ken Burns)
        artView.setScaleX(1.0f);
        artView.setScaleY(1.0f);
        artView.animate().scaleX(1.06f).scaleY(1.06f).setDuration(20000).setInterpolator(new LinearInterpolator()).start();
    }

    private void restartProgress() {
        if (!isAttachedToWindow()) return;
        progressBar.setTranslationX(-dp(90));
        progressBar.animate().translationX(dp(260)).setDuration(1400)
            .setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
            .withEndAction(this::restartProgress).start();
    }

    public void setStatus(String status) {
        if (status == null || status.contentEquals(statusView.getText())) return;
        statusView.animate().cancel();
        statusView.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            statusView.setText(status);
            statusView.animate().alpha(1f).setDuration(180).start();
        }).start();
    }

    private boolean dismissing = false;

    public boolean isDismissing() {
        return dismissing;
    }

    // Desvanece y se quita a si misma del padre.
    public void dismiss() {
        if (getParent() == null || dismissing) return;
        dismissing = true;
        animate().alpha(0f).setDuration(350).withEndAction(() -> {
            if (getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup)getParent()).removeView(this);
        }).start();
    }

    // Circulo de carga: un arco que gira sin parar y se estira y encoge
    // (el mismo movimiento que el indicador de Material Design).
    private static class SpinnerView extends View {
        private final android.graphics.Paint trackPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint arcPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF bounds = new android.graphics.RectF();
        private android.animation.ValueAnimator animator;
        private float progress = 0f;
        // Cada vuelta la cola avanza 240 grados; se acumula para que no haya salto.
        private float cycleOffset = 0f;

        SpinnerView(Context context) {
            super(context);
            float stroke = 3 * context.getResources().getDisplayMetrics().density;
            trackPaint.setStyle(android.graphics.Paint.Style.STROKE);
            trackPaint.setStrokeWidth(stroke);
            trackPaint.setColor(0x26FFFFFF);
            arcPaint.setStyle(android.graphics.Paint.Style.STROKE);
            arcPaint.setStrokeWidth(stroke);
            arcPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            arcPaint.setColor(ACCENT);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1600);
            animator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationRepeat(android.animation.Animator animation) {
                    cycleOffset = (cycleOffset + 240f) % 360f;
                }
            });
            animator.addUpdateListener(a -> {
                progress = (float)a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (animator != null) animator.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float inset = arcPaint.getStrokeWidth();
            bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawOval(bounds, trackPaint);
            // giro continuo + arco que crece (0-50%) y se recoge (50-100%)
            float rotation = progress * 360f * 2f;
            float phase = progress < 0.5f ? progress / 0.5f : (1f - progress) / 0.5f;
            float eased = phase * phase * (3f - 2f * phase);
            float sweep = 30f + eased * 240f;
            float start = cycleOffset + rotation + (progress < 0.5f ? 0f : (1f - eased) * 240f) - 90f;
            canvas.drawArc(bounds, start, sweep, false, arcPaint);
        }
    }

    private TextView text(Context context, String value, float sp, int color, int style) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private Bitmap loadAsset(String path) {
        try (InputStream is = getContext().getAssets().open(path)) {
            return BitmapFactory.decodeStream(is);
        }
        catch (Exception e) {
            return null;
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
