#!/usr/bin/env python3
"""Validate the classified four-section user-journey UC Markdown format."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path


REQUIRED_SECTIONS = ("验证标识", "验证目标", "验证场景", "验证方式")
REMOVED_SECTIONS = (
    "规范来源",
    "架构不变量与安全边界",
    "前置条件",
    "通过规范",
    "需求与规则追踪",
    "待确认事项",
    "变更记录",
    "项目能力缺口",
    "反例",
)
REQUIRED_SCENARIO_FIELDS = (
    "参与用户",
    "开始状态",
    "用户操作",
    "预期结果",
    "场景结束",
)
UC_TYPES = ("流程编排", "流程运行", "任务类型")
IMPLEMENTATION_TERMS = (
    "src/main/",
    "src/test/",
    "FlowWithSource",
    "Execution",
    "TaskRun",
    "ExternalTask",
    "PAUSE",
    "Service",
    "Controller",
    "Command",
    "Repository",
    "Handler",
    "ApplicationContext",
    "DSLContext",
    "JOOQ",
    "PostgreSQL",
    "Mock",
    "companyId",
    "lockVersion",
    "reversion",
    "数据库",
    "server",
    "测试类",
    "类路径",
    "技术架构",
    "架构决策",
    "架构不变量",
    "领域模型",
    "ADR",
)
SCENARIO_PATTERN = re.compile(r"^### (S\d+)\s+(.+)$", re.MULTILINE)
VERIFICATION_PATTERN = re.compile(r"^### (S\d+)\s*$", re.MULTILINE)


@dataclass
class ValidationResult:
    path: Path
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors


def section_body(text: str, heading: str) -> str | None:
    match = re.search(
        rf"^## {re.escape(heading)}\s*$\n(.*?)(?=^## |\Z)",
        text,
        re.MULTILINE | re.DOTALL,
    )
    return match.group(1).strip() if match else None


def numbered_blocks(body: str, pattern: re.Pattern[str]) -> dict[str, str]:
    matches = list(pattern.finditer(body))
    blocks: dict[str, str] = {}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(body)
        blocks[match.group(1)] = body[match.end():end].strip()
    return blocks


def validate(path: Path, strict: bool, require_classification: bool) -> ValidationResult:
    result = ValidationResult(path)
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        result.errors.append(f"无法读取文件：{exc}")
        return result

    if not re.search(r"^# UC-\d+\s+.+$", text, re.MULTILINE):
        result.errors.append("标题必须使用“# UC-<编号> <业务场景主题>”")
    title_id = re.search(r"^# (UC-\d+)\s+.+$", text, re.MULTILINE)

    headings = re.findall(r"^## (.+?)\s*$", text, re.MULTILINE)
    for section in REQUIRED_SECTIONS:
        if section not in headings:
            result.errors.append(f"缺少章节：## {section}")
    for section in REMOVED_SECTIONS:
        if section in headings:
            result.errors.append(f"UC 不应保留章节：## {section}")
    if strict:
        extras = [heading for heading in headings if heading not in REQUIRED_SECTIONS]
        if extras:
            result.errors.append("严格模式只允许四个二级章节，发现：" + "、".join(extras))
        if headings != list(REQUIRED_SECTIONS):
            result.errors.append("四个二级章节必须按验证标识、验证目标、验证场景、验证方式排序")

    identification = section_body(text, "验证标识") or ""
    for label in ("UC", "状态", "领域", "需求基线", "依赖"):
        if not re.search(rf"^- {re.escape(label)}：.+$", identification, re.MULTILINE):
            result.errors.append(f"验证标识缺少：{label}")
    identity_id = re.search(r"^- UC：`?(UC-\d+)`?\s*$", identification, re.MULTILINE)
    if title_id and identity_id and title_id.group(1) != identity_id.group(1):
        result.errors.append("标题 UC 编号与验证标识不一致")
    uc_type_match = re.search(
        r"^- UC 类型：`?([^`\n]+)`?\s*$",
        identification,
        re.MULTILINE,
    )
    uc_type = uc_type_match.group(1).strip() if uc_type_match else None
    if require_classification and uc_type is None:
        result.errors.append("验证标识缺少：UC 类型")
    if uc_type is not None and uc_type not in UC_TYPES:
        result.errors.append("UC 类型只能是：流程编排、流程运行、任务类型")
    target_task_type = re.search(
        r"^- 目标任务类型：`?([^`\n]+)`?\s*$",
        identification,
        re.MULTILINE,
    )
    if uc_type == "任务类型" and target_task_type is None:
        result.errors.append("任务类型 UC 的验证标识缺少：目标任务类型")
    if uc_type in ("流程编排", "流程运行") and target_task_type is not None:
        result.errors.append("非任务类型 UC 不应填写目标任务类型")

    scenario_body = section_body(text, "验证场景") or ""
    scenario_matches = list(SCENARIO_PATTERN.finditer(scenario_body))
    scenarios = numbered_blocks(scenario_body, SCENARIO_PATTERN)
    if not scenarios:
        result.errors.append("验证场景中没有 S* 场景")

    verification_body = section_body(text, "验证方式") or ""
    verification_matches = list(VERIFICATION_PATTERN.finditer(verification_body))
    verifications = numbered_blocks(verification_body, VERIFICATION_PATTERN)

    if len(scenario_matches) != len(scenarios):
        result.errors.append("验证场景存在重复编号")
    if len(verification_matches) != len(verifications):
        result.errors.append("验证方式存在重复编号")
    scenario_h3 = re.findall(r"^### .+$", scenario_body, re.MULTILINE)
    verification_h3 = re.findall(r"^### .+$", verification_body, re.MULTILINE)
    if len(scenario_h3) != len(scenario_matches):
        result.errors.append("验证场景包含非 S* 的三级章节")
    if len(verification_h3) != len(verification_matches):
        result.errors.append("验证方式包含非 S* 的三级章节")

    for scenario_id, block in scenarios.items():
        for label in REQUIRED_SCENARIO_FIELDS:
            if not re.search(rf"^- {re.escape(label)}：.+$", block, re.MULTILINE):
                result.errors.append(f"{scenario_id} 缺少字段：{label}")
        if uc_type == "任务类型" and not re.search(
            r"^- 验证流程：.+$",
            block,
            re.MULTILINE,
        ):
            result.errors.append(f"{scenario_id} 任务类型场景缺少字段：验证流程")
        verification = verifications.get(scenario_id)
        if verification is None:
            result.errors.append(f"{scenario_id} 缺少独立验证方式")
            continue
        if strict and len(re.findall(r"^\d+\.\s+.+$", verification, re.MULTILINE)) < 3:
            result.errors.append(f"{scenario_id} 验证方式至少需要三个用户操作步骤")
        if strict and "查询" not in block + "\n" + verification:
            result.errors.append(f"{scenario_id} 缺少用户查询或重新查询步骤")
        steps = re.findall(r"^\d+\.\s+(.+)$", verification, re.MULTILINE)
        if strict and steps and not re.search(r"查询|确认", steps[-1]):
            result.errors.append(f"{scenario_id} 最后一步必须查询或确认场景结束状态")
        ending = re.search(r"^- 场景结束：(.+)$", block, re.MULTILINE)
        if ending and re.search(r"运行中|等待中|RUNNING|WAITING", ending.group(1)):
            result.errors.append(f"{scenario_id} 场景结束不能停在运行或等待状态")
        if strict and uc_type == "任务类型":
            task_journey = block + "\n" + verification
            for label, pattern in (
                ("定义流程", r"定义|创建"),
                ("发布流程", r"发布"),
                ("启动流程", r"启动"),
                ("观察目标任务", r"任务"),
            ):
                if not re.search(pattern, task_journey):
                    result.errors.append(
                        f"{scenario_id} 任务类型场景缺少完整路径：{label}"
                    )

        is_pause = any(marker in block for marker in ("PAUSE", "暂停任务", "外派任务", "待办任务"))
        if is_pause:
            if "查询" not in verification or "完成" not in verification:
                result.errors.append(f"{scenario_id} 暂停场景缺少查询并完成外派任务步骤")
            ending_text = ending.group(1) if ending else ""
            if not re.search(r"完成|取消|失败|未创建", ending_text):
                result.errors.append(f"{scenario_id} 暂停场景缺少最终业务终态")

    for scenario_id in set(verifications) - set(scenarios):
        result.errors.append(f"验证方式引用了不存在的场景：{scenario_id}")

    implementation_scan_text = re.sub(
        r"^- 目标任务类型：.*$",
        "",
        text,
        flags=re.MULTILINE,
    )
    for term in IMPLEMENTATION_TERMS:
        if term in implementation_scan_text:
            result.errors.append(f"UC 包含技术实现细节：{term}")

    for marker in (
        "PASS-",
        "来源：",
        "保护目标：",
        "INV-",
        "REQ-",
        "ARCH-",
        "FWS-",
    ):
        if marker in text:
            result.errors.append(f"UC 包含已移除的规范标记：{marker}")

    if strict and "```" in text:
        result.errors.append("UC 不应包含代码块")

    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--strict", action="store_true", help="只接受四章节并检查用户闭环")
    parser.add_argument(
        "--require-classification",
        action="store_true",
        help="要求验证标识声明 UC 类型，并校验任务类型专属字段",
    )
    parser.add_argument("files", nargs="+", type=Path)
    args = parser.parse_args()

    results = [
        validate(path, args.strict, args.require_classification)
        for path in args.files
    ]
    for result in results:
        print(f"{'PASS' if result.ok else 'FAIL'} {result.path}")
        for error in result.errors:
            print(f"  ERROR: {error}")
        for warning in result.warnings:
            print(f"  WARN: {warning}")

    return 0 if all(result.ok for result in results) else 1


if __name__ == "__main__":
    sys.exit(main())
