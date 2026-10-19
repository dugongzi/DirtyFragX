package df.root;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertical stack of DraggableCardView cards the user can reorder (VIS1-VIS4).
 *
 * A tap is just a tap - the switches and the manager row keep working exactly as
 * before, and a tap never lifts or selects anything. The stack takes every press
 * that lands on a card so that nothing inside a card can steal it: when the
 * press turns out to be a tap it is replayed to the control under the finger,
 * which is the only way a card can be held without the SU manager popup opening
 * under the finger halfway through. Hold a beat and the card comes off the stack
 * the way a home screen icon does, snaps to sit centred under the finger and
 * sticks there until the finger is lifted, so one continuous hold-and-move
 * gesture is enough. The trip is clamped to the stack's own bounds, so the card
 * is never cut off at the top or the bottom - the first and last slots are the
 * ends of that travel, and they stay reachable. Waiting tolerates the wobble a
 * thumb makes; travelling far reads as a swipe and leaves the cards alone, which
 * is what keeps the log scrollable. The other cards black out slightly, the ones
 * sitting in the target slot shrink and bounce out of the way - with a
 * keyboard-style tick every time one has to slide under the card being moved -
 * and the lifted card rides over them on a shadow. Let go and the new order is
 * committed with a short settle; the card keeps its outline until a tap outside
 * it accepts the layout, and holding a card again before that just lifts it. The
 * activity persists the order and drives the log black-out through the Listener
 * callbacks.
 */
public class CardStackLayout extends LinearLayout {

    /** Drag lifecycle, so MainActivity can react (log, hide button, prefs). */
    public interface Listener {
        /** Card about to come off the stack: put the log away, show the hide
         *  button. Called BEFORE the stack measures the slots the drag is about
         *  to move cards between, so whatever this does to the layout - closing
         *  the log window is the one - is already reflected in them. */
        void onCardLifted(View card);

        /** Every drag frame: the hide button has to follow the card. */
        void onCardMoved(View card);

        /** Card dropped into its slot: the log comes back. */
        void onCardDropped(View card, boolean moved);

        /** Outline cleared (tap outside / hide): the accept overlay is done. */
        void onCardSelectionCleared();
    }

    private static final long MOVE_MS = 210;
    private static final long SETTLE_MS = 190;
    /** How long the lifted card takes to snap under the finger. */
    private static final long SNAP_MS = 130;
    /** Hold anywhere on a card this long and it lifts (VIS1.1). */
    private static final long HOLD_MS = 400;
    private static final long COLLAPSE_MS = 220;
    private static final float BOUNCE = 2.4f;
    /** Default gap between two cards; matches the XML top margins. */
    private static final float GAP_DP = 12f;
    /**
     * Z the stack borrows while a card is in the air. The run/share pills sit on
     * 12dp and the stack on 10dp, so without this a lifted card could slide
     * behind them; it stays under the hide badge (26dp) and the accept sheet.
     */
    private static final float STACK_LIFT_Z_DP = 14f;

    private final float density;
    private final int gap;
    /** How far the finger may wander during a hold before it reads as a swipe. */
    private final float holdSlop;

    private Listener listener;

    /** The hide badge is not part of the stack - it lives in the activity's
     *  root layout, where it can straddle a card's corner. While a card is
     *  selected the stack swallows every touch to accept the layout, so it is
     *  told where the badge sits and lets those touches through instead. */
    private final android.graphics.RectF keepTouch = new android.graphics.RectF();

    /** Area, in this layout's coordinates, the stack must not steal. */
    public void setKeepTouchArea(float l, float t, float r, float b) {
        keepTouch.set(l, t, r, b);
    }

    public void clearKeepTouchArea() {
        keepTouch.setEmpty();
    }

    /** The card the log panel hangs off (VIS4). The stack keeps a window of room
     *  below it, so the log has somewhere to show through and the card after it
     *  is pushed down to make space for it. */
    private View logCard;
    private int logGap;

    public void setLogCard(View card) {
        logCard = card;
        requestLayout();
    }

    /** Height of that window in pixels; 0 takes it out of the layout again. */
    public void setLogGap(int px) {
        int want = Math.max(0, px);
        if (want == logGap) return;
        boolean inFlight = lifted != null;
        logGap = want;
        applySpacing();
        // Laid out again for real, inside this call, rather than asking for a
        // pass and hoping it lands in time: the window closes at the very moment
        // a card is lifted (VIS2.1 puts the log away with the selection), and
        // that lift goes straight on to measure every slot off the tops the
        // cards have once the window is out of the layout. With an async
        // requestLayout it measured a stack that was still a window taller than
        // it is about to be, which is what made the cards below a moved card
        // drop twice as far as they ever should.
        layoutNow();
        placeLogWindow();
        // ... and once more when the layout that was just asked for actually
        // lands, so the window can never be left sitting where the old one was.
        removeCallbacks(recutLogWindow);
        post(recutLogWindow);
        // A window that closes with a card already in the air has just moved the
        // slots under a drag that is measuring them. Put that drag straight.
        if (inFlight) rebaseline();
    }

    /**
     * Views the activity wants glued into that window - the log box, which since
     * VIS4 carries its own gradient. The stack places them itself, and it places
     * them with a real layout pass (a top margin), never with a translation:
     * moving a view that is already on screen with a translation leaves the pixels
     * it drew the first time behind, and the log then shows as a strip running
     * down the screen under the cards, which is exactly what this looked like.
     */
    private final List<View> logViews = new ArrayList<>();

    public void setLogWindow(View... views) {
        logViews.clear();
        if (views != null) {
            for (View v : views) if (v != null) logViews.add(v);
        }
        placeLogWindow();
    }

    /**
     * Put the log panel and its fade into the window: exactly one gap below the
     * log card and exactly logGap tall - the stack owns both edges of the box, so
     * nothing of the log can end up under a card further down the stack, whatever
     * the layout file says. The settled geometry of the card is used on purpose:
     * the log is blacked out while a card is in the air, so the panel only has to
     * be right where the cards come to rest.
     */
    public void placeLogWindow() {
        if (logViews.isEmpty()) return;
        int top = -1;
        if (logCard != null && logGap > 0 && logCard.getVisibility() == VISIBLE) {
            int padTop = getParent() instanceof ViewGroup
                    ? ((ViewGroup) getParent()).getPaddingTop() : 0;
            top = Math.round(logCard.getBottom() + getY() - padTop) + gap;
        }
        for (int i = 0; i < logViews.size(); i++) {
            View v = logViews.get(i);
            if (!(v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) continue;
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            int want = top < 0 ? 0 : top;
            if (lp.height == logGap && lp.topMargin == want) continue;
            lp.height = logGap;
            lp.topMargin = want;
            // Both the place it was drawn and the place it is going to are
            // marked dirty: the old strip has to be wiped, not left behind.
            v.invalidate();
            v.setLayoutParams(lp);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (logViews.isEmpty()) return;
        // Every layout of the stack is a moment where the cards, the window and
        // therefore the log may have moved: a reorder, a collapse, a new order.
        // The margins belong to the parent, and the parent is laying out right
        // now, so the window is re-cut on the next frame rather than starting a
        // second layout pass from inside this one.
        removeCallbacks(recutLogWindow);
        post(recutLogWindow);
    }

    private final Runnable recutLogWindow = this::placeLogWindow;

    // Gesture bookkeeping.
    /** The card the press began on, and where the finger currently is. */
    private DraggableCardView downCard;
    private float downX, downY, lastY;
    /** True only while a tap the stack intercepted is being replayed to the
     *  children underneath (see {@link #forwardTap}). */
    private boolean forwarding;
    /** True when this stack took the press that is in progress. A press that
     *  landed in a gap is left to the log behind the stack, and that only works
     *  if the stack says so when the press reaches it: claiming every DOWN that
     *  arrives made the gaps eat the scroll, so the log could not be dragged once
     *  its window sat between two cards. */
    private boolean claimed;
    /** The finger travelled further than a tap may, so the press that is in
     *  progress is a swipe and gets nothing replayed at the end of it. */
    private boolean slidOff;

    /**
     * The hold that lifts a card. Deliberately not a GestureDetector long
     * press: that one dies as soon as the finger drifts a few pixels, and it
     * only ends when the UP arrives - which it never does when the press lands
     * on a part of a card that nothing inside wants to click, so a plain tap
     * lifted the card half a second later. This timer asks one thing only: that
     * the finger is still down when it goes off.
     */
    private final Runnable holdTimer = new Runnable() {
        @Override
        public void run() {
            if (lifted == null && downCard != null) lift(downCard, lastY);
            downCard = null;
        }
    };

    // Active drag / open selection.
    private DraggableCardView lifted;
    private DraggableCardView selected;
    private final List<View> liftOrder = new ArrayList<>();
    private int[] restTops = new int[0];
    private int[] slotTrans = new int[0];
    private int liftIndex, targetSlot;

    public CardStackLayout(Context context) {
        this(context, null);
    }

    public CardStackLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CardStackLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
        gap = (int) (GAP_DP * density);
        // The lifted card is translated and shadowed outside the stack bounds.
        setClipChildren(false);
        setClipToPadding(false);
        setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        // A thumb wobbles while it waits; a swipe travels. This is where the two
        // stop looking the same.
        holdSlop = 3 * ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelHold();
        super.onDetachedFromWindow();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isDragging() {
        return lifted != null;
    }

    /** The card that still carries the selection outline, or null. */
    public View getSelectedCard() {
        return selected;
    }

    // ---------------------------------------------------------------- layout

    /** Re-stack the children in the given order (startup + "reset layout"). */
    public void applyOrder(List<View> order) {
        for (View v : new ArrayList<>(order)) removeView(v);
        for (View v : order) addView(v);
        applySpacing();
        requestLayout();
    }

    /** Every child in stack order, hidden ones included, so their slot stays. */
    public List<View> cardOrder() {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) out.add(getChildAt(i));
        return out;
    }

    /**
     * First visible card sits flush, the rest keep the 12dp gap - except the one
     * straight after the log card, which keeps the reserved log window too
     * (VIS4), so the log has somewhere to show through before the next card.
     */
    private void applySpacing() {
        View prev = null;
        boolean first = true;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() != VISIBLE) continue;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            int mt = first ? 0 : (prev == logCard ? gap + logGap : gap);
            if (lp.topMargin != mt) {
                lp.topMargin = mt;
                v.setLayoutParams(lp);
            }
            first = false;
            prev = v;
        }
    }

    private List<View> visibleCards() {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == VISIBLE) out.add(v);
        }
        return out;
    }

    /** Top of every card in a given order, exactly as LinearLayout lays out. */
    private int[] simulatedTops(List<View> seq) {
        int[] tops = new int[seq.size()];
        int y = 0;
        for (int i = 0; i < seq.size(); i++) {
            View v = seq.get(i);
            if (i > 0) y += (seq.get(i - 1) == logCard ? gap + logGap : gap);
            tops[i] = y;
            y += v.getHeight();
        }
        return tops;
    }

    /**
     * Force an immediate re-layout so getTop() is valid again after a drop.
     *
     * Measured UNSPECIFIED, never with the height the stack happens to have at
     * this moment: a reorder moves the log window with its card, so the stack can
     * have to be a whole window taller than it was. Measuring with the old height
     * not only kept it too short - the cards below it then hang out of a stack
     * that is drawn over them, which is what looked like the cards below the
     * advanced log hiding and cropping - it also ate the repair: measure() clears
     * the force-layout flag the requestLayout above just set, so no later pass
     * ever came back to size it properly.
     */
    private void layoutNow() {
        requestLayout();
        int w = getMeasuredWidth();
        if (w <= 0) return;
        measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        layout(getLeft(), getTop(), getLeft() + getMeasuredWidth(),
                getTop() + getMeasuredHeight());
    }

    // ----------------------------------------------------------------- touch

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final float x = ev.getX(), y = ev.getY();
        if (forwarding) return false;      // a replayed tap, not a new gesture
        if (lifted != null) return true;   // a drag owns the gesture outright
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                claimed = false;
                // A press on the hide badge belongs to the activity.
                if (!keepTouch.isEmpty() && keepTouch.contains(x, y)) return false;
                // A press that lands between two cards is nobody's: it carries on
                // to the log behind the stack, so the gaps still scroll it.
                if (cardAt(x, y) == null) return false;
                // Take the press. Passing it to the children first is exactly what
                // broke the SU Manager card: its row consumed the DOWN, the hold
                // timer lifted the card regardless, and the row then fired the
                // manager picker on a release that was already on its way to it.
                // A tap is replayed to the child when the gesture ends instead.
                claimed = true;
                armHold(x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                moveHold(x, y);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                releaseHold(ev.getActionMasked() == MotionEvent.ACTION_UP);
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (forwarding) return super.onTouchEvent(ev);
        final int action = ev.getActionMasked();
        if (lifted != null) {
            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    dragTo(ev.getY());
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    drop();
                    break;
                default:
                    break;
            }
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // Only a press onInterceptTouchEvent actually took is ours. A
                // press that landed in a gap has to fall through the stack to the
                // log behind it, or the gaps would eat the scroll.
                return claimed;
            case MotionEvent.ACTION_MOVE:
                moveHold(ev.getX(), ev.getY());
                return true;
            case MotionEvent.ACTION_UP:
                finishTap(ev.getX(), ev.getY());
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelHold();
                return true;
            default:
                return true;
        }
    }

    /** Arm the hold that lifts. Nothing is shown while it fills. */
    private void armHold(float x, float y) {
        downCard = cardAt(x, y);
        downX = x;
        downY = y;
        lastY = y;
        slidOff = false;
        if (downCard == null) return;
        removeCallbacks(holdTimer);
        postDelayed(holdTimer, HOLD_MS);
    }

    /** The press has turned into something else: forget about lifting. */
    private void cancelHold() {
        removeCallbacks(holdTimer);
        downCard = null;
    }

    /**
     * The press ended on a path the stack never owned. A short one that never
     * became a hold is the "accept" tap (VIS2.2): the outline goes and the
     * black-out lifts (VIS2.3).
     */
    private void releaseHold(boolean up) {
        boolean accept = up && selected != null;
        cancelHold();
        if (accept) clearSelection();
    }

    /**
     * Follow the finger while a press might still become a hold. Travelling far
     * is a swipe - scrolling the log - and gives the lift up; waiting is
     * forgiving about the small wobble a thumb makes, which is exactly what a
     * GestureDetector long press was not. A press that travelled that far is no
     * longer a tap either, so the replay at the end of the gesture is called off
     * with it - the same thing a real Button does with a finger that slid off.
     */
    private void moveHold(float x, float y) {
        lastY = y;
        if (downCard == null) return;
        float dx = x - downX, dy = y - downY;
        if (dx * dx + dy * dy > holdSlop * holdSlop) {
            slidOff = true;
            cancelHold();
        }
    }

    // ------------------------------------------------------------ tap replay

    /**
     * The press ended as a tap. While a card still carries the selection outline
     * the tap is the "accept" tap (VIS2.2) and is swallowed; otherwise it belongs
     * to the control under the finger, so it is replayed to it.
     */
    private void finishTap(float x, float y) {
        boolean accept = selected != null;
        boolean swipe = slidOff;
        cancelHold();
        if (accept) {
            clearSelection();
            return;
        }
        if (swipe) return;
        forwardTap(x, y);
    }

    /**
     * Replay a tap the stack took in onInterceptTouchEvent, so the switch or the
     * manager row under the finger still fires. It goes through the stack rather
     * than straight at one child, which keeps Android's own hit test and the
     * click bubbling of the layouts inside the cards exactly as they were.
     */
    private void forwardTap(float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now,
                MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 20,
                MotionEvent.ACTION_UP, x, y, 0);
        forwarding = true;
        super.dispatchTouchEvent(down);
        super.dispatchTouchEvent(up);
        forwarding = false;
        down.recycle();
        up.recycle();
    }

    private DraggableCardView cardAt(float x, float y) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View v = getChildAt(i);
            if (v.getVisibility() != VISIBLE || !(v instanceof DraggableCardView)) continue;
            float l = v.getLeft() + v.getTranslationX(), t = v.getTop() + v.getTranslationY();
            if (x >= l && x <= l + v.getWidth() && y >= t && y <= t + v.getHeight()) {
                return (DraggableCardView) v;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------- drag

    /** Lift a card. y is where the finger is, in this layout's coordinates. */
    private void lift(DraggableCardView card, float y) {
        // The hold landed: the press is over as far as the controls inside the
        // cards are concerned, and the stack owns the rest of the gesture.
        removeCallbacks(holdTimer);
        downCard = null;
        if (selected != null && selected != card) clearSelection();
        List<View> visible = visibleCards();
        if (visible.indexOf(card) < 0 || visible.size() < 2) return;
        // Telling the activity that a card has come off the stack is what puts
        // the advanced log away (VIS2.1), and the window the stack keeps under
        // the log card goes with it. That has to happen BEFORE a single thing is
        // measured: every slot, every preview and the hide badge are read off
        // the tops the cards have once the window is out of the layout, and with
        // the window still in there the cards below the lifted one sat a whole
        // log window too low and slid that much further than they ever should.
        int[] was = new int[2];
        card.getLocationInWindow(was);
        if (listener != null) listener.onCardLifted(card);
        // Re-read the stack the activity has just changed the shape of.
        visible = visibleCards();
        liftOrder.clear();
        liftOrder.addAll(visible);
        int n = liftOrder.size();
        restTops = new int[n];
        slotTrans = new int[n];
        for (int i = 0; i < n; i++) restTops[i] = liftOrder.get(i).getTop();
        liftIndex = liftOrder.indexOf(card);
        targetSlot = liftIndex;
        if (liftIndex < 0) return;

        card.animate().cancel();
        // Closing the window pulls every card under the log card up, this one
        // included. Nudge it back to where it was on screen, so the snap below
        // travels from the card the user is holding instead of from a slot two
        // hundred dp above the finger.
        int[] now = new int[2];
        card.getLocationInWindow(now);
        card.setTranslationY(card.getTranslationY() + was[1] - now[1]);

        lifted = card;
        selected = card;
        // Whatever was mid-flight on this card (a shrink-out-of-the-way, a
        // black-out) is over: the lifted card is shown whole and bright.
        card.setAlpha(1f);
        card.setScaleX(1f);
        card.setScaleY(1f);
        card.setPushed(false);
        card.setHeld(true);
        for (View v : visible) {
            if (v != card) ((DraggableCardView) v).setDimmed(true);
        }
        requestDisallowInterceptTouchEvent(true);
        setTranslationZ(STACK_LIFT_Z_DP * density);
        card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        // Magnetic: the card comes to the finger the moment it is lifted, so it
        // is already riding under it before the finger moves another pixel.
        // (The activity was already told this card had come off the stack, above,
        // before any of the slots were measured.)
        card.animate().translationY(magnetY(y)).setDuration(SNAP_MS)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    /**
     * Translation that puts the lifted card's centre under the finger, clamped
     * so that the card stays inside the stack. Unclamped it slid past the top
     * and bottom of the stack, where the edge of the screen cut half of it off.
     * The ends of the travel are exactly the first and the last slot, so this
     * costs nothing: pulling past either end still lands in that end slot.
     */
    private float magnetY(float y) {
        float h = lifted.getHeight();
        float ty = y - restTops[liftIndex] - h / 2f;
        float min = -restTops[liftIndex];
        float max = Math.max(min, getHeight() - h - restTops[liftIndex]);
        return Math.max(min, Math.min(max, ty));
    }

    /** The card sticks to the finger; the slot it hovers over is re-read. */
    private void dragTo(float y) {
        lifted.animate().cancel();
        lastY = y;
        float ty = magnetY(y);
        lifted.setTranslationY(ty);

        // The slot is picked by the FINGER, not by the card - see slotFor().
        int slot = slotFor(y);
        if (slot != targetSlot) {
            targetSlot = slot;
            preview();
            // The tick that says another card has to slide under this one - the
            // same feedback the on-screen keyboard gives per key.
            lifted.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
        if (listener != null) listener.onCardMoved(lifted);
    }

    /**
     * The slot the finger is over: how many cards have their middle above it,
     * the card being moved itself excluded. The finger picks the slot and not the
     * card because the card is clamped inside the stack, so its centre stops dead
     * at the ends of its travel and could never cross the middle of the first or
     * last slot - moving a card to the bottom of the stack was a 4dp band at the
     * very edge of the screen, in practice impossible to hit. The finger still
     * travels the whole stack.
     */
    private int slotFor(float y) {
        int slot = 0;
        for (int i = 0; i < liftOrder.size(); i++) {
            if (i == liftIndex) continue;
            View v = liftOrder.get(i);
            if (restTops[i] + v.getHeight() / 2f < y) slot++;
        }
        return slot;
    }

    /**
     * The stack changed shape while a card was in the air - closing the log
     * window under a lifted card is the one that does it. Every slot of an open
     * drag is measured off {@link #restTops}, so those are re-read from the fresh
     * layout, the previews built on the old ones are dropped, and the lifted card
     * is put back under the finger, which is now over a different part of the
     * stack than it was.
     */
    private void rebaseline() {
        if (lifted == null) return;
        int n = liftOrder.size();
        if (restTops.length != n) return;
        for (int i = 0; i < n; i++) {
            View v = liftOrder.get(i);
            v.animate().cancel();
            v.setTranslationY(0f);
            slotTrans[i] = 0;
            restTops[i] = v.getTop();
        }
        liftIndex = liftOrder.indexOf(lifted);
        if (liftIndex < 0) return;
        lifted.setTranslationY(magnetY(lastY));
        int slot = slotFor(lastY);
        if (slot != targetSlot) {
            targetSlot = slot;
            preview();
        }
    }

    /** Slide the cards in the way into their new slot, then bounce (VIS3.2). */
    private void preview() {
        List<View> next = new ArrayList<>(liftOrder);
        next.remove(liftIndex);
        next.add(targetSlot, lifted);
        int[] tops = simulatedTops(next);
        for (int k = 0; k < next.size(); k++) {
            View v = next.get(k);
            if (v == lifted) continue;
            int old = liftOrder.indexOf(v);
            int target = tops[k] - restTops[old];
            if (slotTrans[old] == target) continue;
            slotTrans[old] = target;
            v.animate().translationY(target).setDuration(MOVE_MS)
                    .setInterpolator(new OvershootInterpolator(BOUNCE)).start();
            ((DraggableCardView) v).setPushed(target != 0);
        }
    }

    /** Commit the order, then let every card settle into its slot (VIS3.1). */
    private void drop() {
        DraggableCardView card = lifted;
        lifted = null;
        setTranslationZ(0f);
        boolean moved = targetSlot != liftIndex;
        int n = liftOrder.size();
        int[] visual = new int[n];
        for (int i = 0; i < n; i++) {
            View v = liftOrder.get(i);
            visual[i] = v.getTop() + Math.round(v.getTranslationY());
        }
        if (moved) {
            List<View> next = new ArrayList<>(liftOrder);
            next.remove(liftIndex);
            next.add(targetSlot, card);
            for (View v : next) removeView(v);
            for (View v : next) addView(v);
            applySpacing();
            layoutNow();
            // Back to where they visually were, so nothing jumps...
            for (int i = 0; i < n; i++) {
                View v = liftOrder.get(i);
                v.setTranslationY(visual[i] - v.getTop());
            }
        }
        // ...and now the short settle into the real slots.
        for (View v : visibleCards()) {
            v.animate().translationY(0f).setDuration(SETTLE_MS)
                    .setInterpolator(new OvershootInterpolator(BOUNCE)).start();
            if (v != card) ((DraggableCardView) v).setPushed(false);
        }
        if (moved) card.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (listener != null) listener.onCardDropped(card, moved);
    }

    /** Tap outside the outlined card: accept the layout, clear the look. */
    public void clearSelection() {
        if (selected == null) return;
        DraggableCardView card = selected;
        selected = null;
        card.setHeld(false);
        card.setTranslationZ(0f);
        for (View v : visibleCards()) {
            if (v != card) ((DraggableCardView) v).setDimmed(false);
        }
        if (listener != null) listener.onCardSelectionCleared();
    }

    // ----------------------------------------------------------- hide / show

    /** Fade + collapse a card out of the stack (VIS1.4). */
    public void hideCard(final View card) {
        if (card.getVisibility() != VISIBLE) return;
        final int startH = card.getHeight();
        card.animate().cancel();
        card.animate().scaleX(0.94f).scaleY(0.94f).setDuration(COLLAPSE_MS).start();
        ValueAnimator a = ValueAnimator.ofInt(startH, 0);
        a.setDuration(COLLAPSE_MS);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(an -> {
            int h = (int) an.getAnimatedValue();
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            lp.height = h;
            card.setLayoutParams(lp);
            card.setAlpha(startH <= 0 ? 0f : h / (float) startH);
        });
        a.addListener(new EndAnim(() -> {
            card.setVisibility(GONE);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            lp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
            card.setLayoutParams(lp);
            card.setAlpha(1f);
            card.setScaleX(1f);
            card.setScaleY(1f);
            applySpacing();
        }));
        a.start();
    }

    /** Grow a hidden card back into its slot. */
    public void showCard(final View card) {
        if (card.getVisibility() == VISIBLE) return;
        card.setVisibility(VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
        lp.height = 0;
        card.setLayoutParams(lp);
        card.setAlpha(0f);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        applySpacing();
        int w = getMeasuredWidth();
        card.measure(MeasureSpec.makeMeasureSpec(w > 0 ? w : 1080, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        final int targetH = card.getMeasuredHeight();
        ValueAnimator a = ValueAnimator.ofInt(0, targetH);
        a.setDuration(COLLAPSE_MS);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(an -> {
            int h = (int) an.getAnimatedValue();
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) card.getLayoutParams();
            p.height = h;
            card.setLayoutParams(p);
            card.setAlpha(targetH <= 0 ? 1f : h / (float) targetH);
        });
        a.addListener(new EndAnim(() -> {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) card.getLayoutParams();
            p.height = LinearLayout.LayoutParams.WRAP_CONTENT;
            card.setLayoutParams(p);
            card.setAlpha(1f);
            card.setScaleX(1f);
            card.setScaleY(1f);
        }));
        a.start();
    }

    // ---- internals ---------------------------------------------------------

    /** Runs a Runnable when an animator finishes - Java has no withEndAction. */
    private static final class EndAnim
            extends android.animation.AnimatorListenerAdapter {
        private final Runnable action;

        EndAnim(Runnable action) {
            this.action = action;
        }

        @Override
        public void onAnimationEnd(android.animation.Animator anim) {
            action.run();
        }
    }
}
