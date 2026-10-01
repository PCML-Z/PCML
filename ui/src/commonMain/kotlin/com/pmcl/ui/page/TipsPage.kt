package com.pmcl.ui.page
import com.pmcl.ui.widget.PmclLazyColumn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.ui.animation.AnimatedPageSwitch
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.viewmodel.LauncherViewModel

@Composable
fun TipsPage(vm: LauncherViewModel) {
    val articles = remember { tipArticles() }
    var query by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<String?>(null) }
    val current = articles.find { it.id == opened }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(
                visible = current != null,
                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.86f),
                exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.86f)
            ) {
                IconButton(onClick = { opened = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            }
            AnimatedVisibility(
                visible = current == null,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(120))
            ) {
                Icon(Icons.Filled.Lightbulb, null, tint = MaterialTheme.colorScheme.primary)
            }
            if (current == null) Spacer(Modifier.width(8.dp))
            Text(
                current?.title ?: "提示",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        AnimatedPageSwitch(
            targetState = opened,
            direction = if (opened != null) 1 else -1,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { openedId ->
            val article = articles.find { it.id == openedId }
            if (article == null) {
            Column(Modifier.fillMaxSize()) {
            Text(
                "启动器里容易搞混的地方",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜一下，比如房间码、模组、Java") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            val q = query.trim()
            val shown = if (q.isEmpty()) articles else articles.filter { it.matches(q) }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没找到", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                PmclLazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(shown, key = { it.id }) { article ->
                        Card(
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(280, easing = FastOutSlowInEasing),
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            ).fillMaxWidth().glassCardBorder().clickable { opened = article.id },
                            colors = glassCardColors(),
                            elevation = glassCardElevation(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(article.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(article.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                                    Text(article.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(8.dp))
            PmclLazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(article.sections, key = { it.heading }) { section ->
                    Column(Modifier.animateItem(
                        fadeInSpec = tween(280, easing = FastOutSlowInEasing),
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )) {
                        Text(section.heading, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        section.lines.forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                if (article.route != null) {
                    item {
                        FilledTonalButton(
                            onClick = {
                                vm.requestNavigation(article.route)
                                article.section?.let { vm.requestSecondaryNav(article.route, it) }
                            },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(280, easing = FastOutSlowInEasing),
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        ) { Text("去${article.place}") }
                    }
                }
            }
            }
            }
        }
    }
}

private data class TipSection(val heading: String, val lines: List<String>)
private data class TipArticle(
    val id: String,
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val sections: List<TipSection>,
    val route: String? = null,
    val section: String? = null,
    val place: String = ""
) {
    fun matches(q: String): Boolean {
        val blob = (title + summary + sections.joinToString(" ") { it.heading + it.lines.joinToString(" ") }).lowercase()
        return q.lowercase().split(Regex("\\s+")).all { blob.contains(it) }
    }
}

private fun tipArticles() = listOf(
    TipArticle("start", "游戏起不来的时候先看这两处", "没登录，或者没选中装好的版本。", Icons.Filled.PlayArrow, listOf(
        TipSection("启动按钮", listOf(
            "启动页要同时有账号、有选中的版本。少一个就会停在「请先登录账号」或「请先选择版本」，不是按钮坏了。",
            "游戏已经开着时再点，会提示已经在运行。实例那一列也一样，同时只放行一个。"
        )),
        TipSection("版本 Java 和直连", listOf(
            "某个版本可以单独填 Java。留空才用设置里的全局 Java。填了就以这一条为准，哪怕全局那条是好的。",
            "直连地址留空就是正常进主菜单。填上之后启动参数里会有 --server 和 --port，已经在玩的存档不会因此被换掉。"
        ))
    ), "launch", null, "启动"),
    TipArticle("accounts", "账号分四个入口", "列表里看不到登录按钮是正常的。", Icons.Filled.Person, listOf(
        TipSection("去哪登", listOf(
            "「账号管理」只放已经登过的号。新号要去旁边的「离线登录」「微软登录」「GitHub 登录」或「皮肤站登录」。",
            "离线填个名字就行。微软会跳到浏览器。另外两种按那一页自己的框填，别套微软那套。"
        )),
        TipSection("皮肤和好友", listOf(
            "皮肤在登录之后才有。「皮肤管理」里，离线号用皮肤 URL，微软和皮肤站可以上传或重置。",
            "好友是按当前账号的 UUID 分开存的。换号之后看到的是另一份好友和聊天，旧的还在原来那个号下面。"
        ))
    ), "accounts", "list", "账号"),
    TipArticle("download", "版本没下完不会出现在启动页", "市场装的模组进当前实例。", Icons.Filled.Build, listOf(
        TipSection("装原版", listOf(
            "在「下载 → 已安装版本」里装。文件先落在暂存目录，库和资源都过完才变成能启动的版本。装到一半关掉，启动页上不会多出一个残的。",
            "Fabric、Quilt 用的原版 jar 放在它继承的那个版本目录里，不会在加载器自己的文件夹里再抄一份。OptiFine 的 jar 和原版不是同一个文件，会留着。"
        )),
        TipSection("市场和队列", listOf(
            "市场在「下载 → 市场」，搜 Modrinth 和 CurseForge。CurseForge 没配密钥时，那边就是空的。",
            "点安装时看的是你现在选中的实例和游戏版本，jar 进这个实例的 mods。队列页离开之后，悬浮的下载卡片还在。"
        ))
    ), "download", "versions", "下载"),
    TipArticle("mods", "模组关的是文件名，不是列表上的勾这么简单", "实际是把 jar 改成 .jar.disabled。", Icons.Filled.Extension, listOf(
        TipSection("看哪一份", listOf(
            "内容页的模组、整合包、光影、资源包、数据包、配置，都跟着当前游戏目录走。数据包还得先有世界，在那个世界的 datapacks 里。",
            "两个实例都有同名 jar 时，你勾的是当前这个实例里的那一个文件。另一个实例不动。"
        )),
        TipSection("更新", listOf(
            "检查更新只扫当前实例目录。Forge 那种 1.20.1-10.x 的版本号能比出新旧，不会因为中间有横线就一直显示已是最新。",
            "依赖里已经装过的会跳过。找不到和下载失败是分开报的，不会合成一句「装好了」。"
        ))
    ), "content", "mods", "内容"),
    TipArticle("together", "联机先离开房间才能换后端", "陶瓦的房间码别在同一台电脑上再加入一次。", Icons.Filled.Share, listOf(
        TipSection("开房", listOf(
            "空闲时才能切换陶瓦、EasyTier、ConnectX，也才能点「创建房间」。已经在连或者已经进去了，要先离开。",
            "陶瓦创建出来的码以 U/ 开头。对方在联机页粘贴，点「加入房间」。同一台机器不能既当房主又用这个码再加入。"
        )),
        TipSection("进游戏填什么", listOf(
            "陶瓦房客用启动器显示的本地地址，在游戏里直接连接。这个地址是你自己电脑上的，不要发给别人。",
            "EasyTier 和 ConnectX 用虚拟 IP。端口看房主游戏里「对局域网开放」之后日志里的数字。日志里没有，启动器不会拿 25565 顶上。",
            "ConnectX 的邀请以 connectx- 开头，粘贴加入时会自己走 ConnectX。"
        ))
    ), "multiplayer", "room", "联机"),
    TipArticle("friends", "好友里的「联机」要人在线才有", "互加了但发现不了对方，列表就是空的。", Icons.Filled.People, listOf(
        TipSection("加上之后", listOf(
            "添加好友用邀请码或二维码，图片和剪贴板都可以。码不对就提示无效，不会加进一个空白好友。",
            "请求在列表上面，接受了才进好友。聊天先点左边的人。对方不在线时，视频按钮点不了。"
        )),
        TipSection("一键加入", listOf(
            "你开着房间时，邀请码会从加密连接发给在线好友，大概十五秒一次。离开房间后对方那边这条会消失。",
            "对方得到「好友 → 联机」里点加入。两边得已经是好友，并且互相发现得了：同一局域网，或者已经在同一个虚拟网络里。",
            "127 开头、localhost 不会被当成对方的服务器。陶瓦也不会把本机隧道写进直连。"
        ))
    ), "friends", "rooms", "好友"),
    TipArticle("crash", "崩溃弹窗只动这一次的实例", "后来又选了别的版本，恢复还是打在崩溃的那份上。", Icons.Filled.Warning, listOf(
        TipSection("弹窗", listOf(
            "标题是「游戏崩溃了」，旁边有版本和退出码。报告还在写的时候会标「崩溃报告输出中」。",
            "可以看报告、打支持包，或者用这次的配置重开。关掉不会自动再启动。"
        )),
        TipSection("恢复动哪些文件", listOf(
            "禁用最近模组、清 options.txt 和 servers.dat，都在这次启动的实例目录里。不会去翻另一个实例，也不会默认改 ~/.pmcl 根目录那份。",
            "报告会从工作目录、各个实例，以及本机常见的 minecraft 目录里收集，同一路径只出现一次。用兼容 Java 启动也是这个弹窗。"
        ))
    ), "launch", null, "启动"),
    TipArticle("terminal", "终端窗口各自记各自的", "关掉一个，别的还在。", Icons.Filled.Terminal, listOf(
        TipSection("页里能做的", listOf(
            "这里跑的是 PMCL 的命令。可以搜输出、复制、改字号、Tab 补全。正在跑的命令用停止取消，窗口不会一起关。",
            "清屏只清这个窗口的输出。"
        )),
        TipSection("再开一个", listOf(
            "标题栏那个向外的图标，点一次多一个窗口，位置会错开。输入和正在跑的命令都是新的。",
            "这些窗口挂在启动器进程上。主窗口退出，它们一起没。"
        ))
    ), "terminal", null, "终端"),
    TipArticle("server", "收藏了不等于下次会自动连", "要另外设成直连。", Icons.Filled.Dns, listOf(
        TipSection("两个按钮不是一回事", listOf(
            "服务器页可以加地址、改、删、看延迟。这只是一份列表。",
            "设成直连之后，下次启动才带 --server 和 --port。启动页把地址清空，就是这次不自动连。"
        )),
        TipSection("好友加入时", listOf(
            "从好友页加入 EasyTier 或 ConnectX，如果对方带了虚拟 IP 和已经读到的端口，直连会被写成这个地址。陶瓦只进房间，不写 127.0.0.1。"
        ))
    ), "servers", null, "服务器"),
    TipArticle("device", "设备保护关掉才算关", "把许可证文本删掉，启动还是会拦。", Icons.Filled.Shield, listOf(
        TipSection("设置里的开关", listOf(
            "在「设置 → 设备绑定」。打开之后每次启动都会核对这台电脑。",
            "清空许可证字段没用，启动器另外记着保护还开着。要在这一页里关掉。"
        )),
        TipSection("独立 App", listOf(
            "导出 Mac 独立包时，这台机器上得找得到 PMCL。找不到就失败，不会用当前文件夹凑数。",
            "包是跟这份启动器绑的。PMCL.app 没了，别处也没有 PMCL，独立包就起不来。这和设备绑定不是同一个开关。"
        ))
    ), "settings", "device", "设置"),
    TipArticle("java", "单版本的 Java 会盖住全局", "架构不对，主版本对了也没用。", Icons.Filled.Memory, listOf(
        TipSection("填哪里", listOf(
            "全局在「设置 → Java 行为管理」。某个版本要单独用，去启动页填「版本 Java」，选 java 可执行文件，不是选一个文件夹。",
            "那一栏留空，才回到全局或自动检测。"
        )),
        TipSection("启动时", listOf(
            "界面和命令行都会带上这份 Java 的主版本和架构。路径不对，游戏起不来，先看是不是这个版本被单独指到了另一条。"
        ))
    ), "settings", "java", "设置"),
    TipArticle("instances", "实例有自己的一份目录", "模组和崩溃恢复都认这次选中的实例。", Icons.Filled.Dashboard, listOf(
        TipSection("和普通版本", listOf(
            "启动页上版本和实例是分开列的。整合包一般是一个实例，mods、配置、存档都在它自己的目录。",
            "从实例启动不会把当前版本弄丢。市场安装如果跟着选中项，就进这个实例的 mods。"
        )),
        TipSection("崩溃之后", listOf(
            "恢复用的是崩溃时记下的实例，不是你后来在启动页又点中的那个。"
        ))
    ), "instances", null, "实例"),
    TipArticle("plugins", "插件的页面会进侧栏", "卸掉之后，命令面板里也一起没了。", Icons.Filled.Extension, listOf(
        TipSection("装和卸", listOf(
            "插件页有已安装、动作、安装。启用之后，它自己的页面可以出现在左侧。",
            "卸载会停掉它的线程。它如果提供了正在用的主题，主题会收回去，不会留一个已经不存在的主题包。",
            "插件把某个内置页面藏起来时，侧栏和 Ctrl+K（Mac 是 Command+K）里都不会再出现。"
        ))
    ), "plugins", "installed", "插件")
)
