package df.root;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Horizontal milestone chain for one run: every stage is a dot on a single
 * track, the caption sits underneath. Three visual states per stage -
 * pending (hollow grey), current (filled accent), done (filled green); a
 * failed run turns the reached stages red.
 *
 * The view knows nothing about the exploit itself: MainActivity maps log
 * markers to stage indices and calls {@link #setStage(int, int)}.
 */
public class StageStepperView extends View {
    private static final int TRACK_H_DP = 2;
    private static final int DOT_R_DP = 7;
    private static final int LABEL_DP = 13;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int pendingColor;
    private final int accentColor;
    private final int doneColor;
    private final int failColor;
    private final int labelColor;
    private final int mutedLabelColor;

    private String[] stages = new String[0];
    private int current = -1;
    private int halted = -1;
    private boolean failed;

    public StageStepperView(Context context) { this(context, null); }

    public StageStepperView(Context context, AttributeSet attrs) {
        super(context, attrs);
        pendingColor = context.getColor(R.color.surface_3);
        accentColor = context.getColor(R.color.accent_cyan);
        doneColor = context.getColor(R.color.success);
        failColor = context.getColor(R.color.error);
        labelColor = context.getColor(R.color.text_primary);
        mutedLabelColor = context.getColor(R.color.text_tertiary);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(LABEL_DP * getResources().getDisplayMetrics().scaledDensity);
    }

    public void setStages(String... labels) {
        stages = labels;
        requestLayout();
        invalidate();
    }

    /** @param index stage that is running now; -1 means nothing started. */
    public void setStage(int index) {
        current = index;
        halted = -1;
        failed = false;
        invalidate();
    }

    /** Stage the chain currently sits on, for callers that need to freeze it. */
    public int currentStage() {
        return halted >= 0 ? halted : current;
    }

    /** Freeze the chain: everything up to {@code index} is done, nothing after. */
    public void stopAt(int index) {
        halted = index;
        invalidate();
    }

    public void setFailed(boolean f) {
        failed = f;
        invalidate();
    }

    public void reset() {
        current = -1;
        halted = -1;
        failed = false;
        invalidate();
    }

    private boolean isDone(int i) {
        return halted >= 0 ? i <= halted : i < current;
    }

    private boolean isCurrent(int i) {
        return halted < 0 && i == current;
    }

    private int dotColor(int i) {
        if (failed && isDone(i)) return failColor;
        if (isDone(i)) return doneColor;
        if (isCurrent(i)) return failed ? failColor : accentColor;
        return pendingColor;
    }

    private float trackLeft() {
        return getPaddingLeft();
    }

    private float trackRight() {
        return getWidth() - getPaddingRight();
    }

    private float dotY(float d) {
        return getPaddingTop() + 10 * d;
    }

    private float centerX(int i, float d) {
        if (stages.length <= 1) return (trackLeft() + trackRight()) / 2f;
        float usable = trackRight() - trackLeft();
        float inset = DOT_R_DP * d;
        float step = (usable - inset * 2f) / (stages.length - 1);
        return trackLeft() + inset + step * i;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        float d = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        int h = (int) (10 * d + DOT_R_DP * d * 2 + 10 * d + LABEL_DP * sp + 4 * d);
        setMeasuredDimension(resolveSize((int) (240 * d), wSpec),
                resolveSize(h + getPaddingTop() + getPaddingBottom(), hSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (stages.length == 0) return;
        float d = getResources().getDisplayMetrics().density;
        float y = dotY(d);
        float r = DOT_R_DP * d;

        // Reached portion of the track is tinted, the rest stays neutral.
        float first = centerX(0, d);
        float lastDone = centerX(Math.max(0, Math.min(stages.length - 1,
                halted >= 0 ? halted : current)), d);
        trackPaint.setStrokeWidth(TRACK_H_DP * d);
        trackPaint.setColor(pendingColor);
        canvas.drawLine(first, y, centerX(stages.length - 1, d), y, trackPaint);
        if (current >= 0 || halted >= 0) {
            trackPaint.setColor(failed ? failColor : (halted >= 0 ? doneColor : accentColor));
            canvas.drawLine(first, y, lastDone, y, trackPaint);
        }

        for (int i = 0; i < stages.length; i++) {
            float cx = centerX(i, d);
            dotPaint.setStyle(Paint.Style.FILL);
            dotPaint.setColor(dotColor(i));
            if (isDone(i) || isCurrent(i)) {
                canvas.drawCircle(cx, y, r, dotPaint);
                if (isDone(i) && !failed) {
                    // Check mark inside the finished dot.
                    dotPaint.setStyle(Paint.Style.STROKE);
                    dotPaint.setStrokeWidth(1.6f * d);
                    dotPaint.setStrokeCap(Paint.Cap.ROUND);
                    dotPaint.setColor(getContext().getColor(R.color.white));
                    float s = r * 0.45f;
                    canvas.drawLine(cx - s, y, cx - s * 0.15f, y + s * 0.7f, dotPaint);
                    canvas.drawLine(cx - s * 0.15f, y + s * 0.7f, cx + s, y - s * 0.6f, dotPaint);
                    dotPaint.setStyle(Paint.Style.FILL);
                    dotPaint.setStrokeCap(Paint.Cap.BUTT);
                } else if (isCurrent(i)) {
                    dotPaint.setStyle(Paint.Style.STROKE);
                    dotPaint.setStrokeWidth(1.5f * d);
                    dotPaint.setColor(getContext().getColor(R.color.white));
                    canvas.drawCircle(cx, y, r * 0.42f, dotPaint);
                    dotPaint.setStyle(Paint.Style.FILL);
                }
            } else {
                dotPaint.setStyle(Paint.Style.STROKE);
                dotPaint.setStrokeWidth(1.5f * d);
                dotPaint.setColor(pendingColor);
                canvas.drawCircle(cx, y, r * 0.7f, dotPaint);
                dotPaint.setStyle(Paint.Style.FILL);
            }

            labelPaint.setColor(isDone(i) || isCurrent(i) ? labelColor : mutedLabelColor);
            float ty = y + r + 10 * d + LABEL_DP * getResources().getDisplayMetrics().scaledDensity * 0.8f;
            canvas.drawText(stages[i], cx, ty, labelPaint);
        }
    }
}
