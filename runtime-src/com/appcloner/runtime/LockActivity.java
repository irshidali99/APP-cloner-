/*
 * Lock screen of a locked clone. Three shapes:
 *
 *  - passcode:  a text field that unlocks with the passcode,
 *  - pattern:   a 3x3 grid the user draws on,
 *  - calculator: a working looking calculator keypad - the passcode is typed in and confirmed with "=",
 *                so nothing on the screen says "locked".
 *
 * The reset code (shown in App Cloner under the clone's details) opens every shape; in the calculator
 * look it is reached by keeping a finger on the display.
 */
package com.appcloner.runtime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
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

    private static final int PADDING_DP = 24;

    private EditText input;
    private TextView hint;
    private TextView display;
    private boolean resetMode;

    private final StringBuilder entered = new StringBuilder();

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

        if (AppClonerPatch.isPatternLock()) {
            buildPatternUi();
        } else if (AppClonerPatch.isCalculatorLock()) {
            buildCalculatorUi();
        } else {
            buildPasscodeUi();
        }
    }

    // ------------------------------------------------------------------ passcode

    private void buildPasscodeUi() {
        LinearLayout root = column();

        TextView title = headline("This clone is locked");
        root.addView(title);

        hint = explanation("Enter the passcode you set in App Cloner.");
        root.addView(hint);

        input = new EditText(this);
        input.setHint("Passcode");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setLayoutParams(fullWidth());
        root.addView(input);

        Button unlock = button("Unlock");
        unlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                attemptUnlock();
            }
        });
        root.addView(unlock);

        root.addView(forgotButton("Forgot passcode?"));
        root.addView(footNote("The reset code is shown in App Cloner under this clone's details."));

        setContentView(root);
    }

    // ------------------------------------------------------------------ pattern

    private void buildPatternUi() {
        LinearLayout root = column();

        root.addView(headline("Draw your pattern"));
        root.addView(explanation("Draw the pattern you set in App Cloner (at least four dots)."));

        final PatternView pattern = new PatternView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        );
        params.setMargins(0, dp(16), 0, dp(16));
        pattern.setLayoutParams(params);
        pattern.setListener(new PatternView.Listener() {
            @Override
            public void onPattern(String sequence) {
                if (AppClonerPatch.verifyPattern(sequence)) {
                    AppClonerPatch.markUnlocked();
                    finish();
                } else {
                    AppClonerPatch.toast(LockActivity.this, "Wrong pattern");
                    pattern.clear();
                }
            }
        });
        root.addView(pattern);
        root.addView(forgotButton("Forgot pattern?"));
        root.addView(footNote("The reset code is shown in App Cloner under this clone's details."));

        setContentView(root);
    }

    // ------------------------------------------------------------------ calculator disguise

    private void buildCalculatorUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#FF0B0B0F"));
        root.setPadding(dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP));

        display = new TextView(this);
        display.setText("0");
        display.setTextColor(Color.WHITE);
        display.setTextSize(TypedValue.COMPLEX_UNIT_SP, 42);
        display.setGravity(Gravity.END);
        display.setTypeface(Typeface.MONOSPACE);
        display.setPadding(0, dp(24), 0, dp(24));
        display.setLayoutParams(fullWidth());
        // Keeping a finger on the display opens the reset code entry - a calculator has no letters.
        display.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                askForResetCode();
                return true;
            }
        });
        root.addView(display);

        String[][] keys = new String[][] {
            {"7", "8", "9"},
            {"4", "5", "6"},
            {"1", "2", "3"},
            {"C", "0", "="}
        };
        for (String[] row : keys) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ));
            for (String key : row) {
                line.addView(calculatorKey(key));
            }
            root.addView(line);
        }

        setContentView(root);
    }

    private View calculatorKey(final String key) {
        Button button = new Button(this);
        button.setText(key);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.parseColor("#FF1C1C22"));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        button.setLayoutParams(params);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onCalculatorKey(key);
            }
        });
        return button;
    }

    private void onCalculatorKey(String key) {
        if ("C".equals(key)) {
            entered.setLength(0);
        } else if ("=".equals(key)) {
            String value = entered.toString();
            entered.setLength(0);
            if (AppClonerPatch.verifyPasscode(value)) {
                AppClonerPatch.markUnlocked();
                finish();
                return;
            }
            AppClonerPatch.toast(this, "Wrong passcode");
        } else {
            if (entered.length() < 32) entered.append(key);
        }
        if (display != null) {
            display.setText(entered.length() == 0 ? "0" : entered.toString());
        }
    }

    // ------------------------------------------------------------------ shared parts

    private Button forgotButton(String label) {
        Button button = button(label);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (AppClonerPatch.isPatternLock()) {
                    askForResetCode();
                } else {
                    toggleResetMode();
                }
            }
        });
        return button;
    }

    private void toggleResetMode() {
        resetMode = !resetMode;
        if (input == null) return;
        input.setText("");
        if (resetMode) {
            hint.setText("Enter the reset code from App Cloner.");
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        } else {
            hint.setText("Enter the passcode you set in App Cloner.");
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
    }

    /** Asks for the one time reset code - also used by the calculator disguise. */
    private void askForResetCode() {
        final EditText field = new EditText(this);
        field.setHint("Reset code");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);

        new AlertDialog.Builder(this)
            .setTitle("Reset code")
            .setMessage("The reset code is shown in App Cloner under this clone's details.")
            .setView(field)
            .setPositiveButton("Unlock", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String value = field.getText() == null ? "" : field.getText().toString();
                    if (AppClonerPatch.verifyResetCode(LockActivity.this, value)) {
                        AppClonerPatch.markUnlocked();
                        finish();
                    } else {
                        AppClonerPatch.toast(LockActivity.this, "Wrong reset code");
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void attemptUnlock() {
        String value = input == null || input.getText() == null ? "" : input.getText().toString();
        boolean accepted = resetMode
            ? AppClonerPatch.verifyResetCode(this, value)
            : AppClonerPatch.verifyPasscode(value);
        if (accepted) {
            AppClonerPatch.markUnlocked();
            finish();
            return;
        }
        AppClonerPatch.toast(this, resetMode ? "Wrong reset code" : "Wrong passcode");
        if (input != null) input.setText("");
    }

    private LinearLayout column() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int padding = dp(PADDING_DP);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.parseColor("#FF101014"));
        return root;
    }

    private TextView headline(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private TextView explanation(String text) {
        hint = new TextView(this);
        hint.setText(text);
        hint.setTextColor(Color.parseColor("#FFB4B4C0"));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, dp(16));
        return hint;
    }

    private TextView footNote(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.parseColor("#FF8A8A99"));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(16), 0, 0);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setLayoutParams(fullWidth());
        return button;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        // The lock screen must not be dismissible with the back button.
        AppClonerPatch.toast(this, "Enter the passcode to continue");
    }
}
