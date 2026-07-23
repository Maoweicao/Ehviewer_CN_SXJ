package com.hippo.ehviewer.ui.scheduled;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.task.scheduled.CronExpressionBuilder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Cron 表达式生成器对话框
 */
public class CronExpressionDialog extends DialogFragment {

    private OnCronSelectedListener listener;
    private CronExpressionBuilder builder = new CronExpressionBuilder();

    private EditText minuteInput;
    private EditText hourInput;
    private EditText dayOfMonthInput;
    private EditText monthInput;
    private EditText dayOfWeekInput;
    private EditText yearInput;
    private TextView expressionResult;
    private TextView previewText;

    public interface OnCronSelectedListener {
        void onCronSelected(String expression);
    }

    public void setOnCronSelectedListener(OnCronSelectedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_cron_expression, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        setupPresets();
        setupButtons();
        updateExpression();
    }

    private void initViews(View view) {
        minuteInput = view.findViewById(R.id.cron_minute);
        hourInput = view.findViewById(R.id.cron_hour);
        dayOfMonthInput = view.findViewById(R.id.cron_day_of_month);
        monthInput = view.findViewById(R.id.cron_month);
        dayOfWeekInput = view.findViewById(R.id.cron_day_of_week);
        yearInput = view.findViewById(R.id.cron_year);
        expressionResult = view.findViewById(R.id.cron_expression_result);
        previewText = view.findViewById(R.id.cron_preview);
    }

    private void setupPresets() {
        ChipGroup chipGroup = getView().findViewById(R.id.preset_chip_group);
        
        ((Chip) getView().findViewById(R.id.preset_daily_00_00)).setOnClickListener(v -> {
            setCronFields("0", "0", "*", "*", "*", "*");
            updateExpression();
        });

        ((Chip) getView().findViewById(R.id.preset_daily_02_00)).setOnClickListener(v -> {
            setCronFields("0", "2", "*", "*", "*", "*");
            updateExpression();
        });

        ((Chip) getView().findViewById(R.id.preset_daily_06_00)).setOnClickListener(v -> {
            setCronFields("0", "6", "*", "*", "*", "*");
            updateExpression();
        });

        ((Chip) getView().findViewById(R.id.preset_weekly_monday)).setOnClickListener(v -> {
            setCronFields("0", "0", "*", "*", "1", "*");
            updateExpression();
        });

        ((Chip) getView().findViewById(R.id.preset_weekly_sunday)).setOnClickListener(v -> {
            setCronFields("0", "0", "*", "*", "0", "*");
            updateExpression();
        });

        ((Chip) getView().findViewById(R.id.preset_monthly_1st)).setOnClickListener(v -> {
            setCronFields("0", "0", "1", "*", "*", "*");
            updateExpression();
        });
    }

    private void setupButtons() {
        getView().findViewById(R.id.cancel_button).setOnClickListener(v -> dismiss());
        
        getView().findViewById(R.id.confirm_button).setOnClickListener(v -> {
            String expression = expressionResult.getText().toString();
            if (!expression.isEmpty() && listener != null) {
                listener.onCronSelected(expression);
            }
            dismiss();
        });
    }

    private void setCronFields(String minute, String hour, String dayOfMonth, String month, String dayOfWeek, String year) {
        minuteInput.setText(minute);
        hourInput.setText(hour);
        dayOfMonthInput.setText(dayOfMonth);
        monthInput.setText(month);
        dayOfWeekInput.setText(dayOfWeek);
        yearInput.setText(year);
    }

    private void updateExpression() {
        String minute = minuteInput.getText().toString();
        String hour = hourInput.getText().toString();
        String dayOfMonth = dayOfMonthInput.getText().toString();
        String month = monthInput.getText().toString();
        String dayOfWeek = dayOfWeekInput.getText().toString();
        String year = yearInput.getText().toString();

        String expression = String.format("%s %s %s %s %s %s", minute, hour, dayOfMonth, month, dayOfWeek, year);
        expressionResult.setText(expression);

        // 预览
        try {
            String description = builder.getDescription(expression);
            List<Long> nextTimes = builder.getNextNExecutionTimes(expression, 3);
            
            StringBuilder preview = new StringBuilder();
            preview.append(description);
            
            if (!nextTimes.isEmpty()) {
                preview.append("\n\n下次执行时间:\n");
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
                for (Long time : nextTimes) {
                    preview.append(sdf.format(new Date(time)));
                    preview.append("\n");
                }
            }
            
            previewText.setText(preview.toString());
        } catch (Exception e) {
            previewText.setText("无效的表达式");
        }
    }
}
