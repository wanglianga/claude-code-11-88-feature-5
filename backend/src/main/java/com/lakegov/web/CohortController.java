package com.lakegov.web;

import com.lakegov.auth.AuthInterceptor;
import com.lakegov.auth.CurrentUser;
import com.lakegov.cohort.Blueprint;
import com.lakegov.cohort.RunRequest;
import com.lakegov.service.CohortService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 可复现研究队列抽取器 API。
 * 写操作（运行抽取、新增/删除候选）由 AuthInterceptor 统一限制为 ADMIN。
 */
@RestController
@RequestMapping("/api/cohort")
public class CohortController {

    private final CohortService cohort;

    public CohortController(CohortService cohort) {
        this.cohort = cohort;
    }

    /** 总览：120 患者 / 1600 扫描 / 2 设备 / 8 人罕见协议 / 水位 / 分布。 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return cohort.overview();
    }

    @GetMapping("/patients")
    public List<Map<String, Object>> patients(@RequestParam(required = false) String eligibility,
                                               @RequestParam(required = false) String q) {
        return cohort.listPatients(eligibility, q);
    }

    @GetMapping("/scans")
    public List<Map<String, Object>> scans(@RequestParam(required = false) String patientUid) {
        return cohort.listScans(patientUid);
    }

    @GetMapping("/profiles")
    public List<Map<String, Object>> profiles() {
        return cohort.listProfiles();
    }

    /** 约束编译器试算：硬约束/软目标 + 必不可行预警。 */
    @PostMapping("/constraints/compile")
    public Blueprint.CompiledView compile(@RequestBody(required = false) Map<String, Object> body) {
        return cohort.compilePreview(body);
    }

    /** 运行抽取（双路线 / 种子 / 增量父账本），落抽取账并返回完整结果与校验报告。 */
    @PostMapping("/runs")
    public Map<String, Object> run(@RequestBody(required = false) RunRequest request,
                                   HttpServletRequest http) {
        CurrentUser user = AuthInterceptor.require(http);
        return cohort.runExtraction(request, user == null ? null : user.username());
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs() {
        return cohort.listRuns();
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> runDetail(@PathVariable Long id) {
        return cohort.getRun(id);
    }

    /** 新增 CP-TMP- 临时候选患者（增量抽取演示/测试用）。 */
    @PostMapping("/candidates")
    public Map<String, Object> createCandidate(@RequestBody Map<String, Object> body) {
        return cohort.createCandidate(body);
    }

    @DeleteMapping("/candidates/{uid}")
    public Map<String, Object> deleteCandidate(@PathVariable String uid) {
        return cohort.deleteCandidate(uid);
    }
}
