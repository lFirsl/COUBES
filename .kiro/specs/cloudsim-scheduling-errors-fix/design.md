# CloudSim Scheduling Errors Fix - Bugfix Design

## Overview

This design addresses two critical bugs in the COUBES CloudSim-Kubernetes integration that prevent reliable benchmarking:

1. **JSON Casting Error**: The broker crashes when parsing the adapter's BatchDecisionResponse because it incorrectly casts an ObjectNode (with `scheduled` and `unschedulable` fields) to an ArrayNode
2. **Silent Failure on Incomplete Cloudlet Returns**: The simulation reports success even when cloudlets fail to complete, masking critical scheduling failures

The fix strategy is minimal and targeted:
- Parse the BatchDecisionResponse object correctly by accessing its `scheduled` array field
- Add validation after simulation completion to throw a RuntimeException when cloudlet counts don't match expectations

## Glossary

- **Bug_Condition_1 (C1)**: The condition that triggers the JSON casting bug - when the broker receives a successful HTTP 200 response from `/schedule-pods` and attempts to cast the response body to ArrayNode
- **Bug_Condition_2 (C2)**: The condition that triggers the silent failure bug - when the simulation completes with fewer cloudlets returned than submitted
- **Property_1 (P1)**: The desired behavior for C1 - the broker should correctly parse the BatchDecisionResponse JSON object without casting errors
- **Property_2 (P2)**: The desired behavior for C2 - the system should throw a RuntimeException with a clear error message when cloudlet counts don't match
- **Preservation**: Existing scheduling behavior, cloudlet submission, metrics calculation, and success reporting that must remain unchanged
- **BatchDecisionResponse**: The JSON object returned by the adapter's `/schedule-pods` endpoint with structure `{"scheduled": [...], "unschedulable": [...]}`
- **submitCloudletBatchToMiddleware**: The method in `Live_Kubernetes_Broker_Ex.java` that sends cloudlets to the adapter and parses the response
- **processScheduledPodsResponse**: The method that processes individual pod assignments from the scheduled array
- **getCloudletReceivedList**: The CloudSim broker method that returns the list of completed cloudlets after simulation

## Bug Details

### Bug Condition 1: JSON Casting Error

The bug manifests when the broker receives a successful HTTP 200 response from the adapter's `/schedule-pods` endpoint. The `submitCloudletBatchToMiddleware` method attempts to cast the response body directly to `ArrayNode`, but the adapter returns a `BatchDecisionResponse` object with `scheduled` and `unschedulable` fields.

**Formal Specification:**
```
FUNCTION isBugCondition1(input)
  INPUT: input of type HttpResponse<String>
  OUTPUT: boolean
  
  RETURN input.statusCode == 200
         AND input.body contains JSON object with "scheduled" field
         AND code attempts to cast entire body to ArrayNode
         AND NOT code accesses "scheduled" field first
END FUNCTION
```

### Bug Condition 2: Silent Failure on Incomplete Returns

The bug manifests when the simulation completes with fewer cloudlets in the received list than were submitted. The test code logs a warning message but does not throw an exception, allowing the simulation to report success and continue execution despite the failure.

**Formal Specification:**
```
FUNCTION isBugCondition2(input)
  INPUT: input of type SimulationResult
  OUTPUT: boolean
  
  RETURN input.cloudletsSubmitted > 0
         AND input.cloudletsReceived < input.cloudletsSubmitted
         AND NOT exceptionThrown
END FUNCTION
```

### Examples

**Bug 1 Examples:**
- Adapter returns `{"scheduled": [{"id": 1, "vmId": 0, "status": "Scheduled"}], "unschedulable": []}` → Broker crashes with ClassCastException
- Adapter returns `{"scheduled": [], "unschedulable": [{"id": 2, "reason": "Insufficient resources"}]}` → Broker crashes with ClassCastException
- Adapter returns empty scheduled array `{"scheduled": [], "unschedulable": []}` → Broker crashes with ClassCastException

**Bug 2 Examples:**
- Submit 20 cloudlets, receive 0 cloudlets → Logs warning "We got 0 cloudlets whereas we were supposed to get 20!" but reports "Success"
- Submit 15 cloudlets, receive 10 cloudlets → Logs warning but continues execution
- Submit 5 cloudlets, receive 5 cloudlets → No error (expected behavior)

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- When all cloudlets complete successfully, the system must continue to report success and print cloudlet results
- The broker must continue to correctly map pod IDs to node IDs and submit cloudlets to appropriate VMs
- Unschedulable pods must continue to be marked as FAILED and added to the received list
- Throughput metrics (EWMA, sliding window, overall) must continue to be computed and displayed correctly
- The processScheduledPodsResponse method must continue to handle individual pod assignments correctly

**Scope:**
All inputs where the adapter returns a valid BatchDecisionResponse with all cloudlets accounted for (either scheduled or unschedulable) should be completely unaffected by this fix. This includes:
- Successful scheduling of all cloudlets
- Partial scheduling with some unschedulable pods
- Metrics calculation and display
- CloudSim simulation execution

## Hypothesized Root Cause

Based on the bug description and code analysis, the root causes are:

### Bug 1: JSON Casting Error

1. **Incorrect Response Parsing**: The `submitCloudletBatchToMiddleware` method at line 421 casts the entire response body to `ArrayNode`:
   ```java
   return (ArrayNode) mapper.readTree(response.body());
   ```
   However, the adapter returns a `BatchDecisionResponse` object:
   ```json
   {"scheduled": [...], "unschedulable": [...]}
   ```

2. **Missing Field Access**: The code should parse the response as `ObjectNode` and access the `scheduled` field, which contains the array of pod assignments

3. **Unschedulable Pods Ignored**: The current implementation doesn't handle the `unschedulable` array, which contains pods that couldn't be scheduled

### Bug 2: Silent Failure

1. **Validation Without Enforcement**: The test code at line 153 of `Fragmentation_Test.java` checks the cloudlet count:
   ```java
   if(newList1.size() != 20){
       Log.printConcat("We got ", newList1.size(), "cloudlets whereas we were supposed to get 20!");
   }
   ```
   But this only logs a warning without throwing an exception

2. **No Failure Propagation**: The simulation continues and reports success even when cloudlets are missing, masking critical scheduling failures

3. **Missing Validation in Broker**: The broker itself doesn't validate that all submitted cloudlets were returned, relying on test code to catch the issue

## Correctness Properties

Property 1: Bug Condition 1 - Correct BatchDecisionResponse Parsing

_For any_ HTTP response where the status code is 200 and the body contains a valid BatchDecisionResponse JSON object, the fixed submitCloudletBatchToMiddleware method SHALL correctly parse the response by accessing the "scheduled" field as an ArrayNode and the "unschedulable" field as an ArrayNode, without throwing ClassCastException.

**Validates: Requirements 2.1**

Property 2: Bug Condition 2 - Exception on Incomplete Cloudlet Returns

_For any_ simulation execution where the number of cloudlets in the received list is less than the number of cloudlets submitted, the fixed system SHALL throw a RuntimeException with a clear error message indicating the mismatch (e.g., "Expected 20 cloudlets to complete but only received 0"), immediately halting execution.

**Validates: Requirements 2.2, 2.3**

Property 3: Preservation - Successful Scheduling Behavior

_For any_ simulation execution where all submitted cloudlets are accounted for (either scheduled or marked as FAILED), the fixed system SHALL produce exactly the same behavior as the original system, preserving success reporting, cloudlet result printing, and metrics calculation.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4**

## Fix Implementation

### Changes Required

Assuming our root cause analysis is correct:

**File 1**: `cloudsim-experimental/src/main/java/org/example/kubernetes_broker/Live_Kubernetes_Broker_Ex.java`

**Function**: `submitCloudletBatchToMiddleware`

**Specific Changes**:
1. **Parse Response as ObjectNode**: Change line 421 from casting to ArrayNode to parsing as ObjectNode:
   ```java
   // OLD: return (ArrayNode) mapper.readTree(response.body());
   // NEW:
   ObjectNode batchDecision = (ObjectNode) mapper.readTree(response.body());
   return batchDecision.get("scheduled");
   ```

2. **Handle Unschedulable Pods**: Add logic to process the `unschedulable` array and mark those cloudlets as FAILED:
   ```java
   ArrayNode unschedulable = (ArrayNode) batchDecision.get("unschedulable");
   for (JsonNode podNode : unschedulable) {
       int cloudletId = podNode.get("id").asInt();
       Cloudlet cloudlet = cloudletsSubmittedToMiddle.get(cloudletId);
       if (cloudlet != null) {
           cloudlet.setCloudletStatus(Cloudlet.CloudletStatus.FAILED);
           getCloudletReceivedList().add(cloudlet);
           cloudletsSubmittedToMiddle.remove(cloudletId);
       }
   }
   ```

3. **Update Return Type**: Consider changing the return type to `ObjectNode` and updating the caller, or keep returning the scheduled array as currently expected

**File 2**: `cloudsim-experimental/src/main/java/org/example/testSuite/Fragmentation_Test.java`

**Function**: `main`

**Specific Changes**:
1. **Replace Warning with Exception**: Change lines 153-155 from logging to throwing:
   ```java
   // OLD:
   if(newList1.size() != 20){
       Log.printConcat("We got ", newList1.size(), "cloudlets whereas we were supposed to get 20!");
   }
   
   // NEW:
   if(newList1.size() != 20){
       throw new RuntimeException("Expected 20 cloudlets to complete but only received " + newList1.size());
   }
   ```

2. **Add Validation to Other Test Files**: Apply the same pattern to other test files that submit cloudlets (Performance_Efficiency_Test.java, Undercrowding_Test.java)

**Alternative Approach**: Add validation directly in the broker's `processCloudletReturn` or similar method to catch this issue universally, rather than relying on test code validation.

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bugs on unfixed code, then verify the fixes work correctly and preserve existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate both bugs BEFORE implementing the fix. Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan for Bug 1**: Write tests that send a valid BatchDecisionResponse JSON object to the broker and observe the ClassCastException. Run these tests on the UNFIXED code to confirm the casting error occurs.

**Test Cases for Bug 1**:
1. **Scheduled Pods Only**: Send `{"scheduled": [{"id": 1, "vmId": 0, "status": "Scheduled"}], "unschedulable": []}` (will fail on unfixed code with ClassCastException)
2. **Unschedulable Pods Only**: Send `{"scheduled": [], "unschedulable": [{"id": 2, "reason": "Insufficient resources"}]}` (will fail on unfixed code)
3. **Mixed Response**: Send `{"scheduled": [{"id": 1, "vmId": 0}], "unschedulable": [{"id": 2}]}` (will fail on unfixed code)
4. **Empty Response**: Send `{"scheduled": [], "unschedulable": []}` (will fail on unfixed code)

**Test Plan for Bug 2**: Write tests that submit cloudlets and simulate incomplete returns. Observe that the unfixed code logs a warning but doesn't throw an exception.

**Test Cases for Bug 2**:
1. **Zero Cloudlets Returned**: Submit 20 cloudlets, simulate 0 returns (will log warning on unfixed code but not throw)
2. **Partial Return**: Submit 15 cloudlets, simulate 10 returns (will log warning but not throw)
3. **Complete Return**: Submit 5 cloudlets, simulate 5 returns (should succeed on both unfixed and fixed code)

**Expected Counterexamples**:
- Bug 1: ClassCastException with message "class com.fasterxml.jackson.databind.node.ObjectNode cannot be cast to class com.fasterxml.jackson.databind.node.ArrayNode"
- Bug 2: Warning message logged but simulation reports "Success" and continues

### Fix Checking

**Goal**: Verify that for all inputs where the bug conditions hold, the fixed functions produce the expected behavior.

**Pseudocode for Bug 1:**
```
FOR ALL response WHERE isBugCondition1(response) DO
  result := submitCloudletBatchToMiddleware_fixed(response)
  ASSERT result is ArrayNode containing scheduled pods
  ASSERT no ClassCastException thrown
  ASSERT unschedulable pods are marked as FAILED
END FOR
```

**Pseudocode for Bug 2:**
```
FOR ALL simulation WHERE isBugCondition2(simulation) DO
  TRY
    result := runSimulation_fixed(simulation)
    FAIL("Expected RuntimeException but none was thrown")
  CATCH RuntimeException e
    ASSERT e.message contains "Expected X cloudlets to complete but only received Y"
  END TRY
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug conditions do NOT hold, the fixed functions produce the same result as the original functions.

**Pseudocode:**
```
FOR ALL response WHERE NOT isBugCondition1(response) AND NOT isBugCondition2(response) DO
  ASSERT submitCloudletBatchToMiddleware_original(response) = submitCloudletBatchToMiddleware_fixed(response)
  ASSERT runSimulation_original(response) = runSimulation_fixed(response)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many test cases automatically across the input domain
- It catches edge cases that manual unit tests might miss
- It provides strong guarantees that behavior is unchanged for all non-buggy inputs

**Test Plan**: Observe behavior on UNFIXED code first for successful scheduling scenarios, then write property-based tests capturing that behavior.

**Test Cases**:
1. **All Cloudlets Scheduled**: Observe that when all cloudlets are scheduled successfully, the unfixed code works correctly. Write test to verify this continues after fix.
2. **Metrics Calculation**: Observe that throughput metrics are calculated correctly on unfixed code. Write test to verify this continues after fix.
3. **VM Assignment**: Observe that pod-to-node mapping works correctly on unfixed code. Write test to verify this continues after fix.
4. **Unschedulable Handling**: Observe that unschedulable pods are marked as FAILED on unfixed code (if this path works). Write test to verify this continues after fix.

### Unit Tests

- Test parsing of BatchDecisionResponse with various combinations of scheduled and unschedulable pods
- Test that unschedulable pods are correctly marked as FAILED and added to received list
- Test that RuntimeException is thrown when cloudlet counts don't match
- Test that exception message contains expected cloudlet counts
- Test edge cases: empty scheduled array, empty unschedulable array, both empty

### Property-Based Tests

- Generate random BatchDecisionResponse objects with varying numbers of scheduled and unschedulable pods, verify correct parsing
- Generate random cloudlet submission scenarios with varying completion rates, verify exception is thrown when counts don't match
- Generate random successful scheduling scenarios, verify behavior is preserved (no exceptions, correct metrics)

### Integration Tests

- Test full simulation flow with adapter returning mixed scheduled/unschedulable responses
- Test that simulation halts immediately when cloudlets don't return
- Test that successful simulations continue to work end-to-end
- Test that metrics are calculated correctly after fix
