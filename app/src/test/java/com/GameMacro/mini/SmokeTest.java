package com.GameMacro.mini;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SmokeTest {

    @Test public void mainActivityStarts() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        assertNotNull(c.get());
        assertNotNull("底部导航未挂载", c.get().findViewById(R.id.main_nav));
        c.pause().stop().destroy();
    }

    @Test public void macroEditorStarts() {
        ActivityController<MacroEditorActivity> c =
                Robolectric.buildActivity(MacroEditorActivity.class).setup();
        assertNotNull(c.get());
        c.pause().stop().destroy();
    }

    @Test public void macroAllTabsBuild() throws Exception {
        ActivityController<MacroEditorActivity> c =
                Robolectric.buildActivity(MacroEditorActivity.class).setup();
        MacroEditorActivity a = c.get();
        java.lang.reflect.Method showTab =
                MacroEditorActivity.class.getDeclaredMethod("showTab", int.class);
        showTab.setAccessible(true);
        for (int i = 0; i < 5; i++) showTab.invoke(a, i);
        c.pause().stop().destroy();
    }

    @Test public void mappingCellOpensPicker() throws Exception {
        ActivityController<MacroEditorActivity> c =
                Robolectric.buildActivity(MacroEditorActivity.class).setup();
        MacroEditorActivity a = c.get();
        java.lang.reflect.Method mapCell =
                MacroEditorActivity.class.getDeclaredMethod("mapCell", int.class);
        mapCell.setAccessible(true);
        android.view.View cell = (android.view.View) mapCell.invoke(a, 0);
        int w = (int) (360 * a.getResources().getDisplayMetrics().density);
        int h = (int) (48 * a.getResources().getDisplayMetrics().density);
        android.view.ViewGroup content = a.findViewById(android.R.id.content);
        content.addView(cell, new android.widget.FrameLayout.LayoutParams(w, h));
        cell.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY));
        cell.layout(0, 0, w, h);

        android.view.ViewGroup group = (android.view.ViewGroup) cell;
        android.view.View dst = group.getChildAt(2);   // 箭头后面的目标键
        float x = dst.getLeft() + dst.getWidth() / 2f;
        float y = dst.getTop() + dst.getHeight() / 2f;
        long t = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(t, t,
                android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(t, t + 40,
                android.view.MotionEvent.ACTION_UP, x, y, 0);
        System.out.println("dst w/h=" + dst.getWidth() + "/" + dst.getHeight()
                + " listener=" + dst.hasOnClickListeners());
        cell.dispatchTouchEvent(down);
        cell.dispatchTouchEvent(up);
        down.recycle(); up.recycle();
        // View.onTouchEvent 的 performClick 走 post，需要跑一次主线程消息
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        org.junit.Assert.assertNotNull("点目标键没有弹出选择器", dialog);
        org.junit.Assert.assertTrue("选择器未显示", dialog.isShowing());
        dialog.dismiss();
        c.pause().stop().destroy();
    }

    @Test public void keyTestStarts() {
        assertNotNull(Robolectric.buildActivity(KeyTestActivity.class).setup().get());
    }

    @Test public void manualStarts() {
        assertNotNull(Robolectric.buildActivity(ManualActivity.class).setup().get());
    }

    /** 用户明确要求不再支持横屏：四个页面都必须锁定竖屏。 */
    @Test public void allActivitiesLockedPortrait() throws Exception {
        for (Class<?> cls : new Class<?>[]{MainActivity.class, KeyTestActivity.class,
                MacroEditorActivity.class, ManualActivity.class}) {
            android.content.pm.ActivityInfo ai = org.robolectric.RuntimeEnvironment
                    .getApplication().getPackageManager()
                    .getActivityInfo(new android.content.ComponentName(
                            org.robolectric.RuntimeEnvironment.getApplication(), cls), 0);
            org.junit.Assert.assertEquals(cls.getSimpleName() + " 未锁定竖屏",
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    ai.screenOrientation);
        }
    }

    /** GameMacro 只是协议名，Pocket Controller 才是系统里的手柄名，解绑时按同一系列处理。 */
    @Test public void controllerNameFamilyCoversBothNames() {
        org.junit.Assert.assertTrue(BleClient.sameModelFamily("GameMacro", "Pocket Controller"));
        org.junit.Assert.assertTrue(BleClient.sameModelFamily("Pocket Controller", "GameMacro"));
        org.junit.Assert.assertFalse(BleClient.sameModelFamily("GameMacro", "Xiaomi Smart Band 10 Pro"));
        org.junit.Assert.assertFalse(BleClient.sameModelFamily(null, "Pocket Controller"));
    }
}
