"""核对不可变的原始来源清单，以及逐文件登记的修改、迁移和裁剪。"""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent
manifest = json.loads((root / "upstream-manifest.json").read_text())
changes = json.loads((root / "upstream-changes.json").read_text())
assert changes["upstreamCommit"] == manifest["commit"], "裁剪记录的上游版本不匹配"
registered = []
for kind in ("retired", "relocated", "modified"):
    registered.extend(changes[kind])
assert len(registered) == len(set(registered)), "变更记录存在重叠"
assert set(registered) <= manifest["files"].keys(), "变更记录包含未知来源文件"
errors = []
for name, digest in manifest["files"].items():
    path = root / name
    if name in changes["retired"] or name in changes["relocated"]:
        if path.exists():
            errors.append(f"已退出的源路径重新出现：{name}")
        if name in changes["retired"]:
            continue
        relocation = changes["relocated"][name]
        path = root / relocation["target"]
        digest = relocation["sha256"]
    elif name in changes["modified"]:
        digest = changes["modified"][name]["sha256"]
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        errors.append(f"文件缺失或哈希不匹配：{path.relative_to(root)}")
if errors:
    raise SystemExit("来源核对失败：\n" + "\n".join(errors))
print(f"来源核对通过：原始 {len(manifest['files'])} 个文件；"
      f"删除 {len(changes['retired'])}，迁移 {len(changes['relocated'])}，"
      f"原路径适配 {len(changes['modified'])}；其它文件与上游一致。")
