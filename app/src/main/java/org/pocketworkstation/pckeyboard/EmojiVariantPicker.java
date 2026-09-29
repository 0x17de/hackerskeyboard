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
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Skin tone picker drawn as an overlay over the emoji palette.
 *
 * Single-person emoji show one row: the default yellow form followed by the
 * five tones. Two-person emoji show the default on top of a 5x5 grid where the
 * row is the first person's tone and the column the second person's.
 */
public class EmojiVariantPicker extends View {

    public interface Listener {
        void onVariantPicked(EmojiData.Emoji emoji, String form);
    }

    private final EmojiTheme mTheme;
    private final Listener mListener;
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mCard = new RectF();
    private final RectF mTmp = new RectF();
    private final int[] mLocation = new int[2];

    private EmojiData.Emoji mEmoji;
    /** Forms in display order; index 0 is the default (yellow) form. */
    private String[] mForms;
    private int mColumns;
    private int mRows;
    private float mCell;
    private int mSelected = -1;
    /** Where the opening long-press gesture was when the picker appeared. */
    private float mGestureStartX = Float.NaN;
    private float mGestureStartY;
    private boolean mGestureMoved;
    private final int mTouchSlop;

    public EmojiVariantPicker(Context context, EmojiTheme theme, Listener listener) {
        super(context);
        mTheme = theme;
        mListener = listener;
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mCardPaint.setColor(theme.cardColor);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(theme.dp(1));
        mBorderPaint.setColor(theme.withAlpha(theme.textColor, 0x40));
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setVisibility(GONE);
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    /** Opens the picker next to {@code anchor}, highlighting {@code current}. */
    public void show(View anchor, EmojiData.Emoji emoji, String current, float cellSize) {
        mEmoji = emoji;
        mForms = new String[emoji.variants.length + 1];
        mForms[0] = emoji.base;
        System.arraycopy(emoji.variants, 0, mForms, 1, emoji.variants.length);
        boolean grid = emoji.variants.length == 25;
        mColumns = grid ? 5 : mForms.length;
        mRows = grid ? 6 : 1;

        // This view is still GONE (unmeasured) here, but it exactly covers its parent.
        View parent = (View) getParent();
        final int width = parent.getWidth();
        final int height = parent.getHeight();
        float pad = mTheme.dp(6);
        mCell = cellSize;
        mCell = Math.min(mCell, (width - 2 * pad) / mColumns);
        mCell = Math.min(mCell, (height - 2 * pad) / mRows);
        mTextPaint.setTextSize(mCell * 0.62f);

        // Position above the anchor, falling back to below it, clamped to the view.
        parent.getLocationOnScreen(mLocation);
        int ox = mLocation[0], oy = mLocation[1];
        anchor.getLocationOnScreen(mLocation);
        float ax = mLocation[0] - ox, ay = mLocation[1] - oy;
        float w = mColumns * mCell + 2 * pad;
        float h = mRows * mCell + 2 * pad;
        float left = clamp(ax + anchor.getWidth() / 2f - w / 2f, 0, width - w);
        float top = ay - h;
        if (top < 0) top = ay + anchor.getHeight();
        top = clamp(top, 0, height - h);
        mCard.set(left, top, left + w, top + h);

        mSelected = indexOf(current);
        mGestureStartX = Float.NaN;
        mGestureMoved = false;
        setVisibility(VISIBLE);
        invalidate();
    }

    public void dismiss() {
        mSelected = -1;
        setVisibility(GONE);
    }

    /**
     * Continues the long-press gesture that opened the picker: sliding
     * highlights a variant and lifting the finger on one picks it. Lifting
     * without having slid (the card may cover the finger), or outside the
     * card, leaves the picker open for a tap.
     */
    public void onGesture(MotionEvent event) {
        if (Float.isNaN(mGestureStartX)) {
            mGestureStartX = event.getRawX();
            mGestureStartY = event.getRawY();
        } else if (Math.abs(event.getRawX() - mGestureStartX) > mTouchSlop
                || Math.abs(event.getRawY() - mGestureStartY) > mTouchSlop) {
            mGestureMoved = true;
        }
        ((View) getParent()).getLocationOnScreen(mLocation);
        int hit = hitTest(event.getRawX() - mLocation[0], event.getRawY() - mLocation[1]);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (hit >= 0 && mGestureMoved) pick(hit);
            return;
        }
        if (!mGestureMoved) return;
        if (hit >= 0 && hit != mSelected) {
            mSelected = hit;
            invalidate();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isShowing()) return false;
        int hit = hitTest(event.getX(), event.getY());
        switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            if (!mCard.contains(event.getX(), event.getY())) {
                dismiss();
                return true;
            }
            // fall through
        case MotionEvent.ACTION_MOVE:
            if (hit != mSelected) {
                mSelected = hit;
                invalidate();
            }
            return true;
        case MotionEvent.ACTION_UP:
            if (hit >= 0) pick(hit);
            return true;
        }
        return true;
    }

    private void pick(int index) {
        String form = mForms[index];
        EmojiData.Emoji emoji = mEmoji;
        dismiss();
        mListener.onVariantPicked(emoji, form);
    }

    private int indexOf(String form) {
        for (int i = 0; i < mForms.length; ++i) {
            if (mForms[i].equals(form)) return i;
        }
        return -1;
    }

    /** Index into mForms of the cell at (x, y), or -1. */
    private int hitTest(float x, float y) {
        float pad = mTheme.dp(6);
        int col = (int) Math.floor((x - mCard.left - pad) / mCell);
        int row = (int) Math.floor((y - mCard.top - pad) / mCell);
        if (col < 0 || col >= mColumns || row < 0 || row >= mRows) return -1;
        if (mRows == 1) return col;
        if (row == 0) return col == mColumns / 2 ? 0 : -1;
        return 1 + (row - 1) * mColumns + col;
    }

    private void cellRect(int index, RectF out) {
        int col, row;
        if (mRows == 1) {
            col = index;
            row = 0;
        } else if (index == 0) {
            col = mColumns / 2;
            row = 0;
        } else {
            col = (index - 1) % mColumns;
            row = 1 + (index - 1) / mColumns;
        }
        float pad = mTheme.dp(6);
        float l = mCard.left + pad + col * mCell;
        float t = mCard.top + pad + row * mCell;
        out.set(l, t, l + mCell, t + mCell);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mForms == null) return;
        float r = mTheme.dp(12);
        canvas.drawRoundRect(mCard, r, r, mCardPaint);
        canvas.drawRoundRect(mCard, r, r, mBorderPaint);
        Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        for (int i = 0; i < mForms.length; ++i) {
            cellRect(i, mTmp);
            if (i == mSelected) {
                float hr = mCell * 0.2f;
                canvas.drawRoundRect(mTmp, hr, hr, mTheme.highlightPaint);
            }
            float y = mTmp.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(mForms[i], mTmp.centerX(), y, mTextPaint);
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(v, max));
    }
}
