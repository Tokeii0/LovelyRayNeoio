#!/usr/bin/env python3
"""
RayNeo AI APK Custom LLM Patcher (Option 1: airuntime bridge interception)
==========================================================================
Keeps RayNeo original ASR, local hardware control commands, and original TTS untouched.
Replaces only the LLM inference layer with configurable OpenAI-compatible APIs
(/v1/chat/completions AND /v1/responses).

Usage:
    python patch_apk.py <input.apk> [output.apk]
"""

from __future__ import annotations

import difflib
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent
TOOLS_LIB = ROOT_DIR / "tools" / "lib"
KEYSTORE_PATH = Path(os.environ.get("RAYNEO_KEYSTORE", str(ROOT_DIR / "keys" / "rayneo_custom_ai.jks")))
KEY_ALIAS = os.environ.get("RAYNEO_KEY_ALIAS", "rayneo_custom")
KEY_PASS = os.environ.get("RAYNEO_KEY_PASS", "rayneo2026")

STUBS_DIR = ROOT_DIR / "stubs"
SRC_DIR = ROOT_DIR / "src"
ANNOTATIONS_DIR = ROOT_DIR / "annotations"

IS_WIN = os.name == "nt"


def _find_jdk_tool(name: str) -> Path:
    exe = f"{name}.exe" if IS_WIN else name
    env_home = os.environ.get("JAVA_HOME")
    candidates: list[Path] = []
    if env_home:
        candidates.append(Path(env_home) / "bin" / exe)
    if sys.platform == "darwin":
        for brew_root in [Path("/opt/homebrew/opt"), Path("/usr/local/opt")]:
            if brew_root.exists():
                for sub in sorted(brew_root.glob("openjdk*"), reverse=True):
                    candidates.append(sub / "libexec" / "openjdk.jdk" / "Contents" / "Home" / "bin" / exe)
                    candidates.append(sub / "bin" / exe)
        for jvm_base in [Path("/Library/Java/JavaVirtualMachines"), Path.home() / "Library/Java/JavaVirtualMachines"]:
            if jvm_base.exists():
                for sub in sorted(jvm_base.glob("*"), reverse=True):
                    candidates.append(sub / "Contents" / "Home" / "bin" / exe)
    if IS_WIN:
        for base in [Path(r"C:\Program Files\OpenLogic"), Path(r"C:\Program Files\Eclipse Adoptium"), Path(r"C:\Program Files\Java")]:
            if base.exists():
                for sub in sorted(base.glob("jdk*"), reverse=True):
                    candidates.append(sub / "bin" / exe)
    which_hit = shutil.which(exe) or shutil.which(name)
    if which_hit:
        candidates.append(Path(which_hit))
    for c in candidates:
        if c.exists():
            return c.resolve()
    return Path(exe)


def _find_android_sdk() -> Path:
    for env_key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        v = os.environ.get(env_key)
        if v and Path(v).exists():
            return Path(v)
    fallbacks = [
        Path(r"D:\dev\android-sdk"),
        Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" if IS_WIN else Path.home() / "Android" / "Sdk",
        Path.home() / "Library" / "Android" / "sdk",
        Path.home() / "android-sdk",
    ]
    for fb in fallbacks:
        if fb and fb.exists():
            return fb
    return Path("android-sdk-not-found")


def _pick_latest_child(parent: Path, pattern: str = "*") -> Path:
    if not parent.exists():
        return parent / "missing"
    items = sorted([p for p in parent.glob(pattern) if p.is_dir()], key=lambda p: p.name, reverse=True)
    return items[0] if items else (parent / "missing")


ANDROID_SDK = _find_android_sdk()
JAVA_BIN = _find_jdk_tool("javac")
JAVA_EXE = _find_jdk_tool("java")
KEYTOOL_BIN = _find_jdk_tool("keytool")
ANDROID_JAR = _pick_latest_child(ANDROID_SDK / "platforms", "android-*") / "android.jar"
BUILD_TOOLS = _pick_latest_child(ANDROID_SDK / "build-tools")
D8_BAT = BUILD_TOOLS / ("d8.bat" if IS_WIN else "d8")
ZIPALIGN_EXE = BUILD_TOOLS / ("zipalign.exe" if IS_WIN else "zipalign")
APKSIGNER_BAT = BUILD_TOOLS / ("apksigner.bat" if IS_WIN else "apksigner")

MIN_API = "26"
MIN_SDK_CHECK = "26"

STATIC_TARGET_CLASSES = {
    "MOD-01": ("com/rayneo/venus/VenusApplication.smali", "VenusApplication"),
    "MOD-02": ("com/rayneo/venus/MainActivity.smali", "MainActivity"),
    "MOD-03": ("com/rayneo/airuntime/controller/AssistantController.smali", "AssistantController"),
    "MOD-04": ("com/rayneo/airuntime/controller/AskAITask.smali", "AskAITask"),
    "MOD-06": ("com/rayneo/airuntime/controller/ASRTask.smali", "ASRTask"),
}


def run_cmd(cmd: list[str], cwd: Path | None = None, capture: bool = False) -> subprocess.CompletedProcess:
    use_shell = cmd[0].lower().endswith(".bat") or cmd[0].lower().endswith(".cmd")
    print(f"[CMD] {' '.join(cmd)}")
    env = os.environ.copy()
    if JAVA_EXE.exists() and JAVA_EXE.parent.name == "bin":
        jdk_home = str(JAVA_EXE.parent.parent)
        env.setdefault("JAVA_HOME", jdk_home)
        env["PATH"] = f"{JAVA_EXE.parent}{os.pathsep}{env.get('PATH', '')}"
    proc = subprocess.run(
        cmd,
        cwd=str(cwd) if cwd else None,
        shell=use_shell,
        text=True,
        capture_output=capture,
        encoding="utf-8",
        errors="replace",
        env=env,
    )
    if proc.returncode != 0:
        if capture:
            print(proc.stdout)
            print(proc.stderr)
        raise RuntimeError(f"Command failed (rc={proc.returncode}): {' '.join(cmd)}")
    return proc


def ensure_keystore() -> None:
    if KEYSTORE_PATH.exists():
        return
    KEYSTORE_PATH.parent.mkdir(parents=True, exist_ok=True)
    print(f"[INFO] Generating local signing keystore: {KEYSTORE_PATH}")
    run_cmd([
        str(KEYTOOL_BIN), "-genkeypair", "-v",
        "-keystore", str(KEYSTORE_PATH),
        "-alias", KEY_ALIAS,
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-storepass", KEY_PASS, "-keypass", KEY_PASS,
        "-dname", "CN=LovelyRayNeoio, OU=CustomAI, O=Community, L=Shenzhen, S=GD, C=CN",
    ])


def check_env() -> None:
    for label, p in [
        ("JDK javac (set JAVA_HOME)", JAVA_BIN),
        ("JDK java (set JAVA_HOME)", JAVA_EXE),
        ("Android SDK platform android.jar (set ANDROID_HOME)", ANDROID_JAR),
        ("Android build-tools d8 (set ANDROID_HOME)", D8_BAT),
        ("Android build-tools zipalign (set ANDROID_HOME)", ZIPALIGN_EXE),
        ("Android build-tools apksigner (set ANDROID_HOME)", APKSIGNER_BAT),
    ]:
        if not p.exists():
            raise FileNotFoundError(f"Required tool missing [{label}]: {p}")
    ensure_keystore()
    smali_jars = list(TOOLS_LIB.glob("*.jar"))
    if not smali_jars:
        raise FileNotFoundError(f"No smali/baksmali JARs found in {TOOLS_LIB}")


def build_custom_dex(work_dir: Path) -> tuple[Path, Path]:
    print("\n=== Step 1: Compiling CustomAI Java sources + D8 -> DEX ===")
    stub_cls = work_dir / "cls_stubs"
    app_cls = work_dir / "cls_app"
    dex_out = work_dir / "dex_custom"
    for d in [stub_cls, app_cls, dex_out]:
        d.mkdir(parents=True, exist_ok=True)

    stub_files = [str(p) for p in STUBS_DIR.rglob("*.java")]
    src_files = [str(p) for p in SRC_DIR.rglob("*.java")]
    if not src_files:
        raise RuntimeError(f"No source files in {SRC_DIR}")

    run_cmd([
        str(JAVA_BIN),
        "-encoding", "UTF-8",
        "-Xlint:-options",
        "-source", "1.8", "-target", "1.8",
        "-bootclasspath", str(ANDROID_JAR),
        "-d", str(stub_cls),
        *stub_files,
    ])

    cp = f"{ANDROID_JAR}{os.pathsep}{stub_cls}"
    run_cmd([
        str(JAVA_BIN),
        "-encoding", "UTF-8",
        "-Xlint:-options",
        "-source", "1.8", "-target", "1.8",
        "-bootclasspath", str(ANDROID_JAR),
        "-classpath", cp,
        "-d", str(app_cls),
        *src_files,
    ])

    stub_class_files = list(stub_cls.rglob("*.class"))
    stub_jar = work_dir / "custom_stubs.jar"
    with zipfile.ZipFile(stub_jar, "w", compression=zipfile.ZIP_STORED) as zf:
        for scf in stub_class_files:
            zf.write(scf, scf.relative_to(stub_cls).as_posix())

    class_files = list(app_cls.rglob("*.class"))
    app_jar = work_dir / "custom_app.jar"
    with zipfile.ZipFile(app_jar, "w", compression=zipfile.ZIP_STORED) as zf:
        for cf in class_files:
            zf.write(cf, cf.relative_to(app_cls).as_posix())
    run_cmd([
        str(D8_BAT),
        "--min-api", MIN_API,
        "--lib", str(ANDROID_JAR),
        "--classpath", str(stub_jar),
        "--output", str(dex_out),
        str(app_jar),
    ])

    out_dex = dex_out / "classes.dex"
    if not out_dex.exists():
        raise FileNotFoundError(f"Expected d8 output {out_dex} not found")
    print(f"[OK] Compiled custom DEX: {out_dex} ({out_dex.stat().st_size} bytes)")
    return out_dex, stub_jar


def wrap_block(mod_id: str, title: str, body: str) -> str:
    return (
        f"    # ==================== [RAYNEO_CUSTOM_AI_MOD START : {mod_id} | {title}] ====================\n"
        f"{body.rstrip()}\n"
        f"    # ==================== [RAYNEO_CUSTOM_AI_MOD END : {mod_id}] ====================\n"
    )


def inject_after_first(text: str, pattern: str, snippet: str, label: str) -> str:
    m = re.search(pattern, text, flags=re.MULTILINE)
    if not m:
        raise RuntimeError(f"Anchor not found for {label}: pattern={pattern}")
    pos = m.end()
    return text[:pos] + "\n\n" + snippet + text[pos:]


def replace_in_method(text: str, method_sig_regex: str, anchor_regex: str, snippet: str, label: str, before: bool = False) -> str:
    m_method = re.search(rf"(\.method [^\n]*{method_sig_regex}[\s\S]*?\.end method)", text)
    if not m_method:
        raise RuntimeError(f"Method not found for {label}: {method_sig_regex}")
    method_body = m_method.group(1)
    m_anchor = re.search(anchor_regex, method_body, flags=re.MULTILINE)
    if not m_anchor:
        raise RuntimeError(f"Method anchor not found inside {label}: {anchor_regex}")
    idx = m_anchor.start() if before else m_anchor.end()
    if before:
        # Preserve leading whitespace of the matched line
        line_start = method_body.rfind("\n", 0, idx) + 1
        indent = method_body[line_start:idx]
        if indent.strip() == "":
            new_body = method_body[:line_start] + snippet + "\n" + method_body[line_start:]
        else:
            new_body = method_body[:idx] + snippet + "\n    " + method_body[idx:]
    else:
        new_body = method_body[:idx] + "\n\n" + snippet + method_body[idx:]
    return text[:m_method.start()] + new_body + text[m_method.end():]


def patch_venus_application(src: str, bridge_dot_name: str = "com.rayneo.rayneo_venus_sdk_plugin.o4") -> str:
    m_oc = re.search(r"(\.method [^\n]*onCreate\(\)V[\s\S]*?\.end method)", src)
    if m_oc:
        oc_body = m_oc.group(1)
        if re.search(r"^\s*\.registers\s+1\b", oc_body, flags=re.MULTILINE):
            oc_body = re.sub(r"^(\s*\.registers\s+)1\b", r"\g<1>2", oc_body, count=1, flags=re.MULTILINE)
            src = src[:m_oc.start()] + oc_body + src[m_oc.end():]
    block = wrap_block(
        "MOD-01",
        "Init CustomAI on App Start",
        "\n".join(
            [
                f'    const-string v0, "{bridge_dot_name}"',
                "    invoke-static {v0}, Lcom/rayneo/customai/LyricsTeleprompterBridge;->registerBridgeClassName(Ljava/lang/String;)V",
                "    invoke-static/range {p0 .. p0}, Lcom/rayneo/customai/CustomAiHooks;->onAppCreate(Landroid/app/Application;)V",
            ]
        ),
    )
    return replace_in_method(
        src,
        r"onCreate\(\)V",
        r"invoke-super \{p0\}, Landroid/app/Application;->onCreate\(\)V",
        block,
        "MOD-01 VenusApplication.onCreate",
    )


def patch_main_activity(src: str) -> str:
    # MOD-02A: 3x Volume-Up shortcut inside dispatchKeyEvent
    block_a = wrap_block(
        "MOD-02A",
        "3x Volume-Up opens CustomAI Settings",
        "    invoke-static/range {p0 .. p1}, Lcom/rayneo/customai/CustomAiHooks;->onMainActivityKeyEvent(Landroid/app/Activity;Landroid/view/KeyEvent;)Z\n\n"
        "    move-result v0\n\n"
        "    if-eqz v0, :cond_custom_ai_key_pass\n\n"
        "    return v0\n\n"
        "    :cond_custom_ai_key_pass",
    )
    out = replace_in_method(
        src,
        r"dispatchKeyEvent\(Landroid/view/KeyEvent;\)Z",
        r"invoke-static \{p1, v0\}, Lkotlin/jvm/internal/r;->f\(Ljava/lang/Object;Ljava/lang/String;\)V",
        block_a,
        "MOD-02A MainActivity.dispatchKeyEvent",
    )

    # MOD-02B: Floating badge attach on onResume
    block_b = wrap_block(
        "MOD-02B",
        "Attach Draggable CustomAI Floating Badge",
        "    invoke-static/range {p0 .. p0}, Lcom/rayneo/customai/CustomAiHooks;->onMainActivityResume(Landroid/app/Activity;)V",
    )
    out = replace_in_method(
        out,
        r"onResume\(\)V",
        r"invoke-super \{p0\}, [^\n]+->onResume\(\)V",
        block_b,
        "MOD-02B MainActivity.onResume",
    )
    return out


def patch_assistant_controller(src: str) -> str:
    # MOD-03A: initWorkflow
    block_3a = wrap_block(
        "MOD-03A",
        "Register Assistant session",
        "    invoke-static/range {p0 .. p2}, Lcom/rayneo/customai/CustomAiHooks;->onAssistantInitWorkflow(Lcom/rayneo/airuntime/controller/AssistantController;Lcom/rayneo/airuntime/controller/RayNeoAiConfig;Lcom/rayneo/airuntime/controller/AiResultListener;)V",
    )
    out = replace_in_method(
        src,
        r"initWorkflow\(Lcom/rayneo/airuntime/controller/RayNeoAiConfig;Lcom/rayneo/airuntime/controller/AiResultListener;\)Z",
        r"invoke-interface \{v0, v1, p2\}, Ljava/util/Map;->put\(Ljava/lang/Object;Ljava/lang/Object;\)Ljava/lang/Object;",
        block_3a,
        "MOD-03A AssistantController.initWorkflow",
    )

    # MOD-03B: startWorkflow
    block_3b = wrap_block(
        "MOD-03B",
        "Reset turn & handle direct text-query workflow if any",
        "    invoke-static/range {p0 .. p2}, Lcom/rayneo/customai/CustomAiHooks;->onAssistantStartWorkflow(Lcom/rayneo/airuntime/controller/AssistantController;Lcom/rayneo/airuntime/controller/RayNeoAiConfig;Lcom/rayneo/airuntime/controller/RayNeoWorkflowParams;)Z\n\n"
        "    move-result v0\n\n"
        "    if-eqz v0, :cond_custom_ai_start_pass\n\n"
        "    return v0\n\n"
        "    :cond_custom_ai_start_pass",
    )
    out = replace_in_method(
        out,
        r"startWorkflow\(Lcom/rayneo/airuntime/controller/RayNeoAiConfig;Lcom/rayneo/airuntime/controller/RayNeoWorkflowParams;\)Z",
        r"invoke-static \{p1, v0\}, Lkotlin/jvm/internal/r;->f\(Ljava/lang/Object;Ljava/lang/String;\)V",
        block_3b,
        "MOD-03B AssistantController.startWorkflow",
    )

    # MOD-03C: stopWorkflow
    block_3c = wrap_block(
        "MOD-03C",
        "Cancel active CustomAI stream and stock TTS",
        "    invoke-static/range {p0 .. p1}, Lcom/rayneo/customai/CustomAiHooks;->onAssistantStopWorkflow(Lcom/rayneo/airuntime/controller/AssistantController;Lcom/rayneo/airuntime/controller/RayNeoAiConfig;)V",
    )
    out = replace_in_method(
        out,
        r"stopWorkflow\(Lcom/rayneo/airuntime/controller/RayNeoAiConfig;\)Z",
        r"invoke-static \{p1, v0\}, Lkotlin/jvm/internal/r;->f\(Ljava/lang/Object;Ljava/lang/String;\)V",
        block_3c,
        "MOD-03C AssistantController.stopWorkflow",
    )

    # MOD-03D: destroyWorkflow
    block_3d = wrap_block(
        "MOD-03D",
        "Cleanup CustomAI session on destroy",
        "    invoke-static/range {p0 .. p1}, Lcom/rayneo/customai/CustomAiHooks;->onAssistantDestroyWorkflow(Lcom/rayneo/airuntime/controller/AssistantController;Lcom/rayneo/airuntime/controller/RayNeoAiConfig;)V",
    )
    out = replace_in_method(
        out,
        r"destroyWorkflow\(Lcom/rayneo/airuntime/controller/RayNeoAiConfig;\)Z",
        r"invoke-static \{p1, v0\}, Lkotlin/jvm/internal/r;->f\(Ljava/lang/Object;Ljava/lang/String;\)V",
        block_3d,
        "MOD-03D AssistantController.destroyWorkflow",
    )

    # MOD-03E: nativeOnAsrResult (uses invoke-static/range because p4 can be >= v16)
    block_3e = wrap_block(
        "MOD-03E",
        "Trigger Custom LLM when stock ASR finishes (stock ASR stays untouched)",
        "    invoke-static/range {p0 .. p4}, Lcom/rayneo/customai/CustomAiHooks;->onNativeAsrResult(Lcom/rayneo/airuntime/controller/AssistantController;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;)V",
    )
    out = replace_in_method(
        out,
        r"private final nativeOnAsrResult\(Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;\)V",
        r"^\s*\.registers\s+\d+",
        block_3e,
        "MOD-03E AssistantController.nativeOnAsrResult",
    )

    # MOD-03F: nativeOnNlpResult (suppress cloud NLP while letting offline/hardware commands pass)
    block_3f = wrap_block(
        "MOD-03F",
        "Intercept cloud NLP while allowing local offline hardware commands",
        "    invoke-static/range {p0 .. p2}, Lcom/rayneo/customai/CustomAiHooks;->shouldInterceptNativeNlpResult(Lcom/rayneo/airuntime/controller/AssistantController;Ljava/lang/String;Lcom/rayneo/airuntime/controller/NlpResult;)Z\n\n"
        "    move-result v0\n\n"
        "    if-eqz v0, :cond_custom_ai_nlp_pass\n\n"
        "    return-void\n\n"
        "    :cond_custom_ai_nlp_pass",
    )
    out = replace_in_method(
        out,
        r"private final nativeOnNlpResult\(Ljava/lang/String;Lcom/rayneo/airuntime/controller/NlpResult;\)V",
        r"^\s*\.registers\s+\d+",
        block_3f,
        "MOD-03F AssistantController.nativeOnNlpResult",
    )

    # MOD-03H: nativeOnRayClawToolCallInfo (detect RayClaw server/client tool calls immediately -> route to official skill!)
    block_3h = wrap_block(
        "MOD-03H",
        "Detect RayClaw tool calls (weather/search/todo/music/skills) and route to official pipeline",
        "    invoke-static/range {p0 .. p2}, Lcom/rayneo/customai/CustomAiHooks;->onNativeRayClawToolCall(Lcom/rayneo/airuntime/controller/AssistantController;Ljava/lang/String;Lcom/rayneo/airuntime/controller/RayClawToolCallInfo;)Z\n\n"
        "    move-result v0\n\n"
        "    if-eqz v0, :cond_custom_ai_rc_pass\n\n"
        "    return-void\n\n"
        "    :cond_custom_ai_rc_pass",
    )
    out = replace_in_method(
        out,
        r"private final nativeOnRayClawToolCallInfo\(Ljava/lang/String;Lcom/rayneo/airuntime/controller/RayClawToolCallInfo;\)V",
        r"^\s*\.registers\s+\d+",
        block_3h,
        "MOD-03H AssistantController.nativeOnRayClawToolCallInfo",
    )

    # MOD-03G, I..K, N, P: suppress native post-ASR & lifecycle callbacks via shared hook (saves method_ids in classes5.dex)
    suppress_post_asr_specs = [
        ("MOD-03G", r"private final nativeOnDeepThinkStatus\(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;\)V",
         "shouldSuppressNativePostAsr", "cond_custom_ai_dt_pass"),
        ("MOD-03I", r"private final nativeOnTtsStatusChange\(Ljava/lang/String;ILjava/lang/String;\)V",
         "shouldSuppressNativePostAsr", "cond_custom_ai_tts_pass"),
        ("MOD-03J", r"private final nativeOnResponseComplete\(Ljava/lang/String;\)V",
         "shouldSuppressNativePostAsr", "cond_custom_ai_rcmp_pass"),
        ("MOD-03K", r"private final nativeOnError\(Ljava/lang/String;ILjava/lang/String;\)V",
         "shouldSuppressNativePostAsr", "cond_custom_ai_err_pass"),
        ("MOD-03N", r"private final nativeOnAudioRecordStart\(Ljava/lang/String;\)V",
         "shouldSuppressNativePostAsr", "cond_custom_ai_rec_start_pass"),
        ("MOD-03P", r"private final nativeOnNotify\(Ljava/lang/String;IILjava/lang/String;\)V",
         "shouldSuppressNativePostAsr", "cond_custom_ai_notify_pass"),
    ]
    for mod_id, sig, hook_fn, lbl in suppress_post_asr_specs:
        blk = wrap_block(
            mod_id,
            f"Suppress native post-ASR/lifecycle callback via {hook_fn}",
            f"    invoke-static/range {{p0 .. p1}}, Lcom/rayneo/customai/CustomAiHooks;->{hook_fn}(Lcom/rayneo/airuntime/controller/AssistantController;Ljava/lang/String;)Z\n\n"
            f"    move-result v0\n\n"
            f"    if-eqz v0, :{lbl}\n\n"
            f"    return-void\n\n"
            f"    :{lbl}",
        )
        out = replace_in_method(out, sig, r"^\s*\.registers\s+\d+", blk, f"{mod_id} {hook_fn}")

    # MOD-03L, M, O: status-filtered callbacks (SpeechStatus, VadStatusChange, once-per-round AudioRecordEnd)
    extra_suppress_specs = [
        (
            "MOD-03L",
            r"private final nativeOnSpeechStatus\(Ljava/lang/String;I\)V",
            "shouldSuppressNativeIntStatus",
            "(Lcom/rayneo/airuntime/controller/AssistantController;Ljava/lang/String;I)Z",
            "p0 .. p2",
            "cond_custom_ai_speech_pass",
        ),
        (
            "MOD-03M",
            r"private final nativeOnVadStatusChange\(Ljava/lang/String;I\)V",
            "shouldSuppressNativeIntStatus",
            "(Lcom/rayneo/airuntime/controller/AssistantController;Ljava/lang/String;I)Z",
            "p0 .. p2",
            "cond_custom_ai_vad_pass",
        ),
        (
            "MOD-03O",
            r"private final nativeOnAudioRecordEnd\(Ljava/lang/String;\)V",
            "shouldSuppressNativeAudioRecordEnd",
            "(Lcom/rayneo/airuntime/controller/AssistantController;Ljava/lang/String;)Z",
            "p0 .. p1",
            "cond_custom_ai_rec_end_pass",
        ),
    ]
    for mod_id, sig, hook_fn, desc, reg_range, lbl in extra_suppress_specs:
        blk = wrap_block(
            mod_id,
            f"Filter native lifecycle callback via {hook_fn}",
            f"    invoke-static/range {{{reg_range}}}, Lcom/rayneo/customai/CustomAiHooks;->{hook_fn}{desc}\n\n"
            f"    move-result v0\n\n"
            f"    if-eqz v0, :{lbl}\n\n"
            f"    return-void\n\n"
            f"    :{lbl}",
        )
        out = replace_in_method(out, sig, r"^\s*\.registers\s+\d+", blk, f"{mod_id} {hook_fn}")

    # MOD-03Q: startNextRound()V -> bump .registers 1 -> 2 (or .locals 0 -> 1) so v0 doesn't alias p0!
    m_snr = re.search(
        r"(\.method public final startNextRound\(\)V[\s\S]*?\.end method)",
        out,
    )
    if not m_snr:
        raise RuntimeError("Method not found for MOD-03Q AssistantController.startNextRound")
    snr_body = m_snr.group(1)
    if re.search(r"^\s*\.registers\s+1\b", snr_body, flags=re.MULTILINE):
        snr_body = re.sub(r"^(\s*\.registers\s+)1\b", r"\g<1>2", snr_body, count=1, flags=re.MULTILINE)
    elif re.search(r"^\s*\.locals\s+0\b", snr_body, flags=re.MULTILINE):
        snr_body = re.sub(r"^(\s*\.locals\s+)0\b", r"\g<1>1", snr_body, count=1, flags=re.MULTILINE)
    out = out[:m_snr.start()] + snr_body + out[m_snr.end():]

    block_3q = wrap_block(
        "MOD-03Q",
        "Restart workflow cleanly when native C++ workflow was stopped during CustomAI turn",
        "    invoke-static/range {p0 .. p0}, Lcom/rayneo/customai/CustomAiHooks;->onAssistantStartNextRound(Lcom/rayneo/airuntime/controller/AssistantController;)Z\n\n"
        "    move-result v0\n\n"
        "    if-eqz v0, :cond_custom_ai_next_round_orig\n\n"
        "    return-void\n\n"
        "    :cond_custom_ai_next_round_orig",
    )
    out = replace_in_method(
        out,
        r"public final startNextRound\(\)V",
        r"^\s*\.(?:registers|locals)\s+\d+",
        block_3q,
        "MOD-03Q AssistantController.startNextRound",
    )

    return out


def patch_ask_ai_task(src: str) -> str:
    # MOD-04A: initTask - track task & ensure isInit=true when CustomAI AskAI enabled
    block_4a = wrap_block(
        "MOD-04A",
        "Register AskAITask and allow offline init when CustomAI enabled",
        "    invoke-static/range {p0 .. p0}, Lcom/rayneo/customai/CustomAiHooks;->onAskAiInit(Lcom/rayneo/airuntime/controller/AskAITask;)Z\n\n"
        "    move-result v0\n\n"
        "    if-eqz v0, :cond_custom_ai_ask_init_orig\n\n"
        "    iget-boolean v0, p0, Lcom/rayneo/airuntime/controller/AskAITask;->isInit:Z\n\n"
        "    if-nez v0, :cond_custom_ai_ask_init_orig\n\n"
        "    const/4 v0, 0x1\n\n"
        "    iput-boolean v0, p0, Lcom/rayneo/airuntime/controller/AskAITask;->isInit:Z\n\n"
        "    :cond_custom_ai_ask_init_orig",
    )
    out = replace_in_method(
        src,
        r"initTask\(Lcom/rayneo/airuntime/controller/RayNeoAskAIConfig;Lcom/rayneo/airuntime/controller/AskAIResultListener;\)Z",
        r"iget-boolean p0, p0, Lcom/rayneo/airuntime/controller/AskAITask;->isInit:Z",
        block_4a,
        "MOD-04A AskAITask.initTask",
        before=True,
    )

    # MOD-04B: startTask
    block_4b = wrap_block(
        "MOD-04B",
        "Intercept phone AskAI prompt and stream custom LLM response",
        "    invoke-static/range {p0 .. p1}, Lcom/rayneo/customai/CustomAiHooks;->onAskAiStart(Lcom/rayneo/airuntime/controller/AskAITask;Lcom/rayneo/airuntime/controller/RayNeoAskAIParams;)Z\n\n"
        "    move-result v0\n\n"
        "    if-eqz v0, :cond_custom_ai_ask_start_pass\n\n"
        "    return v0\n\n"
        "    :cond_custom_ai_ask_start_pass",
    )
    out = replace_in_method(
        out,
        r"startTask\(Lcom/rayneo/airuntime/controller/RayNeoAskAIParams;\)Z",
        r"iget-object v0, p0, Lcom/rayneo/airuntime/controller/AskAITask;->name:Ljava/lang/String;\s*\n\s*invoke-direct \{p0, v0, p1\}, Lcom/rayneo/airuntime/controller/AskAITask;->nativeStartTask",
        block_4b,
        "MOD-04B AskAITask.startTask",
        before=True,
    )

    # MOD-04C: stopTask
    block_4c = wrap_block(
        "MOD-04C",
        "Cancel active CustomAI AskAI stream",
        "    invoke-static/range {p0 .. p0}, Lcom/rayneo/customai/CustomAiHooks;->onAskAiStop(Lcom/rayneo/airuntime/controller/AskAITask;)V",
    )
    out = replace_in_method(
        out,
        r"stopTask\(\)Z",
        r"iget-object v0, p0, Lcom/rayneo/airuntime/controller/AskAITask;->name:Ljava/lang/String;\s*\n\s*invoke-direct \{p0, v0\}, Lcom/rayneo/airuntime/controller/AskAITask;->nativeStopTask",
        block_4c,
        "MOD-04C AskAITask.stopTask",
        before=True,
    )

    # MOD-04D: releaseTask
    block_4d = wrap_block(
        "MOD-04D",
        "Release AskAITask CustomAI state",
        "    invoke-static/range {p0 .. p0}, Lcom/rayneo/customai/CustomAiHooks;->onAskAiRelease(Lcom/rayneo/airuntime/controller/AskAITask;)V",
    )
    out = replace_in_method(
        out,
        r"releaseTask\(\)Z",
        r"^\s*\.registers\s+\d+",
        block_4d,
        "MOD-04D AskAITask.releaseTask",
    )

    return out


def patch_rayneo_net_bridge(src: str, bridge_desc: str = "Lcom/rayneo/rayneo_venus_sdk_plugin/o4;") -> str:
    # MOD-05: Dynamically find event sink method (String, Map)V containing "onRNAnalyticsEvent"
    evt_method_name = None
    for m in re.finditer(
        r"(\.method public final ([a-zA-Z0-9_]+)\(Ljava/lang/String;Ljava/util/Map;\)V[\s\S]*?\.end method)",
        src,
    ):
        if '"onRNAnalyticsEvent"' in m.group(1):
            evt_method_name = m.group(2)
            break
    if not evt_method_name:
        raise RuntimeError(f"Could not locate event sink (String, Map)V method on {bridge_desc}")

    block_5 = wrap_block(
        "MOD-05",
        "Observe & intercept RayneoNet BLE events for Lyrics <-> Teleprompter real-time sync",
        "\n".join(
            [
                "    invoke-static/range {p0 .. p2}, Lcom/rayneo/customai/CustomAiHooks;->onRayneoNetEvent(Ljava/lang/Object;Ljava/lang/String;Ljava/util/Map;)Z",
                "    move-result v0",
                "    if-eqz v0, :cond_custom_ai_net_pass",
                "    return-void",
                "    :cond_custom_ai_net_pass",
            ]
        ),
    )
    out = replace_in_method(
        src,
        rf"public final {re.escape(evt_method_name)}\(Ljava/lang/String;Ljava/util/Map;\)V",
        r"^\s*\.(?:registers|locals)\s+\d+",
        block_5,
        f"MOD-05 {bridge_desc}->{evt_method_name}(eventName, data)",
    )

    # MOD-05B: Dynamically find low-level packet logger static (<Bridge>, String, String, String, Map, [B, String, I)V
    esc_desc = re.escape(bridge_desc)
    m_trace = re.search(
        rf"\.method public static ([a-zA-Z0-9_]+)\({esc_desc}Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;\[BLjava/lang/String;I\)V",
        out,
    )
    if not m_trace:
        raise RuntimeError(f"Could not locate low-level packet trace method on {bridge_desc}")
    trace_method_name = m_trace.group(1)

    block_5b = wrap_block(
        "MOD-05B",
        "Observe RayneoNet low-level outbound/inbound frames & errors",
        "    invoke-static/range {p0 .. p5}, Lcom/rayneo/customai/CustomAiHooks;->onRayneoNetTrace(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;[B)V",
    )
    return replace_in_method(
        out,
        rf"public static {re.escape(trace_method_name)}\({esc_desc}Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;\[BLjava/lang/String;I\)V",
        r"^\s*\.(?:registers|locals)\s+\d+",
        block_5b,
        f"MOD-05B {bridge_desc}->{trace_method_name}(bridge, dir, event, summary, meta, rawBytes, ...)",
    )


def patch_asr_task(src: str) -> str:
    # MOD-06A: initTask - register Proactive AI ("venus_proactive_ai_asr") listener & config
    block_6a = wrap_block(
        "MOD-06A",
        "Register ASRTask for Disco Elysium Subtext & GALGAME Options when Proactive AI starts",
        "\n".join(
            [
                "    iget-object v0, p0, Lcom/rayneo/airuntime/controller/ASRTask;->name:Ljava/lang/String;",
                "    invoke-static {p0, v0, p1, p3}, Lcom/rayneo/customai/CustomAiHooks;->onAsrTaskInit(Lcom/rayneo/airuntime/controller/ASRTask;Ljava/lang/String;Lcom/rayneo/airuntime/controller/RayNeoASRConfig;Lcom/rayneo/airuntime/controller/ASRResultListener;)V",
            ]
        ),
    )
    out = replace_in_method(
        src,
        r"public final initTask\(Lcom/rayneo/airuntime/controller/RayNeoASRConfig;Lcom/rayneo/airuntime/bridge/AudioRecorderBridge\$AudioRecorderAdapter;Lcom/rayneo/airuntime/controller/ASRResultListener;\)Z",
        r"^\s*\.(?:registers|locals)\s+\d+",
        block_6a,
        "MOD-06A ASRTask.initTask",
    )

    # MOD-06B & MOD-06C: stopTask & releaseTask
    for mod_id, sig in [("MOD-06B", r"public final stopTask\(\)Z"), ("MOD-06C", r"public final releaseTask\(\)Z")]:
        blk = wrap_block(
            mod_id,
            "Clean up Proactive AI Subtext session state on stop/release",
            "\n".join(
                [
                    "    iget-object v0, p0, Lcom/rayneo/airuntime/controller/ASRTask;->name:Ljava/lang/String;",
                    "    invoke-static {p0, v0}, Lcom/rayneo/customai/CustomAiHooks;->onAsrTaskStop(Lcom/rayneo/airuntime/controller/ASRTask;Ljava/lang/String;)V",
                ]
            ),
        )
        out = replace_in_method(out, sig, r"^\s*\.(?:registers|locals)\s+\d+", blk, f"{mod_id} ASRTask")

    # MOD-06D: nativeOnAsrResult(String, Z)V
    block_6d = wrap_block(
        "MOD-06D",
        "Feed Proactive AI ambient ASR clauses into SubtextGalgameEngine",
        "\n".join(
            [
                "    iget-object v0, p0, Lcom/rayneo/airuntime/controller/ASRTask;->name:Ljava/lang/String;",
                "    invoke-static {p0, v0, p1, p2}, Lcom/rayneo/customai/CustomAiHooks;->onAsrTaskResult(Lcom/rayneo/airuntime/controller/ASRTask;Ljava/lang/String;Ljava/lang/String;Z)V",
            ]
        ),
    )
    out = replace_in_method(
        out,
        r"private final nativeOnAsrResult\(Ljava/lang/String;Z\)V",
        r"^\s*\.(?:registers|locals)\s+\d+",
        block_6d,
        "MOD-06D ASRTask.nativeOnAsrResult",
    )

    # MOD-06E: nativeOnProactiveHint(String, Z, String, String, String)V (note: p3 >= v16 -> move-object/from16 v1, p3)
    block_6e = wrap_block(
        "MOD-06E",
        "Suppress stock encyclopedic C++ hints when SubtextGalgameEngine owns Proactive AI",
        "\n".join(
            [
                "    iget-object v0, p0, Lcom/rayneo/airuntime/controller/ASRTask;->name:Ljava/lang/String;",
                "    move-object/from16 v1, p3",
                "    invoke-static {p0, v0, p1, p2, v1}, Lcom/rayneo/customai/CustomAiHooks;->onAsrTaskProactiveHint(Lcom/rayneo/airuntime/controller/ASRTask;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;)Z",
                "    move-result v0",
                "    if-eqz v0, :cond_custom_ai_proactive_pass",
                "    return-void",
                "    :cond_custom_ai_proactive_pass",
            ]
        ),
    )
    out = replace_in_method(
        out,
        r"private final nativeOnProactiveHint\(Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;\)V",
        r"^\s*\.(?:registers|locals)\s+\d+",
        block_6e,
        "MOD-06E ASRTask.nativeOnProactiveHint",
    )
    return out


def patch_caption_hub(src: str, hub_desc: str) -> str:
    esc_desc = re.escape(hub_desc)
    forward_name = None
    for m in re.finditer(
        rf"(\.method public static final ([a-zA-Z0-9_]+)\({esc_desc}Ljava/lang/String;\)V[\s\S]*?\.end method)",
        src,
    ):
        if '"translateResult forward to glass skipped/failed, payload="' in m.group(1):
            forward_name = m.group(2)
            break
    if not forward_name:
        raise RuntimeError(f"Could not locate caption forwarder ({hub_desc}, String)V on {hub_desc}")

    block_7 = wrap_block(
        "MOD-07",
        "Intercept Live Captions / Translate payload for Disco Elysium Subtext & GALGAME Options",
        "\n".join(
            [
                "    invoke-static {p0, p1}, Lcom/rayneo/customai/CustomAiHooks;->onCaptionForwardPayload(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;",
                "    move-result-object p1",
                "    if-nez p1, :cond_custom_ai_cap_pass",
                "    return-void",
                "    :cond_custom_ai_cap_pass",
            ]
        ),
    )
    return replace_in_method(
        src,
        rf"public static final {re.escape(forward_name)}\({esc_desc}Ljava/lang/String;\)V",
        r"^\s*\.(?:registers|locals)\s+\d+",
        block_7,
        f"MOD-07 {hub_desc}->{forward_name}(hub, jsonString)",
    )


def locate_target_dex(apk_path: Path) -> tuple[list[str], str]:
    with zipfile.ZipFile(apk_path, "r") as zf:
        dex_names = sorted(
            [n for n in zf.namelist() if re.fullmatch(r"classes\d*\.dex", n)],
            key=lambda s: 1 if s == "classes.dex" else int(s[7:-4]),
        )
        # Heuristic: look for descriptor string or RayneoNetBridge/CaptionHub anchor string inside raw DEX bytes
        markers = [
            b"Lcom/rayneo/venus/VenusApplication;",
            b"Lcom/rayneo/venus/MainActivity;",
            b"Lcom/rayneo/airuntime/controller/AssistantController;",
            b"Lcom/rayneo/airuntime/controller/AskAITask;",
            b"Lcom/rayneo/airuntime/controller/ASRTask;",
            b"netproxy_http_request_finished",
            b"translateResult forward to glass skipped",
        ]
        found = []
        for dn in dex_names:
            data = zf.read(dn)
            if any(m in data for m in markers):
                found.append(dn)
        if not found:
            raise RuntimeError("Could not locate target RayNeo classes in any DEX!")
        last = dex_names[-1]
        next_idx = 2 if last == "classes.dex" else int(last[7:-4]) + 1
        new_dex_name = f"classes{next_idx}.dex"
        print(f"[OK] Existing DEX count={len(dex_names)} (last={last}), target DEX(es)={found}, new DEX={new_dex_name}")
        return found, new_dex_name


def discover_rayneo_net_bridge_smali(smali_dirs: dict[str, Path]) -> tuple[str, str, str, str]:
    """Scans baksmali directories to dynamically locate RayneoNetBridge regardless of R8 class renaming."""
    hits: list[tuple[str, Path]] = []
    for dn, sdir in smali_dirs.items():
        plugin_dir = sdir / "com" / "rayneo" / "rayneo_venus_sdk_plugin"
        if not plugin_dir.exists():
            continue
        for cand in sorted(plugin_dir.glob("*.smali")):
            if "$" in cand.name:
                continue
            txt = cand.read_text(encoding="utf-8", errors="ignore")
            if '"onRNAnalyticsEvent"' in txt and '"netproxy_http_request_finished"' in txt and '"businessId"' in txt:
                hits.append((dn, cand))
    if len(hits) != 1:
        raise RuntimeError(f"Expected exactly 1 RayneoNetBridge class in rayneo_venus_sdk_plugin, found {len(hits)}: {hits}")
    dn, cand = hits[0]
    rel_path = cand.relative_to(smali_dirs[dn]).as_posix()
    no_ext = rel_path[:-6]  # strip .smali
    dot_name = no_ext.replace("/", ".")
    desc = f"L{no_ext};"
    short_name = f"RayneoNetBridge_{cand.stem}"
    print(f"[OK] Dynamically discovered RayneoNetBridge: {dn}:{rel_path} ({dot_name})")
    return rel_path, short_name, dot_name, desc


def discover_caption_hub_smali(smali_dirs: dict[str, Path]) -> tuple[str, str, str]:
    """Scans baksmali directories to dynamically locate CaptionHub (im0/c0) regardless of R8 renaming."""
    hits: list[tuple[str, Path]] = []
    anchor = '"translateResult forward to glass skipped/failed, payload="'
    for dn, sdir in smali_dirs.items():
        for cand in sorted(sdir.rglob("*.smali")):
            if "$" in cand.name:
                continue
            txt = cand.read_text(encoding="utf-8", errors="ignore")
            if anchor in txt and '"source_transcript"' in txt:
                hits.append((dn, cand))
    if len(hits) != 1:
        raise RuntimeError(f"Expected exactly 1 CaptionHub class matching {anchor}, found {len(hits)}: {hits}")
    dn, cand = hits[0]
    rel_path = cand.relative_to(smali_dirs[dn]).as_posix()
    no_ext = rel_path[:-6]
    desc = f"L{no_ext};"
    short_name = f"CaptionHub_{cand.stem}"
    print(f"[OK] Dynamically discovered CaptionHub: {dn}:{rel_path} ({desc})")
    return rel_path, short_name, desc


_OBJECT_METHODS = {
    ("equals", "(Ljava/lang/Object;)Z"),
    ("hashCode", "()I"),
    ("toString", "()Ljava/lang/String;"),
    ("getClass", "()Ljava/lang/Class;"),
    ("notify", "()V"),
    ("notifyAll", "()V"),
    ("wait", "()V"),
    ("wait", "(J)V"),
    ("wait", "(JI)V"),
    ("clone", "()Ljava/lang/Object;"),
    ("finalize", "()V"),
    ("<init>", "()V"),
}


def verify_apk_dex_symbol_links(apk_path: Path, custom_dex_name: str) -> None:
    """Hierarchy-aware cross-DEX symbol link verifier: ensures 0 missing classes/fields/methods at runtime."""
    all_classes: dict[str, tuple[str | None, list[str]]] = {}
    all_fields: set[tuple[str, str, str]] = set()
    all_methods: set[tuple[str, str, str]] = set()
    custom_refs: tuple[set[str], set[tuple[str, str, str]], set[tuple[str, str, str]]] | None = None

    with zipfile.ZipFile(apk_path, "r") as zf:
        dex_names = sorted(
            [n for n in zf.namelist() if re.fullmatch(r"classes\d*\.dex", n)],
            key=lambda s: 1 if s == "classes.dex" else int(s[7:-4]),
        )
        for dn in dex_names:
            data = zf.read(dn)
            (
                str_sz, str_off, type_sz, type_off, proto_sz, proto_off,
                field_sz, field_off, meth_sz, meth_off, cls_sz, cls_off,
            ) = struct.unpack_from("<12I", data, 56)

            def read_uleb(pos: int) -> tuple[int, int]:
                res, shift = 0, 0
                while True:
                    b = data[pos]
                    pos += 1
                    res |= (b & 0x7F) << shift
                    if not (b & 0x80):
                        break
                    shift += 7
                return res, pos

            strings: list[str] = []
            for i in range(str_sz):
                (off,) = struct.unpack_from("<I", data, str_off + i * 4)
                _, p = read_uleb(off)
                end = data.index(0, p)
                strings.append(data[p:end].decode("utf-8", "replace"))
            types = [strings[struct.unpack_from("<I", data, type_off + i * 4)[0]] for i in range(type_sz)]
            protos: list[str] = []
            for i in range(proto_sz):
                _, ret_idx, params_off = struct.unpack_from("<3I", data, proto_off + i * 12)
                params: list[str] = []
                if params_off:
                    (cnt,) = struct.unpack_from("<I", data, params_off)
                    for j in range(cnt):
                        (t_idx,) = struct.unpack_from("<H", data, params_off + 4 + j * 2)
                        params.append(types[t_idx])
                protos.append(f"({''.join(params)}){types[ret_idx]}")
            fields = [
                (types[c], strings[n], types[t])
                for c, t, n in (struct.unpack_from("<HHI", data, field_off + i * 8) for i in range(field_sz))
            ]
            methods = [
                (types[c], strings[n], protos[p])
                for c, p, n in (struct.unpack_from("<HHI", data, meth_off + i * 8) for i in range(meth_sz))
            ]

            for i in range(cls_sz):
                cls_idx, _, sup_idx, iface_off, _, _, class_data_off, _ = struct.unpack_from("<8I", data, cls_off + i * 32)
                cname = types[cls_idx]
                sup = types[sup_idx] if sup_idx != 0xFFFFFFFF else None
                ifaces: list[str] = []
                if iface_off:
                    (icnt,) = struct.unpack_from("<I", data, iface_off)
                    for j in range(icnt):
                        ifaces.append(types[struct.unpack_from("<H", data, iface_off + 4 + j * 2)[0]])
                all_classes[cname] = (sup, ifaces)
                if class_data_off:
                    sf_sz, pos = read_uleb(class_data_off)
                    if_sz, pos = read_uleb(pos)
                    dm_sz, pos = read_uleb(pos)
                    vm_sz, pos = read_uleb(pos)
                    fidx = 0
                    for _ in range(sf_sz):
                        d, pos = read_uleb(pos)
                        fidx += d
                        _, pos = read_uleb(pos)
                        all_fields.add(fields[fidx])
                    fidx = 0
                    for _ in range(if_sz):
                        d, pos = read_uleb(pos)
                        fidx += d
                        _, pos = read_uleb(pos)
                        all_fields.add(fields[fidx])
                    midx = 0
                    for _ in range(dm_sz):
                        d, pos = read_uleb(pos)
                        midx += d
                        _, pos = read_uleb(pos)
                        _, pos = read_uleb(pos)
                        all_methods.add(methods[midx])
                    midx = 0
                    for _ in range(vm_sz):
                        d, pos = read_uleb(pos)
                        midx += d
                        _, pos = read_uleb(pos)
                        _, pos = read_uleb(pos)
                        all_methods.add(methods[midx])
            if dn == custom_dex_name:
                custom_refs = (set(types), set(fields), set(methods))

    if custom_refs is None:
        raise RuntimeError(f"Custom DEX {custom_dex_name} not found in {apk_path}")

    def is_sdk(t: str | None) -> bool:
        return t is None or t.startswith(("Landroid/", "Ljava/", "Ljavax/", "Ldalvik/", "Lorg/json/", "[")) or len(t) == 1

    def has_method(cls: str, name: str, proto: str) -> bool:
        visited: set[str] = set()
        stack = [cls]
        while stack:
            c = stack.pop()
            if not c or c in visited:
                continue
            visited.add(c)
            if c == "Ljava/lang/Object;":
                if (name, proto) in _OBJECT_METHODS:
                    return True
                continue
            if is_sdk(c):
                return True
            if (c, name, proto) in all_methods:
                return True
            if c in all_classes:
                sup, ifaces = all_classes[c]
                if sup:
                    stack.append(sup)
                stack.extend(ifaces)
        return False

    def has_field(cls: str, name: str, ftype: str) -> bool:
        visited: set[str] = set()
        stack = [cls]
        while stack:
            c = stack.pop()
            if not c or c in visited:
                continue
            visited.add(c)
            if c == "Ljava/lang/Object;":
                continue
            if is_sdk(c):
                return True
            if (c, name, ftype) in all_fields:
                return True
            if c in all_classes:
                sup, ifaces = all_classes[c]
                if sup:
                    stack.append(sup)
                stack.extend(ifaces)
        return False

    rt, rf, rm = custom_refs
    missing_cls = [t for t in sorted(rt) if not is_sdk(t) and t not in all_classes]
    missing_fld = [f for f in sorted(rf) if not is_sdk(f[0]) and not has_field(*f)]
    missing_mth = [m for m in sorted(rm) if not is_sdk(m[0]) and not has_method(*m)]
    if missing_cls or missing_fld or missing_mth:
        lines = ["Cross-DEX symbol link verification FAILED against target APK DEXes:"]
        for c in missing_cls:
            lines.append(f"  MISSING CLASS : {c}")
        for f in missing_fld:
            lines.append(f"  MISSING FIELD : {f[0]}->{f[1]}:{f[2]}")
        for m in missing_mth:
            lines.append(f"  MISSING METHOD: {m[0]}->{m[1]}{m[2]}")
        raise RuntimeError("\n".join(lines))
    print(f"[OK] Full cross-DEX symbol link check passed across {len(all_classes):,} APK classes (0 missing symbols)!")


def save_annotations(mod_id: str, short_name: str, orig_path: Path, patched_text: str) -> str:
    orig_dir = ANNOTATIONS_DIR / "orig"
    patched_dir = ANNOTATIONS_DIR / "patched"
    diff_dir = ANNOTATIONS_DIR / "diffs"
    for d in [orig_dir, patched_dir, diff_dir]:
        d.mkdir(parents=True, exist_ok=True)

    # Remove stale diff files for this MOD-XX when R8 class suffix changes across versions (e.g. o4 -> r4, c0 -> b0)
    for old_diff in diff_dir.glob(f"{mod_id}_*.diff"):
        if old_diff.name != f"{mod_id}_{short_name}.diff":
            old_diff.unlink()

    orig_text = orig_path.read_text(encoding="utf-8")
    (orig_dir / f"{short_name}.orig.smali").write_text(orig_text, encoding="utf-8")
    (patched_dir / f"{short_name}.patched.smali").write_text(patched_text, encoding="utf-8")

    diff_lines = list(difflib.unified_diff(
        orig_text.splitlines(keepends=True),
        patched_text.splitlines(keepends=True),
        fromfile=f"orig/{orig_path.name}",
        tofile=f"patched/{orig_path.name}",
        n=4,
    ))
    diff_str = "".join(diff_lines)
    (diff_dir / f"{mod_id}_{short_name}.diff").write_text(diff_str, encoding="utf-8")
    return diff_str


def patch_apk(input_apk: Path, output_apk: Path) -> None:
    check_env()
    if not input_apk.exists():
        raise FileNotFoundError(f"Input APK not found: {input_apk}")
    if input_apk.resolve() == output_apk.resolve():
        raise ValueError("Output APK path must not overwrite input APK!")

    work_dir = ROOT_DIR / "_work_tmp_build"
    if work_dir.exists():
        shutil.rmtree(work_dir)
    work_dir.mkdir(parents=True)

    try:
        custom_dex, stub_jar = build_custom_dex(work_dir)
        target_dex_names, new_dex_name = locate_target_dex(input_apk)

        applied_mods: set[str] = set()
        patched_dex_map: dict[str, Path] = {}
        all_diffs: list[str] = []

        classpath = str(TOOLS_LIB / "*")

        print("\n=== Step 2: Baksmali -> Discover MOD-05 -> Inject MOD-01..MOD-05 -> Smali ===")
        smali_dirs: dict[str, Path] = {}
        with zipfile.ZipFile(input_apk, "r") as zf:
            for dn in target_dex_names:
                raw_dex = work_dir / f"orig_{dn}"
                raw_dex.write_bytes(zf.read(dn))
                smali_dir = work_dir / f"smali_{dn.replace('.', '_')}"
                run_cmd([
                    str(JAVA_EXE), "-cp", classpath, "com.android.tools.smali.baksmali.Main",
                    "d", str(raw_dex), "-o", str(smali_dir), "--api", MIN_API,
                ])
                smali_dirs[dn] = smali_dir

        bridge_rel_path, bridge_short_name, bridge_dot_name, bridge_desc = discover_rayneo_net_bridge_smali(smali_dirs)
        cap_rel_path, cap_short_name, cap_desc = discover_caption_hub_smali(smali_dirs)

        target_classes = dict(STATIC_TARGET_CLASSES)
        target_classes["MOD-05"] = (bridge_rel_path, bridge_short_name)
        target_classes["MOD-07"] = (cap_rel_path, cap_short_name)

        patchers = {
            "MOD-01": lambda s: patch_venus_application(s, bridge_dot_name),
            "MOD-02": patch_main_activity,
            "MOD-03": patch_assistant_controller,
            "MOD-04": patch_ask_ai_task,
            "MOD-05": lambda s: patch_rayneo_net_bridge(s, bridge_desc),
            "MOD-06": patch_asr_task,
            "MOD-07": lambda s: patch_caption_hub(s, cap_desc),
        }

        for dn, smali_dir in smali_dirs.items():
            dex_patched = False
            for mod_id, (rel_path, short_name) in target_classes.items():
                candidate = smali_dir / rel_path
                if candidate.exists():
                    orig_text = candidate.read_text(encoding="utf-8")
                    patched_text = patchers[mod_id](orig_text)
                    diff_str = save_annotations(mod_id, short_name, candidate, patched_text)
                    all_diffs.append(f"### {mod_id}: {rel_path}\n{diff_str}\n")
                    candidate.write_text(patched_text, encoding="utf-8")
                    applied_mods.add(mod_id)
                    dex_patched = True
                    print(f"[PATCHED] {mod_id} -> {dn}:{rel_path}")

            if dex_patched:
                out_patched_dex = work_dir / f"patched_{dn}"
                run_cmd([
                    str(JAVA_EXE), "-cp", classpath, "com.android.tools.smali.smali.Main",
                    "a", str(smali_dir), "-o", str(out_patched_dex), "--api", MIN_API,
                ])
                patched_dex_map[dn] = out_patched_dex
            else:
                print(f"[SKIP] {dn} contains references only, left byte-for-byte untouched")

        missing = set(target_classes.keys()) - applied_mods
        if missing:
            raise RuntimeError(f"Failed to locate & patch: {sorted(missing)}")

        (ANNOTATIONS_DIR / "diffs" / "ALL_MODIFICATIONS.diff").write_text("\n".join(all_diffs), encoding="utf-8")

        print("\n=== Step 3: Joint D8 Bytecode & Register Verification ===")
        verify_dir = work_dir / "d8_verify_out"
        verify_dir.mkdir(parents=True)
        run_cmd([
            str(D8_BAT),
            "--min-api", MIN_API,
            "--lib", str(ANDROID_JAR),
            "--classpath", str(stub_jar),
            "--output", str(verify_dir),
            *[str(p) for p in patched_dex_map.values()],
            str(custom_dex),
        ])
        print("[OK] Joint D8 bytecode/register verification passed with 0 errors!")

        print("\n=== Step 4: Repackage APK -> Cross-DEX Link Check -> Zipalign (-P 16) -> Sign ===")
        unaligned_apk = work_dir / "unaligned.apk"
        aligned_apk = work_dir / "aligned.apk"

        skip_prefixes = ("META-INF/",)
        with zipfile.ZipFile(input_apk, "r") as zin, zipfile.ZipFile(unaligned_apk, "w") as zout:
            for info in zin.infolist():
                if info.filename.startswith(skip_prefixes):
                    lower = info.filename.lower()
                    if lower.endswith(".sf") or lower.endswith(".rsa") or lower.endswith(".dsa") or lower.endswith("manifest.mf"):
                        continue
                data = patched_dex_map[info.filename].read_bytes() if info.filename in patched_dex_map else zin.read(info.filename)
                zi = zipfile.ZipInfo(info.filename)
                zi.compress_type = info.compress_type
                zi.external_attr = info.external_attr
                zout.writestr(zi, data, compress_type=info.compress_type)
            # Append custom DEX
            zi_new = zipfile.ZipInfo(new_dex_name)
            zi_new.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(zi_new, custom_dex.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)

        verify_apk_dex_symbol_links(unaligned_apk, new_dex_name)

        run_cmd([str(ZIPALIGN_EXE), "-P", "16", "-f", "4", str(unaligned_apk), str(aligned_apk)])
        run_cmd([str(ZIPALIGN_EXE), "-P", "16", "-c", "4", str(aligned_apk)])

        if output_apk.exists():
            output_apk.unlink()
        run_cmd([
            str(APKSIGNER_BAT), "sign",
            "--ks", str(KEYSTORE_PATH),
            "--ks-key-alias", KEY_ALIAS,
            "--ks-pass", f"pass:{KEY_PASS}",
            "--key-pass", f"pass:{KEY_PASS}",
            "--v1-signing-enabled", "true",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            "--out", str(output_apk),
            str(aligned_apk),
        ])
        run_cmd([str(APKSIGNER_BAT), "verify", "--verbose", str(output_apk)])
        print(f"\n[SUCCESS] Built & verified: {output_apk} ({output_apk.stat().st_size:,} bytes)")
    finally:
        if work_dir.exists():
            shutil.rmtree(work_dir, ignore_errors=True)


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: python patch_apk.py <input_official.apk> [output_custom.apk]")
        return 1
    in_apk = Path(sys.argv[1]).resolve()
    if len(sys.argv) >= 3:
        out_apk = Path(sys.argv[2]).resolve()
    else:
        out_apk = in_apk.with_name(f"{in_apk.stem}_CustomAI.apk")
    patch_apk(in_apk, out_apk)
    return 0


if __name__ == "__main__":
    sys.exit(main())
