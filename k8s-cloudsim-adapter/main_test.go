package main

import (
	"flag"
	"os"
	"testing"
)

// TestTestModeFlag verifies that the --test-mode flag is properly registered and parsed.
func TestTestModeFlag(t *testing.T) {
	// Reset flag.CommandLine to avoid conflicts with other tests
	flag.CommandLine = flag.NewFlagSet(os.Args[0], flag.ExitOnError)
	
	// Register flags as in main()
	testMode := flag.Bool("test-mode", false, "Run in standalone test mode (no kube-scheduler required)")
	schedulerName := flag.String("scheduler", "default-scheduler", "Name of the Kubernetes scheduler to use")
	kubeconfig := flag.String("kubeconfig", "", "(ignored) absolute path to the kubeconfig file")
	
	// Test case 1: default values
	flag.CommandLine.Parse([]string{})
	if *testMode != false {
		t.Errorf("Expected testMode to be false by default, got %v", *testMode)
	}
	if *schedulerName != "default-scheduler" {
		t.Errorf("Expected schedulerName to be 'default-scheduler', got %s", *schedulerName)
	}
	
	// Test case 2: --test-mode flag set
	flag.CommandLine = flag.NewFlagSet(os.Args[0], flag.ExitOnError)
	testMode = flag.Bool("test-mode", false, "Run in standalone test mode (no kube-scheduler required)")
	flag.CommandLine.Parse([]string{"--test-mode"})
	if *testMode != true {
		t.Errorf("Expected testMode to be true when --test-mode is set, got %v", *testMode)
	}
	
	// Test case 3: --test-mode with --kubeconfig (both should be accepted)
	flag.CommandLine = flag.NewFlagSet(os.Args[0], flag.ExitOnError)
	testMode = flag.Bool("test-mode", false, "Run in standalone test mode (no kube-scheduler required)")
	kubeconfig = flag.String("kubeconfig", "", "(ignored) absolute path to the kubeconfig file")
	flag.CommandLine.Parse([]string{"--test-mode", "--kubeconfig=/path/to/config"})
	if *testMode != true {
		t.Errorf("Expected testMode to be true, got %v", *testMode)
	}
	if *kubeconfig != "/path/to/config" {
		t.Errorf("Expected kubeconfig to be '/path/to/config', got %s", *kubeconfig)
	}
}
