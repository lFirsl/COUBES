package org.example.testSuite;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.power.models.PowerModelLinear;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.example.kubernetes_broker.CoubesCloudlet;
import org.example.kubernetes_broker.Live_Kubernetes_Broker_Ex;
import org.example.kubernetes_broker.PowerDatacenterCustom;
import org.example.kubernetes_broker.PowerVmCustom;
import org.example.metrics.BoundsCalculator;
import org.example.metrics.BoundsCalculator.*;
import org.example.metrics.SDS;
import org.example.metrics.SimulationMetrics;

import java.text.DecimalFormat;
import java.util.*;

/**
 * Mixed Workload Test — combines heterogeneous VMs, affinity/anti-affinity,
 * multi-PE cloudlets, RAM constraints, and multi-wave arrival to stress-test
 * the bounds calculator and expose scheduler tradeoffs.
 *
 * Infrastructure: 4 heterogeneous VMs on 2 hosts
 *   Host 0: 8 PE × 500 MIPS, 2048 MB RAM, 800W max (10% static) — fast, power-hungry
 *   Host 1: 8 PE × 500 MIPS, 2048 MB RAM, 800W max (10% static)
 *   Host 2: 4 PE × 250 MIPS, 1024 MB RAM, 300W max (20% static) — slow, efficient
 *   Host 3: 4 PE × 250 MIPS, 1024 MB RAM, 300W max (20% static)
 *
 * Wave 1 (t=0) — 8 cloudlets:
 *   3× "web" pods:    1 PE, 256 MB, length 50000, anti-affinity "web" (HA spread)
 *   2× "cache" pods:  2 PE, 512 MB, length 80000, affinity "cache" (co-locate)
 *   3× "worker" pods: 3 PE, 256 MB, length 100000, no constraints
 *
 * Wave 2 (t=100) — 5 cloudlets:
 *   2× "batch" pods:  4 PE, 512 MB, length 200000, no constraints
 *   3× "web-v2" pods: 1 PE, 256 MB, length 50000, anti-affinity "webv2"
 *
 * Key tensions:
 *   - Anti-affinity forces web pods across ≥3 VMs
 *   - Affinity forces cache pair onto one VM (needs 4 PE + 1024 MB → only 8-PE VMs)
 *   - 3-PE workers compete for space with cache pair
 *   - Wave-2 batch pods (4 PE) need big VMs that may be fragmented
 *   - Heterogeneous MIPS: 250 vs 500 → 2× TTC difference for same workload
 */
public class Mixed_Workload_Test {

    public static void main(String[] args) {
        Log.println("Starting Mixed_Workload_Test...");
        try {
            CloudSim.init(2, Calendar.getInstance(), false);
            PowerDatacenterCustom dc = createDatacenter("Datacenter_0");
            dc.setLogLevel(PowerDatacenterCustom.LogLevel.QUIET);

            Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0", -1);
            int bid = broker.getId();

            // ── VMs ──
            List<Vm> vms = List.of(
                new PowerVmCustom(0, bid, 500, 8, 2048, 1000, 10000, 0, "Xen", new CloudletSchedulerTimeShared(), 1, -1),
                new PowerVmCustom(1, bid, 500, 8, 2048, 1000, 10000, 0, "Xen", new CloudletSchedulerTimeShared(), 1, -1),
                new PowerVmCustom(2, bid, 250, 4, 1024, 1000, 10000, 0, "Xen", new CloudletSchedulerTimeShared(), 1, -1),
                new PowerVmCustom(3, bid, 250, 4, 1024, 1000, 10000, 0, "Xen", new CloudletSchedulerTimeShared(), 1, -1)
            );
            broker.submitGuestList(new ArrayList<>(vms));

            UtilizationModel um = new UtilizationModelFull();

            // ── Wave 1 ──
            List<Cloudlet> wave1 = new ArrayList<>();
            // 3× web: anti-affinity "web"
            for (int i = 0; i < 3; i++) {
                CoubesCloudlet cl = new CoubesCloudlet(i, 50000, 1, 300, 300, um, um, um,
                        256, Collections.emptyMap(), null, "web");
                cl.setUserId(bid);
                wave1.add(cl);
            }
            // 2× cache: affinity "cache"
            for (int i = 3; i < 5; i++) {
                CoubesCloudlet cl = new CoubesCloudlet(i, 80000, 2, 300, 300, um, um, um,
                        512, Collections.emptyMap(), "cache", null);
                cl.setUserId(bid);
                wave1.add(cl);
            }
            // 3× worker: no constraints
            for (int i = 5; i < 8; i++) {
                CoubesCloudlet cl = new CoubesCloudlet(i, 100000, 3, 300, 300, um, um, um,
                        256, Collections.emptyMap(), null, null);
                cl.setUserId(bid);
                wave1.add(cl);
            }
            broker.submitCloudletList(new ArrayList<>(wave1));

            // ── Wave 2 ──
            List<Cloudlet> wave2 = new ArrayList<>();
            // 2× batch: no constraints
            for (int i = 0; i < 2; i++) {
                CoubesCloudlet cl = new CoubesCloudlet(100 + i, 200000, 4, 300, 300, um, um, um,
                        512, Collections.emptyMap(), null, null);
                cl.setUserId(bid);
                wave2.add(cl);
            }
            // 3× web-v2: anti-affinity "webv2"
            for (int i = 2; i < 5; i++) {
                CoubesCloudlet cl = new CoubesCloudlet(100 + i, 50000, 1, 300, 300, um, um, um,
                        256, Collections.emptyMap(), null, "webv2");
                cl.setUserId(bid);
                wave2.add(cl);
            }
            broker.submitCloudletList(new ArrayList<>(wave2), 100);

            // ── Bounds ──
            List<VmSpec> vmSpecs = List.of(
                new VmSpec(0, 8, 500, 2048, 800, 0.1),
                new VmSpec(1, 8, 500, 2048, 800, 0.1),
                new VmSpec(2, 4, 250, 1024, 300, 0.2),
                new VmSpec(3, 4, 250, 1024, 300, 0.2)
            );
            List<CloudletSpec> w1Specs = wave1.stream().map(cl -> {
                CoubesCloudlet cc = (CoubesCloudlet) cl;
                return new CloudletSpec(cl.getCloudletId(), cl.getCloudletLength(),
                        cl.getNumberOfPes(), cc.getRamRequest(), cc.getAffinityGroup(), cc.getAntiAffinityGroup());
            }).toList();
            List<CloudletSpec> w2Specs = wave2.stream().map(cl -> {
                CoubesCloudlet cc = (CoubesCloudlet) cl;
                return new CloudletSpec(cl.getCloudletId(), cl.getCloudletLength(),
                        cl.getNumberOfPes(), cc.getRamRequest(), cc.getAffinityGroup(), cc.getAntiAffinityGroup());
            }).toList();
            TheoreticalBounds bounds = BoundsCalculator.compute(vmSpecs,
                    List.of(new Wave(w1Specs, 0.0), new Wave(w2Specs, 100.0)), null);
            System.out.println("── Theoretical Bounds ──");
            System.out.println(bounds);

            // ── Run ──
            CloudSim.resumeSimulation();
            SimulationMetrics metrics = new SimulationMetrics(dc, new ArrayList<>(vms));
            metrics.startWallClock();
            double lastClock = CloudSim.startSimulation();

            List<Cloudlet> results = broker.getCloudletReceivedList();
            CloudSim.stopSimulation();
            metrics.stopWallClock();

            Log.println("Completed: " + results.size() + " / 13 cloudlets");
            printCloudletList(results);
            metrics.printSummary(lastClock, broker.tpOverall(), broker.tpPeak());

            // ── SDS ──
            double actualEnergy = dc.getPower() / 3600.0;
            double actualConsolidation = dc.getConsolidationAverage(lastClock);
            SDS.Result sds = SDS.compute(bounds, lastClock, actualEnergy, actualConsolidation);
            System.out.println("── SDS Result ──");
            System.out.println(sds);

            broker.sendResetRequestToControlPlane();
            Log.println("Mixed_Workload_Test finished!");
        } catch (Exception e) {
            e.printStackTrace();
            Log.println("Simulation terminated due to an unexpected error");
        }
    }

    private static PowerDatacenterCustom createDatacenter(String name) {
        List<Host> hostList = new ArrayList<>();
        int[][] hostConfigs = {
            // {numPes, mips, ram, maxWatts*10, staticPct*100}
            {8, 500, 2048, 8000, 10},
            {8, 500, 2048, 8000, 10},
            {4, 250, 1024, 3000, 20},
            {4, 250, 1024, 3000, 20}
        };
        for (int h = 0; h < hostConfigs.length; h++) {
            int[] cfg = hostConfigs[h];
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < cfg[0]; p++)
                peList.add(new Pe(p, new PeProvisionerSimple(cfg[1])));
            hostList.add(new PowerHost(h,
                    new RamProvisionerSimple(cfg[2]),
                    new BwProvisionerSimple(10000),
                    1000000, peList,
                    new VmSchedulerTimeShared(peList),
                    new PowerModelLinear(cfg[3] / 10.0, cfg[4] / 100.0)));
        }
        DatacenterCharacteristics chars = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);
        try {
            PowerDatacenterCustom dc = new PowerDatacenterCustom(name, chars,
                    new VmAllocationPolicySimple(hostList), new LinkedList<>(), 1, true);
            dc.setDisableMigrations(true);
            return dc;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void printCloudletList(List<Cloudlet> list) {
        String indent = "    ";
        Log.println();
        Log.println("========== OUTPUT ==========");
        Log.println("Cloudlet ID" + indent + "STATUS" + indent + "VM ID" + indent +
                "Time" + indent + "Start" + indent + "Finish");
        DecimalFormat dft = new DecimalFormat("###.##");
        for (Cloudlet c : list) {
            if (c.getStatus() == Cloudlet.CloudletStatus.SUCCESS)
                Log.println(indent + c.getCloudletId() + indent + "SUCCESS" + indent +
                        c.getGuestId() + indent + dft.format(c.getActualCPUTime()) +
                        indent + dft.format(c.getExecStartTime()) + indent + dft.format(c.getExecFinishTime()));
        }
    }
}
