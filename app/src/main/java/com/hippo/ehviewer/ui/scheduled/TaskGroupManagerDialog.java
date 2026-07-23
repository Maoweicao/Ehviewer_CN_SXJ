package com.hippo.ehviewer.ui.scheduled;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.task.scheduled.ExecutionMode;
import com.hippo.ehviewer.task.scheduled.TaskGroup;

/**
 * 任务组管理对话框
 */
public class TaskGroupManagerDialog extends DialogFragment {

    private OnGroupCreatedListener listener;

    public interface OnGroupCreatedListener {
        void onGroupCreated(TaskGroup group);
    }

    public void setOnGroupCreatedListener(OnGroupCreatedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_task_group_manager, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        EditText groupNameInput = view.findViewById(R.id.group_name_input);
        RadioGroup executionModeGroup = view.findViewById(R.id.execution_mode_group);
        TextView modeDescription = view.findViewById(R.id.mode_description);

        executionModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.mode_sequential) {
                modeDescription.setText(R.string.task_group_sequential_desc);
            } else {
                modeDescription.setText(R.string.task_group_parallel_desc);
            }
        });

        view.findViewById(R.id.cancel_button).setOnClickListener(v -> dismiss());

        view.findViewById(R.id.confirm_button).setOnClickListener(v -> {
            String groupName = groupNameInput.getText().toString().trim();
            if (groupName.isEmpty()) {
                Toast.makeText(requireContext(), "请输入任务组名称", Toast.LENGTH_SHORT).show();
                return;
            }

            ExecutionMode mode = executionModeGroup.getCheckedRadioButtonId() == R.id.mode_parallel
                    ? ExecutionMode.PARALLEL
                    : ExecutionMode.SEQUENTIAL;

            TaskGroup group = new TaskGroup(
                    "group_" + System.currentTimeMillis(),
                    groupName,
                    mode
            );

            if (listener != null) {
                listener.onGroupCreated(group);
            }

            dismiss();
        });
    }
}
