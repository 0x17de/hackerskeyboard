/*
 * Copyright (C) 2025 Hacker's Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.pocketworkstation.pckeyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

/**
 * A single emoji in the palette grid or the search results strip.
 *
 * Touch handling is done here rather than with click listeners so that a long
 * press can open the skin tone picker and the same gesture can then slide onto
 * a variant and release to pick it.
 */
public class EmojiCellView extends View {

    public interface Listener {
        void onEmojiClicked(EmojiCellView cell);
        /** @return true if a skin tone picker was opened for this cell. */
        boolean onEmojiLongPressed(EmojiCellView cell);
        /** Move / up events of a gesture that opened the skin tone picker. */
        void onEmojiPickerGesture(EmojiCellView cell, MotionEvent event);
    }

    private final EmojiTheme mTheme;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mMark = new Path();
    private final RectF mRect = new RectF();
    private final int mTouchSlop;

    private Listener mListener;
    private EmojiData.Emoji mEmoji;
    private String mForm;
    private boolean mPressed;
    private boolean mPickerOpen;
    private boolean mShowVariantMark = true;
    private float mDownX;
    private float mDownY;

    private final Runnable mLongPress = new Runnable() {
        public void run() {
            if (!mPressed || mListener == null) return;
            if (mListener.onEmojiLongPressed(EmojiCellView.this)) {
                mPickerOpen = true;
                // Keep the grid from scrolling while the finger slides over the picker.
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            }
        }
    };

    public EmojiCellView(Context context, EmojiTheme theme) {
        super(context);
        mTheme = theme;
        mPaint.setTextAlign(Paint.Align.CENTER);
        mMarkPaint.setColor(theme.withAlpha(theme.textColor, 0x70));
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void bind(EmojiData.Emoji emoji, String form, float textSize, Listener listener) {
        mEmoji = emoji;
        mForm = form;
        mListener = listener;
        mPaint.setTextSize(textSize);
        String name = emoji != null ? emoji.getName() : null;
        setContentDescription(name != null ? name : form);
        cancelPress();
        invalidate();
    }

    /** Whether to hint at skin tone variants; off where long press has no picker. */
    public void setShowVariantMark(boolean show) {
        mShowVariantMark = show;
    }

    public EmojiData.Emoji getEmoji() {
        return mEmoji;
    }

    /** The exact string this cell inserts, e.g. a skin tone variant. */
    public String getForm() {
        return mForm;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (mPressed || mPickerOpen) {
            float inset = Math.min(w, h) * 0.06f;
            mRect.set(inset, inset, w - inset, h - inset);
            float r = Math.min(w, h) * 0.2f;
            canvas.drawRoundRect(mRect, r, r, mTheme.highlightPaint);
        }
        if (mForm == null) return;
        Paint.FontMetrics fm = mPaint.getFontMetrics();
        float y = h / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(mForm, w / 2f, y, mPaint);
        if (mShowVariantMark && mEmoji != null && mEmoji.hasVariants()) {
            // Small corner triangle hinting at the long-press skin tone picker.
            float size = Math.min(w, h) * 0.12f;
            float right = w - Math.min(w, h) * 0.1f;
            float bottom = h - Math.min(w, h) * 0.1f;
            mMark.reset();
            mMark.moveTo(right, bottom - size);
            mMark.lineTo(right, bottom);
            mMark.lineTo(right - size, bottom);
            mMark.close();
            canvas.drawPath(mMark, mMarkPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mListener == null) return false;
        switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            mPressed = true;
            mPickerOpen = false;
            mDownX = event.getX();
            mDownY = event.getY();
            invalidate();
            postDelayed(mLongPress, LatinIME.sKeyboardSettings.longpressTimeout);
            return true;
        case MotionEvent.ACTION_MOVE:
            if (mPickerOpen) {
                mListener.onEmojiPickerGesture(this, event);
            } else if (mPressed && (Math.abs(event.getX() - mDownX) > mTouchSlop
                    || Math.abs(event.getY() - mDownY) > mTouchSlop)
                    && !isInside(event)) {
                cancelPress();
            }
            return true;
        case MotionEvent.ACTION_UP:
            removeCallbacks(mLongPress);
            if (mPickerOpen) {
                mListener.onEmojiPickerGesture(this, event);
            } else if (mPressed) {
                mListener.onEmojiClicked(this);
            }
            cancelPress();
            return true;
        case MotionEvent.ACTION_CANCEL:
            cancelPress();
            return true;
        }
        return false;
    }

    private boolean isInside(MotionEvent event) {
        return event.getX() >= 0 && event.getX() < getWidth()
                && event.getY() >= 0 && event.getY() < getHeight();
    }

    private void cancelPress() {
        removeCallbacks(mLongPress);
        if (mPressed || mPickerOpen) {
            mPressed = false;
            mPickerOpen = false;
            invalidate();
        }
    }
}
