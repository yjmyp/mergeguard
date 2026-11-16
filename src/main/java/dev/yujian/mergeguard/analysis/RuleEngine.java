package dev.yujian.mergeguard.analysis;

import dev.yujian.mergeguard.diff.FileDiff;
import dev.yujian.mergeguard.domain.Severity;
import dev.yujian.mergeguard.github.GhFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

import static dev.yujian.mergeguard.domain.Severity.BLOCKER;
import static dev.yujian.mergeguard.domain.Severity.INFO;
import static dev.yujian.mergeguard.domain.Severity.MAJOR;
import static dev.yujian.mergeguard.domain.Severity.MINOR;

@Component
public class RuleEngine {

    private static final Pattern[] SECRET_PATTERNS = {
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("-----BEGIN ([A-Z ]+ )?PRIVATE KEY-----"),
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{30,}"),
            Pattern.compile("sk-[A-Za-z0-9]{20,}"),
            Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("(password|passwd|pwd|secret|api[_-]?key|access[_-]?token)\\s*[:=]\\s*[\"'][^\"']{8,}[\"']",
                    Pattern.CASE_INSENSITIVE)
    };
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "changeme|change-me|example|placeholder|your[-_]?|dummy|test[-_]?secret|\\$\\{|<.*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP = Pattern.compile("\\bDROP\\s+(TABLE|DATABASE|SCHEMA|COLUMN)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRUNCATE = Pattern.compile("\\bTRUNCATE\\s+TABLE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_NO_WHERE = Pattern.compile("\\bDELETE\\s+FROM\\s+[\\w.`\"]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_CONCAT = Pattern.compile(
            "(executeQuery|executeUpdate|prepareStatement|createQuery|createNativeQuery)\\s*\\(\\s*\"[^\"]*\"\\s*\\+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_CONCAT_ANY = Pattern.compile("\\b(String\\s+sql\\s*=\\s*\".*\"\\s*\\+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMAND_INJECTION = Pattern.compile(
            "(Runtime\\.getRuntime\\(\\)\\.exec\\s*\\(\\s*[^\"']|ProcessBuilder\\s*\\(\\s*[^\"'])|os\\.system\\s*\\(|subprocess\\.(run|call|Popen)\\s*\\([^)]*shell\\s*=\\s*True",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INSECURE_TLS = Pattern.compile(
            "TrustAllCerts|trustAllCerts|ALLOW_ALL_HOSTNAME_VERIFIER|verify\\s*=\\s*False|InsecureSkipVerify|NoopHostnameVerifier",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DEBUG = Pattern.compile(
            "System\\.out\\.print|printStackTrace\\s*\\(|console\\.log\\s*\\(|debugger\\s*;|fmt\\.Println\\s*\\(");
    private static final Pattern DISABLED_TEST = Pattern.compile(
            "@Disabled|@Ignore|\\.skip\\s*\\(|it\\.only\\s*\\(|describe\\.only\\s*\\(|pytest\\.mark\\.skip|enabled\\s*=\\s*false",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMPTY_CATCH = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}", Pattern.CASE_INSENSITIVE);
    private static final Pattern CATCH_OPEN = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*$");
    private static final Pattern BROAD_CATCH = Pattern.compile("catch\\s*\\(\\s*(Exception|Throwable)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TODO = Pattern.compile("(?://|#|\\*|<!--)\\s*(TODO|FIXME|XXX)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERNAL_URL = Pattern.compile("http://(10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.)");
    private static final Pattern EVAL = Pattern.compile("\\b(eval|exec)\\s*\\(");
    private static final Pattern WEAK_CRYPTO = Pattern.compile("\\b(MD5|SHA1|sha1)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RISKY_WORKFLOW = Pattern.compile("pull_request_target", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE_ALL = Pattern.compile("permissions\\s*:\\s*write-all", Pattern.CASE_INSENSITIVE);

    private static final Set<String> IGNORED_FILES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "poetry.lock", "uv.lock");

    public List<FindingDraft> analyze(List<FileDiff> diffs) {
        List<FindingDraft> findings = new ArrayList<>();
        int totalChanges = 0;
        int totalAdded = 0;
        boolean testChanged = false;
        boolean mainChanged = false;
        String firstMainFile = null;

        for (FileDiff diff : diffs) {
            GhFile file = diff.file();
            String name = file.filename();
            totalChanges += file.additions() + file.deletions();
            totalAdded += file.additions();
            if (isTestFile(name)) {
                testChanged = true;
            }
            if (isMainCode(name)) {
                mainChanged = true;
                if (firstMainFile == null) {
                    firstMainFile = name;
                }
            }
            if (isIgnored(name)) {
                continue;
            }
            analyzeFile(diff, findings);
        }

        if (!diffs.isEmpty()) {
            if (totalChanges > 600 || countFiles(diffs) > 30) {
                findings.add(FindingDraft.rule("(pull request)", null, MAJOR, "REVIEW_COST", "R-PR-01",
                        "变更范围过大",
                        totalChanges + " 行变更 / " + countFiles(diffs) + " 个文件",
                        "拆分为可独立验证的小 PR，降低审查遗漏和回滚成本。"));
            } else if (totalChanges > 300) {
                findings.add(FindingDraft.rule("(pull request)", null, MINOR, "REVIEW_COST", "R-PR-02",
                        "变更范围偏大", totalChanges + " 行变更", "考虑拆分，或在描述中给出验证步骤。"));
            }
            if (mainChanged && !testChanged && totalAdded >= 60 && firstMainFile != null) {
                findings.add(FindingDraft.rule(firstMainFile, null, MAJOR, "TESTING", "R-TEST-01",
                        "主代码变更缺少对应测试",
                        "主代码新增 " + totalAdded + " 行，但 PR 没有修改任何测试文件",
                        "补充覆盖新增分支的单测，至少包含失败路径。"));
            }
        }
        return findings;
    }

    private void analyzeFile(FileDiff diff, List<FindingDraft> findings) {
        String name = diff.filename();
        if (name.endsWith(".env") || name.contains(".env.")) {
            findings.add(FindingDraft.rule(name, null, BLOCKER, "SECURITY", "R-SEC-06",
                    "提交了环境变量文件", name, "移除该文件并轮换其中可能泄露的凭证，用部署环境注入。"));
        }
        int todoCount = 0;
        for (FileDiff.AddedLine line : diff.addedLines()) {
            String text = line.content();
            String trimmed = text.trim();

            for (Pattern pattern : SECRET_PATTERNS) {
                var matcher = pattern.matcher(text);
                if (matcher.find() && !PLACEHOLDER.matcher(matcher.group()).find()) {
                    findings.add(FindingDraft.rule(name, line.lineNumber(), BLOCKER, "SECURITY", "R-SEC-01",
                            "疑似硬编码凭证", mask(text), "改为环境变量或密钥管理服务，并立即轮换已提交的凭证。"));
                    break;
                }
            }
            if (DROP.matcher(text).find() || TRUNCATE.matcher(text).find()) {
                findings.add(FindingDraft.rule(name, line.lineNumber(), BLOCKER, "DATA_LOSS", "R-SQL-01",
                        "破坏性数据库变更", trimmed, "改用可回滚的迁移：先加新列/双写，再分批删除。"));
            } else if (DELETE_NO_WHERE.matcher(text).find() && !text.toUpperCase(Locale.ROOT).contains("WHERE")) {
                findings.add(FindingDraft.rule(name, line.lineNumber(), MAJOR, "DATA_LOSS", "R-SQL-02",
                        "DELETE 语句缺少 WHERE", trimmed, "补充精确过滤条件，并在迁移前备份。"));
            }
            if (SQL_CONCAT.matcher(text).find() || SQL_CONCAT_ANY.matcher(text).find()) {
                findings.add(FindingDraft.rule(name, line.lineNumber(), BLOCKER, "SECURITY", "R-SEC-02",
                        "SQL 字符串拼接可能注入", trimmed, "使用参数化查询 / PreparedStatement。"));
            }
            if (COMMAND_INJECTION.matcher(text).find()) {
                findings.add(FindingDraft.rule(name, line.lineNumber(), BLOCKER, "SECURITY", "R-SEC-03",
                        "命令执行参数未受控", trimmed, "使用参数数组（不经过 shell），并对白名单外的输入拒绝。"));
            }
            if (INSECURE_TLS.matcher(text).find()) {
                findings.add(FindingDraft.rule(name, line.lineNumber(), MAJOR, "SECURITY", "R-SEC-04",
                        "TLS 校验被关闭", trimmed, "恢复证书与主机名校验；测试环境用可信测试 CA。"));
            }
            if (DEBUG.matcher(text).find()) {
                findings.add(FindingDraft.rule(name, line.lineNumber(), MINOR, "MAINTAINABILITY", "R-CODE-01",
                        "调试输出残留", trimmed, "改用结构化日志并带上下文；合并前删除临时输出。"));
            }
            if (DISABLED_TEST.matcher(text).find()) {
                findings.add(FindingDraft.rule(name, line.lineNumber(), MAJOR, "TESTING", "R-TEST-02",
                        "测试被禁用或跳过", trimmed, "恢复该用例；若确实不稳定，标记为 flaky 并建修复任务。"));
            }
            if (EMPTY_CATCH.matcher(text).find() || (CATCH_OPEN.matcher(text).find() && nextIsClosingBrace(diff, line))) {
                findings.add(FindingDraft.rule(name, line.lineNumber(), MAJOR, "RELIABILITY", "R-CODE-02",
                        "异常被静默吞掉", trimmed, "至少记录异常并转换为领域错误，避免故障不可见。"));
            } else if (BROAD_CATCH.matcher(text).find()) {
                findings.add(FindingDraft.rule(name, line.lineNumber(), MINOR, "RELIABILITY", "R-CODE-03",
                        "捕获范围过宽", trimmed, "只捕获可处理的异常类型，其余向上抛出。"));
            }
            if (INTERNAL_URL.matcher(text).find()) {
                findings.add(FindingDraft.rule(name, line.lineNumber(), MINOR, "CONFIG", "R-CFG-01",
                        "硬编码内网地址", trimmed, "改为配置项，并区分环境。"));
            }
            if (EVAL.matcher(text).find()) {
                findings.add(FindingDraft.rule(name, line.lineNumber(), MAJOR, "SECURITY", "R-SEC-05",
                        "动态执行代码", trimmed, "移除 eval/exec，改用显式解析或白名单。"));
            }
            if (PromptGuard.isInjectionAttempt(text)) {
                findings.add(FindingDraft.rule(name, line.lineNumber(), MAJOR, "SECURITY", "R-SEC-08",
                        "疑似针对 AI 审查的提示注入", trimmed,
                        "删除面向评审模型的指令性文本；PR 内容会被当作不可信数据处理。"));
            }
            if (WEAK_CRYPTO.matcher(text).find() && !text.contains("test")) {
                findings.add(FindingDraft.rule(name, line.lineNumber(), MINOR, "SECURITY", "R-SEC-07",
                        "使用了弱哈希算法", trimmed, "改用 SHA-256 或 bcrypt/Argon2（口令场景）。"));
            }
            if (TODO.matcher(text).find() && todoCount < 2) {
                todoCount++;
                findings.add(FindingDraft.rule(name, line.lineNumber(), INFO, "MAINTAINABILITY", "R-CODE-04",
                        "遗留 TODO/FIXME", trimmed, "合并前处理，或建 issue 并附链接。"));
            }
        }
        if (name.startsWith(".github/workflows/")) {
            for (FileDiff.AddedLine line : diff.addedLines()) {
                if (RISKY_WORKFLOW.matcher(line.content()).find()) {
                    findings.add(FindingDraft.rule(name, line.lineNumber(), BLOCKER, "CI_SECURITY", "R-CI-01",
                            "使用 pull_request_target 且未隔离权限", line.content().trim(),
                            "改用 pull_request，或确保不检出/执行 PR 代码且权限最小化。"));
                }
                if (WRITE_ALL.matcher(line.content()).find()) {
                    findings.add(FindingDraft.rule(name, line.lineNumber(), MAJOR, "CI_SECURITY", "R-CI-02",
                            "工作流权限过宽", line.content().trim(), "改为按 job 声明最小 permissions。"));
                }
            }
        }
    }

    private boolean nextIsClosingBrace(FileDiff diff, FileDiff.AddedLine current) {
        for (FileDiff.AddedLine line : diff.addedLines()) {
            if (line.lineNumber() == current.lineNumber() + 1) {
                return line.content().trim().equals("}");
            }
        }
        return false;
    }

    private int countFiles(List<FileDiff> diffs) {
        Set<String> names = new HashSet<>();
        for (FileDiff diff : diffs) {
            names.add(diff.filename());
        }
        return names.size();
    }

    private boolean isIgnored(String name) {
        if (IGNORED_FILES.contains(name)) return true;
        return name.endsWith(".min.js") || name.contains("/dist/") || name.contains("node_modules/")
                || name.endsWith(".lock");
    }

    private boolean isTestFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("/test/") || lower.contains("/tests/") || lower.contains("src/test/")
                || lower.endsWith("test.java") || lower.endsWith("_test.py") || lower.endsWith(".test.ts")
                || lower.endsWith(".spec.ts") || lower.endsWith("test.tsx");
    }

    private boolean isMainCode(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (isTestFile(name)) return false;
        return lower.startsWith("src/main/") || lower.contains("/src/main/")
                || lower.startsWith("backend/") || lower.startsWith("app/")
                || lower.endsWith(".java") || lower.endsWith(".py") && !lower.startsWith("scripts/");
    }

    private String mask(String text) {
        String trimmed = text.trim();
        return trimmed.length() > 160 ? trimmed.substring(0, 160) + "..." : trimmed;
    }
}
