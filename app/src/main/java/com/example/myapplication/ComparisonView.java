package com.example.myapplication;

import android.content.Context;
import android.view.MotionEvent;
import androidx.appcompat.widget.AppCompatTextView;
import java.util.function.Consumer;

/** Hold to compare with touch, or toggle the same preview with an accessibility click. */
final class ComparisonView extends AppCompatTextView {
    private final Consumer<Boolean> preview;
    private boolean touching, showingOriginal;

    public ComparisonView(Context context) {
        this(context, show -> {});
    }

    ComparisonView(Context context, Consumer<Boolean> preview) {
        super(context);
        this.preview = preview;
        setClickable(true);
        setFocusable(true);
    }

    private void showOriginal(boolean show) {
        if (showingOriginal == show) return;
        showingOriginal = show;
        setActivated(show);
        preview.accept(show);
    }

    @Override public boolean performClick() {
        if (!isEnabled()) return false;
        super.performClick();
        if (!touching) showOriginal(!showingOriginal);
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                setPressed(true);
                showOriginal(true);
                return true;
            case MotionEvent.ACTION_UP:
                if (!touching) return false;
                showOriginal(false);
                performClick();
                touching = false;
                setPressed(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                showOriginal(false);
                touching = false;
                setPressed(false);
                return true;
            default:
                return touching;
        }
    }
}
