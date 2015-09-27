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
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.View;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.util.WeakHashMap;

/**
 * Bring chain style programming to Android UI development
 */
public class FastIntent<T extends AbstractViewIntent<T>> {

    protected View root;
    protected Activity act;
    private Context context;

    @SuppressWarnings("rawtypes")
	private static Class fastintent = AbstractViewIntent.DefaultIntent.class;

    /**
     * Set your customized ViewIntent class
     *
     * @param myclass
     */
    @SuppressWarnings("rawtypes")
	public static void setIntentClass(Class myclass) {
        fastintent = myclass;
    }


    /**
     * Instantiates a new AIntent object.
     *
     * @param act Activity that's the parent of the to-be-operated views.
     */
    public FastIntent(Activity act) {
        this.act = act;
    }

    /**
     * Instantiates a new AIntent object.
     *
     * @param root View container that's the parent of the to-be-operated views.
     */
    public FastIntent(View root) {
        this.root = root;
    }

    /**
     * Instantiates a new AIntent object. This constructor should be used for Fragments.
     *
     * @param act  Activity
     * @param root View container that's the parent of the to-be-operated views.
     */
    public FastIntent(Activity act, View root) {
        this.root = root;
        this.act = act;
    }


    /**
     * Instantiates a new AIntent object.
     *
     * @param context Context that will be used in async operations.
     */

    public FastIntent(Context context) {
        this.context = context;
    }


    /**
     * Return the context of activity or view.
     *
     * @return Context
     */

    protected Context getContext() {
        if (act != null) {
            return act;
        }
        if (root != null) {
            return root.getContext();
        }
        return context;
    }

    /**
     * Select view, and start the chain
     *
     * @param view
     * @return
     */
    public T v(View view) {
        return create(view);
    }

    /**
     * Select view of id, and start the chain
     *
     * @param id
     * @return
     */
    public T v(int id) {
        return create(findView(id));
    }

    /**
     * replace root view with given view
     * @param view
     * @return
     */
    public FastIntent<T> recycle(View view) {
        this.root = view;
        return this;
    }


    /**
     * Select view, and start the chain
     *
     * @param id
     * @return
     */
    public T id(int id) {
        return v(id);
    }


    @SuppressWarnings("unchecked")
	private T create(View view) {

        T result = null;

        try {
            Constructor<T> c = getConstructor();
            result = (T) c.newInstance(view);
            result.act = act;
            result.context = getContext();
            result.root = root;
            result.fastintent = this;
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalStateException("Please set your own ViewIntent by setViewIntent() method");
        }
        return result;

    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
	private Constructor getConstructor() {
        try {
            return (Constructor<T>) fastintent.getConstructor(View.class);
        } catch (Exception e) {
            //should never happen
            e.printStackTrace();
        }
        return null;
    }

    private View findView(int id) {
        View result = null;
        if (root != null) {
            result = root.findViewById(id);
        } else if (act != null) {
            result = act.findViewById(id);
        }
        return result;
    }

    /**
     * Show toast message
     *
     * @param message
     */
    public void toast(final CharSequence message) {
        if (act == null)
            return;

        act.runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(act, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Show toast message
     *
     * @param strId
     */
    public void toast(final int strId) {
        if (act == null)
            return;

        act.runOnUiThread(new Runnable() {

            public void run() {
                Toast.makeText(act, strId, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Show toast message with long duration
     *
     * @param cha
     */
    public void longToast(final CharSequence cha) {
        if (act == null)
            return;

        act.runOnUiThread(new Runnable() {

            public void run() {
                Toast.makeText(act, cha, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Show toast message with long duration
     *
     * @param strId
     */
    public void longToast(final int strId) {
        if (act == null)
            return;

        act.runOnUiThread(new Runnable() {

            public void run() {
                Toast.makeText(act, strId, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Open a alert dialog with title and message
     *
     * @param title
     * @param message
     */
    public void alert(final String title, final CharSequence message) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(
                getContext());
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setIcon(android.R.drawable.ic_dialog_alert);
        builder.setPositiveButton(android.R.string.ok,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog,
                                        final int which) {
                        final AlertDialog ad = builder.create();
                        ad.cancel();
                    }
                });
        builder.show();
    }

    /**
     * Open a alert dialog with title and message
     *
     * @param title
     * @param message
     */
    public void alert(final int title, final int message) {
        new AlertDialog.Builder(getContext());
        alert(getContext().getString(title),
                getContext().getString(message));
    }

    /**
     * Open a confirm dialog with title and message
     *
     * @param title
     * @param message
     */
    public void confirm(final int title, final int message,
                        final DialogInterface.OnClickListener onClickListener) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(
                getContext());
        builder.setTitle(title).setIcon(android.R.drawable.ic_dialog_info).setMessage(message);
        builder.setPositiveButton(android.R.string.ok,
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(final DialogInterface dialog,
                                        final int which) {
                        if (onClickListener != null) {
                            onClickListener.onClick(dialog, which);
                        }

                    }
                });
        builder.setNegativeButton(android.R.string.cancel,
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(final DialogInterface dialog,
                                        final int which) {
                        if (onClickListener != null) {
                            onClickListener.onClick(dialog, which);
                        }
                    }
                });
        builder.show();
    }


    /**
     * Open a alert dialog with title and message
     *
     * @param title
     * @param message
     */
    public void alert(final int title, final int message,
                      final DialogInterface.OnClickListener onClickListener) {

        final AlertDialog.Builder builder = new AlertDialog.Builder(
                getContext());
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setIcon(android.R.drawable.ic_dialog_alert);
        builder.setPositiveButton(android.R.string.ok,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog,
                                        final int which) {
                        if (onClickListener != null) {
                            onClickListener.onClick(dialog, which);
                        }
                        final AlertDialog ad = builder.create();
                        ad.cancel();
                    }
                });
        builder.show();
    }

    /**
     * Open a dialog with single choice list
     *
     * @param title
     * @param list
     * @param listener
     */
    public void dialog(final int title, int list, DialogInterface.OnClickListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(title)
                .setItems(list, listener);
        builder.create().show();
    }

    /**
     * Open a dialog with single choice list
     *
     * @param title
     * @param list
     * @param listener
     */
    public void dialog(final int title, CharSequence[] list, DialogInterface.OnClickListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(title)
                .setItems(list, listener);
        builder.create().show();
    }

    @SuppressWarnings("rawtypes")
	public FastIntent task(final FastTask<?> task) {
        taskpool.put(task.hashCode(), task);
        if (act!=null)
            task.async(act);
        else
            task.async(getContext());
        return this;
    }

    private final WeakHashMap<Integer, FastTask<?>> taskpool = new WeakHashMap<Integer, FastTask<?>>();

    /**
     * Cancle all the task in pool
     *
     * @return
     */
    @SuppressWarnings("rawtypes")
	private FastIntent CleanAllTask() {
        for (final FastTask<?> reference : taskpool.values()) {
            if (reference != null) {
                reference.cancel();
            }
        }
        return this;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        CleanAllTask();
    }

    /**
     * Launch a new activity
     * @param clz
     */
    @SuppressWarnings("rawtypes")
	public void startActivity(Class clz) {
        act.startActivity(new Intent(getContext(),clz));
    }

    /**
     * Launch an activity for which you would like a result when it finished
     *
     * @param clz
     * @param requestCode
     */
    @SuppressWarnings("rawtypes")
	public void startActivityForResult(Class clz,int requestCode) {
        act.startActivityForResult(new Intent(getContext(),clz),requestCode);
    }
}
