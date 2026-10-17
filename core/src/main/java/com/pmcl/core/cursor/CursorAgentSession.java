package com.pmcl.core.cursor;

import com.google.gson.JsonObject;

/**
 * 启动器交给本机 MCP 的操作面。实现里只能放界面上已经有的动作，
 * 不能返回账号令牌、密码或游戏日志。
 */
public interface CursorAgentSession {
    String launcherVersion();

    JsonObject status();

    JsonObject versions();

    JsonObject instances();

    JsonObject accounts();

    JsonObject pages();

    JsonObject openPage(String route, String section);

    JsonObject selectVersion(String versionId);

    JsonObject launchVersion(String versionId);

    JsonObject launchInstance(String instanceId);

    JsonObject launchSelected();

    JsonObject refresh();
}
