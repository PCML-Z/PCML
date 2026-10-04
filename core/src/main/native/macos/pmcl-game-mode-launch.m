#import <Cocoa/Cocoa.h>
#import <unistd.h>

int main(int argc, char **argv) {
    @autoreleasepool {
        /* 先以这个 .app 向 Launch Services 报到，再换成 java。
           进程号不变，全屏时系统才能把这个游戏认进 Game Mode。 */
        NSApplication *app = [NSApplication sharedApplication];
        [app setActivationPolicy:NSApplicationActivationPolicyRegular];
        if (argc < 2) return 2;
        execvp(argv[1], argv + 1);
        return 127;
    }
}
