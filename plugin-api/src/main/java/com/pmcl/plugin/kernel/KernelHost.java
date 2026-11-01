package com.pmcl.plugin.kernel;

import java.nio.file.Path;

/**
 * 启动器交给内核的窄接口。内核与 PMCL 在同一进程或同一用户权限下运行。
 */
public interface KernelHost {

    /** 这个内核可写的数据目录，位于已安装内核目录下的 data。 */
    Path dataDirectory();

    /** 写一条诊断信息，启动器记到错误输出。 */
    void info(String message);
}
