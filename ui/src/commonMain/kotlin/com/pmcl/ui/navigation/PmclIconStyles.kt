package com.pmcl.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource

/**
 * 界面图标有两套。简洁一套是默认，原来的一套留在设置里。
 * 读取 [style] 的界面会在切换后重绘。
 */
object PmclIconStyles {
    enum class Style { Modern, Classic }

    val style = mutableStateOf(Style.Modern)

    fun apply(raw: String?) {
        style.value = if (raw == "classic") Style.Classic else Style.Modern
    }

    fun asset(): String = "pmcl_icon.png"
}

object PmclIcons {
    val Launch: ImageVector get() = pick(PmclModernIcons.Launch, PmclClassicIcons.Launch)
    val News: ImageVector get() = pick(PmclModernIcons.News, PmclClassicIcons.News)
    val Tips: ImageVector get() = pick(PmclModernIcons.Tips, PmclClassicIcons.Tips)
    val Multiplayer: ImageVector get() = pick(PmclModernIcons.Multiplayer, PmclClassicIcons.Multiplayer)
    val Servers: ImageVector get() = pick(PmclModernIcons.Servers, PmclClassicIcons.Servers)
    val Friends: ImageVector get() = pick(PmclModernIcons.Friends, PmclClassicIcons.Friends)
    val Download: ImageVector get() = pick(PmclModernIcons.Download, PmclClassicIcons.Download)
    val Content: ImageVector get() = pick(PmclModernIcons.Content, PmclClassicIcons.Content)
    val Saves: ImageVector get() = pick(PmclModernIcons.Saves, PmclClassicIcons.Saves)
    val Statistics: ImageVector get() = pick(PmclModernIcons.Statistics, PmclClassicIcons.Statistics)
    val Accounts: ImageVector get() = pick(PmclModernIcons.Accounts, PmclClassicIcons.Accounts)
    val Settings: ImageVector get() = pick(PmclModernIcons.Settings, PmclClassicIcons.Settings)
    val Terminal: ImageVector get() = pick(PmclModernIcons.Terminal, PmclClassicIcons.Terminal)
    val Plugins: ImageVector get() = pick(PmclModernIcons.Plugins, PmclClassicIcons.Plugins)
    val Ecxp: ImageVector get() = pick(PmclModernIcons.Ecxp, PmclClassicIcons.Ecxp)
    val Kernel: ImageVector get() = pick(PmclModernIcons.Kernel, PmclClassicIcons.Kernel)
    val Instances: ImageVector get() = pick(PmclModernIcons.Instances, PmclClassicIcons.Instances)
    val Nbt: ImageVector get() = pick(PmclModernIcons.Nbt, PmclClassicIcons.Nbt)
    val Music: ImageVector get() = pick(PmclModernIcons.Music, PmclClassicIcons.Music)
    val Palette: ImageVector get() = pick(PmclModernIcons.Palette, PmclClassicIcons.Palette)
    val Bolt: ImageVector get() = pick(PmclModernIcons.Bolt, PmclClassicIcons.Bolt)
    val Wrench: ImageVector get() = pick(PmclModernIcons.Wrench, PmclClassicIcons.Wrench)
    val Speed: ImageVector get() = pick(PmclModernIcons.Speed, PmclClassicIcons.Speed)
    val Update: ImageVector get() = pick(PmclModernIcons.Update, PmclClassicIcons.Update)
    val Shield: ImageVector get() = pick(PmclModernIcons.Shield, PmclClassicIcons.Shield)
    val Article: ImageVector get() = pick(PmclModernIcons.Article, PmclClassicIcons.Article)
    val Qr: ImageVector get() = pick(PmclModernIcons.Qr, PmclClassicIcons.Qr)
    val Gavel: ImageVector get() = pick(PmclModernIcons.Gavel, PmclClassicIcons.Gavel)
    val Modpack: ImageVector get() = pick(PmclModernIcons.Modpack, PmclClassicIcons.Modpack)
    val Sun: ImageVector get() = pick(PmclModernIcons.Sun, PmclClassicIcons.Sun)
    val Grid: ImageVector get() = pick(PmclModernIcons.Grid, PmclClassicIcons.Grid)
    val Dataset: ImageVector get() = pick(PmclModernIcons.Dataset, PmclClassicIcons.Dataset)
    val Edit: ImageVector get() = pick(PmclModernIcons.Edit, PmclClassicIcons.Edit)
    val Browser: ImageVector get() = pick(PmclModernIcons.Browser, PmclClassicIcons.Browser)
    val Key: ImageVector get() = pick(PmclModernIcons.Key, PmclClassicIcons.Key)
    val Globe: ImageVector get() = pick(PmclModernIcons.Globe, PmclClassicIcons.Globe)
    val History: ImageVector get() = pick(PmclModernIcons.History, PmclClassicIcons.History)
    val Image: ImageVector get() = pick(PmclModernIcons.Image, PmclClassicIcons.Image)
    val Video: ImageVector get() = pick(PmclModernIcons.Video, PmclClassicIcons.Video)
    val Plus: ImageVector get() = pick(PmclModernIcons.Plus, PmclClassicIcons.Plus)
    val Error: ImageVector get() = pick(PmclModernIcons.Error, PmclClassicIcons.Error)
    val Star: ImageVector get() = pick(PmclModernIcons.Star, PmclClassicIcons.Star)
    val Playlist: ImageVector get() = pick(PmclModernIcons.Playlist, PmclClassicIcons.Playlist)
    val Mail: ImageVector get() = pick(PmclModernIcons.Mail, PmclClassicIcons.Mail)

    private fun pick(modern: ImageVector, classic: ImageVector): ImageVector =
        if (PmclIconStyles.style.value == PmclIconStyles.Style.Classic) classic else modern
}

@Composable
fun pmclWindowIcon(): Painter = painterResource(PmclIconStyles.asset())
