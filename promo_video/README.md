# 🎬 LovelyRayNeoio 前端产品宣传片工程 (`promo_video/`)

基于纯前端技术栈（HTML5 + CSS3 + Canvas 光学粒子动效 + SVG 雷鸟双目波导 HUD 渲染 + 确定性时间轴控制器）与 `Playwright + FFmpeg` 自动化管线构建的 **1080p 有声高清宣传视频**（总时长控制在 2 分钟内）。

## 目录结构

- `index.html`：1920×1080 全屏交互式前端分镜舞台（可直接在浏览器打开预览、拖拽时间轴、点击顶部 7 大章节切换，也暴露 `window.__seekTo(sec)` 供逐帧渲染）。
- `render_video.mjs`：自动化配音、科技感环境 BGM 合成与 1080p H.264 MP4 逐帧渲染器。
- `LovelyRayNeoio_Promo_1080p.mp4`：导出的最终高清有声成片（含神经网络中文旁白 + 合成器环境音乐 + 逐帧同步字幕动画）。

## 本地预览与重新导出

1. **浏览器交互预览**：直接双击打开 `index.html` 即可。
2. **重新渲染导出 1080p MP4**：
   ```bash
   cd promo_video
   npm install
   npm run render
   ```
