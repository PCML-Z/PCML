package com.pmcl.core.auth;

/**
 * 设备码响应：用户在浏览器输入 userCode 完成登录。
 */
public final class DeviceCode {

    private final String deviceCode;
    private final String userCode;
    private final String verificationUri;
    private final int expiresIn;
    private final int interval;
    private final String message;

    public DeviceCode(String deviceCode, String userCode, String verificationUri,
                      int expiresIn, int interval, String message) {
        this.deviceCode = deviceCode;
        this.userCode = userCode;
        this.verificationUri = verificationUri;
        this.expiresIn = expiresIn;
        this.interval = interval;
        this.message = message;
    }

    public String getDeviceCode() { return deviceCode; }
    public String getUserCode() { return userCode; }
    public String getVerificationUri() { return verificationUri; }
    public int getExpiresIn() { return expiresIn; }
    public int getInterval() { return interval; }
    public String getMessage() { return message; }
}
