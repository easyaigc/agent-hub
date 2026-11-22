"""Validate the preserved historical ledger and render source views with the repo's Prettier."""

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


def scenario_assertions(scenarios):
    return {(scenario["id"], assertion["id"]): assertion
            for scenario in scenarios for assertion in scenario.get("assertionResults", [])}


def validate_execution_focus(focus, features, item_ids, scenarios):
    integration = {f["id"]: f for f in features if f["plannedScope"] == "integration"}
    open_ids = {fid for fid, feature in integration.items() if feature["execution"]["state"] != "closed"}
    phases = focus["phases"]
    require([p["id"] for p in phases] == ["core", "extensions", "hardening", "hold"], "Invalid execution focus order")
    allocated = []
    for phase in phases:
        for group in phase["groups"]:
            require(bool(group["title"].strip()) and bool(group["items"]), f"{phase['id']}: missing focus group")
            require(set(group["reuse"]) <= integration.keys(), f"{group['title']}: unknown evidence reuse feature")
            for fid, action in group["items"].items():
                require(fid in integration and bool(action.strip()), f"{fid}: invalid focus assignment")
                if fid in open_ids:
                    require((integration[fid]["execution"]["state"] == "paused") == (phase["id"] == "hold"),
                            f"{fid}: paused feature must be assigned separately")
                allocated.append(fid)
    require(len(allocated) == len(set(allocated)), "A feature belongs to more than one execution phase")
    require(open_ids <= set(allocated), "Execution focus must assign all open integration features")
    require(all(set(d["items"]) <= item_ids for d in focus["desktop"]), "Unknown desktop focus item")
    assertions = scenario_assertions(scenarios)
    for result in focus.get("latestCoreRun", []):
        require((result["scenario"], result["assertion"]) in assertions,
                f"{result['title']}: unknown core run assertion")


def validate_functional_deliverables(data):
    workflow = data.get("deliveryWorkflow")
    if not workflow:
        return
    features = {feature["id"] for feature in data["features"] if feature["plannedScope"] == "integration"}
    items = data["functionalDeliverables"]
    require(len({item["id"] for item in items}) == len(items), "Duplicate concrete function ID")
    require({fid for item in items for fid in item["featureIds"]} == features,
            "Concrete functions must cover the complete integration scope")
    merged = data["mergedPullRequests"]
    for item in items:
        require(item["state"] in workflow["stateDefinitions"], f"{item['id']}: invalid delivery state")
        require(item["phase"] in {"core", "extensions", "hardening", "hold", "completed"},
                f"{item['id']}: invalid phase")
        require(bool(item["behavior"].strip()) and bool(item["acceptanceCriteria"].strip())
                and bool(item["nextAction"].strip()), f"{item['id']}: missing concrete behavior")
        require(set(item["evidenceRefs"]) <= data["evidenceRegistry"].keys(),
                f"{item['id']}: unknown original evidence")
        require(set(item["pullRequests"]) <= merged.keys(), f"{item['id']}: unknown pull request")
        if item["state"] == "merged":
            require(item["acceptanceBasis"] == "merged-pr" and bool(item["pullRequests"]),
                    f"{item['id']}: merged function requires an actual merged pull request")
            for ref in item["pullRequests"]:
                pr = merged[ref]
                require(pr["state"] == "merged" and bool(pr["mergedAt"])
                        and re.fullmatch(r"[a-f0-9]{40}", pr["mergeCommit"])
                        and bool(pr["checkedMainCommit"]), f"{ref}: incomplete merge record")


def render_functional_deliverables(data):
    if "deliveryWorkflow" not in data:
        return []
    workflow = data["deliveryWorkflow"]
    merged = data["mergedPullRequests"]
    lines = ["## 具体功能开发与交付", "", workflow["rule"], "", workflow["acceptanceRule"], ""]
    for phase, title in [("core", "第一阶段具体功能"), ("extensions", "第二阶段具体功能"),
                         ("hardening", "后续工程断言"), ("hold", "暂停范围"),
                         ("completed", "已关闭范围的交付跟进")]:
        rows = []
        for item in data["functionalDeliverables"]:
            if item["phase"] != phase:
                continue
            prs = "、".join(f"[{merged[ref]['repository'].split('/')[-1]} #{merged[ref]['number']}]({merged[ref]['url']})"
                           for ref in item["pullRequests"])
            basis = prs or "、".join(item["evidenceRefs"]) or "待执行"
            rows.append((item["id"], item["behavior"], workflow["stateDefinitions"][item["state"]],
                         item["acceptanceCriteria"], basis))
        if rows:
            lines += ["### " + title, "", table(["唯一功能 ID", "用户操作", "当前状态", "完成条件与下一步", "原交付依据"], rows), ""]
    return lines


def render_execution_focus(data, banner):
    focus = data["executionFocus"]
    definition = data["stateDefinitions"]
    features = {f["id"]: f for f in data["features"] if f["plannedScope"] == "integration"}
    open_ids = {fid for fid, feature in features.items() if feature["execution"]["state"] != "closed"}
    development = Counter(f["development"]["state"] for fid, f in features.items() if fid in open_ids)
    breakdown = "、".join(f"{definition['development'][state]} {count} 项" for state, count in development.most_common())

    def open_count(phase):
        return sum(fid in open_ids for group in phase["groups"] for fid in group["items"])

    lines = [
        "# 核心使用验收与剩余对接盘点", "", banner, focus["summary"], "",
        table(["执行顺序", "未闭合父项"], [(p["title"], open_count(p)) for p in focus["phases"]]), "",
        f"对接 {len(features)} 项，已按登记范围关闭 {len(features) - len(open_ids)} 项，尚有 {len(open_ids)} 项。",
        "开放项的开发状态包括" + breakdown + "。交付与完整证据见 [75 项逐项清单](checklist.md)。", "",
        "## 第一阶段如何开始", "", focus["firstRun"], "", focus["exitRule"], "",
        "手机共用的最近列表、正文、键盘、滚动与回最新、草稿和文件选择随普通流程一并观察。影响核心操作的问题优先修复，结果按原共用范围登记。", "",
    ]
    lines += render_functional_deliverables(data)
    if focus.get("latestCoreRun"):
        assertions = scenario_assertions(data["acceptanceScenarios"])
        rows = []
        for result in focus["latestCoreRun"]:
            assertion = assertions[result["scenario"], result["assertion"]]
            rows.append((result["title"], definition["scenario"][assertion["state"]],
                         assertion["scope"], result["scenario"]))
        lines += ["## 最近一次核心操作验收", "", table(["用户操作", "结果", "已核对范围", "原场景"], rows), ""]
    lines += ["## 延后执行的断言", "", focus["deferred"], ""]
    for phase in focus["phases"]:
        lines += ["## " + phase["title"], ""]
        for group in phase["groups"]:
            lines += ["### " + group["title"], ""]
            if group["flow"]:
                lines += [group["flow"], ""]
            reuse = [
                f"{fid} {features[fid]['name']}" for fid in group["reuse"]
                if features[fid]["execution"]["state"] == "closed"
            ]
            if reuse:
                lines += ["已有登记范围通过可供核对复用：" + "、".join(reuse) + "。", ""]
            rows = [
                (fid + " " + features[fid]["name"],
                 definition["development"][features[fid]["development"]["state"]],
                 definition["acceptance"][features[fid]["acceptance"]["state"]],
                 "/".join(features[fid]["acceptancePlan"]["scenarioIds"]), action)
                for fid, action in group["items"].items() if fid in open_ids
            ]
            if rows:
                lines += [table(["功能", "开发", "验收", "原场景", "后续验收动作"], rows), ""]
            else:
                lines += ["本组已无开放父项。", ""]
    lines += ["## EasyWork 桌面端如何补齐", "",
        table(["范围", "原事项", "已知情况与下一步"],
              [(d["scope"], "/".join(d["items"]), d["note"]) for d in focus["desktop"]]), "",
        "用户可见的第一阶段断言通过后，剩余正式验收和交付条件仍由原 F、AC 和事项记录维护。", ""]
    return "\n".join(lines)



def validate_incremental_alignment(data):
    refresh = data.get("alignmentRefresh")
    if not refresh:
        return  # Older ledger snapshots remain readable.
    require(refresh["schemaVersion"] == 1, "Unknown incremental audit schema")
    baseline = {f["id"] for f in data["features"]}
    extensions = data["extensionFeatures"]
    extension_ids = {f["id"] for f in extensions}
    require(len(extension_ids) == len(extensions) and not baseline & extension_ids,
            "Extension IDs must be unique and separate from the baseline")
    review = refresh["scopeReview"]
    require(review["baselineCount"] == len(baseline) == 75,
            "Incremental audit must preserve the original denominator")
    require(set(review["additionalIds"]) == extension_ids
            and review["extensionCount"] == len(extension_ids), "Unreviewed extension scope")
    registry = data["evidenceRegistry"]
    for feature in extensions:
        for dimension in ("development", "acceptance", "delivery", "execution"):
            record = feature[dimension]
            require(record["state"] in data["stateDefinitions"][dimension],
                    f"{feature['id']}: invalid extension {dimension}")
            require(set(record.get("evidenceRefs", [])) <= registry.keys(),
                    f"{feature['id']}: unknown extension evidence")
        passed = feature["acceptance"]["state"] in {"current-passed", "matched-reuse"}
        refs = feature["acceptance"]["currentEvidence"]
        require(set(refs) <= registry.keys(), "Unknown current extension evidence")
        if passed:
            require(bool(refs) and feature["development"]["state"] == "implemented-scoped",
                    "Extension pass requires implementation and evidence")
            for ref in refs:
                evidence = registry[ref]
                require(evidence["kind"] in {"real-dev", "exact-match-review"},
                        "Source audit cannot close extension acceptance")
                require(all(evidence.get(key) for key in (
                    "sourceCommit", "packageSha256", "environment", "scope", "path", "sha256")),
                    "Incomplete extension acceptance proof")
                allowed = {"simulator", "not-required"} if data["acceptancePolicy"].get("mobileEnvironment") == "simulator" else {"simulator", "physical", "not-required"}
                require(evidence.get("mobileEnvironment") in allowed,
                        "Extension acceptance environment mismatch")
        if feature["execution"]["state"] == "closed":
            require(passed, "Extension cannot close without acceptance")
        require(bool(feature["nextAction"].strip()), "Missing extension next action")
    deliverables = data["functionalDeliverables"] + data["extensionDeliverables"]
    deliverable_ids = {d["id"] for d in deliverables}
    require(len(deliverable_ids) == len(deliverables), "Duplicate extension deliverable")
    require({fid for d in data["extensionDeliverables"] for fid in d["featureIds"]} == extension_ids,
            "Extension deliverables must cover exactly the extension scope")
    for item in data["extensionDeliverables"]:
        require(set(item["evidenceRefs"]) <= registry.keys(), "Unknown extension deliverable evidence")
        for dimension in ("development", "acceptance", "delivery", "execution"):
            require(item[dimension + "State"] in data["stateDefinitions"][dimension],
                    "Invalid extension deliverable state")
        require(all(item.get(key) for key in ("behavior", "acceptanceCriteria", "nextAction", "owner")),
                "Incomplete concrete extension deliverable")
        if item["acceptanceState"] in {"current-passed", "matched-reuse"}:
            require(all(next(f for f in extensions if f["id"] == fid)["acceptance"]["state"]
                        in {"current-passed", "matched-reuse"} for fid in item["featureIds"]),
                    "Extension deliverable pass requires evidenced parent acceptance")
        require(item["executionState"] != "closed" or all(
            next(f for f in extensions if f["id"] == fid)["execution"]["state"] == "closed"
            for fid in item["featureIds"]), "Closed extension deliverable requires closed parent")
    plans = refresh["plans"]
    plan_ids = {p["id"] for p in plans}
    require(len(plan_ids) == len(plans), "Duplicate incremental plan ID")
    for plan in plans:
        require(set(plan["featureIds"]) <= baseline | extension_ids
                and set(plan["deliverableIds"]) <= deliverable_ids,
                "Unknown incremental plan function")
        require(set(plan["dependsOn"]) <= plan_ids and plan["id"] not in plan["dependsOn"],
                "Invalid incremental dependency")
        require(all(plan.get(key) for key in ("implementation", "acceptance", "completion")),
                "Incomplete incremental plan")
    require(refresh["counts"]["actualDevelopmentGaps"] == sum(
        plan["kind"] == "development-gap" for plan in plans), "Development gap count mismatch")
    require(refresh["counts"]["additionalReconciliationAndReleaseTasks"] == sum(
        plan["kind"] != "development-gap" for plan in plans), "Reconciliation count mismatch")
    groups = refresh["featureGroups"]
    group_ids = {g["id"] for g in groups}
    require(len(group_ids) == len(groups) == refresh["counts"]["deduplicatedThemes"],
            "Incremental theme count mismatch")
    for group in groups:
        require(set(group["functionalDeliverableIds"]) <= deliverable_ids,
                "Unknown theme deliverable")
    for repository, key in (("hub", "hubCommits"), ("desktop", "desktopCommits")):
        commits = refresh[key]
        shas = {c["sha"] for c in commits}
        require(len(shas) == len(commits) == refresh["sourceFreeze"][repository]["commitCount"],
                "Commit audit coverage mismatch")
        require(all(re.fullmatch(r"[a-f0-9]{40}", sha) for sha in shas), "Invalid commit SHA")
        require(refresh["sourceFreeze"][repository]["commit"] in shas,
                "Commit audit must include frozen tip")
        for commit in commits:
            require(all(commit.get(k) is not None for k in ("subject", "committedAt", "changedPaths", "basis")),
                    "Incomplete commit audit record")
            if repository == "hub":
                require(set(commit["groupIds"]) <= group_ids, "Unknown commit theme")
        if repository == "hub":
            require(all(set(g["commitShas"]) <= shas for g in groups), "Theme commit outside audit range")
    require(refresh["counts"]["newProductAcceptancePasses"] == 0,
            "Static incremental audit does not create product acceptance")


def render_incremental_alignment(data, banner):
    refresh = data["alignmentRefresh"]
    freeze = refresh["sourceFreeze"]
    release = refresh["releaseBaseline"]
    definitions = data["stateDefinitions"]
    lines = ["# 新增提交、EasyWork 对齐与后续计划", "", banner,
             "本页及逐提交页都由同一 status.json 生成。首次盘点、源码合入、历史验收与发布包是不同事实。", "",
             "## 对齐时间与准确版本", "",
             table(["口径", "时间 / 提交", "边界"], [
                 ("计划建立", refresh["planEstablishedAt"], "唯一计划开始日期"),
                 ("首次源码快照", refresh["initialSourceCommittedAt"] + " / " + refresh["initialSourceCommit"], "首次审计并非统一验收提交"),
                 ("原本地台账", str(refresh["previousLocalLedger"]["revision"]) + " / " + refresh["previousLocalLedger"]["updatedAt"], "更新时点不能代表源码已全量对齐"),
                 ("本次承接主线台账", str(refresh["incorporatedMainLedger"]["revision"]) + " / " + refresh["incorporatedMainLedger"]["updatedAt"], "保留新增发布和失败记录"),
                 ("Hub main 冻结", freeze["hub"]["committedAt"] + " / " + freeze["hub"]["commit"], "已 fetch 远端，按可达提交盘点"),
                 ("EasyWork master 冻结", freeze["desktop"]["committedAt"] + " / " + freeze["desktop"]["commit"], "源码状态；未称当前安装指纹"),
                 (release["version"] + "发布记录", release["sourceCommit"], release["scope"]),
             ]), "",
             "正式包 SHA-256：`" + release["packageSha256"] + "`。晚于此包的源变化：`" + release["pendingSourceCommit"] + "`；仅影响本轮两个原文件解析方法的恢复分类及共同向量，后续发布需纳入，不改写118原记录。", "",
             "## 最近一次收尾后的实际净变化", "", refresh["recentNetDelta"]["scope"], "",
             f"R503之后有{refresh['recentNetDelta']['reachableCommitCount']}个可达提交；限定生产路径净变更{len(refresh['recentNetDelta']['changedPaths'])}个文件，主要行为如下。", "",
             table(["行为变化", "源提交", "原功能", "EasyWork下一步"], [
                 (item["behavior"], "、".join(item["commits"]), "、".join(item["existingDeliverableIds"]), item["easyworkAction"])
                 for item in refresh["recentNetDelta"]["changes"]]), "",
             "## 逐提交盘点与去重口径", "", refresh["method"], "",
             f"Hub 完整可达区间 {freeze['hub']['commitCount']} 个提交；iOS相关路径 {refresh['counts']['iosScopedCommits']} 个，其中非合并生产修改 {refresh['counts']['iosNonMergeProductionCommits']} 个；Host初筛 {refresh['counts']['hostInitiallyScopedCommits']} 个，与iOS有重叠。EasyWork完整区间 {freeze['desktop']['commitCount']} 个。归并 {len(refresh['featureGroups'])} 个主题（含验收基础设施），这些数量不能相加当功能数。完整 SHA、日期、路径和归组见 [逐提交盘点](commit-audit.md)。", "",
             f"本轮确认{refresh['counts']['actualDevelopmentGaps']}项明确开发缺口，另有{refresh['counts']['additionalReconciliationAndReleaseTasks']}项原证明/发布收口；登记它们不会回退此前已合PR且范围匹配的功能验收。", "",
             table(["主题", "功能与优化", "原唯一功能", "EasyWork处理"], [
                 (g["id"], g["behavior"] + "；" + "；".join(g.get("optimizations", [])), "、".join(g["functionalDeliverableIds"]) or "支持检查/本轮扩展", g["assessment"])
                 for g in refresh["featureGroups"]]), "",
             "## 新增F76/F77（原75项分母保留）", "", refresh["scopeReview"]["rule"], "",
             table(["新增功能", "开发", "验收", "交付", "执行 / 下一步"], [
                 (f["id"] + " " + f["name"], definitions["development"][f["development"]["state"]] + "：" + f["development"]["scope"],
                  definitions["acceptance"][f["acceptance"]["state"]] + "：" + f["acceptance"]["scope"],
                  definitions["delivery"][f["delivery"]["state"]] + "：" + f["delivery"]["note"],
                  definitions["execution"][f["execution"]["state"]] + "：" + f["nextAction"]) for f in data["extensionFeatures"]]), "",
             table(["具体唯一功能", "用户操作", "验收条件", "原范围 / 下一步"], [
                 (d["id"], d["behavior"], d["acceptanceCriteria"], d["nextAction"]) for d in data["extensionDeliverables"]]), "",
             "## 后续开发、模拟器验收与合并顺序", "",
             "每项沿稳定ID开发、验收、合并；桌面使用真实Electron独立核对，iOS只用模拟器。先查原交付与准确范围，已通过的不重跑。此处是排期，没有启动新验收或部署。", ""]
    for plan in sorted(refresh["plans"], key=lambda p: p["order"]):
        lines += ["### " + plan["id"] + " · " + plan["title"], "",
                  "范围：" + "、".join(plan["featureIds"]) + "；唯一功能：" + "、".join(plan["deliverableIds"]) + "。状态：" + plan["state"] + "。", "",
                  "开发/核对：" + plan["implementation"], "", "验收/复用：" + plan["acceptance"], "",
                  "完成条件：" + plan["completion"], ""]
    lines += ["## 关键生产调用链", ""]
    for name, records in refresh["mappingEvidence"].items():
        lines += ["### " + name, "", table(["冻结源码", "生产位置", "核对结果"], [
            (record["sha"], record["path"] + ":" + ",".join(map(str, record.get("lines", []))), record["detail"])
            for record in records]), ""]
    lines += ["## 本轮证据边界", "", *["- " + item for item in refresh["knownLimitations"]], ""]
    audit = ["# Git 提交逐项盘点", "", banner,
             "同一台账的只读索引。所有提交已由可达祖先关系确认在冻结主线；合并/文档/测试不另计功能，逐行记录不代表逐提交新增验收。完整路径留在JSON，表中显示实际组件便于浏览。", ""]
    for repository, key in (("hub", "hubCommits"), ("desktop", "desktopCommits")):
        frozen = freeze[repository]
        audit += ["## " + frozen["repository"] + " / " + frozen["branch"], "",
                  "区间（左开右闭）：`" + frozen["rangeBaseExclusive"] + ".." + frozen["commit"] + "`。", "",
                  table(["提交 / 日期", "功能或优化 / 提交说明", "主题与处理", "实际组件"], [
                      ("[" + c["sha"] + "](https://git.51easyai.com/" + frozen["repository"] + "/commit/" + c["sha"] + ") / " + c["committedAt"],
                       c["subject"], "、".join(c.get("groupIds", c.get("featureGroups", []))) + " / " + c["disposition"] + "：" + c["basis"],
                       "、".join(sorted({"/".join(path.split("/")[:2]) for path in c["changedPaths"]})) or "无首父文件差异")
                      for c in refresh[key]]), ""]
    return {"incremental-alignment.md": "\n".join(lines), "commit-audit.md": "\n".join(audit)}


def validate(data):
    validate_functional_deliverables(data)
    validate_incremental_alignment(data)
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
            if data["acceptancePolicy"].get("mobileEnvironment") == "simulator":
                require(evidence["mobileEnvironment"] in {"simulator", "not-required"}, f"{owner}: current scope requires simulator evidence")
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
        if data["acceptancePolicy"].get("mobileEnvironment") == "simulator":
            require(not policy["physicalAssertions"], f"{scenario['id']}: current scope has a physical gate")
            require(policy["mode"] != "split", f"{scenario['id']}: current scope requires simulator assertions")
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
    if "executionFocus" in data:
        validate_execution_focus(data["executionFocus"], features, item_ids, data["acceptanceScenarios"])
    require({s["id"] for s in data["acceptanceScenarios"]} == set(re.findall(r"^\|\s*(AC\d{2})\s*\|", (ROOT / "acceptance.md").read_text(), re.M)), "Scenario definitions and ledger differ")


def render(data):
    definitions = data["stateDefinitions"]
    features = data["features"]
    label = lambda dimension, state: definitions[dimension][state]
    fingerprint = hashlib.sha256((ROOT / "status.json").read_bytes()).hexdigest()
    banner = f"> 历史来源快照，勿手改；数据：[status.json](status.json)，修订 {data['revision']}，记录时间 {data['updatedAt']}。台账 SHA-256：`{fingerprint}`。\n> 下方状态与计划仅代表原记录时点；全项目当前功能及双端实现/验收只维护[唯一完整功能清单](../android-ios-alignment-20261107/progress.md)，不从本快照推导当前完成率。\n"
    counts = []
    for dimension in ("development", "acceptance", "delivery", "execution"):
        counter = Counter(f[dimension]["state"] for f in features)
        counts.append((dimension, [(label(dimension, key), count) for key, count in counter.items()]))
    active = [f for f in features if f["plannedScope"] == "integration"]
    matched = sum(f["acceptance"]["state"] == "historical-scope-awaiting-match" for f in active)
    unmatched = sum(f["acceptance"]["state"] == "historical-scope-unmatched" for f in active)
    closed = sum(f["acceptance"]["state"] in {"current-passed", "matched-reuse"} for f in active)
    open_count = len(active) - matched - unmatched - closed
    progress = ["# 历史进度快照", "", banner,
        "本表是台账的只读投影，不是另一套进度记录。状态定义和更新流程见 [维护规则](maintenance.md)。", "",
        f"当前共 {len(features)} 个父功能：{len(active)} 个对接、{sum(f['plannedScope']=='phone-only' for f in features)} 个手机共用、{sum(f['plannedScope']=='excluded' for f in features)} 个范围外。对接范围有 {open_count} 个开放父项、{matched + unmatched} 个历史限定通过未复用（待匹配 {matched}，本次匹配未成立 {unmatched}）、{closed} 个按当前范围关闭。开放数不等于未开发数。", ""]
    if "executionFocus" in data:
        progress += ["当前执行顺序和剩余验收动作见 [核心使用验收与剩余对接盘点](core-acceptance.md)。", ""]
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
        table(["场景", "环境口径", "模拟器/非手机范围", "本轮模拟器替代断言"], [
            (s["id"], {"no-mobile": "无需手机", "simulator-sufficient": "模拟器足够", "split": "拆子项", "inherits": "继承受影响范围"}[s["environmentPolicy"]["mode"]], s["environmentPolicy"]["simulatorScope"], "；".join(s["environmentPolicy"].get("simulatorReplacementAssertions", [])) or "沿用原生模拟器范围") for s in data["acceptanceScenarios"]]), ""]
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
    checklist = ["# 历史75项功能状态快照", "", banner,
        "开发、验收、交付和执行为独立维度；不能互相替代。证据范围、原状态和来源提交完整保存在台账。", "",
        table(["ID / 功能", "开发状态", "验收状态", "交付状态", "执行状态", "包 / 事项", "下一步"], [
            (f["id"] + " " + f["name"], label("development", f["development"]["state"]), label("acceptance", f["acceptance"]["state"]), label("delivery", f["delivery"]["state"]), label("execution", f["execution"]["state"]), "/".join(f["packages"] + f["openItemIds"]) or "共用/范围外", f["nextAction"]) for f in features]), "",
        "## 开发判断依据与证据索引", "",
        table(["ID", "开发判断依据", "证据"], [(f["id"], f["development"]["basis"], "/".join(dict.fromkeys(f["development"]["evidenceRefs"] + f["acceptance"]["evidenceRefs"]))) for f in features]), "",
        "证据ID在 status.json 的 evidenceRegistry 中解析为准确提交、路径和范围。历史日志不会被状态投影覆盖。", ""]
    checklist += ["## 52项对接功能的完整验收范围", "",
        "执行顺序见 [核心使用验收与剩余对接盘点](core-acceptance.md)。下表保留完整验收范围，本轮全部 iOS 验收使用模拟器。真实桌面、模型和原资源按对应断言验证。", "",
        table(["F / 顺序", "AC", "开发门槛", "模拟器可执行断言", "本轮模拟器替代断言"], [
            (f["id"] + " / " + f["acceptancePlan"]["priority"] + " / " + str(f["acceptancePlan"]["sequence"]), "/".join(f["acceptancePlan"]["scenarioIds"]), f["acceptancePlan"]["developmentGate"], f["acceptancePlan"]["nativeSimulatorAssertions"], "；".join(x["scenario"] + "：" + x["assertion"] for x in f["acceptancePlan"].get("simulatorReplacementAssertions", [])) or "沿用原生模拟器范围") for f in sorted(active, key=lambda f: (f["acceptancePlan"]["sequence"], f["id"]))]), ""]
    views = {"progress.md": "\n".join(progress), "checklist.md": "\n".join(checklist)}
    if "executionFocus" in data:
        views["core-acceptance.md"] = render_execution_focus(data, banner)
    if data.get("alignmentRefresh"):
        views.update(render_incremental_alignment(data, banner))
        for name in ("progress.md", "checklist.md", "core-acceptance.md"):
            if name in views:
                views[name] += "\n\n新增提交与F76/F77扩展、EasyWork待补能力和后续计划见 [增量对齐](incremental-alignment.md)；原75项统计口径保留。\n"
    return views


def format_view(content, path):
    # pnpm exec may prepare missing dependencies and prepend install logs to
    # stdout. Generated views must contain only Markdown, with no installs or
    # environment-specific paths caused by formatting a cold workspace.
    repository = ROOT.parents[2]
    formatter = repository / "node_modules" / "prettier" / "bin" / "prettier.cjs"
    require(formatter.is_file(), "Workspace Prettier is missing; prepare the existing locked dependencies before rendering")
    formatted = subprocess.run(
        ["node", str(formatter), "--stdin-filepath", str(path)],
        input=content, text=True, capture_output=True, cwd=repository,
        timeout=60, check=False,
    )
    require(formatted.returncode == 0, "Prettier failed: " + formatted.stderr[-1000:])
    require(formatted.stdout.startswith(content.splitlines()[0] + "\n"), "Prettier returned unexpected output before the generated heading")
    return formatted.stdout


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
        content = format_view(content, path)
        if args.write:
            path.write_text(content)
        else:
            require(path.exists() and path.read_text() == content, f"Stale view: {name}; run --write")
    print("OK: historical source ledger validated; 75 features, 16 work packages, 32 scenarios; views " + ("written" if args.write else "match"))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError) as error:
        print(f"Plan validation failed: {error}", file=sys.stderr)
        sys.exit(1)
