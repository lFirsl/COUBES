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
import org.example.metrics.SimulationMetrics;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

/**
 * Memory Fragmentation Test — designed to produce different outcomes under
 * LeastAllocated (spreading) vs MostAllocated (bin-packing) scheduling.
 *
 * Cluster: 4 VMs × 4 PEs × 1024 MB RAM each (4096 MB total).
 *
 * Wave 1 (t=0): 4 pods × 1 PE × 384 MB RAM (1536 MB total).
 *   - LeastAllocated: 1 pod per VM → each VM has 640 MB free.
 *   - MostAllocated:  2 pods on VM0, 2 on VM1 → VM0/VM1 have 256 MB free, VM2/VM3 have 1024 MB free.
 *
 * Wave 2 (t=50): 4 pods × 1 PE × 640 MB RAM (2560 MB total, fits in 2560 MB cluster free).
 *   - LeastAllocated: each VM has 640 MB free → all 4 pods fit immediately. No rescheduling.
 *   - MostAllocated:  VM0/VM1 have only 256 MB free (can't fit 640 MB pod).
 *                     VM2/VM3 have 1024 MB free → only 2 pods fit. 2 pods must wait
 *                     for wave 1 to complete and free RAM before rescheduling.
 *
 * This demonstrates memory fragmentation: the total free RAM is sufficient, but
 * bin-packing scatters it into unusable fragments.
 *
 * Run with:
 *   bash run_test.sh --test-mode org.example.testSuite.Memory_Fragmentation_Test
 *     → round-robin spreading: all wave-2 pods fit immediately (like LeastAllocated)
 *   bash run_test.sh org.example.testSuite.Memory_Fragmentation_Test
 *     → with --scheduler=default-scheduler: LeastAllocated, all fit immediately
 *   bash run_test.sh org.example.testSuite.Memory_Fragmentation_Test
 *     → with --scheduler=my-scheduler: MostAllocated, 2 wave-2 pods delayed
 *
 * See docs/memory-fragmentation-test.md for full design rationale.
 */
public class Memory_Fragmentation_Test {
	public static Live_Kubernetes_Broker_Ex broker;

	private static List<Vm> createVM(int userId, int vms, int idShift) {
		LinkedList<Vm> list = new LinkedList<>();
		long size = 10000;
		int ram = 1024;       // 1024 MB — the resource that fragments
		int mips = 250;
		long bw = 1000;
		int pesNumber = 4;    // plenty of CPU headroom
		String vmm = "Xen";

		for (int i = 0; i < vms; i++) {
			list.add(new PowerVmCustom(idShift + i, userId, mips, pesNumber, ram, bw, size,
					0, vmm, new CloudletSchedulerTimeShared(), 500, -1));
		}
		return list;
	}

	private static List<Cloudlet> createCloudlet(int userId, int cloudlets, int length, int pes, int ramMB, int idShift) {
		LinkedList<Cloudlet> list = new LinkedList<>();
		long fileSize = 300;
		long outputSize = 300;
		UtilizationModel utilizationModel = new UtilizationModelFull();

		for (int i = 0; i < cloudlets; i++) {
			Cloudlet cl = new CoubesCloudlet(idShift + i, length, pes, fileSize, outputSize,
					utilizationModel, utilizationModel, utilizationModel, ramMB);
			cl.setUserId(userId);
			list.add(cl);
		}
		return list;
	}

	public static void main(String[] args) {
		Log.println("Starting Memory_Fragmentation_Test...");

		try {
			CloudSim.init(2, Calendar.getInstance(), false);

			PowerDatacenterCustom datacenter0 = createDatacenter("Datacenter_0");
			datacenter0.setLogLevel(PowerDatacenterCustom.LogLevel.QUIET);

			broker = new Live_Kubernetes_Broker_Ex("Broker_0", -1);
			int brokerId = broker.getId();

			List<Vm> vmlist = createVM(brokerId, 4, 0);
			List<Cloudlet> allCloudlets = new ArrayList<>();

			// Wave 1: 4 pods × 1 PE × 384 MB — uses 1536 of 4096 MB total
			List<Cloudlet> wave1 = createCloudlet(brokerId, 4, 40000, 1, 384, 0);
			allCloudlets.addAll(wave1);

			// Wave 2: 4 pods × 1 PE × 640 MB — needs 2560 MB, exactly matches free capacity
			List<Cloudlet> wave2 = createCloudlet(brokerId, 4, 40000, 1, 640, 100);
			allCloudlets.addAll(wave2);

			broker.submitGuestList(vmlist);
			broker.submitCloudletList(wave1);
			broker.submitCloudletList(wave2, 50);

			CloudSim.resumeSimulation();

			SimulationMetrics metrics = new SimulationMetrics(datacenter0, vmlist);
			metrics.startWallClock();
			double lastClock = CloudSim.startSimulation();

			List<Cloudlet> results = broker.getCloudletReceivedList();
			CloudSim.stopSimulation();
			metrics.stopWallClock();

			if (results.size() != 8) {
				throw new RuntimeException("Expected 8 cloudlets to complete but only received " + results.size());
			}
			printCloudletList(results);
			metrics.printSummary(lastClock, broker.tpOverall(), broker.getRoundCount(), 2);

			broker.sendResetRequestToControlPlane();
			Log.println("Memory_Fragmentation_Test finished!");
		} catch (Exception e) {
			e.printStackTrace();
			Log.println("The simulation has been terminated due to an unexpected error");
		}
	}

	private static PowerDatacenterCustom createDatacenter(String name) {
		List<Host> hostList = new ArrayList<>();
		int mips = 250;
		int hostId = 0;
		int ram = 16384;
		long storage = 1000000;
		int bw = 10000;

		for (int x = 0; x < 4; x++) {
			List<Pe> peList = new ArrayList<>();
			for (int p = 0; p < 4; p++) {
				peList.add(new Pe(p, new PeProvisionerSimple(mips)));
			}
			hostList.add(new PowerHost(hostId++,
					new RamProvisionerSimple(ram), new BwProvisionerSimple(bw),
					storage, peList, new VmSchedulerTimeShared(peList),
					new PowerModelLinear(500, 0.1)));
		}

		DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
				"x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);

		PowerDatacenterCustom datacenter = null;
		try {
			datacenter = new PowerDatacenterCustom(name, characteristics,
					new VmAllocationPolicySimple(hostList), new LinkedList<>(), 1, true);
			datacenter.setDisableMigrations(true);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return datacenter;
	}

	private static void printCloudletList(List<Cloudlet> list) {
		String indent = "    ";
		Log.println();
		Log.println("========== OUTPUT ==========");
		Log.println("Cloudlet ID" + indent + "STATUS" + indent +
				"Data center ID" + indent + "VM ID" + indent + indent +
				"Time" + indent + "Start Time" + indent + "Finish Time");

		DecimalFormat dft = new DecimalFormat("###.##");
		for (Cloudlet cloudlet : list) {
			Log.print(indent + cloudlet.getCloudletId() + indent + indent);
			if (cloudlet.getStatus() == Cloudlet.CloudletStatus.SUCCESS) {
				Log.print("SUCCESS");
				Log.println(indent + indent + cloudlet.getResourceId() + indent + indent + indent +
						cloudlet.getGuestId() + indent + indent + indent +
						dft.format(cloudlet.getActualCPUTime()) + indent + indent +
						dft.format(cloudlet.getExecStartTime()) + indent + indent + indent +
						dft.format(cloudlet.getExecFinishTime()));
			}
		}
	}
}
