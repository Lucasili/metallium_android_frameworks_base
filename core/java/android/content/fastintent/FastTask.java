/*
 * Copyright (C) 2015 The Metallium - OS Project
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

package android.content.fastintent;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.view.View;
import android.widget.ProgressBar;

import java.lang.ref.WeakReference;


/**
 * FastTask is a usage simplified AsyncTask, with progress dialog support and callback activity check(in order to prevent NPE in some case)
 * Don't overuse this as AsyncTask has many drawbacks.
 *
 * @param <T>
 */

public abstract class FastTask<T> implements OnCancelListener {

    private WeakReference<Object> progress;

    private T result;

    private View view;
    private boolean running;

    public abstract T backgroundWork() throws Exception;

    private AsyncTask<?, ?, ?> fasttask;

    private ProgressDialog dialog;

    private int dialogreid = -1;

    private boolean dialogcancel = false;

    private boolean dialogdeterminate = true;

    private WeakReference<Activity> act;


    @Override
    public int hashCode() {
        int result = fasttask != null ? fasttask.hashCode() : (int) (Math.random() * 1000);
        result = 31 * result + (act != null ? act.hashCode() : 0);
        return result;
    }

    /**
     * This method will be trigger before the background start to run.
     */
    public void pre() {

    }

    /**
     * Callback when background fasttask finished without any exception
     *
     * @param result the object
     */
    public void callback(final T result) {

    }

    /**
     * Callback when background fasttask failed with exception
     *
     * @param result the object
     */
    public void failcallback(final T result, final Exception e) {

    }

    /**
     * This will be trigger after callback function been called no matter the fasttask is failed or success.
     */
    public void end() {

    }

    private boolean isActive() {
        if (act == null) {
            return true;
        }

        final Activity a = act.get();

        if (a == null || a.isFinishing()) {
            return false;
        }

        return true;
    }


    void run() {
        try {
            result = backgroundWork();
            showProgress(false);
            callback(result);
        } catch (final Exception e) {
            e.printStackTrace();
            failcallback(result, e);
        }
    }

    /**
     * Progress view will only be shown during fasttask is executing
     *
     * @param progress
     * @return
     */
    public FastTask<T> progress(final View progress) {
        if (progress != null) {
            this.progress = new WeakReference<Object>(progress);
        }
        return this;
    }


    /**
     * Progress view will only be shown during fasttask is executing
     *
     * @param progress
     * @return
     */
    public FastTask<T> progress(final Dialog progress) {
        if (progress != null) {
            this.progress = new WeakReference<Object>(progress);
        }
        return this;
    }

    /**
     * Progress view will only be shown during fasttask is executing
     *
     * @param progress
     * @return
     */
    public FastTask<T> progress(final Activity progress) {
        if (progress != null) {
            this.progress = new WeakReference<Object>(progress);
        }
        return this;
    }

    protected void progressUpdate(final String... values) {
        if (null != dialog && !dialog.isIndeterminate()) {
            dialog.setProgress(Integer.valueOf(values[0]));
        }
    }


    void async(final Activity act) {
        this.act = new WeakReference<Activity>(act);
        if (act.isFinishing()) {
            return;
        }

        runfasttask(act);
    }

    private void runfasttask(final Context act) {
        fasttask = new AsyncTask<Object, String, T>() {

            private Exception e;

            @Override
            protected void onPostExecute(final T result) {
                try {
                    if (e == null) {
                        if (!isCancelled()) {
                            if (isActive()) {
                                callback(result);
                            }

                        }
                    } else {
                        e.printStackTrace();
                        if (isActive()) {
                            failcallback(result, e);
                        }
                    }
                } finally {
                    // 无论如何，关闭progress
                    showProgress(false);
                    end();
                }
                running = false;
            }

            @Override
            protected T doInBackground(final Object... params) {
                try {
                    return backgroundWork();
                } catch (final Exception e) {
                    this.e = e;
                }
                return null;
            }

            @Override
            protected void onPreExecute() {
                if (dialogreid != -1) {
                    dialog = new ProgressDialog(act);
                    dialog.setCancelable(dialogcancel);
                    dialog.setIndeterminate(dialogdeterminate);
                    dialog.setMessage(act.getText(dialogreid));
                    if (dialogcancel) {
                        dialog.setOnCancelListener(FastTask.this);
                    }
                }
                showProgress(true);
                pre();
            }

            @Override
            protected void onProgressUpdate(final String... values) {
                progressUpdate(values);
            }

        };
        execute(fasttask);
        running = true;
    }

    protected void showProgress(final boolean show) {
        if (act.get() == null || act.get().isFinishing()) {
            return;
        }
        if (progress != null) {
            showProgress(progress.get(), null, show);
        }
        if (dialog != null) {
            try {
                if (show) {
                    if (!dialog.isShowing())
                        dialog.show();
                } else {
                    if (dialog.isShowing())
                        dialog.dismiss();
                }
            }catch (Throwable e) {

            }
        }
        if (view != null) {
            view.setVisibility(!show ? View.VISIBLE
                    : View.INVISIBLE);
        }
    }


    /**
     * This fore view will be hide during fasttask is executing, and visible after task done.
     *
     * @param view
     */
    public FastTask<T> view(final View view) {
        this.view = view;
        return this;
    }

    /**
     * Cancel the fasttask
     */
    public void cancel() {
        if (fasttask != null && !fasttask.isCancelled()
                && fasttask.getStatus() != AsyncTask.Status.FINISHED) {
            fasttask.cancel(true);
        }
    }

    @Override
    public void onCancel(final DialogInterface arg0) {
        cancel();
    }

    /**
     * Change the message in progress dialog
     *
     * @param resId
     */
    public void updateDialogMsg(final int resId) {
        if (dialog != null) {
            this.dialog.setMessage(dialog.getContext().getText(resId));
        }
    }

    /**
     * FastTask with a progress dialog showing message
     *
     * @param resId
     * @return
     */
    public FastTask<T> dialog(final int resId) {
        this.dialogreid = resId;
        return this;
    }

    /**
     * This task can be cancel by press back key
     *
     * @return
     */
    public FastTask<T> cancelable() {
        this.dialogcancel = true;
        return this;
    }

    /**
     * Progress bar is determinate
     *
     * @return
     */
    public FastTask<T> determinate() {
        this.dialogdeterminate = false;
        return this;
    }

    private static void showProgress(Object p, String url, boolean show) {

        if (p != null) {

            if (p instanceof View) {
                View pv = (View) p;
                ProgressBar pbar = null;

                if (p instanceof ProgressBar) {
                    pbar = (ProgressBar) p;
                }

                if (show) {
                    pv.setVisibility(View.VISIBLE);
                    if (pbar != null) {
                        pbar.setProgress(0);
                        pbar.setMax(100);
                    }
                } else {
                    if (pbar == null || pbar.isIndeterminate()) {
                        pv.setVisibility(View.GONE);
                    }
                }
            } else if (p instanceof Dialog) {
                Dialog pd = (Dialog) p;
                if (show) {
                    pd.show();
                } else {
                    if (pd.isShowing())
                        pd.dismiss();
                }
            } else if (p instanceof Activity) {
                Activity act = (Activity) p;
                act.setProgressBarIndeterminateVisibility(show);
                act.setProgressBarVisibility(show);

                if (show) {
                    act.setProgress(0);
                }
            }
        }

    }

    /**
     * Execute an {@link AsyncTask} on a thread pool.
     *
     * @param task Task to execute.
     * @param <T>  Task argument type.
     */
    @SuppressWarnings("unchecked")
	private static <T> void execute(AsyncTask<T, ?, ?> task) {
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public boolean isRunning() {
        return running;
    }

    public void async(Context context) {
        runfasttask(context);
    }
}
