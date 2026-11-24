import { chromium } from "playwright-core";
import { execFileSync, spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const AUDIO_DIR = path.join(__dirname, "_build_audio");
const OUTPUT_MP4 = path.join(__dirname, "LovelyRayNeoio_Promo_1080p.mp4");
const HTML_PATH = path.join(__dirname, "index.html");

const FFMPEG = "/opt/homebrew/bin/ffmpeg";
const FFPROBE = "/opt/homebrew/bin/ffprobe";
const EDGE_TTS = "/Users/maozhuoyu/.gemini/antigravity/brain/90c7c819-a84e-422d-b20b-7e887b5ad514/scratch/tts_venv/bin/edge-tts";
const FPS = 30;

// Find installed Playwright Chromium binary
function findChromiumExecutable() {
  const candidates = [
    path.join(process.env.HOME || "", "Library/Caches/ms-playwright/chromium-1228/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"),
    path.join(process.env.HOME || "", "Library/Caches/ms-playwright/chromium-1208/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"),
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  ];
  for (const p of candidates) {
    if (fs.existsSync(p)) return p;
  }
  throw new Error("Could not locate Chromium / Chrome binary.");
}

// Scripted scenes: concise, punchy, < 2 minutes total
const SCENE_SCRIPTS = [
  {
    id: 0,
    segments: [
      {
        sub: "唤醒「小雷小雷」很顺滑，但受限于固定模型、没法语音点歌、提词器也没法同步歌词？",
        tts: "唤醒小雷小雷很顺滑，但受限于固定模型、没法语音点歌、提词器也没法同步歌词？"
      },
      {
        sub: "认识一下开源补丁工程 LovelyRayNeoio，无损解锁雷鸟智能眼镜的 AI 与音乐完全体。",
        tts: "认识一下开源补丁工程 Lovely RayNeo I O，无损解锁雷鸟智能眼镜的 AI 与音乐完全体。"
      }
    ]
  },
  {
    id: 1,
    segments: [
      {
        sub: "采用未混淆桥接层最小侵入方案，100% 保留原厂离线唤醒、语音识别与原厂音色播报；",
        tts: "它采用未混淆桥接层最小侵入方案，百分之百保留原厂离线唤醒、语音识别与原厂音色播报；"
      },
      {
        sub: "原生支持 Chat 与 Responses 双协议流式接入，全球前沿大模型随心切换，长文播报不断流。",
        tts: "原生支持 Chat 与 Responses 双协议流式接入，全球前沿大模型随心切换，长文播报稳定不断流。"
      }
    ]
  },
  {
    id: 2,
    segments: [
      {
        sub: "全新打通音乐歌词与眼镜提词器毫秒级联动！说一句「打开歌词」，自动命中原版时间轴歌词；",
        tts: "全新打通音乐歌词与眼镜提词器毫秒级联动！说一句打开歌词，自动命中原版时间轴歌词；"
      },
      {
        sub: "VIP 与日语译名原曲精准匹配，自动倒计时进入提词，无需手按镜腿，整首零刷屏丝滑跟唱。",
        tts: "连 VIP 与日语译名原曲也能精准匹配，自动倒计时进入提词，无需手按镜腿，整首歌零刷屏丝滑跟唱。"
      }
    ]
  },
  {
    id: 3,
    segments: [
      {
        sub: "突破官方限制，一句话直接点播网易云或 QQ 音乐正版好歌、开启每日推荐与私人 FM；",
        tts: "突破官方限制，一句话直接点播网易云或 QQ 音乐正版好歌、开启每日推荐与私人 FM，后台无感起播；"
      },
      {
        sub: "支持全平台语音切歌识曲，更会自动把当前播放曲目注入大模型，边听歌边聊音乐故事。",
        tts: "支持全平台语音切歌识曲，更会自动把当前播放曲目注入大模型，边听歌边聊音乐故事。"
      }
    ]
  },
  {
    id: 4,
    segments: [
      {
        sub: "独创三层零误杀分流：查天气、建待办提醒、拍照录像、高德导航零延迟直通官方原版引擎；",
        tts: "独创三层零误杀分流：查天气、建待办提醒、拍照录像、高德导航零延迟直通官方原版引擎；"
      },
      {
        sub: "日常百科问答与深度创作自动交给自定义大模型，原厂技能与最强 AI 鱼和熊掌兼得。",
        tts: "日常百科问答与深度创作自动交给自定义大模型，原厂技能与最强 AI 鱼和熊掌兼得。"
      }
    ]
  },
  {
    id: 5,
    segments: [
      {
        sub: "怎么用？只需三步：第一，将官方安装包拖入构建脚本，一分钟自动生成专属签名安装包；",
        tts: "怎么用？只需三步：第一，将官方安装包拖入构建脚本，一分钟自动生成专属签名安装包；"
      },
      {
        sub: "第二，安装并开启通知读取权限，后续升级直接覆盖安装，蓝牙配对与配置百分之百保留；",
        tts: "第二，安装并开启通知读取权限，后续升级直接覆盖安装，蓝牙配对与配置百分之百保留；"
      },
      {
        sub: "第三，点开 App 右上角 LLM 悬浮球或连按三次音量加，填入密钥、开启歌词联动，即刻生效！",
        tts: "第三，点开 App 右上角 L L M 悬浮球或连按三次音量加，填入密钥、开启歌词联动，即刻生效！"
      }
    ]
  },
  {
    id: 6,
    segments: [
      {
        sub: "永久免费开源，严禁任何商业收费。立即前往 GitHub 搜索 LovelyRayNeoio 体验吧！",
        tts: "永久免费开源，严禁任何商业收费。立即前往 GitHub 搜索 Lovely RayNeo I O 体验吧！"
      }
    ]
  }
];

function probeDurationSec(filePath) {
  const out = execFileSync(FFPROBE, [
    "-v", "error",
    "-show_entries", "format=duration",
    "-of", "default=noprint_wrappers=1:nokey=1",
    filePath
  ], { encoding: "utf-8" }).trim();
  return parseFloat(out);
}

function synthesizeSegmentAudio(segIdx, text, outWav) {
  const rawAudio = path.join(AUDIO_DIR, `raw_${segIdx}.mp3`);
  let usedEdge = false;
  if (fs.existsSync(EDGE_TTS)) {
    try {
      execFileSync(EDGE_TTS, [
        "--voice", "zh-CN-YunxiNeural",
        "--rate=+12%",
        "--text", text,
        "--write-media", rawAudio
      ], { stdio: "ignore", timeout: 15000 });
      if (fs.existsSync(rawAudio) && fs.statSync(rawAudio).size > 1000) {
        usedEdge = true;
      }
    } catch {
      usedEdge = false;
    }
  }

  if (!usedEdge) {
    const aiff = path.join(AUDIO_DIR, `raw_${segIdx}.aiff`);
    execFileSync("say", ["-v", "Tingting", "-r", "205", "-o", aiff, text]);
    execFileSync(FFMPEG, [
      "-y", "-i", aiff,
      "-af", "silenceremove=start_periods=1:start_silence=0.05:start_threshold=-45dB,areverse,silenceremove=start_periods=1:start_silence=0.08:start_threshold=-45dB,areverse",
      "-ar", "44100", "-ac", "2", outWav
    ], { stdio: "ignore" });
    return;
  }

  execFileSync(FFMPEG, [
    "-y", "-i", rawAudio,
    "-af", "silenceremove=start_periods=1:start_silence=0.04:start_threshold=-46dB,areverse,silenceremove=start_periods=1:start_silence=0.08:start_threshold=-46dB,areverse",
    "-ar", "44100", "-ac", "2", outWav
  ], { stdio: "ignore" });
}

// Generate warm futuristic ambient synth BGM in pure PCM WAV (44.1kHz stereo)
function generateAmbientBgmWav(filePath, durationSec) {
  const sampleRate = 44100;
  const numSamples = Math.ceil(durationSec * sampleRate);
  const dataSize = numSamples * 4; // 16-bit stereo
  const buf = Buffer.alloc(44 + dataSize);

  // WAV header
  buf.write("RIFF", 0);
  buf.writeUInt32LE(36 + dataSize, 4);
  buf.write("WAVE", 8);
  buf.write("fmt ", 12);
  buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20); // PCM
  buf.writeUInt16LE(2, 22); // Stereo
  buf.writeUInt32LE(sampleRate, 24);
  buf.writeUInt32LE(sampleRate * 4, 28);
  buf.writeUInt16LE(4, 32);
  buf.writeUInt16LE(16, 34);
  buf.write("data", 36);
  buf.writeUInt32LE(dataSize, 40);

  // 4-chord progression (4s per chord = 16s loop): Cmaj9 -> Am9 -> Fmaj7 -> G6
  const chords = [
    [130.81, 196.00, 246.94, 293.66, 329.63],
    [110.00, 164.81, 220.00, 246.94, 329.63],
    [87.31,  174.61, 220.00, 261.63, 329.63],
    [98.00,  196.00, 246.94, 293.66, 329.63]
  ];
  const arpNotes = [523.25, 659.25, 783.99, 987.77, 783.99, 659.25];

  for (let i = 0; i < numSamples; i++) {
    const t = i / sampleRate;
    const chordIdx = Math.floor((t / 4.0)) % chords.length;
    const nextIdx = (chordIdx + 1) % chords.length;
    const chordPos = (t % 4.0) / 4.0;
    const xfade = chordPos > 0.82 ? (chordPos - 0.82) / 0.18 : 0.0;

    let padL = 0;
    let padR = 0;
    const cNow = chords[chordIdx];
    const cNext = chords[nextIdx];
    for (let k = 0; k < cNow.length; k++) {
      const sNow = Math.sin(2 * Math.PI * cNow[k] * t + k * 0.4);
      const sNext = Math.sin(2 * Math.PI * cNext[k] * t + k * 0.4);
      const v = sNow * (1 - xfade) + sNext * xfade;
      padL += v * (k % 2 === 0 ? 0.22 : 0.15);
      padR += v * (k % 2 === 1 ? 0.22 : 0.15);
    }

    // Gentle crystal pulse every 0.25s
    const step = Math.floor(t / 0.25);
    const localArpT = t - step * 0.25;
    const env = Math.exp(-localArpT * 14);
    const arpFreq = arpNotes[step % arpNotes.length];
    const arp = Math.sin(2 * Math.PI * arpFreq * t) * env * 0.12;

    // Master fade-in (1.2s) & fade-out (2.0s)
    let masterEnv = 1.0;
    if (t < 1.2) masterEnv = t / 1.2;
    else if (t > durationSec - 2.0) masterEnv = Math.max(0, (durationSec - t) / 2.0);

    const outL = Math.max(-1, Math.min(1, (padL * 0.35 + arp * 0.9) * masterEnv));
    const outR = Math.max(-1, Math.min(1, (padR * 0.35 + arp * 0.7) * masterEnv));

    buf.writeInt16LE(Math.round(outL * 32767), 44 + i * 4);
    buf.writeInt16LE(Math.round(outR * 32767), 44 + i * 4 + 2);
  }

  fs.writeFileSync(filePath, buf);
}

async function main() {
  fs.mkdirSync(AUDIO_DIR, { recursive: true });
  console.log("=== Step 1: Generating Chinese Narration & Exact Scene Timeline ===");

  const computedScenes = [];
  const filterInputs = [];
  const delayFilters = [];
  let cursorSec = 0.0;
  let globalSegCounter = 0;

  for (const sc of SCENE_SCRIPTS) {
    const sceneStart = cursorSec;
    let localCursor = 0.25; // slight visual entrance lead-in
    const subtitles = [];

    for (let si = 0; si < sc.segments.length; si++) {
      const seg = sc.segments[si];
      const wavPath = path.join(AUDIO_DIR, `seg_${globalSegCounter}.wav`);
      synthesizeSegmentAudio(globalSegCounter, seg.tts, wavPath);
      const segDur = probeDurationSec(wavPath);

      const startInScene = localCursor;
      const endInScene = localCursor + segDur + 0.22;
      subtitles.push([
        si === 0 ? 0.0 : startInScene,
        endInScene,
        seg.sub
      ]);

      const absStartMs = Math.round((sceneStart + startInScene) * 1000);
      filterInputs.push("-i", wavPath);
      delayFilters.push(`[${globalSegCounter + 1}:a]adelay=${absStartMs}|${absStartMs}[v${globalSegCounter}]`);

      localCursor = endInScene;
      globalSegCounter++;
    }

    const sceneDuration = Math.round((localCursor + 0.35) * 100) / 100;
    // Extend last subtitle to end of scene
    subtitles[subtitles.length - 1][1] = sceneDuration;

    computedScenes.push({
      id: sc.id,
      start: Math.round(sceneStart * 100) / 100,
      duration: sceneDuration,
      subtitles
    });

    cursorSec += sceneDuration;
  }

  // Add 0.8s tail hold on the final frame
  computedScenes[computedScenes.length - 1].duration =
    Math.round((computedScenes[computedScenes.length - 1].duration + 0.8) * 100) / 100;
  computedScenes[computedScenes.length - 1].subtitles[0][1] =
    computedScenes[computedScenes.length - 1].duration;

  const totalDurationSec = Math.round(
    (computedScenes[computedScenes.length - 1].start + computedScenes[computedScenes.length - 1].duration) * 100
  ) / 100;

  console.log(`Total video duration: ${totalDurationSec.toFixed(2)}s (< 120s target)`);
  fs.writeFileSync(path.join(AUDIO_DIR, "timeline.json"), JSON.stringify(computedScenes, null, 2));

  console.log("=== Step 2: Synthesizing Ambient BGM & Mixing Master Audio ===");
  const bgmWav = path.join(AUDIO_DIR, "bgm.wav");
  generateAmbientBgmWav(bgmWav, totalDurationSec);

  const voiceLabels = Array.from({ length: globalSegCounter }, (_, i) => `[v${i}]`).join("");
  const filterComplex = [
    ...delayFilters,
    `${voiceLabels}amix=inputs=${globalSegCounter}:normalize=0,volume=1.12[voice]`,
    `[0:a]volume=0.14[bgm]`,
    `[bgm][voice]amix=inputs=2:duration=first:normalize=0[aout]`
  ].join(";");

  const masterAudio = path.join(AUDIO_DIR, "master_mix.m4a");
  execFileSync(FFMPEG, [
    "-y",
    "-i", bgmWav,
    ...filterInputs,
    "-filter_complex", filterComplex,
    "-map", "[aout]",
    "-c:a", "aac",
    "-b:a", "192k",
    "-t", String(totalDurationSec),
    masterAudio
  ], { stdio: "inherit" });

  console.log("=== Step 3: Rendering 1920x1080 @ 30fps Frontend Frames -> H.264 MP4 ===");
  const chromePath = findChromiumExecutable();
  const browser = await chromium.launch({
    executablePath: chromePath,
    headless: true,
    args: ["--no-sandbox", "--disable-gpu-vsync", "--font-render-hinting=none"]
  });

  const page = await browser.newPage({
    viewport: { width: 1920, height: 1080 },
    deviceScaleFactor: 1
  });

  await page.goto(`file://${HTML_PATH}`, { waitUntil: "load" });
  await page.evaluate((scenes) => {
    window.__setRenderTimeline(scenes);
  }, computedScenes);

  const totalFrames = Math.ceil(totalDurationSec * FPS);

  const ff = spawn(FFMPEG, [
    "-y",
    "-f", "image2pipe",
    "-vcodec", "mjpeg",
    "-framerate", String(FPS),
    "-i", "-",
    "-i", masterAudio,
    "-map", "0:v:0",
    "-map", "1:a:0",
    "-c:v", "libx264",
    "-preset", "fast",
    "-crf", "18",
    "-pix_fmt", "yuv420p",
    "-c:a", "copy",
    "-movflags", "+faststart",
    "-shortest",
    OUTPUT_MP4
  ], { stdio: ["pipe", "ignore", "inherit"] });

  for (let f = 0; f < totalFrames; f++) {
    const t = f / FPS;
    await page.evaluate((sec) => window.__seekTo(sec), t);
    const jpg = await page.screenshot({ type: "jpeg", quality: 94 });
    const ok = ff.stdin.write(jpg);
    if (!ok) {
      await new Promise((resolve) => ff.stdin.once("drain", resolve));
    }
    if (f % 150 === 0 || f === totalFrames - 1) {
      console.log(`Rendered frame ${f + 1} / ${totalFrames} (${((f + 1) / totalFrames * 100).toFixed(1)}%)`);
    }
  }

  ff.stdin.end();
  await new Promise((resolve, reject) => {
    ff.on("close", (code) => (code === 0 ? resolve() : reject(new Error(`ffmpeg exited ${code}`))));
  });

  await browser.close();
  const stat = fs.statSync(OUTPUT_MP4);
  console.log(`\n[SUCCESS] Exported HD Promo Video: ${OUTPUT_MP4} (${(stat.size / 1024 / 1024).toFixed(2)} MB, ${totalDurationSec.toFixed(1)}s)`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
