package com.GameMacro.mini;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T50 键码表。
 *
 * 键码来自手柄配置回包的逐项比对，是固件真正识别的单字节值。
 *
 * 早期版本用过 0x01~0x14 的推测值，与固件对不上，
 * 导致 0x37（连发）写入被设备当作未知键丢弃、0x77 回读连发表恒为 0。
 * 换成这张表后连发和宏写入才会真正落到设备。
 */
public final class KeyCodes {

    public static final class Key {
        public final int code;
        public final String name;
        public Key(int code, String name) { this.code = code; this.name = name; }
    }

    /** 物理按键（连发页可编辑项）。顺序沿用官方连发 tab：ABXY / 肩键 / 扳机 / 菜单 / 十字 / 背键 */
    public static final Key[] BUTTONS = {
            new Key(0xA0, "A"),
            new Key(0xA1, "B"),
            new Key(0xA2, "X"),
            new Key(0xA3, "Y"),
            new Key(0xA4, "L1"),
            new Key(0xA7, "R1"),
            new Key(0xA5, "L2"),
            new Key(0xA8, "R2"),
            new Key(0xA6, "L3"),
            new Key(0xA9, "R3"),
            new Key(0xAA, "-"),
            new Key(0xAB, "+"),
            new Key(0xD3, "十字上"),
            new Key(0xD4, "十字下"),
            new Key(0xD1, "十字左"),
            new Key(0xD2, "十字右"),
            new Key(0x01, "M1"),
            new Key(0x02, "M2"),
    };

    /**
     * 全表（含摇杆方向），用于解析设备回包时把键码翻译成名字。
     * 摇杆方向键一般不出现在连发表里，但 0x86 / 0x77 回包可能会带。
     */
    public static final Key[] ALL = {
            new Key(0xA0, "A"), new Key(0xA1, "B"), new Key(0xA2, "X"), new Key(0xA3, "Y"),
            new Key(0xA4, "L1"), new Key(0xA5, "L2"), new Key(0xA6, "L3"),
            new Key(0xA7, "R1"), new Key(0xA8, "R2"), new Key(0xA9, "R3"),
            new Key(0xAA, "-"), new Key(0xAB, "+"), new Key(0xAE, "i/turbo"),
            new Key(0x01, "M1"), new Key(0x02, "M2"),

            new Key(0xD0, "十字·中"),
            new Key(0xD1, "十字左"), new Key(0xD2, "十字右"),
            new Key(0xD3, "十字上"), new Key(0xD4, "十字下"),
            new Key(0xD5, "十字左上"), new Key(0xD6, "十字右上"),
            new Key(0xD7, "十字左下"), new Key(0xD8, "十字右下"),

            new Key(0xE0, "左摇杆·中"),
            new Key(0xE1, "左摇杆左"), new Key(0xE2, "左摇杆右"),
            new Key(0xE3, "左摇杆上"), new Key(0xE4, "左摇杆下"),
            new Key(0xE5, "左摇杆左上"), new Key(0xE6, "左摇杆右上"),
            new Key(0xE7, "左摇杆左下"), new Key(0xE8, "左摇杆右下"),

            new Key(0xF0, "右摇杆·中"),
            new Key(0xF1, "右摇杆左"), new Key(0xF2, "右摇杆右"),
            new Key(0xF3, "右摇杆上"), new Key(0xF4, "右摇杆下"),
            new Key(0xF5, "右摇杆左上"), new Key(0xF6, "右摇杆右上"),
            new Key(0xF7, "右摇杆左下"), new Key(0xF8, "右摇杆右下"),
    };

    private static final Map<Integer, String> BY_CODE = new LinkedHashMap<>();
    static {
        for (Key k : ALL) BY_CODE.put(k.code, k.name);
    }

    public static String name(int code) {
        String n = BY_CODE.get(code & 0xff);
        return n != null ? n : ("0x" + Integer.toHexString(code & 0xff).toUpperCase());
    }

    /** 该键码是否为可连发/可映射的物理键 */
    public static boolean isButton(int code) {
        for (Key k : BUTTONS) if (k.code == (code & 0xff)) return true;
        return false;
    }

    private KeyCodes() {}
}
