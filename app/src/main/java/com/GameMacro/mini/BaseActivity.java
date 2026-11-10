package com.GameMacro.mini;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 手柄的按键在系统里会变成方向键、回车或返回键，界面会跟着移动焦点、点按钮。
 * 这里把来自手柄的按键和摇杆事件统一吃掉，界面只认触摸。
 *
 * 按键测试页需要这些事件来点亮按键，它自己覆写了 dispatchKeyEvent，
 * 会先于这里执行，不受影响。
 */
public class BaseActivity extends AppCompatActivity {

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (fromPad(e.getDeviceId(), e.getSource())) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if (fromPad(e.getDeviceId(), e.getSource())) return true;
        return super.onGenericMotionEvent(e);
    }

    /** 判断事件是不是手柄发出来的：先看 source，再看设备类型和名字。 */
    protected static boolean fromPad(int deviceId, int source) {
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return true;
        if ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;

        InputDevice d = InputDevice.getDevice(deviceId);
        if (d == null) return false;
        if ((d.getSources() & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK)) != 0) {
            return true;
        }

        String name = d.getName();
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.contains("gamemacro") || n.contains("pocket controller") || n.contains("t50");
    }
}
