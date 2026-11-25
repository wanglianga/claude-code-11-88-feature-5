package com.lakegov.web;

import com.lakegov.service.SnapshotService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/snapshots")
public class SnapshotController {

    private final SnapshotService snapshots;

    public SnapshotController(SnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @PostMapping
    public Map<String, Object> create() {
        return snapshots.createSnapshot();
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return snapshots.listSnapshots();
    }

    @GetMapping("/{id}/replay")
    public Map<String, Object> replay(@PathVariable long id) {
        return snapshots.replay(id);
    }
}
