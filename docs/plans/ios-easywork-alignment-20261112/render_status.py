"""Validate the canonical plan ledger and render views with the repo's Prettier."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


def require(condition, message):
    if not condition:
        raise ValueError(message)


def cell(value):
    return str(value).replace("|", "\\|").replace("\n", " ")


def table(headers, rows):
    return "\n".join(
        ["| " + " | ".join(headers) + " |", "| " + " | ".join(["---"] * len(headers)) + " |"]
        + ["| " + " | ".join(cell(v) for v in row) + " |" for row in rows]
    )


def validate(data):
    require(data["schemaVersion"] == 2, "Unsupported ledger schema")
    features = data["features"]
    require(len(features) == 75, "Expected 75 existing F IDs; a scope change needs an explicit schema review")
    require({f["id"] for f in features} == {f"F{i:02}" for i in range(1, 76)}, "F IDs must be unique and complete")
    definitions = data["stateDefinitions"]
    registry = data["evidenceRegistry"]
    def acceptance_evidence(refs, owner):
        require(bool(refs), f"{owner}: pass requires current evidence")
        for key in refs:
            require(key in registry, f"{owner}: unknown evidence")
            evidence = registry[key]
            require(evidence.get("kind") in {"real-dev", "exact-match-review"}, f"{owner}: auxiliary evidence cannot close acceptance")
            for field in ("sourceCommit", "packageSha256", "environment", "scope", "path", "sha256"):
                require(bool(evidence.get(field)), f"{owner}: evidence missing {field}")
            require(evidence.get("mobileEnvironment") in {"simulator", "physical", "not-required"}, f"{owner}: missing actual mobile environment")
    packages = {p["id"] for p in data["workPackages"]}
    scenarios = {s["id"] for s in data["acceptanceScenarios"]}
    require(len(packages) == len(data["workPackages"]) == 16, "Work package IDs inconsistent")
    require(len(scenarios) == len(data["acceptanceScenarios"]) == 32, "Scenario IDs inconsistent")
    item_ids = {i["id"] for i in data["openItems"]}
    require(len(item_ids) == len(data["openItems"]), "Duplicate open item ID")
    known_f = {f["id"] for f in features}
    for feature in features:
        fid = feature["id"]
        require(feature["plannedScope"] in {"integration", "phone-only", "excluded"}, f"{fid}: invalid scope")
        require(set(feature["packages"]) <= packages, f"{fid}: invalid package")
        require(set(feature["openItemIds"]) <= item_ids, f"{fid}: invalid item")
        require(bool(feature["nextAction"].strip()), f"{fid}: missing next action")
        if feature["plannedScope"] == "integration":
            plan = feature["acceptancePlan"]
            require(bool(plan["scenarioIds"]) and set(plan["scenarioIds"]) <= scenarios, f"{fid}: invalid acceptance plan scenarios")
            require(plan["basis"] in registry, f"{fid}: unknown acceptance plan basis")
        for dimension in ("development", "acceptance", "delivery", "execution"):
            record = feature[dimension]
            require(record["state"] in definitions[dimension], f"{fid}: invalid {dimension}")
            require(set(record.get("evidenceRefs", [])) <= registry.keys(), f"{fid}: unknown evidence")
        require(set(feature["acceptance"]["currentEvidence"]) <= registry.keys(), f"{fid}: unknown current evidence")
        if feature["acceptance"]["state"] in {"current-passed", "matched-reuse"}:
            refs = feature["acceptance"]["currentEvidence"]
            acceptance_evidence(refs, fid)
            require(feature["development"]["state"] == "implemented-scoped", f"{fid}: unresolved development cannot close")
        if feature["execution"]["state"] == "closed":
            require(feature["acceptance"]["state"] in {"current-passed", "matched-reuse"}, f"{fid}: cannot close without acceptance")
    for item in data["openItems"]:
        require(set(item["featureIds"]) <= known_f, f"{item['id']}: invalid F IDs")
        require(item["developmentState"] in definitions["development"], f"{item['id']}: invalid development")
        require(item["executionState"] in definitions["execution"], f"{item['id']}: invalid execution")
        require(set(item["evidenceRefs"]) <= registry.keys(), f"{item['id']}: unknown evidence")
        for finding in item.get("reviewFindings", []):
            require(finding["evidenceRef"] in registry, f"{finding['id']}: unknown review evidence")
            require(set(finding.get("featureIds", item["featureIds"])) <= set(item["featureIds"]), f"{finding['id']}: invalid finding scope")
        for gap in item.get("remainingDevelopmentGaps", []):
            require(gap["developmentState"] in definitions["development"], f"{gap['id']}: invalid development state")
            require(gap["evidenceRef"] in registry, f"{gap['id']}: unknown development gap evidence")
            require(set(gap.get("featureIds", item["featureIds"])) <= set(item["featureIds"]), f"{gap['id']}: invalid gap scope")
        for feature in features:
            if feature["id"] not in item["featureIds"] or feature["execution"]["state"] != "closed":
                continue
            relevant_findings = [f for f in item.get("reviewFindings", []) if feature["id"] in f.get("featureIds", item["featureIds"])]
            require(all(f["state"] in {"resolved", "superseded-with-evidence"} for f in relevant_findings), f"{item['id']}: unresolved review findings prevent closure")
            relevant_gaps = [g for g in item.get("remainingDevelopmentGaps", []) if feature["id"] in g.get("featureIds", item["featureIds"])]
            require(all(g["developmentState"] in {"implemented-scoped", "pending-integration", "not-applicable"} for g in relevant_gaps), f"{item['id']}: undeveloped scope prevents closure")
        for fid in item["featureIds"]:
            require(item["id"] in next(f for f in features if f["id"] == fid)["openItemIds"], f"{item['id']}: missing reciprocal link")
    for package in data["workPackages"]:
        require(package["executionState"] in definitions["execution"], f"{package['id']}: invalid execution")
        require(package["features"] == "all" or set(package["features"]) <= known_f, f"{package['id']}: invalid F IDs")
    for scenario in data["acceptanceScenarios"]:
        require(scenario["state"] in definitions["scenario"], f"{scenario['id']}: invalid state")
        require(set(scenario["currentEvidence"]) <= registry.keys(), f"{scenario['id']}: unknown evidence")
        policy = scenario["environmentPolicy"]
        require(policy["mode"] in {"no-mobile", "simulator-sufficient", "split", "inherits"}, f"{scenario['id']}: invalid environment policy")
        require(bool(policy["simulatorScope"]), f"{scenario['id']}: missing environment scope")
        require(set(policy["hardwareEvidence"]) <= registry.keys(), f"{scenario['id']}: unknown hardware evidence")
        for assertion in scenario.get("assertionResults", []):
            require(assertion["state"] in definitions["scenario"], f"{scenario['id']}: invalid assertion state")
            require(set(assertion["currentEvidence"]) <= registry.keys(), f"{scenario['id']}: unknown assertion evidence")
            if assertion["state"] in {"passed", "matched-reuse"}:
                acceptance_evidence(assertion["currentEvidence"], scenario["id"] + "/" + assertion["id"])
        if scenario["state"] in {"passed", "matched-reuse"}:
            acceptance_evidence(scenario["currentEvidence"], scenario["id"])
            if policy["mode"] == "split" and policy["physicalAssertions"]:
                require(bool(policy["hardwareEvidence"]), f"{scenario['id']}: physical assertions need physical evidence or an explicit scope revision")
                acceptance_evidence(policy["hardwareEvidence"], scenario["id"])
                require(all(registry[key]["mobileEnvironment"] == "physical" for key in policy["hardwareEvidence"]), f"{scenario['id']}: simulator cannot close physical assertions")
    for batch in data.get("executionBatches", []):
        require(set(batch["evidenceRefs"]) <= registry.keys(), f"{batch['id']}: unknown evidence")
        for task in batch["tasks"]:
            require(set(task["packages"]) <= packages, f"{task['id']}: invalid package")
            if task.get("observedModel"):
                require(task["observedModel"] == batch["requestedModel"] and task["observedEffort"] == batch["requestedEffort"], f"{task['id']}: requested/observed model mismatch")
    for blocker in data.get("executionBlockers", []):
        require(set(blocker["evidenceRefs"]) <= registry.keys(), f"{blocker['id']}: unknown blocker evidence")
    require({s["id"] for s in data["acceptanceScenarios"]} == set(re.findall(r"^\|\s*(AC\d{2})\s*\|", (ROOT / "acceptance.md").read_text(), re.M)), "Scenario definitions and ledger differ")


def render(data):
    definitions = data["stateDefinitions"]
    features = data["features"]
    label = lambda dimension, state: definitions[dimension][state]
    fingerprint = hashlib.sha256((ROOT / "status.json").read_bytes()).hexdigest()
    banner = f"> 自动生成，勿手改；唯一状态来源：[status.json](status.json)，修订 {data['revision']}，更新 {data['updatedAt']}。台账 SHA-256：`{fingerprint}`。\n"
    counts = []
    for dimension in ("development", "acceptance", "delivery", "execution"):
        counter = Counter(f[dimension]["state"] for f in features)
        counts.append((dimension, [(label(dimension, key), count) for key, count in counter.items()]))
    active = [f for f in features if f["plannedScope"] == "integration"]
    matched = sum(f["acceptance"]["state"] == "historical-scope-awaiting-match" for f in active)
    unmatched = sum(f["acceptance"]["state"] == "historical-scope-unmatched" for f in active)
    closed = sum(f["acceptance"]["state"] in {"current-passed", "matched-reuse"} for f in active)
    open_count = len(active) - matched - unmatched - closed
    progress = ["# 当前唯一进度总表", "", banner,
        "本表是台账的只读投影，不是另一套进度记录。状态定义和更新流程见 [维护规则](maintenance.md)。", "",
        f"当前共 {len(features)} 个父功能：{len(active)} 个对接、{sum(f['plannedScope']=='phone-only' for f in features)} 个手机共用、{sum(f['plannedScope']=='excluded' for f in features)} 个范围外。对接范围有 {open_count} 个开放父项、{matched + unmatched} 个历史限定通过未复用（待匹配 {matched}，本次匹配未成立 {unmatched}）、{closed} 个按当前范围关闭。开放数不等于未开发数。", ""]
    if data.get("currentSourcePins"):
        progress += ["## 当前源码与运行边界", "",
            "commit 是已有提交；tree 是隔离候选的内容快照，不表示已合主线或已部署。逐功能对齐和验收范围仍按下方事项及功能清单判断。", "",
            table(["组件", "可核基准提交", "当前候选 tree", "检查与运行状态"], [
                (pin["component"], pin["baseCommit"], pin.get("candidateTree", "未取得"), pin["verification"])
                for pin in data["currentSourcePins"]]), ""]
    title = {"development": "开发状态（75项）", "acceptance": "验收状态（75项）", "delivery": "集成/部署状态（75项）", "execution": "执行状态（75项）"}
    for dimension, rows in counts:
        progress += ["## " + title[dimension], "", table(["状态", "项数"], rows), ""]
    progress += ["## 验收环境规则", "", data["acceptancePolicy"]["rule"], "",
        table(["场景", "环境口径", "模拟器/非手机范围", "实体子断言"], [
            (s["id"], {"no-mobile": "无需手机", "simulator-sufficient": "模拟器足够", "split": "拆子项", "inherits": "继承受影响范围"}[s["environmentPolicy"]["mode"]], s["environmentPolicy"]["simulatorScope"], "；".join(s["environmentPolicy"]["physicalAssertions"]) or "无独立实体门槛") for s in data["acceptanceScenarios"]]), ""]
    for batch in data.get("executionBatches", []):
        progress += ["## Host 执行批次 " + batch["id"], "", "阶段：" + batch["phase"] + "；状态：" + batch["state"] + "。协调任务与产品验收分别记录，任务启动不计作功能通过。", "",
            f"协调模型轮次 {batch['coordinationModelTurns']}；产品验收业务模型轮次 {batch['productAcceptanceModelTurns']}。", "",
            table(["任务", "职责/包", "状态", "观察到的模型/强度", "下一步"], [
                (t["id"], t["role"] + " / " + "/".join(t["packages"]), t["state"], t.get("observedModel", "未核实") + " / " + t.get("observedEffort", "未核实"), t["nextAction"]) for t in batch["tasks"]]), "",
            "清理状态：" + batch["cleanup"]["state"] + "。" + batch["cleanup"]["retentionRule"], ""]
    progress += ["## 当前执行阻塞", "", table(["编号", "范围", "原因", "继续条件"], [
        (b["id"], b["scope"], b["reason"], b["nextAction"]) for b in data.get("executionBlockers", []) if b["state"] == "blocked"]), ""]
    auxiliary = data.get("auxiliaryValidation")
    if auxiliary:
        progress += ["## 辅助验证", "", f"原生/本地夹具辅助检查通过 {auxiliary['checksPassed']} 项，编译探针通过 {auxiliary['compilerProbesPassed']} 项；新增产品验收通过 {auxiliary['newProductAcceptancePasses']} 项。准确范围和清理回执见台账 evidenceRegistry。", ""]
        if "closeoutNodeTests" in auxiliary:
            node = auxiliary["closeoutNodeTests"]
            progress += [f"Host收尾任务另有 {node['passed']} 项 Node 辅助测试通过，失败 {node['failed']}、跳过 {node['skipped']}；与原生检查是不同分母，不累加为功能完成数。", ""]
        if auxiliary.get("candidateBuilds"):
            progress += [table(["候选 / 范围", "iOS构建", "文件预览辅助检查", "证据"], [
                (b["candidate"], b["iosBuild"], b["filePreviewCheck"], "/".join(b["evidenceRefs"])) for b in auxiliary["candidateBuilds"]]), "",
                "构建与辅助检查不代表模拟器运行或产品场景已通过；最终候选修改后按受影响源重新匹配。", ""]
        if auxiliary.get("nativeSubassertions"):
            progress += [table(["候选", "实际执行层级", "通过 / 失败", "已覆盖范围与边界", "证据"], [
                (b["candidate"], b["level"], f"{b['passed']} / {b['failed']}", b["scope"], "/".join(b["evidenceRefs"])) for b in auxiliary["nativeSubassertions"]]), "",
                "这些已运行的原生子断言保留，真实EasyWork对端及完整入口的剩余断言另验；不要求改用真机重复非硬件测试。", ""]
    progress += ["## 当前明确事项", "",
        "事项是父功能的细项，不增加75项分母。未开发只指明确标出的子能力；桌面尚未核实的实现不能直接判为不存在。", "",
        table(["ID / 事项", "开发", "执行", "手机与桌面边界", "下一步"], [
            (i["id"] + " / " + i["name"], label("development", i["developmentState"]), label("execution", i["executionState"]), i["ios"] + " " + i["desktop"], i["nextAction"]) for i in data["openItems"]]), "",
        "## 工作包", "", table(["包", "功能范围", "执行状态", "下一步"], [
            (p["id"] + " " + p["name"], "全部" if p["features"] == "all" else "/".join(p["features"]), label("execution", p["executionState"]), p["nextAction"]) for p in data["workPackages"]]), "",
        "## 当前验收场景状态", "", table(["状态", "场景组数"], [
            (definitions["scenario"][key], value) for key, value in Counter(s["state"] for s in data["acceptanceScenarios"]).items()]), "",
        "每组的具体动作/断言见 [验收计划](acceptance.md)。已保存历史结果继续由逐F条目引用，未新增执行不等于过去从未测试。", "",
        "## 最近更新", "", table(["修订", "日期", "变化"], [(x["revision"], x["date"], x["scope"]) for x in data["changeLog"][-8:]]), ""]
    findings = [(i["id"], f) for i in data["openItems"] for f in i.get("reviewFindings", [])]
    if findings:
        reviewed = sum(f["state"] == "resolved" for _, f in findings)
        adjusted = sum(f["state"] == "superseded-with-evidence" for _, f in findings)
        progress += ["## 候选代码审查问题", "", f"本轮具体代码问题 {len(findings)} 项：修复已复核 {reviewed} 项，待修或待复核 {len(findings) - reviewed - adjusted} 项，有证据调整范围 {adjusted} 项。这是代码问题分母，不是F功能验收通过数。", "",
            table(["编号 / 事项", "问题", "处理状态"], [
                (f["id"] + " / " + item_id, f["description"], {"assigned-for-fix": "已分派修复", "fixed-awaiting-review": "已修复待复核", "resolved": "代码修复已复核；正式验收另计", "blocked": "存在明确开发阻塞", "superseded-with-evidence": "有证据调整范围"}.get(f["state"], f["state"])) for item_id, f in findings]), ""]
    gaps = [gap for item in data["openItems"] for gap in item.get("remainingDevelopmentGaps", [])]
    if gaps:
        progress += ["## 仍有开发缺口的具体子项", "", table(["子项", "开发状态", "准确范围"], [
            (g["id"] + " " + g["name"], label("development", g["developmentState"]), g["scope"]) for g in gaps]), ""]
    checklist = ["# 75项功能状态清单", "", banner,
        "开发、验收、交付和执行为独立维度；不能互相替代。证据范围、原状态和来源提交完整保存在台账。", "",
        table(["ID / 功能", "开发状态", "验收状态", "交付状态", "执行状态", "包 / 事项", "下一步"], [
            (f["id"] + " " + f["name"], label("development", f["development"]["state"]), label("acceptance", f["acceptance"]["state"]), label("delivery", f["delivery"]["state"]), label("execution", f["execution"]["state"]), "/".join(f["packages"] + f["openItemIds"]) or "共用/范围外", f["nextAction"]) for f in features]), "",
        "## 开发判断依据与证据索引", "",
        table(["ID", "开发判断依据", "证据"], [(f["id"], f["development"]["basis"], "/".join(dict.fromkeys(f["development"]["evidenceRefs"] + f["acceptance"]["evidenceRefs"]))) for f in features]), "",
        "证据ID在 status.json 的 evidenceRegistry 中解析为准确提交、路径和范围。历史日志不会被状态投影覆盖。", ""]
    checklist += ["## 52项对接功能的验收执行动作", "",
        "按优先序和前置依赖执行；已实现先验，明确缺口先补再验。模拟器适用规则不降低真实桌面/模型/原资源要求，实体子项单列。下表仍从唯一台账生成。", "",
        table(["F / 顺序", "AC", "开发门槛", "模拟器可执行断言", "实体子项"], [
            (f["id"] + " / " + f["acceptancePlan"]["priority"] + " / " + str(f["acceptancePlan"]["sequence"]), "/".join(f["acceptancePlan"]["scenarioIds"]), f["acceptancePlan"]["developmentGate"], f["acceptancePlan"]["nativeSimulatorAssertions"], "；".join(x["scenario"] + "：" + x["assertion"] for x in f["acceptancePlan"]["physicalAssertions"]) or "无独立实体要求") for f in sorted(active, key=lambda f: (f["acceptancePlan"]["sequence"], f["id"]))]), ""]
    return {"progress.md": "\n".join(progress), "checklist.md": "\n".join(checklist)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--write", action="store_true", help="Regenerate Markdown from status.json")
    action.add_argument("--check", action="store_true", help="Validate ledger and reject stale Markdown")
    args = parser.parse_args()
    data = json.loads((ROOT / "status.json").read_text())
    validate(data)
    for name, content in render(data).items():
        path = ROOT / name
        formatted = subprocess.run(
            ["pnpm", "exec", "prettier", "--stdin-filepath", str(path)],
            input=content, text=True, capture_output=True, cwd=ROOT.parents[2],
            timeout=60, check=False,
        )
        require(formatted.returncode == 0, "Prettier failed: " + formatted.stderr[-1000:])
        content = formatted.stdout
        if args.write:
            path.write_text(content)
        else:
            require(path.exists() and path.read_text() == content, f"Stale view: {name}; run --write")
    print("OK: canonical ledger validated; 75 features, 16 work packages, 32 scenarios; views " + ("written" if args.write else "match"))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError) as error:
        print(f"Plan validation failed: {error}", file=sys.stderr)
        sys.exit(1)
