(() => {
  "use strict";

  const REPO = "PCML-Z/PCML";
  const RELEASES = `https://github.com/${REPO}/releases`;
  const MIRROR = "https://gh-proxy.com/";
  const CACHE_KEY = "pml-releases";
  const CACHE_TTL = 10 * 60 * 1000;
  const CHANGELOG_COUNT = 4;
  const BILIBILI_PLAYER = "https://player.bilibili.com/player.html?bvid=BV1Bp4j61EDm&p=1&autoplay=1&danmaku=0&high_quality=1";

  const asset = (tag, name, size) => ({
    name,
    size,
    browser_download_url: `${RELEASES}/download/${tag}/${name}`,
  });

  const FALLBACK = [
    {
      tag_name: "v2.12.0",
      published_at: "2026-11-28",
      assets: [
        ["pmcl-2.12.0-macos-arm64.pkg", 402974810],
        ["pmcl-2.12.0-macos-arm64.jar", 380368751],
        ["pmcl-2.12.0-windows-x64.msi", 388063864],
        ["pmcl-2.12.0-windows-x64.jar", 363163156],
        ["pmcl-2.12.0-linux-x64.deb", 380617800],
        ["pmcl-2.12.0-linux-x64.rpm", 380704091],
        ["pmcl-2.12.0-linux-x64.jar", 371827409],
      ].map(([n, s]) => asset("v2.12.0", n, s)),
      body: `## 竖屏
- 把窗口拉得比宽更高后，界面改成竖屏。再拉宽就回到横屏
- 竖屏里的整合包、模组、资源包和光影包进入对应的下载市场
- 快速来回拉动窗口时，虚化会立刻收回，不会停在整屏模糊
## 下载
- 模组文件没有写进目录时不再显示为已完成，装完会刷新已安装列表
- 下载线程上残留的中断标记不会让下一个任务直接停掉
- 市场里开始含依赖下载时，只有正在下载的那一行转圈
## 崩溃弹窗
- 游戏崩溃时「AI 修复」按钮留在窗口里，说明过多时中间区域可以滚动`,
    },
    {
      tag_name: "v2.11.3",
      published_at: "2026-11-22",
      assets: [],
      body: `## 下载
- 热更新同时下载多个文件，单次读取的缓冲也加大，不再一个文件排完再下一个
- 下载队列在暂停或网络超时之后会清掉线程上残留的中断标记。超时记为失败，可以重新下载，不会把后面的任务全部卡成已暂停`,
    },
    {
      tag_name: "v2.11.2",
      published_at: "2026-11-21",
      assets: [],
      body: `## 稳定性
- 背景图超过 32MB 不再整文件读进内存，解码改到后台并按屏幕尺寸缩小
- 背景视频按长边缩小。单帧超过上限就停，避免一帧把启动器撑满
- 下载和 HTTP 线程改为守护线程。窗口关掉后，卡住的连接不会把进程留住
- 退出时设置只落盘一次，避免和关闭钩子抢同一个配置文件`,
    },
  ];

  const OS = {
    macos: { name: "macOS", order: ["pkg", "dmg", "jar"] },
    windows: { name: "Windows", order: ["msi", "exe", "jar"] },
    linux: { name: "Linux", order: ["deb", "rpm", "AppImage", "tar.gz", "jar"] },
  };
  const OS_ORDER = ["macos", "windows", "linux"];
  const ASSET_RE = /^pmcl-.+?-(macos|windows|linux)-([a-z0-9_]+)\.(pkg|dmg|msi|exe|deb|rpm|AppImage|tar\.gz|jar)$/i;

  const $ = (s) => document.querySelector(s);

  const ua = navigator.userAgent || "";
  const myOs = /Android|iPhone|iPad|iPod/i.test(ua)
    ? null
    : /Mac/i.test(ua) ? "macos" : /Win/i.test(ua) ? "windows" : /Linux|X11|CrOS/i.test(ua) ? "linux" : null;

  let useMirror = false;
  try { useMirror = localStorage.getItem("pml-mirror") === "1"; } catch (_) {}

  const esc = (t) => String(t).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  const version = (r) => r.tag_name.replace(/^v/, "");
  const pad = (n) => String(n).padStart(2, "0");
  const day = (iso) => {
    if (/^\d{4}-\d{2}-\d{2}$/.test(iso || "")) return iso;
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? "" : `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  };
  const size = (b) => (b ? `${Math.round(b / 1048576)} MB` : "");
  const link = (a) => {
    const url = a.browser_download_url;
    return useMirror && url.startsWith("https://github.com/") ? MIRROR + url : url;
  };

  function filesByOs(release) {
    const out = {};
    for (const a of release.assets || []) {
      const m = ASSET_RE.exec(a.name);
      if (!m) continue;
      const os = m[1].toLowerCase();
      (out[os] = out[os] || []).push({ ...a, arch: m[2].toLowerCase(), ext: m[3] });
    }
    for (const os of Object.keys(out)) {
      const order = OS[os].order;
      out[os].sort((x, y) => order.indexOf(x.ext) - order.indexOf(y.ext));
    }
    return out;
  }

  function notesHtml(body) {
    const lines = String(body || "").replace(/\r/g, "").split("\n");
    let html = "";
    let open = false;
    const close = () => { if (open) { html += "</ul>"; open = false; } };
    for (const raw of lines) {
      const line = raw.trim();
      if (/^##\s*更新方式/.test(line)) break;
      if (!line || /^#\s/.test(line)) { close(); continue; }
      const h = /^#{2,4}\s+(.*)$/.exec(line);
      if (h) { close(); html += `<h4>${esc(h[1])}</h4>`; continue; }
      const li = /^[-*]\s+(.*)$/.exec(line);
      if (li) {
        if (!open) { html += "<ul>"; open = true; }
        html += `<li>${esc(li[1])}</li>`;
        continue;
      }
      close();
      html += `<p>${esc(line)}</p>`;
    }
    close();
    return html;
  }

  function archText(os, arch) {
    if (os === "macos") return arch === "arm64" ? "Apple 芯片" : "Intel";
    return arch;
  }

  function renderDownloads(release) {
    const tbody = $("[data-downloads]");
    const groups = filesByOs(release);
    let rows = "";
    for (const os of OS_ORDER) {
      const files = groups[os] || [];
      const mine = os === myOs ? " mine" : "";
      if (!files.length) {
        rows += `<tr class="${mine}"><td class="os">${OS[os].name}</td><td>这一版没有</td><td></td></tr>`;
        continue;
      }
      files.forEach((f, i) => {
        const osCell = i === 0
          ? `<td class="os" rowspan="${files.length}">${OS[os].name}${mine ? '<span class="you">你在用</span>' : ""}</td>`
          : "";
        const kind = f.ext === "jar" ? "需要 Java 21" : `${archText(os, f.arch)}安装包`;
        rows += `<tr class="${mine}">${osCell}<td><a href="${esc(link(f))}">${esc(f.name)}</a><span class="kind">${esc(kind)}</span></td><td>${size(f.size)}</td></tr>`;
      });
    }
    tbody.innerHTML = rows;

    const btn = $("[data-hero-download]");
    const meta = $("[data-hero-meta]");
    const mineFiles = myOs ? groups[myOs] || [] : [];
    if (mineFiles.length) {
      btn.href = link(mineFiles[0]);
      btn.textContent = `下载 ${OS[myOs].name} 版`;
      meta.innerHTML = `${esc(version(release))} · ${size(mineFiles[0].size)} · <a href="#download">其他系统</a>`;
    } else {
      btn.href = "#download";
      btn.textContent = "下载";
      meta.textContent = `当前版本 ${version(release)}`;
    }
  }

  function render(releases) {
    const latest = releases[0];
    $("[data-release-line]").textContent = `${version(latest)}，${day(latest.published_at)} 发布`;
    renderDownloads(latest);

    $("[data-changelog]").innerHTML = releases
      .slice(0, CHANGELOG_COUNT)
      .map((r) => `<article class="release"><h3>${esc(version(r))}<small>${esc(day(r.published_at))}</small></h3>${notesHtml(r.body) || "<p>这一版没写更新说明。</p>"}</article>`)
      .join("");
    current = releases;
  }

  let current = FALLBACK;

  const box = $("[data-mirror]");
  box.checked = useMirror;
  box.addEventListener("change", () => {
    useMirror = box.checked;
    try { localStorage.setItem("pml-mirror", useMirror ? "1" : "0"); } catch (_) {}
    renderDownloads(current[0]);
  });

  async function load() {
    try {
      const cached = JSON.parse(sessionStorage.getItem(CACHE_KEY) || "null");
      if (cached && Date.now() - cached.at < CACHE_TTL) return cached.list;
    } catch (_) {}
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 6000);
    try {
      const res = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=${CHANGELOG_COUNT + 3}`, {
        headers: { Accept: "application/vnd.github+json" },
        signal: ctrl.signal,
      });
      if (!res.ok) return null;
      const data = await res.json();
      const list = (Array.isArray(data) ? data : [])
        .filter((r) => r && !r.draft && !r.prerelease && r.tag_name)
        .map((r) => ({
          tag_name: r.tag_name,
          published_at: r.published_at,
          body: r.body || "",
          assets: (r.assets || []).map((a) => ({ name: a.name, size: a.size, browser_download_url: a.browser_download_url })),
        }));
      if (!list.length) return null;
      try { sessionStorage.setItem(CACHE_KEY, JSON.stringify({ at: Date.now(), list })); } catch (_) {}
      return list;
    } catch (_) {
      return null;
    } finally {
      clearTimeout(timer);
    }
  }

  function setupShowreel() {
    const frame = $("[data-showreel]");
    if (!frame) return;
    const video = frame.querySelector("video");
    const still = matchMedia("(prefers-reduced-motion: reduce)").matches
      || (navigator.connection && navigator.connection.saveData);

    if (video && !still) {
      const play = () => video.play().catch(() => {});
      if ("IntersectionObserver" in window) {
        new IntersectionObserver((entries) => {
          entries.forEach((e) => (e.isIntersecting ? play() : video.pause()));
        }, { threshold: 0.2 }).observe(video);
      } else {
        play();
      }
    }

    $("[data-play-full]").addEventListener("click", () => {
      const iframe = document.createElement("iframe");
      iframe.src = BILIBILI_PLAYER;
      iframe.title = "Proxima ML 宣传片";
      iframe.allow = "autoplay; fullscreen; picture-in-picture";
      iframe.allowFullscreen = true;
      iframe.setAttribute("scrolling", "no");
      iframe.setAttribute("referrerpolicy", "no-referrer-when-downgrade");
      frame.replaceChildren(iframe);
    });
  }

  setupShowreel();
  render(FALLBACK);
  load().then((list) => { if (list) render(list); });
})();
