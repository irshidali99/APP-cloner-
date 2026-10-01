/*
 * Passcode screen of a clone with "password lock" enabled.
 *
 * The activity is built entirely in code (no resources of the cloned app are touched) and is opened by
 * [AppClonerPatch] whenever one of the clone's own activities comes to the front. It stays in front until
 * the passcode - or the reset code App Cloner shows for this clone - is entered.
 */
package com.appcloner.runtime;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class LockActivity extends Activity {

    private EditText input;
    private TextView hint;
    private boolean resetMode;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            );
        } catch (Throwable ignored) {
        }
        setTitle("Locked");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int padding = dp(24);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.parseColor("#FF101014"));

        TextView title = new TextView(this);
        title.setText("This clone is locked");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        hint = new TextView(this);
        hint.setText("Enter the passcode you set in App Cloner.");
        hint.setTextColor(Color.parseColor("#FFB4B4C0"));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, dp(16));
        root.addView(hint);

        input = new EditText(this);
        input.setHint("Passcode");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(input);

        Button unlock = new Button(this);
        unlock.setText("Unlock");
        unlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                attemptUnlock();
            }
        });
        root.addView(unlock);

        Button forgot = new Button(this);
        forgot.setText(resetMode ? "Enter reset code" : "Forgot passcode?");
        forgot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleResetMode();
            }
        });
        root.addView(forgot);

        TextView note = new TextView(this);
        note.setText("The reset code is shown in App Cloner under this clone's details.");
        note.setTextColor(Color.parseColor("#FF8A8A99"));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);

        setContentView(root);
    }

    private void toggleResetMode() {
        resetMode = !resetMode;
        input.setText("");
        if (resetMode) {
            hint.setText("Enter the reset code from App Cloner.");
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        } else {
            hint.setText("Enter the passcode you set in App Cloner.");
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
    }

    private void attemptUnlock() {
        String value = input.getText() == null ? "" : input.getText().toString();
        boolean accepted = resetMode
            ? AppClonerPatch.verifyResetCode(this, value)
            : AppClonerPatch.verifyPasscode(value);
        if (accepted) {
            AppClonerPatch.markUnlocked();
            finish();
            return;
        }
        toast(resetMode ? "Wrong reset code" : "Wrong passcode");
        input.setText("");
    }

    private void toast(String message) {
        try {
            Toast.makeText((Context) this, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        // The lock screen must not be dismissible with the back button.
        toast("Enter the passcode to continue");
    }
}
