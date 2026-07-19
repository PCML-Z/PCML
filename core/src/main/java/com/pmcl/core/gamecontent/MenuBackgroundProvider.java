package com.pmcl.core.gamecontent;

import java.nio.file.Path;

/**
 * 主菜单背景替换提供者接口。
 * <p>
 * Minecraft Java 版主菜单背景原生只支持 6 张静态全景图（panorama_0.png ~ panorama_5.png），
 * 不支持视频。PMCL 通过「视频帧提取 → 6 张 panorama png → 资源包 zip → 启用」的链路
 * 让用户自定义主菜单背景。
 * <p>
 * 接口定义在 core 模块，具体实现由 video 模块提供（依赖 JavaCV/FFmpeg），
 * 由 UI 层在启动时通过 {@code LaunchProfileBuilder.setMenuBackgroundProvider} 注入。
 * 这样 core 模块不依赖 video 模块，避免循环依赖。
 * <p>
 * 若未注入实现（provider == null），启动器行为与之前完全一致，不影响任何功能。
 */
public interface MenuBackgroundProvider {

    /**
     * 在指定 gameDir 中安装主菜单背景资源包。
     * <p>
     * 实现应完成：
     * <ol>
     *   <li>检查缓存（按视频文件路径 + size + mtime 哈希命名），命中则复用</li>
     *   <li>缓存未命中：用 JavaCV 从视频按时间间隔提取 6 帧，缩放裁剪为正方形 PNG</li>
     *   <li>打包为资源包 zip（含 pack.mcmeta + 6 张 panorama_*.png）</li>
     *   <li>复制/写入到 {@code gameDir/resourcepacks/<返回值>}</li>
     * </ol>
     * 调用方（LaunchProfileBuilder）负责随后调用 {@link OptionsTxtWriter#enableResourcePack}
     * 把返回的资源包加入 options.txt 的 resourcePacks 列表。
     *
     * @param gameDir    游戏工作目录（resourcepacks 子目录的父目录）
     * @param videoPath  用户选择的视频文件路径（已确认存在）
     * @param cacheDir   缓存目录（用于缓存生成的 zip，避免每次启动重新解码视频）
     * @param mcVersion  Minecraft 版本 ID（如 "1.21.4"），用于确定 pack.mcmeta 的 pack_format
     * @return 安装的资源包文件名（如 "PMCL_MenuBg.zip"），失败返回 null
     */
    String installTo(Path gameDir, Path videoPath, Path cacheDir, String mcVersion);
}
