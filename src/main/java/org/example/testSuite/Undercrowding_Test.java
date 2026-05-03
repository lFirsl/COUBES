/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation
 *               of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009, The University of Melbourne, Australia
 */


package org.example.testSuite;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.power.models.PowerModel;
import org.cloudbus.cloudsim.power.models.PowerModelLinear;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.example.helper.Constants;
import org.example.helper.Helper;
import org.example.kubernetes_broker.Live_Kubernetes_Broker_Ex;
import org.example.kubernetes_broker.PowerDatacenterCustom;
import org.example.kubernetes_broker.PowerVmCustom;
import org.example.kubernetes_broker.UtilizationModelSlice;
import org.example.metrics.BoundsCalculator;
import org.example.metrics.BoundsCalculator.*;
import org.example.metrics.SDS;
import org.example.metrics.SimulationMetrics;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

/**
 * An example showing how to pause and resume the simulation,
 * and create simulation entities (a DatacenterBroker in this example)
 * dynamically.
 */
public class Undercrowding_Test {
	public static Live_Kubernetes_Broker_Ex broker;

	/** The cloudlet list. */
	private static List<Cloudlet> cloudletList;

	/** The vmlist. */
	private static List<Vm> vmlist;

	private static List<Vm> createVM(int userId, int vms, int idShift) {
		//Creates a container to store VMs. This list is passed to the broker later
		LinkedList<Vm> list = new LinkedList<>();

		//VM Parameters
		long size = 10000; //image size (MB)
		int ram = 512; //vm memory (MB)
		int mips = 250;
		long bw = 1000;
		int pesNumber = 4; //number of cpus
		String vmm = "Xen"; //VMM name

		//create VMs
		Vm[] vm = new Vm[vms];

		for(int i=0;i<vms;i++){
			vm[i] = new PowerVmCustom(idShift + i, userId, mips, pesNumber, ram, bw, size,0, vmm, new CloudletSchedulerTimeShared(),200,-1);
			list.add(vm[i]);
		}

		return list;
	}


	private static List<Cloudlet> createCloudlet(int userId, int cloudlets, int idShift){
		// Creates a container to store Cloudlets
		LinkedList<Cloudlet> list = new LinkedList<>();

		//cloudlet parameters
		long length = 40000;
		long fileSize = 300;
		long outputSize = 300;
		int pesNumber = 1;
		UtilizationModel utilizationModel = new UtilizationModelSlice(4);
        UtilizationModel utilizationModelFull = new UtilizationModelFull();

		Cloudlet[] cloudlet = new Cloudlet[cloudlets];

		for(int i=0;i<cloudlets;i++){
			cloudlet[i] = new Cloudlet(idShift + i, length, pesNumber, fileSize, outputSize, utilizationModelFull, utilizationModelFull,utilizationModelFull);
			// setting the owner of these Cloudlets
			cloudlet[i].setUserId(userId);
			list.add(cloudlet[i]);
		}

		return list;
	}


	////////////////////////// STATIC METHODS ///////////////////////

	/**
	 * Creates main() to run this example
	 */
	public static void main(String[] args) {
		Log.println("Starting CloudSimExample7...");

		try {
			// First step: Initialize the CloudSim package. It should be called
			// before creating any entities.
			int num_user = 2;   // number of grid users
			Calendar calendar = Calendar.getInstance();
			boolean trace_flag = false;  // mean trace events

			// Initialize the CloudSim library
			CloudSim.init(num_user, calendar, trace_flag);

			// Second step: Create Datacenters
			//Datacenters are the resource providers in CloudSim. We need at list one of them to run a CloudSim simulation
			PowerDatacenterCustom datacenter0 = createDatacenter("Datacenter_0");

			//Third step: Create Broker
			broker = new Live_Kubernetes_Broker_Ex("Broker_0",-1);
			int brokerId = broker.getId();

			//Fourth step: Create VMs and Cloudlets and send them to broker
			vmlist = createVM(brokerId, 10, 0); //creating 10 vms
			cloudletList = createCloudlet(brokerId, 10, 0); // creating 10 cloudlets

			broker.submitGuestList(vmlist);
			broker.submitCloudletList(cloudletList);

			// ── Compute theoretical bounds via ILP ──
			List<VmSpec> vmSpecs = new ArrayList<>();
			for (Vm vm : vmlist) {
				vmSpecs.add(new VmSpec(vm.getId(), vm.getNumberOfPes(),
						(int) vm.getMips(), vm.getRam()));
			}
			PowerModelSpec powerSpec = new PowerModelSpec(500, 0.1);
			List<CloudletSpec> clSpecs = new ArrayList<>();
			for (Cloudlet cl : cloudletList) {
				clSpecs.add(new CloudletSpec(cl.getCloudletId(), cl.getCloudletLength(),
						cl.getNumberOfPes(), 0, null, null));
			}
			List<Wave> waves = List.of(new Wave(clSpecs, 0.0));
			TheoreticalBounds bounds = BoundsCalculator.compute(vmSpecs, waves, powerSpec);
			System.out.println("── Theoretical Bounds ──");
			System.out.println(bounds);

			// Fifth step: Starts the simulation
			SimulationMetrics metrics = new SimulationMetrics(datacenter0,vmlist);
			metrics.startWallClock();
			double lastClock = CloudSim.startSimulation();

			// Final step: Print results when simulation is over
			List<Cloudlet> newList1 = broker.getCloudletReceivedList();
			Log.printConcat("Broker has a lifetime of: ",broker.getLifeLength());

			CloudSim.stopSimulation();
			metrics.stopWallClock();

			if(newList1.size() != 10){
				throw new RuntimeException("Expected 10 cloudlets to complete but only received " + newList1.size());
			}
			printCloudletList(newList1);
			metrics.printSummary(lastClock,broker.tpOverall());

			// ── Compute SDS ──
			double actualEnergy = datacenter0.getPower() / 3600.0;
			double actualConsolidation = datacenter0.getConsolidationAverage(lastClock);
			SDS.Result sds = SDS.compute(bounds, lastClock, actualEnergy, actualConsolidation);
			System.out.println("── SDS Result ──");
			System.out.println(sds);

			Helper.printResults(
					datacenter0,
					vmlist,
					lastClock,
					"Pause_Example_Single_Broker_Power",
					Constants.OUTPUT_CSV,
					"C:\\Users\\flori\\Desktop");


			broker.sendResetRequestToControlPlane();

			Log.println("CloudSimExample7 finished!");
		}
		catch (Exception e)
		{
			e.printStackTrace();
			Log.println("The simulation has been terminated due to an unexpected error");
		}
	}

	private static PowerDatacenterCustom createDatacenter(String name){

		// Here are the steps needed to create a PowerDatacenter:
		// 1. We need to create a list to store one or more
		//    Machines
		List<Host> hostList = new ArrayList<>();

		// 2. A Machine contains one or more PEs or CPUs/Cores. Therefore, should
		//    create a list to store these PEs before creating
		//    a Machine.
		List<Pe> peList1 = new ArrayList<>();

		int mips = 250;

		// 3. Create PEs and add these into the list.
		//for a quad-core machine, a list of 4 PEs is required:
		for(int i = 0; i< 4; i++){
			peList1.add(new Pe(i, new PeProvisionerSimple(mips))); // need to store Pe id and MIPS Rating
		}

		//4. Create Hosts with its id and list of PEs and add them to the list of machines
		int hostId=0;
		int ram = 16384; //host memory (MB)
		long storage = 1000000; //host storage
		int bw = 10000;

		PowerModel powerModelHigh = new PowerModelLinear(500,0.1);  // 500 watts max
		for(int x = 0; x < 10; x++){
			hostList.add(
					new PowerHost(
							hostId++,
							new RamProvisionerSimple(ram),
							new BwProvisionerSimple(bw),
							storage,
							peList1,
							new VmSchedulerTimeShared(peList1),
							powerModelHigh
					)
			); // This is our first machine
		}

		// 5. Create a DatacenterCharacteristics object that stores the
		//    properties of a data center: architecture, OS, list of
		//    Machines, allocation policy: time- or space-shared, time zone
		//    and its price (G$/Pe time unit).
		String arch = "x86";      // system architecture
		String os = "Linux";          // operating system
		String vmm = "Xen";
		double time_zone = 10.0;         // time zone this resource located
		double cost = 3.0;              // the cost of using processing in this resource
		double costPerMem = 0.05;		// the cost of using memory in this resource
		double costPerStorage = 0.1;	// the cost of using storage in this resource
		double costPerBw = 0.1;			// the cost of using bw in this resource
		LinkedList<Storage> storageList = new LinkedList<>();	//we are not adding SAN devices by now

		DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
				arch, os, vmm, hostList, time_zone, cost, costPerMem, costPerStorage, costPerBw);


		// 6. Finally, we need to create a PowerDatacenter object.
		PowerDatacenterCustom datacenter = null;
		try {
			//datacenter = new Datacenter(name, characteristics, new VmAllocationPolicySimple(hostList), storageList, 2000);
			datacenter = new PowerDatacenterCustom(name, characteristics, new VmAllocationPolicySimple(hostList), storageList, 1);
			datacenter.setDisableMigrations(true);
		} catch (Exception e) {
			e.printStackTrace();
		}

		return datacenter;
	}

	/**
	 * Prints the Cloudlet objects
	 * @param list  list of Cloudlets
	 */
	private static void printCloudletList(List<Cloudlet> list) {
		Cloudlet cloudlet;

		String indent = "    ";
		Log.println();
		Log.println("========== OUTPUT ==========");
		Log.println("Cloudlet ID" + indent + "STATUS" + indent +
				"Data center ID" + indent + "VM ID" + indent + indent + "Time" + indent + "Start Time" + indent + "Finish Time");

		DecimalFormat dft = new DecimalFormat("###.##");
		for (Cloudlet value : list) {
			cloudlet = value;
			Log.print(indent + cloudlet.getCloudletId() + indent + indent);

			if (cloudlet.getStatus() == Cloudlet.CloudletStatus.SUCCESS) {
				Log.print("SUCCESS");

				Log.println(indent + indent + cloudlet.getResourceId() + indent + indent + indent + cloudlet.getGuestId() +
						indent + indent + indent + dft.format(cloudlet.getActualCPUTime()) +
						indent + indent + dft.format(cloudlet.getExecStartTime()) + indent + indent + indent + dft.format(cloudlet.getExecFinishTime()));
			}
		}

	}
}
