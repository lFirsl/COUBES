# Bugfix Requirements Document

## Introduction

This document specifies the requirements for fixing two critical bugs in the COUBES CloudSim-Kubernetes integration:

1. **JSON Casting Error During Scheduling**: The broker crashes when processing the adapter's response due to incorrect JSON type casting (ObjectNode vs ArrayNode)
2. **Silent Failure on Incomplete Cloudlet Returns**: The simulation reports success even when cloudlets fail to complete, masking critical scheduling failures

These bugs affect the reliability and correctness of the COUBES framework, preventing accurate benchmarking of Kubernetes schedulers.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN the broker receives a successful HTTP 200 response from the adapter's `/schedule` endpoint THEN the system crashes with "class com.fasterxml.jackson.databind.node.ObjectNode cannot be cast to class com.fasterxml.jackson.databind.node.ArrayNode"

1.2 WHEN the simulation completes with fewer cloudlets returned than expected (e.g., 0 cloudlets returned when 20 were submitted) THEN the system logs a warning message but reports "Success" and continues execution

1.3 WHEN cloudlets fail to return properly during simulation THEN the system does not throw an error or halt execution to alert the user of the failure

### Expected Behavior (Correct)

2.1 WHEN the broker receives a successful HTTP 200 response from the adapter's `/schedule` endpoint THEN the system SHALL correctly parse the BatchDecisionResponse JSON object without casting errors

2.2 WHEN the simulation completes with fewer cloudlets returned than expected THEN the system SHALL throw a RuntimeException with a clear error message indicating the mismatch (e.g., "Expected 20 cloudlets to complete but only received 0")

2.3 WHEN cloudlets fail to return properly during simulation THEN the system SHALL immediately halt execution and report the failure to prevent silent data corruption

### Unchanged Behavior (Regression Prevention)

3.1 WHEN the broker successfully schedules all cloudlets and all cloudlets complete normally THEN the system SHALL CONTINUE TO report success and print the cloudlet results

3.2 WHEN the broker processes scheduled pod assignments from the adapter THEN the system SHALL CONTINUE TO correctly map pod IDs to node IDs and submit cloudlets to the appropriate VMs

3.3 WHEN the broker handles unschedulable pods from the adapter THEN the system SHALL CONTINUE TO mark them as FAILED and add them to the received list

3.4 WHEN the broker calculates throughput metrics (EWMA, sliding window, overall) THEN the system SHALL CONTINUE TO compute and display these metrics correctly
