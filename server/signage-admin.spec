# -*- mode: python ; coding: utf-8 -*-
"""
把网页后台打成独立的 signage-admin.exe。

用 server/build-exe.py 调用，不要直接 pyinstaller 这个文件（脚本会负责装依赖、
清目录、跑冒烟测试）。

打包后的关键点（代码里也写了，这里再记一遍，改东西之前先看这四条）：

1. `web/` 必须作为 datas 打进包里，代码用 `sys._MEIPASS` 找它。
   漏了这一步的表现是：exe 能起来、日志正常，但打开网页 404。
2. 单文件模式（EXE 里直接带 binaries/datas）每次启动都解到新的临时目录，
   所以 `data/` 绝不能落在 `_MEIPASS` 里 —— 代码按 `sys.executable` 的目录取。
3. `signage_core`（在 ../tools/）和 `backend` 是运行时按 sys.path 找的，
   打包时要靠 pathex 让 PyInstaller 静态分析得到，否则打出来的 exe 一跑就
   ImportError。
4. 不要加 `--noconsole`（这里就是 console=True）：访问码、局域网地址、电视端
   该填的地址都打在控制台上，没有控制台等于把唯一的说明书撕了。
"""

from pathlib import Path

HERE = Path(SPECPATH)          # server/
ROOT = HERE.parent             # 工程根
TOOLS = ROOT / "tools"

# 明显用不上的大件，排除掉省体积。故意保守：只排跟本项目毫不相干的
# GUI/科学计算/测试框架，不碰 setuptools、pkg_resources 这类可能被间接依赖的
EXCLUDES = [
    "tkinter",
    "PyQt5", "PyQt6", "PySide2", "PySide6",
    "matplotlib", "numpy", "pandas", "scipy",
    "pytest", "IPython", "notebook",
    "PIL",
]

a = Analysis(
    [str(HERE / "signage_admin.py")],
    pathex=[str(HERE), str(TOOLS)],
    binaries=[],
    # web 目录在包里的名字必须正好是 "web"：代码里是 _BUNDLE_DIR / "web"
    datas=[(str(HERE / "web"), "web")],
    hiddenimports=["signage_core", "backend"],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=EXCLUDES,
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="signage-admin",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,               # 别开 UPX：杀软见一个就往隔离区扔一个
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,            # 见文件头第 4 条，必须是 True
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
