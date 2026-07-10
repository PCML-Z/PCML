package com.pmcl.core.install;

/**
 * 安装进度事件，UI 通过它显示进度条与状态文本。
 */
public final class InstallProgress {

    public enum Stage {
        DOWNLOAD_VERSION_JSON,
        DOWNLOAD_CLIENT,
        DOWNLOAD_LIBRARIES,
        DOWNLOAD_ASSET_INDEX,
        DOWNLOAD_ASSETS,
        DONE,
        FAILED
    }

    private final Stage stage;
    private final long completed;
    private final long total;
    private final String message;

    public InstallProgress(Stage stage, long completed, long total, String message) {
        this.stage = stage;
        this.completed = completed;
        this.total = total;
        this.message = message;
    }

    public Stage getStage() { return stage; }
    public long getCompleted() { return completed; }
    public long getTotal() { return total; }
    public String getMessage() { return message; }

    public double percent() {
        if (total <= 0) return 0;
        return (completed * 100.0) / total;
    }
}
