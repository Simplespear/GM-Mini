package com.GameMacro.mini;

import java.io.ByteArrayOutputStream;

/**
 * Kr 协议帧编解码。
 * 请求帧: [len=content+3][id][SN][content...]
 * 响应帧: [len][id][SN][content...]
 * 1.4 新增: 宏配置读写(0x38/0x37/0x35/0x3d/0x3e/0x59/0x5b/0x62/0x64/0x66/0x34/0x45)。
 */
public final class Kr {

    /** 命令字 */
    public static final int ID_SET_KEYBOARD_MODE = 0x01;
    public static final int ID_QUERY_BATTERY = 0x31;
    public static final int ID_QUERY_HANDLE_INFO = 0x84;
    public static final int ID_QUERY_HANDLE_MODE = 0x87;
    public static final int ID_QUERY_MACRO_INFO = 0x77;
    public static final int ID_QUERY_MACRO_SUB = 0x79;
    public static final int ID_QUERY_MAPPING_KEY = 0x50;
    public static final int ID_QUERY_RECORD_KEY = 0x90;
    public static final int ID_QUERY_TRIGGER_TYPE = 0x85;
    public static final int ID_RECORD_MACRO_START = 0x46;
    public static final int ID_RECORD_MACRO_STOP = 0x47;
    public static final int ID_HEARTBEAT = 0x88;

    // ---- 宏配置命令字 ----
    public static final int ID_SET_MACRO_PLATFORM = 0x36;    // SetMacroPlatformReq content[2]=(1,0) 进入宏配置模式
    public static final int ID_SET_MACRO_PROFILE_END = 0x34; // SetMacroProfileEndReq(应用收尾,无 content)
    public static final int ID_SET_VIBRATION_LEVEL = 0x35;   // SetVibrationLevelReq content[2]=左,右
    public static final int ID_QUERY_MACRO_CONFIG = 0x38;    // QueryMacroConfigReq content[1]=idx;回包=连发表+摇杆+振动
    public static final int ID_SET_MACRO_BURST_RATE = 0x37;  // SetMacroBurstRateReq content[3]=key,mode,speed
    public static final int ID_SET_MACRO_KEY_MAPPING = 0x3d; // SetMacroKeyMappingReq content[2]=原键,目标键
    public static final int ID_SET_ROCKER_LINEAR = 0x3e;     // SetMacroRockerLinearReq content[8]
    public static final int ID_SET_KINECT_CONFIG = 0x5b;     // SetKinectConfigReq content[8](byte5 固定 0xff)
    public static final int ID_SET_ROCKER_ADDITIONAL = 0x59; // SetRockerAdditionalReq content[10]
    public static final int ID_SET_ROCKER_POINT = 0x3f;      // SetMacroRockerPointReq content=[method,len,cnt,x0,y0,x1,y1]
    public static final int ID_QUERY_MACRO_ROCKER = 0x62;    // QueryMacroRockerReq content[1];回包 p[2..9]
    public static final int ID_QUERY_VIBRATE_SETTING = 0x64; // QueryVibrateSettingReq 无 content;回包 p[2]左 p[3]右
    public static final int ID_QUERY_ROCKER_CROSS = 0x66;    // QueryRockerCrossReq content[1];回包 p[2]交叉L p[3]交叉R p[4]交换
    public static final int ID_VIBRATION_TEST = 0x45;        // forceVibrationTest(试振动)
    public static final int ID_QUERY_MACRO_SUPPORT = 0x86;   // QueryMacroSupportReq/QueryBurstSupportReq(多帧)

    // ---- 1.9 手柄详情（对应官方 GameMacro 同名功能，命令字取自 KrAgreement）----
    public static final int ID_TEST_MODE = 0x07;             // 按键测试上报（TestModeEventRsp）
    public static final int ID_RESET_KEYBOARD = 0x05;        // ResetKeyBoardReq：恢复出厂
    public static final int ID_CALIB_START = 0x41;           // StartSelfCalibrationReq：陀螺仪校准开始
    public static final int ID_CALIB_STOP = 0x42;            // StopSelfCalibrationReq：陀螺仪校准结束
    public static final int ID_CALIB_COMPLETE = 0x43;        // AlignCompleteGyroscopeReq
    public static final int ID_STICK_CALIB_START = 0x55;     // AlignBeginRockerOrTriggerReq：摇杆校准开始
    public static final int ID_STICK_CALIB_END = 0x56;       // AlignEndRockerOrTriggerReq
    public static final int ID_STICK_CALIB_COMPLETE = 0x57;  // AlignCompleteRockerOrTriggerReq

    private static int sSn = 0;

    private Kr() {}

    public static synchronized int nextSn() {
        int v = sSn & 0xff;
        sSn = (sSn + 1) & 0xff;
        return v;
    }

    /**
     * 帧 = [总长][命令字][content...][SN];总长 = content.length + 3。
     * content 从 index 2 开始拷入,SN 写在 index = content.length + 2(即帧尾)。
     */
    public static byte[] build(int id, byte[] content) {
        if (content == null) content = new byte[0];
        int len = content.length + 3;
        byte[] out = new byte[len];
        out[0] = (byte) (len & 0xff);
        out[1] = (byte) (id & 0xff);
        System.arraycopy(content, 0, out, 2, content.length);
        out[content.length + 2] = (byte) nextSn();
        return out;
    }

    public static byte[] heartbeatFrame() {
        // 心跳固定帧: 04 88 01 02（len=4, id=0x88, content=[0x01], SN=0x02）
        return new byte[]{0x04, (byte) 0x88, 0x01, 0x02};
    }

    public static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x & 0xff));
        return sb.toString();
    }

    // ---------- 常用请求 ----------

    /** 查询电量: 03 31 SN */
    public static byte[] reqBattery() {
        return build(ID_QUERY_BATTERY, new byte[0]);
    }

    /** 查询手柄当前模式（QueryHandleModeReq，content=[2]；回包 f[3] 即 mode）。 */
    public static byte[] reqHandleMode() {
        return build(ID_QUERY_HANDLE_MODE, new byte[]{0x02});
    }

    /** 查询手柄信息: content = [subId, 0] */
    public static byte[] reqHandleInfo(int subId) {
        return build(ID_QUERY_HANDLE_INFO, new byte[]{(byte) subId, 0x00});
    }

    /** 设置键盘模式(1=蓝牙模式) */
    public static byte[] reqSetKeyboardMode(int mode) {
        return build(ID_SET_KEYBOARD_MODE, new byte[]{(byte) mode});
    }

    /** 查询宏支持: content = [0x03, idx] */
    public static byte[] reqMacroSupport(int idx) {
        return build(0x86, new byte[]{0x03, (byte) idx});
    }

    /** 查询连发支持: content = [0x01, idx](QueryBurstSupportReq,回包带支持键码数组) */
    public static byte[] reqBurstSupport(int idx) {
        return build(0x86, new byte[]{0x01, (byte) idx});
    }

    /** 查询映射支持: content = [0x02, idx](QueryMapperSupportReq,回包 [能力位, 键码…]) */
    public static byte[] reqMapperSupport(int idx) {
        return build(0x86, new byte[]{0x02, (byte) idx});
    }

    /** 查询体感触发键支持: content = [0x08, idx](QueryKinectTriggerSupportReq) */
    public static byte[] reqKinectKeySupport(int idx) {
        return build(0x86, new byte[]{0x08, (byte) idx});
    }

    /** 查询宏信息(多帧): content = [subId, idx] */
    public static byte[] reqMacroInfo(int subId, int idx) {
        return build(ID_QUERY_MACRO_INFO, new byte[]{(byte) subId, (byte) idx});
    }

    /**
     * 查询宏总配置(照抄原版 ConnectedViewModel.queryMacroInfo):
     * QueryMacroHandleReq.setContent(pid, 3) → content=[0x03, pid], subId=3。
     * 原版参数顺序易混:content[0]=p2&0xff(=3), content[1]=p1&0xff(=pid)。
     */
    public static byte[] reqMacroHandle(int pid) {
        return build(ID_QUERY_MACRO_INFO, new byte[]{0x03, (byte) pid});
    }

    /** 查询宏子配置: content = [0x01, idx] */
    public static byte[] reqMacroSub(int idx) {
        return build(ID_QUERY_MACRO_SUB, new byte[]{0x01, (byte) idx});
    }

    /** 查询映射键: content = [page] */
    public static byte[] reqMappingKey(int page) {
        return build(ID_QUERY_MAPPING_KEY, new byte[]{(byte) page});
    }

    /** 查询可录制按键: content = [0x02, idx] */
    public static byte[] reqRecordKey(int idx) {
        return build(ID_QUERY_RECORD_KEY, new byte[]{0x02, (byte) idx});
    }

    public static byte[] reqTriggerType() {
        return build(ID_QUERY_TRIGGER_TYPE, new byte[]{0x07});
    }

    public static byte[] reqRecordStart() {
        return build(ID_RECORD_MACRO_START, new byte[0]);
    }

    public static byte[] reqRecordStop() {
        return build(ID_RECORD_MACRO_STOP, new byte[]{0x01});
    }

    // ---------- 1.4 宏配置读写 ----------

    /** 查询宏配置(连发表+摇杆+振动): content=[idx] */
    public static byte[] reqMacroConfig(int idx) {
        return build(ID_QUERY_MACRO_CONFIG, new byte[]{(byte) idx});
    }

    /** 进入宏配置模式: SetMacroPlatformReq content[2]=(1,0) —— 写回链第一条(照抄原版 macroPushMacro type0) */
    public static byte[] setMacroPlatform() {
        return build(ID_SET_MACRO_PLATFORM, new byte[]{0x01, 0x00});
    }

    /** 连发设置: content=[key,mode,speed] mode=2关/1单击/0按住(官方 MacroBurst.auto) */
    public static byte[] setBurstRate(int key, int mode, int speed) {
        return build(ID_SET_MACRO_BURST_RATE, new byte[]{(byte) key, (byte) mode, (byte) speed});
    }

    /** 振动强度: content=[left,right] */
    public static byte[] setVibration(int left, int right) {
        return build(ID_SET_VIBRATION_LEVEL, new byte[]{(byte) left, (byte) right});
    }

    /** 键映射: content=[srcKey,dstKey] */
    public static byte[] setKeyMapping(int src, int dst) {
        return build(ID_SET_MACRO_KEY_MAPPING, new byte[]{(byte) src, (byte) dst});
    }

    /** 摇杆线性: content[8]=[lMin,lMax,exLx,exLy,rMin,rMax,exRx,exRy]
     *  (SetMacroRockerLinearReq.setContent 实证:b52 修正 —— 2/3/6/7 是 4 个 bool 交换位,不是 0) */
    public static byte[] setRockerLinear(int lMin, int lMax, boolean exLx, boolean exLy,
                                         int rMin, int rMax, boolean exRx, boolean exRy) {
        return build(ID_SET_ROCKER_LINEAR, new byte[]{
                (byte) lMin, (byte) lMax, (byte) (exLx ? 1 : 0), (byte) (exLy ? 1 : 0),
                (byte) rMin, (byte) rMax, (byte) (exRx ? 1 : 0), (byte) (exRy ? 1 : 0)});
    }

    /** 摇杆附加(死区/曲线/修正): content[10] */
    public static byte[] setRockerAdditional(int dzL, int maxL, int curveApplyL, int curveKeyL, int lineCorrL,
                                             int dzR, int maxR, int curveApplyR, int curveKeyR, int lineCorrR) {
        return build(ID_SET_ROCKER_ADDITIONAL, new byte[]{
                (byte) dzL, (byte) maxL, (byte) curveApplyL, (byte) curveKeyL, (byte) lineCorrL,
                (byte) dzR, (byte) maxR, (byte) curveApplyR, (byte) curveKeyR, (byte) lineCorrR});
    }

    /** 体感配置: content[8]=[switch,mapperSwitch,method,key,deathZone,sensHi,sensLo,mapperModel]
     *  (SetKinectConfigReq 实证:b52 修正 —— 灵敏度是大端两字节,byte5 不是固定 0xff) */
    public static byte[] setKinect(int sw, int mapperSw, int method, int key, int dz, int sens, int model) {
        return build(ID_SET_KINECT_CONFIG, new byte[]{
                (byte) sw, (byte) mapperSw, (byte) method, (byte) key, (byte) dz,
                (byte) ((sens >> 8) & 0xff), (byte) (sens & 0xff), (byte) model});
    }

    /** 查询摇杆(死区/交换): content=[1];回包 p[2]=lMin p[3]=lMax p[4]=exLx p[5]=exLy p[6]=rMin p[7]=rMax p[8]=exRx p[9]=exRy */
    public static byte[] queryRocker() {
        return build(ID_QUERY_MACRO_ROCKER, new byte[]{0x01});
    }

    /** 查询振动设置: 无 content;回包 p[2]=左 p[3]=右 */
    public static byte[] queryVibration() {
        return build(ID_QUERY_VIBRATE_SETTING, new byte[0]);
    }

    /** 查询摇杆交叉/交换: content=[1];回包 p[2]=交叉L p[3]=交叉R p[4]=交换 */
    public static byte[] queryRockerCross() {
        return build(ID_QUERY_ROCKER_CROSS, new byte[]{0x01});
    }

    /** 应用收尾: SetMacroProfileEndReq(无 content)——所有写操作完成后必须发送 */
    public static byte[] setProfileEnd() {
        return build(ID_SET_MACRO_PROFILE_END, new byte[0]);
    }

    /** 摇杆曲线点：method 13=左摇杆 / 14=右摇杆，pts={x0,y0,x1,y1}（0–100）
     *  官方 SetMacroRockerPointReq.setContent(method,length,points) → content=[method,count,count,x,y,...] */
    public static byte[] setRockerCurve(int method, int[] pts) {
        if (pts == null || pts.length < 4) return build(ID_SET_ROCKER_POINT,
                new byte[]{(byte) method, 0, 0});
        byte[] c = new byte[7];
        c[0] = (byte) (method & 0xff);
        c[1] = 2;
        c[2] = 2;
        c[3] = (byte) Math.max(0, Math.min(100, pts[0]));
        c[4] = (byte) Math.max(0, Math.min(100, pts[1]));
        c[5] = (byte) Math.max(0, Math.min(100, pts[2]));
        c[6] = (byte) Math.max(0, Math.min(100, pts[3]));
        return build(ID_SET_ROCKER_POINT, c);
    }

    /** 试振动: forceVibrationTest content=[left,right,3]，强度 0–255（官方 ×2.55） */
    public static byte[] vibrationTest(int left, int right) {
        return build(ID_VIBRATION_TEST, new byte[]{
                (byte) Math.max(0, Math.min(255, left)),
                (byte) Math.max(0, Math.min(255, right)), 0x03});
    }

    // ---------- 1.9 手柄详情 ----------

    /**
     * 切换手柄工作模式（SetKeyBoardModeReq，0x01 content=[mode]）。
     * 官方按键测试页就是靠这个把固件切到 BETA 模式，之后才会推 TestModeEventRsp：
     *   非经典 老固件 2 / 新固件 66；经典 老固件 130 / 新固件 194。
     * 退出时切回宏模式：4 / 68 / 132 / 196。
     */
    public static byte[] setKeyBoardMode(int mode) {
        return build(ID_SET_KEYBOARD_MODE, new byte[]{(byte) (mode & 0xff)});
    }

    /** 按键测试：0x07 content=[1] 进入，[0] 退出。 */
    public static byte[] reqTestMode(boolean on) {
        return build(ID_TEST_MODE, new byte[]{(byte) (on ? 1 : 0)});
    }

    /** 恢复出厂（ResetKeyBoardReq）：0x05 content=[1]。 */
    public static byte[] reqFactoryReset() {
        return build(ID_RESET_KEYBOARD, new byte[]{0x01});
    }

    /** 官方 AlignBeginGyroscopeReq：id=0x41，content=[1,0]。 */
    public static byte[] reqGyroCalibStart() {
        return build(ID_CALIB_START, new byte[]{0x01, 0x00});
    }

    /** 官方 AlignEndGyroscopeReq：id=0x42，content=[1,0]；正常结束与手动取消都发它。 */
    public static byte[] reqGyroCalibStop() {
        return build(ID_CALIB_STOP, new byte[]{0x01, 0x00});
    }

    /** 陀螺仪校准收尾：0x43 content=[1,0,0]（AlignCompleteGyroscopeReq，type=2 无需回包）。 */
    public static byte[] reqGyroCalibComplete() {
        return build(ID_CALIB_COMPLETE, new byte[]{0x01, 0x00, 0x00});
    }

    /**
     * 摇杆/扳机校准。官方 AlignBegin/EndRockerOrTriggerReq 的 subId：
     * 2 = 摇杆、3 = 扳机（不是左右摇杆）；content=[subId, 0]。
     */
    public static byte[] reqStickCalibStart(int subId) {
        return build(ID_STICK_CALIB_START, new byte[]{(byte) subId, 0x00});
    }

    public static byte[] reqStickCalibEnd(int subId) {
        return build(ID_STICK_CALIB_END, new byte[]{(byte) subId, 0x00});
    }

    /** 保留兼容；官方 App 在摇杆校准流程中并不发送该帧，改由 0x57 上报收尾。 */
    public static byte[] reqStickCalibComplete(int subId) {
        return build(ID_STICK_CALIB_COMPLETE, new byte[]{(byte) subId, 0x00, 0x00});
    }

    /**
     * 官方 DeviceAlignRsp(BleModeEvent) 的校准结果上报：
     * 0x43 / subId=1 陀螺仪、0x57 / subId=2 摇杆、0x57 / subId=3 扳机。
     * 帧布局 [len][id][subId][..][result][flags][..]：result=1 成功，
     * 失败时 subId=2 取 f[5]、subId=3 取 f[6] 的低 3 位作为原因位。
     */
    public static final class AlignReport {
        public final int subId;
        public final boolean success;
        public final int flags;

        private AlignReport(int subId, boolean success, int flags) {
            this.subId = subId;
            this.success = success;
            this.flags = flags;
        }
    }

    /** 非校准结果帧返回 null；调用方需自行判断当前是否处于校准会话。 */
    public static AlignReport parseAlignReport(byte[] f) {
        if (f == null || f.length < 5) return null;
        int id = f[1] & 0xff;
        if (id != ID_CALIB_COMPLETE && id != ID_STICK_CALIB_COMPLETE) return null;
        int sub = f[2] & 0xff;
        if (id == ID_CALIB_COMPLETE && sub != 1) return null;
        if (id == ID_STICK_CALIB_COMPLETE && sub != 2 && sub != 3) return null;
        boolean ok = (f[4] & 0xff) == 1;
        int raw = 0;
        if (!ok) {
            if (sub == 2 && f.length > 5) raw = f[5] & 0xff;
            else if (sub == 3 && f.length > 6) raw = f[6] & 0xff;
        }
        return new AlignReport(sub, ok, raw & 0x07);
    }

    // ---------- 多帧重组 ----------

    /**
     * 多帧响应重组器。响应帧布局(与请求不同!):
     * [0]=帧长 [1]=cmdId [2]=subId [3]=总帧数 [4]=当前帧号(1..总) [5..len-2]=payload [len-1]=SN
     * 每帧累加 arrayLen - 6 字节,payload 从 index 5 开始拷;
     *   p[4] < p[3] 未完成(返回 false),p[4] == p[3] 完成(返回 true)。
     */
    public static final class Reassembler {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private boolean done;

        public void reset() {
            buf.reset();
            done = false;
        }

        public boolean isDone() {
            return done;
        }

        /** @return 是否在此帧后完成 */
        public boolean feed(byte[] p) {
            if (p == null || p.length < 6) return false;
            int total = p[3] & 0xff;
            int idx = p[4] & 0xff;
            int n = p.length - 6;
            buf.write(p, 5, n);
            if (idx >= total) {
                done = true;
                return true;
            }
            return false;
        }

        public byte[] data() {
            return buf.toByteArray();
        }
    }
}
