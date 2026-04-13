# Bug Condition Exploration Results

## Test Execution Summary

**Date**: 2026-04-13
**Status**: Tests written and executed on UNFIXED code
**Result**: Bug conditions successfully demonstrated

## Bug 1: JSON Casting Error

### Counterexamples Found

The bug condition exploration tests demonstrate that casting a BatchDecisionResponse JSON object to ArrayNode ALWAYS throws `ClassCastException`, regardless of the content:

**Test Case 1: Mixed scheduled and unschedulable pods**
```json
{
    "scheduled": [
        {"podId": 1, "nodeId": 0, "bindingTimestamp": "2024-01-01T00:00:00Z"}
    ],
    "unschedulable": [
        {"podId": 2, "reason": "Insufficient resources"}
    ]
}
```
**Result**: `ClassCastException: class com.fasterxml.jackson.databind.node.ObjectNode cannot be cast to class com.fasterxml.jackson.databind.node.ArrayNode`

**Test Case 2: Scheduled pods only**
```json
{
    "scheduled": [
        {"podId": 1, "nodeId": 0, "bindingTimestamp": "2024-01-01T00:00:00Z"}
    ],
    "unschedulable": []
}
```
**Result**: `ClassCastException` (same error)

**Test Case 3: Unschedulable pods only**
```json
{
    "scheduled": [],
    "unschedulable": [
        {"podId": 2, "reason": "Insufficient resources"}
    ]
}
```
**Result**: `ClassCastException` (same error)

**Test Case 4: Empty response**
```json
{
    "scheduled": [],
    "unschedulable": []
}
```
**Result**: `ClassCastException` (same error)

### Root Cause Confirmed

The bug exists in `Live_Kubernetes_Broker_Ex.java` at line 421 in the `submitCloudletBatchToMiddleware` method:

```java
return (ArrayNode) mapper.readTree(response.body());
```

This line attempts to cast the entire response body (which is an ObjectNode with "scheduled" and "unschedulable" fields) to ArrayNode, which always fails.

### Expected Fix

The correct approach is to parse as ObjectNode first, then access the "scheduled" field:

```java
ObjectNode batchDecision = (ObjectNode) mapper.readTree(response.body());
return batchDecision.get("scheduled");
```

## Bug 2: Silent Failure on Incomplete Cloudlet Returns

### Counterexamples Found

The bug condition exploration tests encode the EXPECTED behavior (throwing RuntimeException when cloudlet counts don't match). The actual bug is in `Fragmentation_Test.java` at line 153.

**Test Case 1: Zero cloudlets returned**
- **Submitted**: 20 cloudlets
- **Received**: 0 cloudlets
- **Expected behavior**: Throw `RuntimeException` with message "Expected 20 cloudlets to complete but only received 0"
- **Actual behavior (UNFIXED)**: Logs warning "We got 0 cloudlets whereas we were supposed to get 20!" but continues execution

**Test Case 2: Partial cloudlet return**
- **Submitted**: 15 cloudlets
- **Received**: 10 cloudlets
- **Expected behavior**: Throw `RuntimeException` with message "Expected 15 cloudlets to complete but only received 10"
- **Actual behavior (UNFIXED)**: Logs warning but continues execution

**Test Case 3: Complete cloudlet return (preservation)**
- **Submitted**: 5 cloudlets
- **Received**: 5 cloudlets
- **Expected behavior**: No exception thrown, simulation reports success
- **Actual behavior**: Works correctly (this behavior must be preserved)

### Root Cause Confirmed

The bug exists in `Fragmentation_Test.java` at line 153:

```java
if(newList1.size() != 20){
    Log.printConcat("We got ", newList1.size(), "cloudlets whereas we were supposed to get 20!");
}
```

This code only logs a warning instead of throwing an exception, allowing the simulation to report success even when cloudlets fail to complete.

### Expected Fix

Replace the warning log with a RuntimeException:

```java
if(newList1.size() != 20){
    throw new RuntimeException("Expected 20 cloudlets to complete but only received " + newList1.size());
}
```

## Test Files Created

1. **BugConditionExplorationTest.java**: Tests demonstrating Bug 1 (JSON casting error)
   - 4 unit tests covering different JSON response scenarios
   - 1 property-based test generating 20 random BatchDecisionResponse combinations
   - All tests demonstrate that ArrayNode casting fails while ObjectNode parsing succeeds

2. **SilentFailureExplorationTest.java**: Tests encoding expected behavior for Bug 2
   - 3 unit tests covering zero returns, partial returns, and complete returns
   - 2 property-based tests generating 50+ random scenarios each
   - Tests encode the expected behavior (throwing RuntimeException on mismatch)

## Next Steps

1. Implement fixes for both bugs as specified in tasks.md
2. Re-run these same tests after implementing fixes
3. Verify that Bug 1 tests still pass (ObjectNode parsing works)
4. Verify that Bug 2 tests still pass (RuntimeException is thrown on mismatch)
5. Run preservation tests to ensure no regressions

## Notes

- Bug 1 exists in dead code (`submitCloudletBatchToMiddleware` is not currently called), but the bug is real and would manifest if this method were used
- Bug 2 is actively affecting the test suite, allowing simulations to report success when they should fail
- Both bugs are confirmed and ready for implementation of fixes
