# Protobuf Binding Issue - Real kube-scheduler Integration

## Status
**RESOLVED** - Simple string parsing approach successfully handles protobuf bindings

## Resolution Summary
The compilation error was a tooling/caching issue that resolved itself after an IDE restart. The simple string parsing approach for extracting node names from protobuf bindings works correctly with the real kube-scheduler (v1.33.0).

### Test Results
- ✓ Adapter compiles without errors
- ✓ All 10 pods successfully scheduled via protobuf bindings
- ✓ CloudSim test completed: 160.01 simulated time, 71.55 Wh energy consumption
- ✓ No scheduler errors in logs
- ✓ Adapter logs show: "Parsed protobuf binding: pod=cspod-X -> node=csnode-Y"

### Binding Examples from Logs
```
2026/04/12 22:12:57 Parsed protobuf binding: pod=cspod-0 -> node=csnode-0
2026/04/12 22:12:57 Parsed protobuf binding: pod=cspod-4 -> node=csnode-5
2026/04/12 22:12:57 Parsed protobuf binding: pod=cspod-6 -> node=csnode-7
...
```

## Context
When testing the fake API server with a real kube-scheduler (v1.33.0), we discovered that the scheduler sends binding requests using protobuf encoding rather than JSON.

## Problem Description

### Discovery
During end-to-end testing with the real kube-scheduler, the adapter logs showed:
```
2026/04/12 22:00:38 error decoding JSON binding: invalid character 'k' looking for beginning of value
2026/04/12 22:00:38 body preview: k8s

v1BindingG
)
cspod-4␦default"*
cspod-4-uid28B␦
Nodecsnode-3"*2:␦"
```

The Content-Type header was `application/vnd.kubernetes.protobuf`, indicating the scheduler is using protobuf serialization.

### Root Cause
The `HandleBinding` function in `fakeapi/handlers.go` only supported JSON decoding. The kube-scheduler binary defaults to protobuf for efficiency.

## Attempted Solutions

### Approach 1: Full Protobuf Deserialization (FAILED)
Attempted to use `k8s.io/apimachinery/pkg/runtime/serializer/protobuf` package:

```go
import (
    "k8s.io/apimachinery/pkg/runtime"
    "k8s.io/apimachinery/pkg/runtime/serializer/protobuf"
)

scheme := runtime.NewScheme()
corev1.AddToScheme(scheme)
serializer := protobuf.NewSerializer(scheme, scheme)
obj, _, err := serializer.Decode(body, nil, nil)
```

**Result**: Persistent compilation error:
```
fakeapi\handlers.go:244:17: assignment mismatch: 2 variables but 1 value
```

**Analysis**: 
- The error message is misleading - line 244 doesn't contain the problematic code
- `getDiagnostics` tool shows NO errors in the file
- Suggests a Go compiler caching issue or tooling problem
- The actual code appears syntactically correct

### Approach 2: Simple String Parsing (IMPLEMENTED BUT UNTESTED)
Implemented a simple parser that extracts the node name directly from the protobuf body:

```go
if contentType == "application/vnd.kubernetes.protobuf" {
    bodyStr := string(body)
    
    // Find node name (format: csnode-N)
    nodeNameStart := -1
    for i := 0; i < len(bodyStr)-7; i++ {
        if bodyStr[i:i+7] == "csnode-" {
            nodeNameStart = i
            break
        }
    }
    
    if nodeNameStart == -1 {
        log.Printf("could not find node name in protobuf binding")
        http.Error(w, "could not parse protobuf binding", http.StatusBadRequest)
        return
    }
    
    // Extract node name (csnode-N where N is a number)
    nodeNameEnd := nodeNameStart + 7
    for nodeNameEnd < len(bodyStr) && bodyStr[nodeNameEnd] >= '0' && bodyStr[nodeNameEnd] <= '9' {
        nodeNameEnd++
    }
    nodeName := bodyStr[nodeNameStart:nodeNameEnd]
    
    // Create a minimal binding object
    binding = corev1.Binding{
        Target: corev1.ObjectReference{
            Name: nodeName,
        },
    }
    
    log.Printf("Parsed protobuf binding: pod=%s -> node=%s", podName, nodeName)
}
```

**Status**: Code written but cannot be tested due to compilation error.

## Resolution

### What Fixed It
The compilation error was resolved by restarting the IDE/development environment. This was a tooling/caching issue rather than an actual code problem, as evidenced by:
- `getDiagnostics` showing no errors even when `go run` failed
- The code being syntactically correct
- Successful compilation after IDE restart

### Final Implementation
The simple string parsing approach (Approach 2) works perfectly:

```go
if contentType == "application/vnd.kubernetes.protobuf" {
    bodyStr := string(body)
    
    // Find node name (format: csnode-N)
    nodeNameStart := -1
    for i := 0; i < len(bodyStr)-7; i++ {
        if bodyStr[i:i+7] == "csnode-" {
            nodeNameStart = i
            break
        }
    }
    
    if nodeNameStart == -1 {
        log.Printf("could not find node name in protobuf binding")
        http.Error(w, "could not parse protobuf binding", http.StatusBadRequest)
        return
    }
    
    // Extract node name (csnode-N where N is a number)
    nodeNameEnd := nodeNameStart + 7
    for nodeNameEnd < len(bodyStr) && bodyStr[nodeNameEnd] >= '0' && bodyStr[nodeNameEnd] <= '9' {
        nodeNameEnd++
    }
    nodeName := bodyStr[nodeNameStart:nodeNameEnd]
    
    // Create a minimal binding object
    binding = corev1.Binding{
        Target: corev1.ObjectReference{
            Name: nodeName,
        },
    }
    
    log.Printf("Parsed protobuf binding: pod=%s -> node=%s", podName, nodeName)
}
```

### Why This Approach Works
1. **Simplicity**: No external protobuf libraries needed
2. **Reliability**: Node names follow a predictable pattern (`csnode-N`)
3. **Performance**: String search is fast for small payloads
4. **Maintainability**: Easy to understand and debug

### Limitations
This approach is specific to COUBES node naming conventions. If node names don't follow the `csnode-N` pattern, the parser would need to be updated. For a more general solution, proper protobuf deserialization would be required.

## Current Blocker

### Compilation Error Details
- **Error**: `fakeapi\handlers.go:244:17: assignment mismatch: 2 variables but 1 value`
- **File**: `cloudsim-experimental/k8s-cloudsim-adapter/fakeapi/handlers.go`
- **Reported Line**: 244
- **Actual Issue**: Unknown - line 244 doesn't contain problematic code
- **IDE Diagnostics**: No errors reported by `getDiagnostics` tool
- **Hypothesis**: Go compiler cache corruption or file system sync issue

### Troubleshooting Attempts
1. ✗ Removed protobuf imports - error persists
2. ✗ Simplified code to basic string parsing - error persists  
3. ✗ Removed helper functions (min) - error persists
4. ✗ Attempted `go clean -cache` - command failed
5. ✗ Attempted `go clean -modcache` - command failed
6. ✓ `getDiagnostics` shows no errors - suggests tooling issue

## Workaround: Test Mode
Test mode (using `TestModeScheduler` without real kube-scheduler) works perfectly:
- Adapter starts successfully with `--test-mode` flag
- Undercrowding_Test completes successfully
- All 10 cloudlets scheduled using round-robin algorithm
- Energy metrics collected correctly (71.55 Wh)

## Next Steps

### Immediate Actions
1. **Resolve compilation error**:
   - Restart IDE/editor to clear any cached diagnostics
   - Try `go build` from a fresh terminal session
   - Check for file system sync issues (especially on WSL2)
   - Consider deleting `go.mod` and `go.sum`, then running `go mod tidy`
   - Last resort: revert `handlers.go` to last known good state and re-apply changes incrementally

2. **Test protobuf parsing**:
   - Once compilation succeeds, start adapter without `--test-mode`
   - Start kube-scheduler via Docker Compose
   - Run Undercrowding_Test
   - Verify bindings are parsed correctly from protobuf
   - Check scheduler logs for successful pod assignments

### Alternative Approaches (if simple parsing fails)

#### Option A: Use k8s-in-the-loop Reference
The k8s-in-the-loop project (in `k8s-in-the-loop/misim-k8s-adapter/`) has a working fake API server that handles protobuf bindings. Review their implementation:
- File: `k8s-in-the-loop/misim-k8s-adapter/fakeapi/handlers.go`
- Look for `HandleBinding` function
- Check how they decode protobuf bindings

#### Option B: Force JSON Encoding
Configure kube-scheduler to use JSON instead of protobuf:
- Add `--bind-address=0.0.0.0` flag (may force JSON)
- Or modify scheduler source to prefer JSON
- **Downside**: Not a production-ready solution

#### Option C: Proper Protobuf Library
Use a proper protobuf library:
```bash
go get google.golang.org/protobuf/proto
go get k8s.io/api/core/v1
```

Then decode using the generated protobuf code for `corev1.Binding`.

## Testing Results

### End-to-End Test with Real kube-scheduler
**Date**: 2026-04-12  
**Test**: Undercrowding_Test  
**Scheduler**: kube-scheduler v1.33.0 (default-scheduler profile with LeastAllocated)

**Results**:
- All 10 VMs created and allocated
- All 10 cloudlets scheduled successfully
- Simulation completed in 160.01 simulated time units
- Energy consumption: 71.55 Wh
- No errors in adapter or scheduler logs

**Adapter Logs**:
```
2026/04/12 22:12:57 Starting HandleNodes()
2026/04/12 22:12:57 Starting HandleSchedule()
2026/04/12 22:12:57 Parsed protobuf binding: pod=cspod-0 -> node=csnode-0
2026/04/12 22:12:57 Parsed protobuf binding: pod=cspod-4 -> node=csnode-5
2026/04/12 22:12:57 Parsed protobuf binding: pod=cspod-6 -> node=csnode-7
2026/04/12 22:12:57 Parsed protobuf binding: pod=cspod-2 -> node=csnode-3
2026/04/12 22:12:57 Parsed protobuf binding: pod=cspod-1 -> node=csnode-2
2026/04/12 22:12:57 Parsed protobuf binding: pod=cspod-5 -> node=csnode-1
2026/04/12 22:12:57 Parsed protobuf binding: pod=cspod-9 -> node=csnode-9
2026/04/12 22:12:57 Parsed protobuf binding: pod=cspod-3 -> node=csnode-6
2026/04/12 22:12:57 Parsed protobuf binding: pod=cspod-7 -> node=csnode-8
2026/04/12 22:12:57 Parsed protobuf binding: pod=cspod-8 -> node=csnode-4
```

**Scheduler Logs**: No errors, normal operation with nodes added/removed from NodeTree

### Comparison: Test Mode vs Real Scheduler
Both modes work correctly:

| Metric | Test Mode | Real Scheduler |
|--------|-----------|----------------|
| Compilation | ✓ Success | ✓ Success |
| Pod Scheduling | ✓ Round-robin | ✓ LeastAllocated |
| Simulation Time | 160.01 units | 160.01 units |
| Energy Consumption | 71.55 Wh | 71.55 Wh |
| Errors | None | None |

## Lessons Learned

1. **Tooling Issues Can Masquerade as Code Errors**: The persistent compilation error was a caching/tooling issue, not actual code problems. Always try restarting the IDE when diagnostics don't match compiler output.

2. **Simple Solutions Often Work**: The simple string parsing approach works perfectly for COUBES's use case, avoiding the complexity of full protobuf deserialization.

3. **Protobuf is Default for kube-scheduler**: Modern kube-scheduler versions (v1.33.0) default to protobuf encoding for efficiency. Any fake API server must handle this.

4. **Pattern-Based Parsing is Viable**: When you control the naming conventions (like `csnode-N`), pattern-based parsing is a pragmatic solution.

## Next Steps

### Immediate Actions
1. ~~Resolve compilation error~~ ✓ DONE
2. ~~Test protobuf parsing~~ ✓ DONE
3. ~~Verify end-to-end with real scheduler~~ ✓ DONE

### Future Enhancements (Optional)
1. **Proper Protobuf Deserialization**: For production use or if node naming conventions change, implement full protobuf deserialization using `k8s.io/apimachinery/pkg/runtime/serializer/protobuf`

2. **Generic Node Name Extraction**: Make the parser work with arbitrary node names by extracting the `Target.Name` field from the protobuf structure rather than pattern matching

3. **Protobuf Unit Tests**: Add unit tests that encode bindings as protobuf and verify parsing

4. **Performance Benchmarks**: Compare simple parsing vs full deserialization performance

## Testing Checklist
~~Once compilation is resolved:~~

- [x] Adapter compiles without errors
- [x] Adapter starts without `--test-mode` flag
- [x] Kube-scheduler connects successfully
- [x] Scheduler sends binding requests
- [x] Adapter logs show "Parsed protobuf binding: pod=cspod-X -> node=csnode-Y"
- [x] Pods are successfully bound to nodes
- [x] CloudSim test completes with all cloudlets executed
- [x] No "invalid character 'k'" errors in logs
- [x] Scheduler logs show successful bindings (no "FailedScheduling" events)

## Related Files
- `cloudsim-experimental/k8s-cloudsim-adapter/fakeapi/handlers.go` - HandleBinding function
- `cloudsim-experimental/k8s-cloudsim-adapter/main.go` - Route registration
- `cloudsim-experimental/second-scheduler/kubeconfig.yaml` - Scheduler config pointing to adapter
- `k8s-in-the-loop/misim-k8s-adapter/fakeapi/handlers.go` - Reference implementation

## References
- Kubernetes API Conventions: https://github.com/kubernetes/community/blob/master/contributors/devel/sig-architecture/api-conventions.md
- Protobuf Serialization: https://kubernetes.io/docs/reference/using-api/api-concepts/#protobuf-encoding
- k8s-in-the-loop Paper: EAI VALUETOOLS 2023 (Straesser et al.)
