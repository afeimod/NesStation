package org.citra.citra_emu.applets;

/**
 * Mii 选择 applet —— 与上游 org.citra.citra_emu.applets.MiiSelector 一致：
 * native 构造 MiiSelectorConfig（按字段名 enableCancelButton/
 * initiallySelectedMiiIndex/miiNames/title 写入），调用 static Execute(config)
 * 阻塞等待，返回 MiiSelectorData（returnCode: 0=选定, 1=取消）。
 *
 * NesStation 简化实现：直接返回「取消」—— 绝大多数游戏在取消时回退
 * 系统 Mii（game 侧不影响运行）。返回数据字段名 returnCode/index 被
 * native 直接读取（GetLongField/GetIntField），勿改名。
 */
public final class MiiSelector {

    public static final MiiSelector INSTANCE = new MiiSelector();

    private MiiSelector() {
    }

    public static final class MiiSelectorConfig
        implements java.io.Serializable {
        private boolean enableCancelButton;
        private long initiallySelectedMiiIndex;
        public String[] miiNames;
        private String title;

        public MiiSelectorConfig() {
        }

        public final boolean getEnableCancelButton() {
            return enableCancelButton;
        }

        public final void setEnableCancelButton(boolean v) {
            this.enableCancelButton = v;
        }

        public final long getInitiallySelectedMiiIndex() {
            return initiallySelectedMiiIndex;
        }

        public final void setInitiallySelectedMiiIndex(long v) {
            this.initiallySelectedMiiIndex = v;
        }

        public final String[] getMiiNames() {
            return miiNames;
        }

        public final void setMiiNames(String[] v) {
            this.miiNames = v;
        }

        public final String getTitle() {
            return title;
        }

        public final void setTitle(String v) {
            this.title = v;
        }
    }

    public static final class MiiSelectorData {
        private long returnCode;
        private int index;

        public MiiSelectorData(long returnCode, int index) {
            this.returnCode = returnCode;
            this.index = index;
        }

        public final long getReturnCode() {
            return returnCode;
        }

        public final void setReturnCode(long v) {
            this.returnCode = v;
        }

        public final int getIndex() {
            return index;
        }

        public final void setIndex(int v) {
            this.index = v;
        }
    }

    /** native 调用：返回取消（returnCode=1），游戏回退默认 Mii。 */
    public static MiiSelectorData Execute(MiiSelectorConfig config) {
        android.util.Log.i("CitraHost", "MiiSelector.Execute -> cancel (returnCode=1)");
        return new MiiSelectorData(1, 0);
    }
}
