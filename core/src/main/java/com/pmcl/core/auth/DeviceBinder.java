package com.pmcl.core.auth;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 设备绑定保护：11498 位设备加密码 + RSA-2048 签名许可证。
 * 加密实现部分经过超级强混淆处理。
 */
public final class DeviceBinder {

    public static final int DEVICE_CODE_LENGTH = 11498;
    public static final String LICENSE_PREFIX;
    public static final String EXPORTED_KEY_PREFIX;
    public static final String LOCAL_KEY_PREFIX;

    private static final int _G = 1 << 7;
    private static final int _V = (1 << 4) - 4;
    private static final int _S = 1 << 4;
    private static final int _I = (0x3 << 16) | (0xD << 8) | 0x40;
    private static final int _K = 1 << 8;
    private static final int _R = 1 << 11;

    private static final SecureRandom _RNG = new SecureRandom();

    // ===== 字符串解密器（FNV-1a 流式加密，每串独立 seed） =====
    private static String _x(int _s, int... _e) {
        char[] _c = new char[_e.length];
        int _k = _s ^ 0x5A5A5A5A;
        for (int _i = 0; _i < _e.length; _i++) {
            int _ci = _e[_i];
            _c[_i] = (char) (_ci ^ (_k & 0xFF));
            _k = (_k * 0x01000193) ^ _ci;
            _k ^= (_k >>> 13);
            _k = (_k << 7) | (_k >>> 25);
            _k ^= 0x5A;
        }
        return new String(_c);
    }

    // ===== 不透明谓词（永真/永假，用于迷惑分析者） =====
    private static boolean _q1(int _v) { return (_v * _v + 2 * _v + 1) - ((_v + 1) * (_v + 1)) == 0; }
    private static boolean _q2(int _v) { return (_v | 1) != 0; }
    private static boolean _q3(long _v) { return _v * _v >= 0; }
    private static boolean _q4(int _v) { return (_v * 7 + 1) % 2 != 2; }
    private static boolean _q5(int _v) { return ((_v & 0) ^ 0) == 0; }
    private static int _q6(int _v) { return _q1(_v) ? (_v ^ _v) : (_v | _v); }

    // ===== 加密字符串池 =====
    private static final String _ALG_GCM;
    private static final String _KDF;
    private static final String _SHA;
    private static final String _RSA;
    private static final String _SIG;
    private static final String _AES;
    private static final String _B62;
    private static final char _CH_H;
    private static final char _CH_E;
    private static final char _CH_T;
    private static final char _CH_N;
    private static final String _STR_TRUE;

    static {
        _ALG_GCM = _x(-1582119983, 0xCA, 0x12, 0x7C, 0xDA, 0xDB, 0x6A, 0x76, 0xF4, 0x45, 0xC2, 0x81, 0xD4, 0xAC, 0x22, 0xD6, 0xEC, 0x05);
        _KDF = _x(0x4E5D6C72, 0x78, 0x4F, 0x51, 0xE3, 0xB7, 0x2A, 0xE3, 0xE0, 0x5B, 0xD7, 0x67, 0x06, 0xAB, 0x3A, 0xCC, 0xFA, 0x87, 0x17, 0xCB, 0x67);
        _SHA = _x(0x12345678, 0x71, 0x81, 0x55, 0xFD, 0x2B, 0x13, 0xC1);
        _RSA = _x(-559038737, 0xE7, 0x0A, 0x9A);
        _SIG = _x(-889275714, 0xB7, 0xD9, 0xBA, 0x40, 0x62, 0x4F, 0x68, 0x53, 0x9F, 0x94, 0x60, 0x59, 0xB1);
        _AES = _x(0x0BADC0DE, 0xC5, 0x59, 0xF3);
        LICENSE_PREFIX = _x(-17958194, 0xE4, 0x47, 0xF0, 0xBB, 0x6E, 0x51, 0xC5, 0x5E, 0x6A, 0xEC, 0xF3, 0x80, 0xEF, 0x6B, 0x40, 0x93);
        EXPORTED_KEY_PREFIX = _x(-1379860498, 0xC4, 0x09, 0x78, 0xB0, 0xC4, 0x77, 0xF9, 0x08, 0xC6, 0xD2, 0x86, 0x2F);
        LOCAL_KEY_PREFIX = _x(0x1234ABCD, 0xE7, 0x79, 0x6F, 0x22, 0x6A, 0x5B, 0x4A, 0x44, 0x90, 0x14, 0x6D, 0x21, 0xDF, 0xCE, 0xDE, 0x71, 0xBC);
        _B62 = _x(0x5A5A5A5A, 0x30, 0x6B, 0xC5, 0xB3, 0x88, 0xBA, 0xD2, 0x69, 0x93, 0xFB, 0xF5, 0x76, 0x77, 0xE7, 0x30, 0x4B, 0xA3, 0x41, 0x54, 0x52, 0xD5, 0x6D, 0xB6, 0x2A, 0x39, 0x10, 0x5A, 0x7C, 0xD4, 0x86, 0x13, 0x3C, 0x32, 0xB9, 0x4D, 0x4E, 0x89, 0x45, 0xA4, 0xC0, 0x92, 0x50, 0x6E, 0xDC, 0x0A, 0x08, 0x57, 0xDD, 0x41, 0x8E, 0xED, 0xAC, 0x7F, 0x41, 0x72, 0x54, 0x72, 0x0D, 0xF5, 0x4C, 0x3D, 0xCA);
        _STR_TRUE = _x(0x11111111, 0x3F, 0x41, 0xCA, 0xEB);
        _CH_H = _x(0x44444444, 0x76).charAt(0);
        _CH_E = _x(0x55555555, 0x6A).charAt(0);
        _CH_T = _x(0x66666666, 0x48).charAt(0);
        _CH_N = _x(0x77777777, 0x43).charAt(0);
    }

    private DeviceBinder() {}

    // ===== 设备指纹采集 =====

    public static String collectFingerprint() {
        StringBuilder _fp = new StringBuilder(256);
        int _st = 0;
        while (_st < 5) {
            switch (_st) {
                case 0:
                    try {
                        oshi.SystemInfo _si = new oshi.SystemInfo();
                        oshi.hardware.HardwareAbstractionLayer _hw = _si.getHardware();
                        oshi.software.os.OperatingSystem _os = _si.getOperatingSystem();
                        _a(_fp, _hw);
                        _b(_fp, _hw);
                        _c(_fp, _hw);
                        _d(_fp, _os);
                        _st = 5;
                    } catch (Throwable _t) {
                        _fp.append("err=").append(_t.getClass().getSimpleName()).append('\n');
                        _st = 5;
                    }
                    break;
                case 1: _st = _q1(3) ? 5 : 5; break;
                case 2: _st = _q2(0) ? 5 : 5; break;
                case 3: _st = 5; break;
                case 4: _st = 5; break;
                default: _st = 5;
            }
        }
        _e(_fp);
        return _fp.toString();
    }

    private static void _a(StringBuilder _fp, oshi.hardware.HardwareAbstractionLayer _hw) {
        int _st = 0;
        while (_st != 9) {
            switch (_st) {
                case 0:
                    try {
                        oshi.hardware.CentralProcessor _cpu = _hw.getProcessor();
                        oshi.hardware.CentralProcessor.ProcessorIdentifier _pid = _cpu.getProcessorIdentifier();
                        _fp.append("cpu=").append(_pid.getVendor())
                           .append('|').append(_pid.getFamily())
                           .append('|').append(_pid.getModel())
                           .append('|').append(_pid.getStepping())
                           .append('|').append(_pid.getMicroarchitecture())
                           .append('|').append(_pid.getProcessorID())
                           .append('\n');
                        _st = 9;
                    } catch (Throwable _t) {
                        _fp.append("cpu=?\n");
                        _st = 9;
                    }
                    break;
                case 1: _st = _q1(7) ? 9 : 9; break;
                case 2: _st = _q5(99) ? 9 : 9; break;
                default: _st = 9;
            }
        }
    }

    private static void _b(StringBuilder _fp, oshi.hardware.HardwareAbstractionLayer _hw) {
        int _st = 0;
        loop:
        while (true) {
            switch (_st) {
                case 0:
                    try {
                        oshi.hardware.ComputerSystem _cs = _hw.getComputerSystem();
                        _fp.append("board=").append(_s(_cs.getBaseboard().getManufacturer()))
                           .append('|').append(_s(_cs.getBaseboard().getModel()))
                           .append('|').append(_s(_cs.getBaseboard().getSerialNumber()))
                           .append('|').append(_s(_cs.getBaseboard().getVersion()))
                           .append('\n');
                        _fp.append("system=").append(_s(_cs.getManufacturer()))
                           .append('|').append(_s(_cs.getModel()))
                           .append('|').append(_s(_cs.getSerialNumber()))
                           .append('\n');
                        _st = 1;
                    } catch (Throwable _t) {
                        _fp.append("board=?\nsystem=?\n");
                        _st = 3;
                    }
                    break;
                case 1:
                    try {
                        oshi.hardware.Firmware _fw = _hw.getComputerSystem().getFirmware();
                        _fp.append("fw=").append(_s(_fw.getManufacturer()))
                           .append('|').append(_s(_fw.getName()))
                           .append('|').append(_s(_fw.getVersion()))
                           .append('|').append(_s(_fw.getReleaseDate()))
                           .append('\n');
                        _st = _q2(1) ? 3 : 3;
                    } catch (Throwable _t) {
                        _fp.append("fw=?\n");
                        _st = 3;
                    }
                    break;
                case 2: _st = 3; break;
                case 3: break loop;
                default: _st = 3;
            }
        }
    }

    private static void _c(StringBuilder _fp, oshi.hardware.HardwareAbstractionLayer _hw) {
        int _st = 0;
        while (_st != 7) {
            switch (_st) {
                case 0:
                    try {
                        _fp.append("mac=").append(_mac(_hw)).append('\n');
                        _st = 7;
                    } catch (Throwable _t) {
                        _fp.append("mac=?\n");
                        _st = 7;
                    }
                    break;
                case 1: _st = _q3(42L) ? 7 : 7; break;
                case 2: _st = 7; break;
                default: _st = 7;
            }
        }
    }

    private static void _d(StringBuilder _fp, oshi.software.os.OperatingSystem _os) {
        int _st = 0;
        while (_st != 5) {
            switch (_st) {
                case 0:
                    try {
                        _fp.append("os=").append(_os.getManufacturer())
                           .append('|').append(_os.getFamily())
                           .append('|').append(_os.getVersionInfo().getVersion())
                           .append('|').append(_os.getBitness())
                           .append('\n');
                        _st = 5;
                    } catch (Throwable _t) {
                        _fp.append("os=?\n");
                        _st = 5;
                    }
                    break;
                case 1: _st = _q4(13) ? 5 : 5; break;
                default: _st = 5;
            }
        }
    }

    private static void _e(StringBuilder _fp) {
        int _st = 0;
        while (_st != 3) {
            switch (_st) {
                case 0:
                    _fp.append("user=").append(System.getProperty("user.name", "unknown"))
                       .append('|').append(System.getProperty("user.home", "/tmp"))
                       .append('|').append(System.getProperty("os.name", "unknown"))
                       .append('|').append(System.getProperty("os.arch", "unknown"))
                       .append('\n');
                    _st = _q1(0) ? 3 : 3;
                    break;
                default: _st = 3;
            }
        }
    }

    private static String _s(String _v) {
        return _v == null ? "?" : _v;
    }

    private static String _mac(oshi.hardware.HardwareAbstractionLayer _hw) {
        try {
            java.util.List<oshi.hardware.NetworkIF> _nics = _hw.getNetworkIFs();
            int _ph1 = 0;
            while (_ph1 < 2) {
                switch (_ph1) {
                    case 0:
                        for (oshi.hardware.NetworkIF _nic : _nics) {
                            String _m = _nic.getMacaddr();
                            if (_m == null || _m.isEmpty() || _m.equals("00:00:00:00:00:00")) continue;
                            String _n = _nic.getName() == null ? "" : _nic.getName().toLowerCase();
                            String _dd = _nic.getDisplayName() == null ? "" : _nic.getDisplayName().toLowerCase();
                            if (_n.contains("virtual") || _n.contains("tap") || _n.contains("tun")
                                || _n.contains("vmnet") || _n.contains("docker")
                                || _dd.contains("virtual") || _dd.contains("tap")) continue;
                            return _m;
                        }
                        _ph1 = 1;
                        break;
                    case 1:
                        for (oshi.hardware.NetworkIF _nic : _nics) {
                            String _m = _nic.getMacaddr();
                            if (_m != null && !_m.isEmpty() && !_m.equals("00:00:00:00:00:00")) return _m;
                        }
                        _ph1 = 2;
                        break;
                    default: _ph1 = 2;
                }
            }
        } catch (Throwable _t) {}
        return "unknown";
    }

    // ===== 11498 位设备码生成（强混淆核心） =====

    public static String getDeviceCode() {
        return _gen(collectFingerprint());
    }

    static String _gen(String _fp) {
        try {
            MessageDigest _md = MessageDigest.getInstance(_SHA);
            byte[] _seed = _md.digest(_fp.getBytes(StandardCharsets.UTF_8));
            int _tb = 0x43 << 7;
            byte[] _mat = _gen_mat(_md, _seed, _tb);
            return _enc(_mat);
        } catch (Throwable _t) {
            throw new RuntimeException(_t.getMessage(), _t);
        }
    }

    private static byte[] _gen_mat(MessageDigest _md, byte[] _seed, int _tb) {
        byte[] _mat = new byte[_tb];
        int _off = 0;
        int _cnt = 0;
        int _state = 0;
        while (_off < _tb) {
            switch (_state) {
                case 0: {
                    ByteBuffer _bb = ByteBuffer.allocate(_seed.length + 4);
                    _bb.put(_seed);
                    _bb.putInt(_cnt);
                    byte[] _h = _md.digest(_bb.array());
                    int _cp = Math.min(_h.length, _tb - _off);
                    System.arraycopy(_h, 0, _mat, _off, _cp);
                    _off += _cp;
                    _cnt++;
                    _state = _q1(_cnt) ? 1 : 1;
                    break;
                }
                case 1: {
                    int _junk = (_cnt * 31) ^ 0xDEAD;
                    if (_junk == 0) _state = 2; else _state = 0;
                    if (!_q2(_cnt)) _state = 0;
                    break;
                }
                case 2: _state = _q5(_off) ? 0 : 0; break;
                case 3: _state = 0; break;
                case 4: _state = 0; break;
                default: _state = 0;
            }
        }
        return _mat;
    }

    private static String _enc(byte[] _mat) {
        java.math.BigInteger _num = new java.math.BigInteger(1, _mat);
        StringBuilder _sb = new StringBuilder(DEVICE_CODE_LENGTH);
        java.math.BigInteger _b = java.math.BigInteger.valueOf(62);
        int _state = 0;
        while (_state != 9) {
            switch (_state) {
                case 0:
                    if (_num.compareTo(java.math.BigInteger.ZERO) > 0) {
                        java.math.BigInteger[] _dm = _num.divideAndRemainder(_b);
                        _sb.append(_B62.charAt(_dm[1].intValue()));
                        _num = _dm[0];
                        _state = _q1(1) ? 1 : 1;
                    } else {
                        _state = 2;
                    }
                    break;
                case 1:
                    _state = _q3(_sb.length()) ? 0 : 0;
                    break;
                case 2:
                    _sb.reverse();
                    _state = _q2(3) ? 3 : 3;
                    break;
                case 3:
                    while (_sb.length() < DEVICE_CODE_LENGTH) _sb.insert(0, _B62.charAt(0));
                    if (_sb.length() > DEVICE_CODE_LENGTH) _sb.setLength(DEVICE_CODE_LENGTH);
                    _state = 9;
                    break;
                case 4: _state = 0; break;
                case 5: _state = 0; break;
                default: _state = 0;
            }
        }
        return _sb.toString();
    }

    public static String hashDeviceCode(String _dc) {
        try {
            byte[] _h = MessageDigest.getInstance(_SHA)
                    .digest(_dc.getBytes(StandardCharsets.UTF_8));
            StringBuilder _sb = new StringBuilder(_h.length * 2);
            int _st = 0;
            for (byte _b : _h) {
                switch (_st) {
                    case 0:
                        _sb.append(String.format("%02x", _b & 0xff));
                        _st = _q1(_b & 0xff) ? 0 : 0;
                        break;
                    default: _st = 0;
                }
            }
            return _sb.toString();
        } catch (Throwable _t) {
            throw new RuntimeException(_t);
        }
    }

    // ===== RSA 密钥对 =====

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator _g = KeyPairGenerator.getInstance(_RSA);
            _g.initialize(_R, _RNG);
            return _g.generateKeyPair();
        } catch (Throwable _t) {
            throw new RuntimeException(_t.getMessage(), _t);
        }
    }

    public static PrivateKey loadPrivateKey(byte[] _der) {
        try {
            return java.security.KeyFactory.getInstance(_RSA)
                    .generatePrivate(new PKCS8EncodedKeySpec(_der));
        } catch (Throwable _t) {
            throw new RuntimeException(_t.getMessage(), _t);
        }
    }

    public static PublicKey loadPublicKey(byte[] _der) {
        try {
            return java.security.KeyFactory.getInstance(_RSA)
                    .generatePublic(new X509EncodedKeySpec(_der));
        } catch (Throwable _t) {
            throw new RuntimeException(_t.getMessage(), _t);
        }
    }

    public static byte[] privateKeyToDer(PrivateKey _k) { return _k.getEncoded(); }
    public static byte[] publicKeyToDer(PublicKey _k) { return _k.getEncoded(); }
    public static String toBase64(byte[] _d) { return Base64.getEncoder().encodeToString(_d); }
    public static byte[] fromBase64(String _s) { return Base64.getDecoder().decode(_s); }

    // ===== 许可证签发与验证 =====

    public static String signLicense(String _dch, boolean _en, PrivateKey _pk) {
        try {
            long _ts = System.currentTimeMillis();
            long _nn = _RNG.nextLong();
            byte[] _pbb = _buildPayload(_dch, _en, _ts, _nn);
            Signature _sg = Signature.getInstance(_SIG);
            _sg.initSign(_pk);
            _sg.update(_pbb);
            byte[] _sig = _sg.sign();
            return LICENSE_PREFIX
                    + Base64.getEncoder().encodeToString(_pbb)
                    + "."
                    + Base64.getEncoder().encodeToString(_sig);
        } catch (Throwable _t) {
            throw new RuntimeException(_t.getMessage(), _t);
        }
    }

    private static byte[] _buildPayload(String _dch, boolean _en, long _ts, long _nn) {
        StringBuilder _pb = new StringBuilder();
        int _st = 0;
        while (_st != 5) {
            switch (_st) {
                case 0:
                    _pb.append('{').append('"').append(_CH_H).append('"').append(':').append('"').append(_dch).append('"');
                    _st = _q1(1) ? 1 : 1;
                    break;
                case 1:
                    _pb.append(',').append('"').append(_CH_E).append('"').append(':').append(_en);
                    _st = _q2(2) ? 2 : 2;
                    break;
                case 2:
                    _pb.append(',').append('"').append(_CH_T).append('"').append(':').append(_ts);
                    _st = _q3(_ts) ? 3 : 3;
                    break;
                case 3:
                    _pb.append(',').append('"').append(_CH_N).append('"').append(':').append(_nn).append('}');
                    _st = 4;
                    break;
                case 4: _st = 5; break;
                default: _st = 5;
            }
        }
        return _pb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static boolean verifyLicense(String _lic, String _dch, PublicKey _pk) {
        if (!_chk_prefix(_lic, LICENSE_PREFIX)) return false;
        try {
            String _rest = _lic.substring(LICENSE_PREFIX.length());
            int _dot = _rest.indexOf('.');
            if (_dot < 0) return false;
            byte[] _pb = Base64.getDecoder().decode(_rest.substring(0, _dot));
            byte[] _sig = Base64.getDecoder().decode(_rest.substring(_dot + 1));
            Signature _s = Signature.getInstance(_SIG);
            _s.initVerify(_pk);
            _s.update(_pb);
            if (!_s.verify(_sig)) return false;
            String _pl = new String(_pb, StandardCharsets.UTF_8);
            String _h = _fld(_pl);
            boolean _en = _bool(_pl);
            if (!_en) return false;
            return _h != null && _h.equals(_dch);
        } catch (Throwable _t) {
            return false;
        }
    }

    public static boolean isLicenseEnabled(String _lic) {
        if (!_chk_prefix(_lic, LICENSE_PREFIX)) return false;
        try {
            String _rest = _lic.substring(LICENSE_PREFIX.length());
            int _dot = _rest.indexOf('.');
            if (_dot < 0) return false;
            byte[] _pb = Base64.getDecoder().decode(_rest.substring(0, _dot));
            String _pl = new String(_pb, StandardCharsets.UTF_8);
            return _bool(_pl);
        } catch (Throwable _t) {
            return false;
        }
    }

    private static boolean _chk_prefix(String _s, String _p) {
        if (_s == null) return false;
        int _st = 0;
        boolean _r = false;
        while (_st != 3) {
            switch (_st) {
                case 0: _r = _s.startsWith(_p); _st = _q1(0) ? 1 : 1; break;
                case 1: _st = 2; break;
                case 2: _st = 3; break;
                default: _st = 3;
            }
        }
        return _r;
    }

    private static String _fld(String _j) {
        StringBuilder _k = new StringBuilder();
        _k.append('"').append(_CH_H).append('"').append(':').append('"');
        int _i = _j.indexOf(_k.toString());
        if (_i < 0) return null;
        int _st = _i + _k.length();
        int _en = _j.indexOf('"', _st);
        if (_en < 0) return null;
        return _j.substring(_st, _en);
    }

    private static boolean _bool(String _j) {
        StringBuilder _k = new StringBuilder();
        _k.append('"').append(_CH_E).append('"').append(':');
        int _i = _j.indexOf(_k.toString());
        if (_i < 0) return false;
        int _st = _i + _k.length();
        int _st2 = _st;
        boolean _r = false;
        int _ph = 0;
        while (_ph != 3) {
            switch (_ph) {
                case 0:
                    if (_st2 + 4 <= _j.length() && _j.regionMatches(_st2, _STR_TRUE, 0, 4)) {
                        _r = true;
                    }
                    _ph = _q1(1) ? 1 : 1;
                    break;
                case 1: _ph = 2; break;
                case 2: _ph = 3; break;
                default: _ph = 3;
            }
        }
        return _r;
    }

    // ===== 私钥加密存储 =====

    public static String encryptPrivateKey(PrivateKey _k, char[] _pwd) {
        try {
            byte[] _salt = new byte[_S]; _RNG.nextBytes(_salt);
            byte[] _iv = new byte[_V]; _RNG.nextBytes(_iv);
            SecretKey _ak = _dk(_pwd, _salt);
            Cipher _c = Cipher.getInstance(_ALG_GCM);
            _c.init(Cipher.ENCRYPT_MODE, _ak, new GCMParameterSpec(_G, _iv));
            byte[] _ct = _c.doFinal(_k.getEncoded());
            ByteBuffer _buf = ByteBuffer.allocate(_salt.length + _iv.length + _ct.length);
            _buf.put(_salt).put(_iv).put(_ct);
            return EXPORTED_KEY_PREFIX + Base64.getEncoder().encodeToString(_buf.array());
        } catch (Throwable _t) {
            throw new RuntimeException(_t.getMessage(), _t);
        }
    }

    public static PrivateKey decryptPrivateKey(String _enc, char[] _pwd) {
        if (!_chk_prefix(_enc, EXPORTED_KEY_PREFIX)) {
            throw new IllegalArgumentException("invalid");
        }
        try {
            byte[] _all = Base64.getDecoder().decode(_enc.substring(EXPORTED_KEY_PREFIX.length()));
            ByteBuffer _buf = ByteBuffer.wrap(_all);
            byte[] _salt = new byte[_S]; byte[] _iv = new byte[_V];
            _buf.get(_salt); _buf.get(_iv);
            byte[] _ct = new byte[_buf.remaining()]; _buf.get(_ct);
            SecretKey _ak = _dk(_pwd, _salt);
            Cipher _c = Cipher.getInstance(_ALG_GCM);
            _c.init(Cipher.DECRYPT_MODE, _ak, new GCMParameterSpec(_G, _iv));
            byte[] _pk = _c.doFinal(_ct);
            return loadPrivateKey(_pk);
        } catch (Throwable _t) {
            throw new RuntimeException(_t.getMessage(), _t);
        }
    }

    public static String encryptPrivateKeyWithDeviceCode(PrivateKey _k, String _dc) {
        try {
            byte[] _salt = new byte[_S]; _RNG.nextBytes(_salt);
            byte[] _iv = new byte[_V]; _RNG.nextBytes(_iv);
            SecretKey _ak = _dk(_dc.toCharArray(), _salt);
            Cipher _c = Cipher.getInstance(_ALG_GCM);
            _c.init(Cipher.ENCRYPT_MODE, _ak, new GCMParameterSpec(_G, _iv));
            byte[] _ct = _c.doFinal(_k.getEncoded());
            ByteBuffer _buf = ByteBuffer.allocate(_salt.length + _iv.length + _ct.length);
            _buf.put(_salt).put(_iv).put(_ct);
            return LOCAL_KEY_PREFIX + Base64.getEncoder().encodeToString(_buf.array());
        } catch (Throwable _t) {
            throw new RuntimeException(_t.getMessage(), _t);
        }
    }

    public static PrivateKey decryptPrivateKeyWithDeviceCode(String _enc, String _dc) {
        if (!_chk_prefix(_enc, LOCAL_KEY_PREFIX)) {
            throw new IllegalArgumentException("invalid");
        }
        try {
            byte[] _all = Base64.getDecoder().decode(_enc.substring(LOCAL_KEY_PREFIX.length()));
            ByteBuffer _buf = ByteBuffer.wrap(_all);
            byte[] _salt = new byte[_S]; byte[] _iv = new byte[_V];
            _buf.get(_salt); _buf.get(_iv);
            byte[] _ct = new byte[_buf.remaining()]; _buf.get(_ct);
            SecretKey _ak = _dk(_dc.toCharArray(), _salt);
            Cipher _c = Cipher.getInstance(_ALG_GCM);
            _c.init(Cipher.DECRYPT_MODE, _ak, new GCMParameterSpec(_G, _iv));
            byte[] _pk = _c.doFinal(_ct);
            return loadPrivateKey(_pk);
        } catch (Throwable _t) {
            throw new RuntimeException(_t.getMessage(), _t);
        }
    }

    private static SecretKey _dk(char[] _pwd, byte[] _salt) throws Exception {
        PBEKeySpec _sp = new PBEKeySpec(_pwd, _salt, _I, _K);
        SecretKeyFactory _f = SecretKeyFactory.getInstance(_KDF);
        byte[] _kb = _f.generateSecret(_sp).getEncoded();
        _sp.clearPassword();
        return new SecretKeySpec(_kb, _AES);
    }

    public static boolean keyPairMatches(PrivateKey _pr, PublicKey _pu) {
        try {
            byte[] _d = new byte[32]; _RNG.nextBytes(_d);
            Signature _s = Signature.getInstance(_SIG);
            _s.initSign(_pr); _s.update(_d);
            byte[] _sig = _s.sign();
            _s.initVerify(_pu); _s.update(_d);
            return _s.verify(_sig);
        } catch (Throwable _t) {
            return false;
        }
    }

    // ===== 高级 API =====

    public static EnableResult enableProtection(char[] _pwd) {
        if (_pwd == null || _pwd.length == 0) {
            throw new IllegalArgumentException("pwd");
        }
        String _dc = getDeviceCode();
        String _dch = hashDeviceCode(_dc);
        KeyPair _kp = generateKeyPair();
        PrivateKey _pr = _kp.getPrivate();
        PublicKey _pu = _kp.getPublic();
        String _pub = toBase64(publicKeyToDer(_pu));
        String _lic = signLicense(_dch, true, _pr);
        String _lk = encryptPrivateKeyWithDeviceCode(_pr, _dc);
        String _ek = encryptPrivateKey(_pr, _pwd);
        return new EnableResult(_pub, _dch, _lic, _lk, _ek, _dc);
    }

    public static String disableProtection(String _ek, char[] _pwd, String _pub) {
        try {
            PrivateKey _pr = decryptPrivateKey(_ek, _pwd);
            PublicKey _pu = loadPublicKey(fromBase64(_pub));
            if (!keyPairMatches(_pr, _pu)) return null;
            String _dc = getDeviceCode();
            String _dch = hashDeviceCode(_dc);
            return signLicense(_dch, false, _pr);
        } catch (Throwable _t) {
            return null;
        }
    }

    public static ReenableResult reenableProtection(String _ek, char[] _pwd, String _pub) {
        try {
            PrivateKey _pr = decryptPrivateKey(_ek, _pwd);
            PublicKey _pu = loadPublicKey(fromBase64(_pub));
            if (!keyPairMatches(_pr, _pu)) return null;
            String _dc = getDeviceCode();
            String _dch = hashDeviceCode(_dc);
            String _lic = signLicense(_dch, true, _pr);
            String _lk = encryptPrivateKeyWithDeviceCode(_pr, _dc);
            return new ReenableResult(_lic, _lk, _dch);
        } catch (Throwable _t) {
            return null;
        }
    }

    public static boolean verifyOnLaunch(String _lic, String _pub) {
        if (_lic == null || _lic.isEmpty() || _pub == null || _pub.isEmpty()) return true;
        if (!_chk_prefix(_lic, LICENSE_PREFIX)) return true;
        if (!isLicenseEnabled(_lic)) return true;
        try {
            PublicKey _pk = loadPublicKey(fromBase64(_pub));
            String _dc = getDeviceCode();
            String _dch = hashDeviceCode(_dc);
            return verifyLicense(_lic, _dch, _pk);
        } catch (Throwable _t) {
            return false;
        }
    }

    // ===== 结果类 =====

    public static final class EnableResult {
        public final String publicKeyB64;
        public final String deviceCodeHash;
        public final String license;
        public final String localKeyEnc;
        public final String exportedKey;
        public final String deviceCode;
        public EnableResult(String _a, String _b, String _c, String _d, String _e, String _f) {
            publicKeyB64 = _a; deviceCodeHash = _b; license = _c;
            localKeyEnc = _d; exportedKey = _e; deviceCode = _f;
        }
    }

    public static final class ReenableResult {
        public final String license;
        public final String localKeyEnc;
        public final String deviceCodeHash;
        public ReenableResult(String _a, String _b, String _c) {
            license = _a; localKeyEnc = _b; deviceCodeHash = _c;
        }
    }
}
