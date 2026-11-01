package com.pmcl.plugin.kernel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Java 启动器内核。实现类必须有公开的无参构造，并由清单里的 entry 指向。
 * <p>
 * C# 与 Rust 内核不实现这个接口，它们是独立进程。
 * 启用时从标准输入读一行 {@code {"op":"hello"}}，向标准输出写一行 {@code {"ok":true}}。
 * 接手一次游戏时，启动器另起一个进程，写入同一份启动请求：
 * {@code {"op":"launch","id":"...","directory":"...","command":[...],"env":{...}}}。
 * 这是唯一的启动标准。内核向标准错误写一行 {@code {"ok":true}}，然后执行 command。
 * 标准输出上的 hello 应答不算接手。启动器不会按某个内核的私有接口再生成一套参数。
 * 游戏的标准输出和标准错误都写到内核进程的标准输出，游戏退出后内核以同样的退出码退出。
 * Unix 上应 exec 替换自身，启动器跟踪到的就是游戏进程。
 */
public interface LauncherKernel {

    /** 与 pmcl-kernel.json 里的 id 一致。 */
    String id();

    /** 启用时调用。应尽快返回，长时间工作放到自己的线程里。 */
    default void start(KernelHost host) {
    }

    /** 停用或启动器退出时调用。 */
    default void stop() {
    }

    /**
     * 启动这一次游戏。返回的进程就是游戏进程。
     * {@code appendLog} 非空时把输出追加到该文件；{@code discardOutput} 为真时丢弃输出。
     * 两者都没有时，输出留在管道里交给启动器读取。
     */
    default Process launch(KernelHost host, List<String> command, Path directory,
                           Map<String, String> environment, Path appendLog,
                           boolean discardOutput) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (directory != null) builder.directory(directory.toFile());
        if (environment != null && !environment.isEmpty()) {
            builder.environment().putAll(environment);
        }
        if (discardOutput) {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        } else if (appendLog != null) {
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(appendLog.toFile()));
            builder.redirectErrorStream(true);
        } else {
            builder.redirectErrorStream(true);
        }
        return builder.start();
    }
}
