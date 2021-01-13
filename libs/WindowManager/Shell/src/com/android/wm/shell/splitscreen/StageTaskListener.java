/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.splitscreen;

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import android.annotation.CallSuper;
import android.app.ActivityManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.SurfaceControl;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;

/**
 * Base class that handle common task org. related for split-screen stages.
 * Note that this class and its sub-class do not directly perform hierarchy operations.
 * They only serve to hold a collection of tasks and provide APIs like
 * {@link #setBounds(Rect, WindowContainerTransaction)} for the centralized {@link StageCoordinator}
 * to perform operations in-sync with other containers.
 * @see StageCoordinator
 */
class StageTaskListener implements ShellTaskOrganizer.TaskListener {
    private static final String TAG = StageTaskListener.class.getSimpleName();

    /** Callback interface for listening to changes in a split-screen stage. */
    public interface StageListenerCallbacks {
        void onRootTaskAppeared();
        void onStatusChanged(boolean visible, boolean hasChildren);
        void onRootTaskVanished();
    }
    private final StageListenerCallbacks mCallbacks;
    private final SyncTransactionQueue mSyncQueue;

    protected ActivityManager.RunningTaskInfo mRootTaskInfo;
    protected SurfaceControl mRootLeash;
    protected SparseArray<ActivityManager.RunningTaskInfo> mChildrenTaskInfo = new SparseArray<>();
    private final SparseArray<SurfaceControl> mChildrenLeashes = new SparseArray<>();

    StageTaskListener(ShellTaskOrganizer taskOrganizer, int displayId,
            StageListenerCallbacks callbacks, SyncTransactionQueue syncQueue) {
        mCallbacks = callbacks;
        mSyncQueue = syncQueue;
        taskOrganizer.createRootTask(displayId, WINDOWING_MODE_MULTI_WINDOW, this);
    }

    @Override
    @CallSuper
    public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo, SurfaceControl leash) {
        if (!taskInfo.hasParentTask()) {
            mCallbacks.onRootTaskAppeared();
            mRootLeash = leash;
            mRootTaskInfo = taskInfo;
        } else if (taskInfo.parentTaskId == mRootTaskInfo.taskId) {
            mChildrenLeashes.put(taskInfo.taskId, leash);
            mChildrenTaskInfo.put(taskInfo.taskId, taskInfo);
            updateChildTaskSurface(taskInfo, leash, true /* firstAppeared */);
        } else {
            throw new IllegalArgumentException("Unknown task: " + taskInfo);
        }
        sendStatusChanged();
    }

    @Override
    @CallSuper
    public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
        if (mRootTaskInfo.taskId == taskInfo.taskId) {
            mRootTaskInfo = taskInfo;
        } else if (taskInfo.parentTaskId == mRootTaskInfo.taskId) {
            mChildrenTaskInfo.put(taskInfo.taskId, taskInfo);
            updateChildTaskSurface(
                    taskInfo, mChildrenLeashes.get(taskInfo.taskId), false /* firstAppeared */);
        } else {
            throw new IllegalArgumentException("Unknown task: " + taskInfo);
        }
        sendStatusChanged();
    }

    @Override
    @CallSuper
    public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        final int taskId = taskInfo.taskId;
        if (mRootTaskInfo.taskId == taskId) {
            mCallbacks.onRootTaskVanished();
            mRootTaskInfo = null;
        } else if (mChildrenTaskInfo.contains(taskId)) {
            mChildrenTaskInfo.remove(taskId);
            mChildrenLeashes.remove(taskId);
            sendStatusChanged();
        } else {
            throw new IllegalArgumentException("Unknown task: " + taskInfo);
        }
    }

    void setBounds(Rect bounds, WindowContainerTransaction wct) {
        wct.setBounds(mRootTaskInfo.token, bounds);
    }

    void setVisibility(boolean visible, WindowContainerTransaction wct) {
        wct.reorder(mRootTaskInfo.token, visible /* onTop */);
    }

    private void updateChildTaskSurface(ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl leash, boolean firstAppeared) {
        final Point taskPositionInParent = taskInfo.positionInParent;
        mSyncQueue.runInSync(t -> {
            t.setWindowCrop(leash, null);
            t.setPosition(leash, taskPositionInParent.x, taskPositionInParent.y);
            if (firstAppeared && !Transitions.ENABLE_SHELL_TRANSITIONS) {
                t.setAlpha(leash, 1f);
                t.setMatrix(leash, 1, 0, 0, 1);
                t.show(leash);
            }
        });
    }

    private void sendStatusChanged() {
        mCallbacks.onStatusChanged(mRootTaskInfo.isVisible, mChildrenTaskInfo.size() > 0);
    }

    @Override
    @CallSuper
    public void dump(@NonNull PrintWriter pw, String prefix) {

    }
}
