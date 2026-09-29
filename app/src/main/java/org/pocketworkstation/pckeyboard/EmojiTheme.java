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

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.widget.ImageView;

/**
 * Colors for the emoji views, derived from the active keyboard theme so the
 * emoji palette matches whichever of the keyboard layouts is selected.
 */
public class EmojiTheme {
    public final int textColor;
    public final int secondaryTextColor;
    /** Opaque color for the skin tone picker card. */
    public final int cardColor;
    public final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable mBackground;
    private final float mDensity;

    public EmojiTheme(LatinKeyboardBaseView keyboardView) {
        textColor = keyboardView.getKeyTextColor();
        secondaryTextColor = withAlpha(textColor, 0xA0);
        highlightPaint.setColor(withAlpha(textColor, 0x28));
        boolean darkTheme = luminance(textColor) > 0.5f;
        cardColor = darkTheme ? 0xFF3C4043 : 0xFFFFFFFF;
        mBackground = keyboardView.getBackground();
        mDensity = keyboardView.getResources().getDisplayMetrics().density;
    }

    /** A fresh copy of the keyboard background, so views don't share drawable state. */
    public Drawable newBackground() {
        if (mBackground != null && mBackground.getConstantState() != null) {
            return mBackground.getConstantState().newDrawable().mutate();
        }
        return new ColorDrawable(cardColor);
    }

    /** Rounded "pill" background used for buttons and the search field. */
    public Drawable newPill(int alpha) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(withAlpha(textColor, alpha));
        d.setCornerRadius(dp(100));
        return d;
    }

    public void tint(ImageView view, int color) {
        view.setImageTintList(ColorStateList.valueOf(color));
    }

    public int dp(float dp) {
        return Math.round(dp * mDensity);
    }

    public int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static float luminance(int color) {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color)
                + 0.114f * Color.blue(color)) / 255f;
    }
}
