package df.root;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Bottom fade matching the log surface in both themes. */
public class BottomFadeView extends FrameLayout {
    private final Paint paint = new Paint();
    private static final float[] POSITIONS = {0f, 0.50f, 0.72f, 0.93f, 1f};

    public BottomFadeView(Context context) {
        super(context);
        init();
    }

    public BottomFadeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BottomFadeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        int surface = getContext().getColor(R.color.surface_1);
        int rgb = surface & 0x00FFFFFF;
        int[] colors = {rgb, rgb | 0x40000000, rgb | 0x99000000, surface, surface};
        paint.setShader(new LinearGradient(0, 0, 0, h, colors, POSITIONS, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
    }
}
