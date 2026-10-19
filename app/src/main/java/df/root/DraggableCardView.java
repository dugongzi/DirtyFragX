package df.root;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;

import com.google.android.material.card.MaterialCardView;

/**
 * OneUI card that lives inside a CardStackLayout. It owns nothing but looks:
 * the lifted look (shaded white fill, thin white outline, a shadow to sit on),
 * the slight black-out of the cards that are NOT being moved, and the small
 * shrink of the cards that have to give up their slot. There is no handle to
 * grab: hold anywhere on the card and it lifts, the way a home screen icon does.
 *
 * The gesture belongs to CardStackLayout, which is why this class only answers
 * "what should I look like?". The lifted look arrives with the lift and leaves
 * with the selection. It is deliberately not faded in or charged up first: a
 * highlight that starts animating the moment the finger lands looks like a
 * glitch and says nothing about which card is about to move.
 */
public class DraggableCardView extends MaterialCardView {

    private static final int REST_BG = 0xFF1C1C1C, HELD_BG = 0xFF3A3A3C;
    private static final int HELD_STROKE = 0xFFFFFFFF;
    private static final float STROKE_W_DP = 1.2f;
    /** How far the lifted card floats above the stack. */
    private static final float LIFT_Z_DP = 18f;
    /** "Slight black out" of the cards that are not being moved (VIS1.6). */
    private static final float DIM_ALPHA = 0.42f;
    /** Cards that make room shrink a little in every axis (VIS3.1). */
    private static final float PUSHED_SCALE = 0.94f;
    private static final long VISUAL_MS = 150;

    private final float density;
    private final int maxStroke;
    private final float liftZ;

    private boolean held, dimmed, pushed;
    private boolean hideable = true;

    public DraggableCardView(Context context) {
        this(context, null);
    }

    public DraggableCardView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DraggableCardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
        maxStroke = (int) (STROKE_W_DP * density);
        liftZ = LIFT_Z_DP * density;
        // The outline is always white; the rest state keeps a width of zero, so
        // the card stays the flat card the app has always had.
        setStrokeColor(ColorStateList.valueOf(HELD_STROKE));
        setCardBackgroundColor(REST_BG);
    }

    /** Held = lifted card: shaded white fill + thin white outline (VIS1.3). */
    public void setHeld(boolean held) {
        if (this.held == held) return;
        this.held = held;
        setCardBackgroundColor(held ? HELD_BG : REST_BG);
        setStrokeWidth(held ? maxStroke : 0);
        setTranslationZ(held ? liftZ : 0f);
        invalidate();
    }

    public boolean isHeld() {
        return held;
    }

    /** Slight black-out so the lifted card reads as the selection (VIS1.6). */
    public void setDimmed(boolean dimmed) {
        if (this.dimmed == dimmed) return;
        this.dimmed = dimmed;
        animate().alpha(dimmed ? DIM_ALPHA : 1f).setDuration(VISUAL_MS).start();
    }

    /** Cards pushed out of the slot shrink a touch, then bounce back. */
    public void setPushed(boolean pushed) {
        if (this.pushed == pushed) return;
        this.pushed = pushed;
        float s = pushed ? PUSHED_SCALE : 1f;
        animate().scaleX(s).scaleY(s).setDuration(VISUAL_MS).start();
    }

    /** The SU Manager card moves but can never be hidden. */
    public boolean isHideable() {
        return hideable;
    }

    public void setHideable(boolean hideable) {
        this.hideable = hideable;
    }
}
