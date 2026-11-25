package com.lakegov.web;

import com.lakegov.service.CatalogService;
import com.lakegov.service.LineageService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogService catalog;
    private final LineageService lineage;

    public CatalogController(CatalogService catalog, LineageService lineage) {
        this.catalog = catalog;
        this.lineage = lineage;
    }

    @GetMapping("/objects")
    public List<Map<String, Object>> objects(@RequestParam(required = false) String category,
                                             @RequestParam(required = false) String tier,
                                             @RequestParam(required = false) Long projectId) {
        return catalog.list(category, tier, projectId);
    }

    @GetMapping("/objects/{uid}")
    public Map<String, Object> detail(@PathVariable String uid) {
        return catalog.detail(uid);
    }

    @PostMapping("/objects")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return catalog.create(body);
    }

    @GetMapping("/tiers")
    public List<Map<String, Object>> tiers() {
        return catalog.tiers();
    }

    @GetMapping("/tier-recommendations")
    public Map<String, Object> recommendations() {
        return catalog.tierRecommendation();
    }

    @PostMapping("/migrate")
    public Map<String, Object> migrate(@RequestBody Map<String, String> body) {
        return catalog.migrate(body.get("uid"), body.get("targetTier"), body.get("reason"));
    }

    @GetMapping("/lineage")
    public Map<String, Object> lineage() {
        return lineage.fullGraph();
    }

    @PostMapping("/lineage/edge")
    public Map<String, Object> addEdge(@RequestBody Map<String, Object> body) {
        return lineage.addEdge((String) body.get("fromUid"), (String) body.get("toUid"),
                (String) body.get("edgeVersion"), (String) body.get("recipe"),
                (String) body.get("softwareVersion"),
                Boolean.TRUE.equals(body.get("sharedReference")));
    }

    @GetMapping("/shared")
    public List<Map<String, Object>> shared() {
        return lineage.sharedReferences();
    }
}
