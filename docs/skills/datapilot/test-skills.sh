#!/usr/bin/env bash
# cloud-datapilot skill 深度语义验收脚本
# 对每个 skill 执行 7 项检查，输出 PASS/FAIL 矩阵
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SKILLS=()
while IFS= read -r skill_dir; do
  SKILLS+=("$(basename "$skill_dir")")
done < <(find "$ROOT" -maxdepth 1 -type d -name 'datapilot-*' | sort)

PASS=0
FAIL=0

# 单项检查：打印结果并累计计数
check() {
  local label="$1"
  local ok="$2"
  if [[ "$ok" == "1" ]]; then
    echo "    PASS  $label"
    PASS=$((PASS + 1))
  else
    echo "    FAIL  $label"
    FAIL=$((FAIL + 1))
  fi
}

echo "[suite metadata]"
sync_base_file="$ROOT/SYNC_BASE.json"
if [[ -f "$sync_base_file" ]]; then
  check "sync base file exists" 1
else
  check "sync base file exists" 0
fi

source_commit=""
source_commit_short=""
if [[ -f "$sync_base_file" ]]; then
  source_commit="$(sed -n 's/.*"sourceCommit": "\([0-9a-f]*\)".*/\1/p' "$sync_base_file")"
  source_commit_short="$(sed -n 's/.*"sourceCommitShort": "\([0-9a-f]*\)".*/\1/p' "$sync_base_file")"
fi
if [[ "$source_commit" =~ ^[0-9a-f]{40}$ ]] && git -C "$ROOT" cat-file -e "$source_commit^{commit}" 2>/dev/null; then
  check "sourceCommit resolves to a Git commit" 1
else
  check "sourceCommit resolves to a Git commit (got: $source_commit)" 0
fi

resolved_short="$(git -C "$ROOT" rev-parse --short=9 "$source_commit" 2>/dev/null || true)"
if [[ "$source_commit_short" == "$resolved_short" ]]; then
  check "sourceCommitShort matches sourceCommit" 1
else
  check "sourceCommitShort matches sourceCommit (got: $source_commit_short)" 0
fi

if git -C "$ROOT" merge-base --is-ancestor "$source_commit" HEAD 2>/dev/null; then
  check "sourceCommit is an ancestor of HEAD" 1
else
  check "sourceCommit is an ancestor of HEAD" 0
fi

if grep -q 'org.x9.cloud:cloud-datapilot:' "$ROOT/README.md"; then
  check "README contains current Maven coordinate" 1
else
  check "README contains current Maven coordinate" 0
fi

echo

for skill in "${SKILLS[@]}"; do
  echo "[$skill]"
  dir="$ROOT/$skill"
  file="$dir/SKILL.md"

  # 1. 目录 + SKILL.md 存在
  if [[ -d "$dir" && -f "$file" ]]; then
    check "1. directory + SKILL.md exists" 1
  else
    check "1. directory + SKILL.md exists" 0
    continue
  fi

  # 提取 frontmatter（首对 --- 之间）
  fm="$(awk '/^---$/{c++; next} c==1{print} c==2{exit}' "$file")"

  # 2. frontmatter name 与目录名一致
  fm_name="$(printf '%s\n' "$fm" | grep -E '^name:' | head -1 | sed -E 's/^name:[[:space:]]*//; s/[[:space:]]*$//')"
  if [[ "$fm_name" == "$skill" ]]; then
    check "2. frontmatter name == $skill" 1
  else
    check "2. frontmatter name == $skill (got: $fm_name)" 0
  fi

  # 3. description 非空
  fm_desc="$(printf '%s\n' "$fm" | grep -E '^description:' | head -1 | sed -E 's/^description:[[:space:]]*//')"
  if [[ -n "$fm_desc" ]]; then
    check "3. description non-empty" 1
  else
    check "3. description non-empty" 0
  fi

  # 4. 文件 >= 30 行
  lines="$(wc -l < "$file" | tr -d ' ')"
  if [[ "$lines" -ge 30 ]]; then
    check "4. line count >= 30 ($lines)" 1
  else
    check "4. line count >= 30 ($lines)" 0
  fi

  # 5. frontmatter version 非空
  fm_version="$(printf '%s\n' "$fm" | grep -E '^version:' | head -1 | sed -E 's/^version:[[:space:]]*//')"
  if [[ -n "$fm_version" ]]; then
    check "5. version non-empty ($fm_version)" 1
  else
    check "5. version non-empty" 0
  fi

  # 6. 至少一段代码块
  if grep -qE '^```[a-zA-Z0-9_-]*$' "$file"; then
    check "6. has code block" 1
  else
    check "6. has code block" 0
  fi

  # 7. 不得出现 GitNexus / 读源码 等违禁词
  if grep -qiE 'gitnexus|读源码|查看源码|read the source' "$file"; then
    check "7. no GitNexus/read-source references" 0
  else
    check "7. no GitNexus/read-source references" 1
  fi
done

echo
echo "Result: $PASS passed, $FAIL failed"
[[ "$FAIL" -eq 0 ]] || exit 1
