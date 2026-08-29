import com.pmcl.mario.Decor;
import com.pmcl.mario.Game;
import com.pmcl.mario.Input;
import com.pmcl.mario.LevelData;
import com.pmcl.mario.LevelKt;
import com.pmcl.mario.Spawn;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import java.util.Locale;

/**
 * 马里奥插件的无头冒烟测试。
 *
 * 不启动 JavaFX 工具箱、不依赖 PMCL 宿主，直接驱动 [Game] 的定步长主循环，
 * 用来验证：关卡数据、落地判定、跳跃高度、坑洞可通过性，以及旗杆→城堡→过关
 * 的整条终局流程。
 *
 * 运行：mario-plugin/tools/smoke/run.sh
 *
 * 它抓出过两个真 bug，改动 Level.kt / Game.kt 后务必重跑：
 *   1) 落地吸附多减了 0.01px，导致 onGround 隔帧为假（跳跃输入约一半被吞）；
 *   2) 深坑助跑区正上方有砖块，一起跳就撞头掉坑。
 */
public class Smoke {

    static final Function1<Integer, Unit> NOOP = v -> Unit.INSTANCE;
    static final float DT = 1f / 60f;
    static final int COLS = 210;

    static LevelData lv;
    static int failures = 0;

    public static void main(String[] args) {
        lv = LevelKt.buildLevel();
        checkLevel();
        checkGrounding();
        checkJump();
        runBot();
        System.out.println(failures == 0 ? "\n=== ALL CHECKS PASSED ===" : "\n=== " + failures + " CHECK(S) FAILED ===");
        if (failures > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ 关卡

    static void checkLevel() {
        System.out.println("== 关卡数据 ==");
        System.out.println("  grid=" + lv.getGrid()[0].length + "x" + lv.getGrid().length
                + " widthPx=" + lv.getWidthPx() + " flagX=" + lv.getFlagX() + " castleX=" + lv.getCastleX());
        int goomba = 0, koopa = 0, pits = 0;
        for (Spawn s : lv.getSpawns())
            if (s.getKind().name().equals("GOOMBA")) goomba++; else koopa++;
        for (int x = 0; x < COLS; x++) if (lv.getGrid()[13][x] == ' ') pits++;
        System.out.println("  装饰=" + lv.getDecor().size() + " 敌人: 栗宝宝=" + goomba
                + " 慢慢龟=" + koopa + " 深坑列数=" + pits);
        for (Decor d : lv.getDecor()) {
            if (d.getTx() < 0 || d.getTx() >= COLS) { fail("装饰越界 tx=" + d.getTx()); }
        }

        // 每个坑的前 3 列不能有 row 8~12 的实心方块，否则助跑起跳会撞头掉坑
        for (int x = 1; x < COLS; x++) {
            if (lv.getGrid()[13][x] != ' ' || lv.getGrid()[13][x - 1] == ' ') continue;
            for (int d = 1; d <= 3; d++) {
                int c = x - d;
                for (int y = 8; y <= 12; y++) {
                    char t = lv.getGrid()[y][c];
                    if (t == 'B' || t == '?' || t == '!' || t == '#' || t == 'P') {
                        fail("坑@col" + x + " 的助跑区 (" + c + "," + y + ") 被 '" + t + "' 挡住");
                    }
                }
            }
        }
        System.out.println("  坑前助跑区: " + (failures == 0 ? "OK" : "见上方告警"));
    }

    // ---------------------------------------------------------------- 静止

    static void checkGrounding() {
        System.out.println("\n== 静止稳定性 ==");
        Game g = new Game(0, NOOP);
        Input in = new Input();
        g.confirm();
        int grounded = 0;
        for (int i = 0; i < 120; i++) {
            g.update(DT, in);
            if (g.debug().contains("ground=true")) grounded++;
        }
        System.out.println("  着地帧 " + grounded + "/120  " + g.debug());
        if (grounded < 118) fail("着地判定不稳定（" + grounded + "/120），跳跃会间歇失灵");
    }

    // ---------------------------------------------------------------- 跳跃

    static void checkJump() {
        System.out.println("\n== 跳跃 ==");
        Game g = new Game(0, NOOP);
        Input in = new Input();
        g.confirm();
        for (int i = 0; i < 30; i++) g.update(DT, in);
        float groundY = field(g, "y=");
        in.setJump(true);
        g.update(DT, in);
        float minY = groundY;
        for (int i = 0; i < 90; i++) {
            g.update(DT, in);
            minY = Math.min(minY, field(g, "y="));
        }
        in.setJump(false);
        for (int i = 0; i < 60; i++) g.update(DT, in);
        float h = groundY - minY;
        System.out.printf(Locale.US, "  跳跃高度 %.1fpx = %.2f 格，落回 y=%.1f%n", h, h / 16.0, field(g, "y="));
        if (h < 64) fail("跳跃高度只有 " + String.format(Locale.US, "%.1f", h) + "px，上不去 4 格高的方块");
        if (Math.abs(field(g, "y=") - groundY) > 1f) fail("跳跃后没有落回地面");
    }

    // ---------------------------------------------------------------- 跑关

    /** 只会「看到坑/敌人/墙就跳」的机器人，用来确认关卡确实可通行。 */
    static void runBot() {
        System.out.println("\n== 机器人跑关（最多 120 秒）==");
        Game bot = new Game(0, NOOP);
        bot.confirm();
        Input in = new Input();
        in.setRight(true);
        in.setFire(true);
        int jumpTimer = 0, deaths = 0, best = 0;
        String prev = "PLAYING";
        for (int i = 0; i < 60 * 120; i++) {
            if (jumpTimer > 0) {
                if (--jumpTimer == 0) in.setJump(false);
            } else {
                float px = field(bot, "x=");
                if (bot.debug().contains("ground=true")) {
                    boolean pit = false;
                    for (int d = 18; d <= 52; d += 8) {
                        int c = ((int) px + d) / 16;
                        if (c >= 0 && c < COLS && lv.getGrid()[13][c] == ' ') { pit = true; break; }
                    }
                    if (pit || foeNear(bot, px, 145) || field(bot, "vx=") < 25f) {
                        in.setJump(true);
                        jumpTimer = 26;
                    }
                }
            }
            bot.update(DT, in);
            String ph = bot.getPhase().name();
            if (!ph.equals(prev)) {
                if (ph.equals("DYING")) deaths++;
                System.out.println("  t=" + fmt(i / 60.0) + "s " + prev + " -> " + ph + " | " + bot.debug());
                prev = ph;
            }
            best = Math.max(best, (int) field(bot, "x="));
            if (ph.equals("CLEAR") || ph.equals("GAME_OVER")) break;
        }
        System.out.println("  最远 x=" + best + " / 旗杆 x=" + lv.getFlagX()
                + "  死亡=" + deaths + "  得分=" + bot.getScore() + "  金币=" + bot.getCoins());
        if (best < 1200) fail("机器人卡在 x=" + best + "，关卡前段可能不可通行");
    }

    static boolean foeNear(Game g, float px, int range) {
        for (String tok : g.enemyDump().split(" ")) {
            if (tok.isEmpty()) continue;
            try {
                String[] xy = tok.split("@")[1].split("[,/]");
                int ex = Integer.parseInt(xy[0]), ey = Integer.parseInt(xy[1]), eh = Integer.parseInt(xy[2]);
                if (ex > px + 6 && ex < px + range && ey + eh > 170) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    // ---------------------------------------------------------------- 工具

    static float field(Game g, String key) {
        for (String t : g.debug().split(" "))
            if (t.startsWith(key)) return Float.parseFloat(t.substring(key.length()));
        return 0f;
    }

    static String fmt(double v) { return String.format(Locale.US, "%.2f", v); }

    static void fail(String msg) {
        failures++;
        System.out.println("  !! " + msg);
    }
}
