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

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

/**
 * A floating button on the settings screens that opens a text field with the
 * keyboard, to try out a setting right away without leaving the settings.
 * Long press opens the system input method picker.
 */
public class KeyboardTestButton {
    private final Activity mActivity;
    private final InputMethodManager mImm;
    private final EditText mField;
    private final ImageView mButton;

    private KeyboardTestButton(Activity activity) {
        mActivity = activity;
        mImm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        float density = activity.getResources().getDisplayMetrics().density;
        int accent = accentColor(activity);

        // Keep the field and the button above the keyboard.
        activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        FrameLayout content = (FrameLayout) activity.findViewById(android.R.id.content);

        mField = new EditText(activity);
        mField.setHint(R.string.keyboard_test_hint);
        mField.setVisibility(View.GONE);
        GradientDrawable fieldBackground = new GradientDrawable();
        fieldBackground.setColor(0xFFFFFFFF);
        fieldBackground.setStroke(dp(density, 2), accent);
        fieldBackground.setCornerRadius(dp(density, 24));
        mField.setBackground(fieldBackground);
        mField.setTextColor(0xFF000000);
        mField.setHintTextColor(0x80000000);
        mField.setPadding(dp(density, 16), dp(density, 10), dp(density, 16), dp(density, 10));
        mField.setElevation(dp(density, 6));
        FrameLayout.LayoutParams fieldParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        fieldParams.setMargins(dp(density, 16), 0, dp(density, 88), dp(density, 20));
        content.addView(mField, fieldParams);

        mButton = new ImageView(activity);
        mButton.setImageResource(R.drawable.ic_keyboard_test);
        mButton.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setShape(GradientDrawable.OVAL);
        buttonBackground.setColor(accent);
        mButton.setBackground(buttonBackground);
        mButton.setElevation(dp(density, 6));
        mButton.setContentDescription(activity.getString(R.string.keyboard_test_button));
        mButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggle();
            }
        });
        mButton.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                mImm.showInputMethodPicker();
                return true;
            }
        });
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                dp(density, 56), dp(density, 56), Gravity.BOTTOM | Gravity.END);
        buttonParams.setMargins(0, 0, dp(density, 16), dp(density, 16));
        content.addView(mButton, buttonParams);
    }

    /** Adds the button to a settings screen; call after its content is set. */
    public static void attach(Activity activity) {
        new KeyboardTestButton(activity);
    }

    private void toggle() {
        if (mField.getVisibility() == View.VISIBLE) {
            mImm.hideSoftInputFromWindow(mField.getWindowToken(), 0);
            mField.clearFocus();
            mField.setVisibility(View.GONE);
            return;
        }
        if (!isCurrentInputMethod()) {
            Toast.makeText(mActivity, R.string.keyboard_test_not_selected, Toast.LENGTH_SHORT).show();
            mImm.showInputMethodPicker();
        }
        mField.setVisibility(View.VISIBLE);
        mField.requestFocus();
        mField.post(new Runnable() {
            public void run() {
                mImm.showSoftInput(mField, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private boolean isCurrentInputMethod() {
        String current = Settings.Secure.getString(mActivity.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        return current != null && current.startsWith(mActivity.getPackageName() + "/");
    }

    private static int accentColor(Context context) {
        TypedArray a = context.obtainStyledAttributes(new int[] { android.R.attr.colorAccent });
        try {
            return a.getColor(0, 0xFF3F51B5);
        } finally {
            a.recycle();
        }
    }

    private static int dp(float density, float dp) {
        return Math.round(dp * density);
    }
}
