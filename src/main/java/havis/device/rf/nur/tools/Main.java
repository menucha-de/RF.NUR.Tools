package havis.device.rf.nur.tools;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Objects;

import com.nordicid.nativeserial.NativeSerialTransport;
import com.nordicid.nativeserial.SerialPort;
import com.nordicid.nurapi.NurApi;
import com.nordicid.nurapi.NurApiException;
import com.nordicid.nurapi.NurApiListener;
import com.nordicid.nurapi.NurBinFileType;
import com.nordicid.nurapi.NurEventAutotune;
import com.nordicid.nurapi.NurEventClientInfo;
import com.nordicid.nurapi.NurEventDeviceInfo;
import com.nordicid.nurapi.NurEventEpcEnum;
import com.nordicid.nurapi.NurEventFrequencyHop;
import com.nordicid.nurapi.NurEventIOChange;
import com.nordicid.nurapi.NurEventInventory;
import com.nordicid.nurapi.NurEventNxpAlarm;
import com.nordicid.nurapi.NurEventProgrammingProgress;
import com.nordicid.nurapi.NurEventTagTrackingChange;
import com.nordicid.nurapi.NurEventTagTrackingData;
import com.nordicid.nurapi.NurEventTraceTag;
import com.nordicid.nurapi.NurEventTriggeredRead;
import com.nordicid.nurapi.NurGPIOConfig;
import com.nordicid.nurapi.NurIRConfig;
import com.nordicid.nurapi.NurRespDevCaps;
import com.nordicid.nurapi.NurRespGPIOStatus;
import com.nordicid.nurapi.NurRespReaderInfo;
import com.nordicid.nurapi.NurRespRegionInfo;
import com.nordicid.nurapi.NurSetup;
import com.nordicid.nurapi.NurTuneResponse;
import com.nordicid.nurapi.ReflectedPower;

public class Main implements NurApiListener {

	public static final String NAME = "havis.device.rf.nur.tools.jar";
	public static final String MODULE_TYPE = "NUR-05WL2";

	public static final String[] USAGE = new String[] {
		"USAGE:",
		NAME + " -b|-f <binary_file> (-p)",
		NAME + " -s b|a",
		NAME + " -r (-4)",
		NAME + " -t 1|2(|3|4)",
		NAME + " -d 1|2(|3|4)",
		"",
		" -b\t Update boot loader",
		" -f\t Update firmware",
		" -u\t Check whether the provided firmware is newer than the installed firmware",
		" -p\t Pretend update only",
		" -r\t Reset module configuration",
		" -g\t Set GPIO configuration",
		" -i\t Set GPIO state",
		" -c\t Display module configuration",
		" -t\t Tune antenna",
		" -d\t Detect antenna connection state",
		" -s\t Switch to (b)oot loader or (a)pplication mode",
		" -h\t Print this help",
		"",
		"Update boot loader:         " + NAME + " -b <binary_file> (-p)",
		"Update firmware:            " + NAME + " -f <binary_file> (-p)\n",
		"Check firmware:             " + NAME + " -u <binary_file>\n",
		"Switch to boot loader mode: " + NAME + " -s a",
		"Switch to application mode: " + NAME + " -s b\n",
		"Set GPIO configuration:     " + NAME + " -g <io> <type> <edge> <enabled>",
		"Set GPIO state:             " + NAME + " -i <io> <state>\n",
		"Tune antenna:               " + NAME + " -t 1|2(|3|4)",
		"Detect antenna:             " + NAME + " -d 1|2(|3|4)",
		"",
		"Info: Please make sure that the native lib for your OS is on the same path as "
				+ NAME + ".",
		"      Otherwise you can specify the native lib location with java -Djava.library.path=/path/to/native/lib -jar "
				+ NAME + " [...]" 
	};
	
	public static final int ERR_CODE_ALL_OK = 0x0;
	public static final int ERR_CODE_BAD_SYNTAX = 0x01;
	public static final int ERR_CODE_FILE_NOT_FOUND = 0x02;
	public static final int ERR_CODE_NO_DEVICE = 0x04;
	public static final int ERR_CODE_MODE_SWITCH_ERROR = 0x08;
	public static final int ERR_CODE_APP_UPD_ERROR = 0x10;
	public static final int ERR_CODE_BOOT_LOADER_UPD_ERROR = 0x20;
	public static final int ERR_CODE_ILLEGAL_ARG = 0x30;
	public static final int ERR_CODE_CONFIG_RESET_ERROR = 0x40;
	public static final int ERR_CODE_CONFIG_DISPLAY_ERROR = 0x50;
	public static final int ERR_CODE_SET_GPIO_ERROR = 0x60;
	public static final int ERR_CODE_NO_UPD = 0xFF;
	
	private static final int RETRY_CONNECT = 60;
	private static final int WAIT_BERFORE_RETRY = 1000;
	
	private NativeSerialTransport transport = null;
	private SerialPort serialPort = null;
	private NurApi nurApi = null;

	// private boolean waitingForSignal;
	// private Semaphore semaphore = new Semaphore(0);

	public static void main(String[] args) {

		if (args.length == 0)
			usage();
		String command = args[0];
		File binFile = null;
		String mode = null;
		int antennaId = 0;
		boolean pretend = false;
		boolean fourAntennas = false;
		int io = 0;
		int type = 0;
		int edge = 0;
		boolean enabled = false;

		if (command.equals("-f") || command.equals("-b") || command.equals("-u")) {			
			if (args.length < 2) usage();
			
			if (args.length > 2)
				pretend = args[2].equals("-p");

			binFile = new File(args[1].replaceFirst("~",
					System.getProperty("user.home")));
			if (!binFile.exists())
				die(ERR_CODE_FILE_NOT_FOUND,
						"Binary file '" + binFile.getAbsolutePath()
								+ "' could not be found.");
		}

		else if (command.equals("-t") || command.equals("-d")) {
			if (args.length < 2) usage();
			
			try { 
				antennaId = Integer.parseInt(args[1]);
				if (antennaId > 4 || antennaId < 1)
					die(ERR_CODE_ILLEGAL_ARG, "Invalid antenna ID: " + antennaId);
			} catch (NumberFormatException ex) {
				die(ERR_CODE_ILLEGAL_ARG, "Unrecognized antenna ID: " + args[1]);
			}
		}
		
		else if (command.equals("-s")) {
			if (args.length < 2) usage();
			
			mode = args[1];
			if (!(mode.equals("a") || mode.equals("b")))
				usage();
		}

		else if (command.equals("-r")) {			
			if (args.length > 1 && args[1].equals("-4")) 
				fourAntennas = true;
		}
		
		else if (command.equals("-g")) {
			if (args.length != 5) usage();
			try {
				io = Integer.parseInt(args[1]);
				type = Integer.parseInt(args[2]);
				edge = Integer.parseInt(args[3]);
				if ("true".equalsIgnoreCase(args[4]) || "false".equalsIgnoreCase(args[4])) {
					enabled = Boolean.parseBoolean(args[4]);
				} else {
					enabled = Integer.parseInt(args[4]) != 0;
				}
			} catch (NumberFormatException ex) {
				die(ERR_CODE_ILLEGAL_ARG, "Unrecognized GPIO config");
			}
		}
		
		else if (command.equals("-i")) {
			if (args.length != 3) usage();
			try {
				io = Integer.parseInt(args[1]);
				if ("true".equalsIgnoreCase(args[2]) || "false".equalsIgnoreCase(args[2])) {
					enabled = Boolean.parseBoolean(args[2]);
				} else {
					enabled = Integer.parseInt(args[2]) != 0;
				}
			} catch (NumberFormatException ex) {
				die(ERR_CODE_ILLEGAL_ARG, "Unrecognized GPIO state");
			}
		}

		String device;
		int attempts = 0;
		while ((device = findDevice("/dev/ttyACM")) == null && ++attempts < RETRY_CONNECT) {
			try {
				Thread.sleep(WAIT_BERFORE_RETRY);
			} catch (InterruptedException e) {
				break;
			}
		}

		if (device == null)
			die(ERR_CODE_NO_DEVICE, "Failed to find device. Is NUR module connected?");

		Main main = new Main();

		try {
			/* "Dirty" hack to set the java.lib.path in code to current dir */
			System.setProperty("java.library.path", ".");
			Field fieldSysPath = ClassLoader.class
					.getDeclaredField("sys_paths");
			fieldSysPath.setAccessible(true);
			fieldSysPath.set(null, null);
		} catch (Exception e) {
		}

		switch (command) {
		case "-s":
			try {
				main.switchMode(device, mode);
			} catch (Exception e) {
				die(ERR_CODE_MODE_SWITCH_ERROR, e.getMessage());
			}
			break;

		case "-f":
			try {
				main.updateFirmware(device, binFile, pretend);
			} catch (Exception e) {
				die(ERR_CODE_APP_UPD_ERROR, e.getMessage());
			}
			break;

		case "-b":
			try {
				main.updateBootLoader(device, binFile, pretend);
			} catch (Exception e) {
				die(ERR_CODE_BOOT_LOADER_UPD_ERROR, e.getMessage());
			}
			break;
		case "-u":
			try {
				if (!main.canUpdate(device, binFile)) {
					System.exit(ERR_CODE_NO_UPD);
				}
			} catch (Exception e) {
				die(ERR_CODE_APP_UPD_ERROR, e.getMessage());
			}
			break;
		case "-r":
			try {
				main.resetConfig(device, fourAntennas);
			} catch (Exception e) {
				die(ERR_CODE_CONFIG_RESET_ERROR, e.getMessage());
			}
			break;
		case "-c":
			try {
				main.displayConfig(device);
			} catch (Exception e) {
				die(ERR_CODE_CONFIG_DISPLAY_ERROR, e.getMessage());
			}
			break;
		case "-g":
			try {
				main.setGpioConfig(device, io, type, edge, enabled);
			} catch (Exception e) {
				die(ERR_CODE_SET_GPIO_ERROR, e.getMessage());
			}
			break;
		case "-i":
			try {
				main.setGpioState(device, io, enabled);
			} catch (Exception e) {
				die(ERR_CODE_SET_GPIO_ERROR, e.getMessage());
			}
			break;
		case "-t":
			try {
				main.tune(device, antennaId);
			} catch (Exception e) {
				die(ERR_CODE_CONFIG_RESET_ERROR, e.getMessage());
			}
			break;
		case "-d":
			try {
				main.autoDetect(device, antennaId);
			} catch (Exception e) {
				die(ERR_CODE_CONFIG_RESET_ERROR, e.getMessage());
			}
			break;
		default:
			die(ERR_CODE_ILLEGAL_ARG, "Illegal argument exception.");
		}
		System.exit(ERR_CODE_ALL_OK);
	}

	private static String findDevice(String prefix) {
		for (int i = 0; i < 127; i++) {
			if (new File(prefix + i).exists())
				return prefix + i;
		}
		return null;
	}

	private static void die(int retCode, String message) {
		if (message != null)
			System.err.println("PROGRAM EXIT WITH ERROR: " + message);
		System.exit(retCode);
	}

	private static void usage() {
		for (String usage : USAGE)
			System.out.println(usage);
		die(ERR_CODE_BAD_SYNTAX, null);
	}


	private void checkModuleMode(String desiredState) throws Exception {
		if (this.nurApi.getMode().equals("B") && "A".equals(desiredState)) {
			log("Module is in wrong mode to perform operation.");
			throw new IllegalStateException("Module must be in application mode to perform operation.");
		}
		if (this.nurApi.getMode().equals("A") && "B".equals(desiredState)) {
			log("Module is in wrong mode to perform operation.");
			throw new IllegalStateException("Module must be in boot loader mode to perform operation.");
		}
	}
	
	private void switchMode(String device, String mode) throws Exception {
		connect(device);

		switch (mode) {
		case "a":
			if (nurApi.getMode().equals("A")) {
				log("Module already in application mode");
				disconnect(true);
			} else {
				log("Switching to application mode");
				try {
					this.nurApi.exitBootLoader();
				} catch (NurApiException e) {
					System.out.println(e.error);
					throw e;
				}
			}
			break;
		case "b":
			if (nurApi.getMode().equals("B")) {
				log("Module already in boot loader mode");
				disconnect(true);
			} else {
				log("Switching to boot loader mode");
				try {
					this.nurApi.moduleBoot(false);
				} catch (NurApiException e) {
					System.out.println(e.error);
					throw e;
				}
			}
			break;
		default:
			throw new IllegalArgumentException();
		}
	}

	private void updateFirmware(String device, File binFile, boolean pretend) throws Exception {
		connect(device);

		try { checkModuleMode("B"); }
		catch (IllegalStateException e) {
			disconnect(true);
			throw e;
		}
		
		if (pretend)
			log("Pretending firmware update");
		else {
			log("Installing firmware update");
			this.nurApi.programApplicationFile(binFile.getAbsolutePath());
		}
		
		disconnect(true);

	}

	private boolean canUpdate(String device, File firmwareFile) throws Exception {
		connect(device);

		try {
			checkModuleMode("A");
		} catch (IllegalStateException e) {
			disconnect(true);
			throw e;
		}

		NurBinFileType type = nurApi.checkNurFwBinaryFile(firmwareFile.getAbsolutePath(), MODULE_TYPE);
		String currentFwVersion = this.nurApi.getReaderInfo().swVersion;
		String newFwVersion = type.getVersion();
		log("New firmware version:     " + newFwVersion);
		log("File:                     " + firmwareFile.getAbsolutePath());
		disconnect(true);
		return !currentFwVersion.equals(newFwVersion);
	}

	private void tune(String device, int antennaId) throws Exception {
		connect(device);
		
		try { checkModuleMode("A"); }
		catch (IllegalStateException e) {
			disconnect(true);
			throw e;
		}
		
		log ("Reading region ID from module.");
		int regionId = this.nurApi.getModuleSetup().regionId;
		int antennaMask = this.nurApi.getSetupAntennaMask();
		log("Current antenna mask: " + Integer.toBinaryString(antennaMask));
		
		try {
			if (regionId == NurApi.REGIONID_EU || regionId == NurApi.REGIONID_FCC) {
				setAntennaMaskForId(antennaId);
			}
			
			if (regionId == NurApi.REGIONID_EU) {		
				log("Tuning antenna " + antennaId + " for region EU.");				
				NurTuneResponse[] res = this.nurApi.tuneEUBand(antennaId, true);
				log("Tuned antenna " + antennaId + " with result " + res[0].dBm + " dBm.");
			}
			
			else if (regionId == NurApi.REGIONID_FCC) {		
				log("Tuning antenna " + antennaId + " for FCC regions.");
				NurTuneResponse[] res = this.nurApi.tuneFCCBands(antennaId, true);
				log("Tuned antenna " + antennaId + " with result " + res[0].dBm + " dBm.");
			}
			else
				log("Unsupported or unspecified region is set. Skipping tune of antenna " + antennaId);			
			
		}
		catch (Exception ex) {
			throw ex;
		}
		finally {
			if (regionId == NurApi.REGIONID_EU || regionId == NurApi.REGIONID_FCC)
				restoreAntennaMask(antennaMask);
			disconnect(true);
		}
	}

	private void autoDetect(String device, int antennaId) throws Exception {
		connect(device);
		
		try { checkModuleMode("A"); }
		catch (IllegalStateException e) {
			disconnect(true);
			throw e;
		}
		
		int prevAntennaMask = this.nurApi.getSetupAntennaMask();
		log("Previous antenna mask: " + Integer.toBinaryString(prevAntennaMask));
		try {
			
			setAntennaMaskForId(antennaId);				
			ReflectedPower reflPower = this.nurApi.getReflectedPower();
	
			double rf = Math.sqrt((double) (reflPower.iPart
					* reflPower.iPart + reflPower.qPart * reflPower.qPart));
			rf /= ((double) reflPower.divider);
			rf = Math.log10(rf) * 20.0;
			if (Double.isInfinite(rf))
				rf = -30;
			
			log("Reflected power info for antenna " + antennaId + ": " + rf);
			if (rf < 0) log("Antenna " + antennaId + " is CONNECTED");
			else log("Antenna " + antennaId + " is DISCONNECTED");
						

		} catch (Exception ex) { 
			throw ex; 			
		} finally {
			restoreAntennaMask(prevAntennaMask);
			disconnect(true);
		}		
	}
	
	private void setAntennaMaskForId(int antennaId) throws Exception {
		int antMask = 0;
		
		switch (antennaId) {
			case 1: 
				antMask = NurApi.ANTENNAMASK_1;
				break;
			case 2: 
				antMask = NurApi.ANTENNAMASK_2;
				break;
			case 3: 
				antMask = NurApi.ANTENNAMASK_3;
				break;
			default: 
				antMask = NurApi.ANTENNAMASK_4;
				break;
		}

		if (this.nurApi.getSetupAntennaMask() == antMask) return;
		
		log("Setting antenna mask: " + Integer.toBinaryString(antMask));
		this.nurApi.setSetupAntennaMask(antMask);
		this.nurApi.storeSetup(NurApi.SETUP_ANTMASK);  
	}
	
	private void restoreAntennaMask(int prevAntennaMask) throws Exception {
		if (this.nurApi.getSetupAntennaMask() != prevAntennaMask) {
			log("Restoring antenna mask " + Integer.toBinaryString(prevAntennaMask));
			this.nurApi.setSetupAntennaMask(prevAntennaMask);
			this.nurApi.storeSetup(NurApi.SETUP_ANTMASK);
		}
	}
	
	private void setGpioConfig(String device, int io, int type, int edge, boolean enabled) throws Exception {
		connect(device);
		
		try { checkModuleMode("A"); }
		catch (IllegalStateException e) {
			disconnect(true);
			throw e;
		}
		
		log("Acquiring current GPIO config");
		NurGPIOConfig[] cfg = null;
		
		try { cfg = nurApi.getGPIOConfigure(); }
		catch (Exception ex) {
			disconnect(true);
			throw ex;
		}
		
		log("Current GPIO config:");
		logGpio(cfg);
		
		if (io < 0 || io > cfg.length - 1) {
			log("Illegal GPIO cannot be set: " + io);	
			disconnect(true);
		}
		
		cfg[io].type = type;
		cfg[io].edge = edge;
		cfg[io].enabled = enabled;
		
		log("Setting new GPIO config:");
		try { nurApi.setGPIOConfigure(cfg); }
		catch (Exception ex) {
			disconnect(true);
			throw ex;
		}
		logGpio(cfg);
		
		log("Storing config");
		try { nurApi.storeSetup(NurApi.STORE_ALL); }
		catch (Exception ex) {
			disconnect(true);
			throw ex;
		}
		
		disconnect(true);
	}
	
	private void setGpioState(String device, int io, boolean state) throws Exception {
		connect(device);
		
		try { checkModuleMode("A"); }
		catch (IllegalStateException e) {
			disconnect(true);
			throw e;
		}
		
		log("Acquiring current GPIO state");
		NurRespGPIOStatus status;
		try { status = nurApi.getGPIOStatus(io); }
		catch (Exception ex) {
			disconnect(true);
			throw ex;
		}
		
		log("Current GPIO " + io + " state:");
		logObject(NurRespGPIOStatus.class, status);		
		
		log("Changing GPIO " + io + " state to " + (state ? "on" : "off"));
		try { nurApi.setGPIOStatus(io, state); }
		catch (Exception ex) {
			disconnect(true);
			throw ex;
		}
		
		disconnect(true);
	}
	
	private void resetConfig(String device, boolean fourAntennas) throws Exception {
		connect(device);

		try { checkModuleMode("A"); }
		catch (IllegalStateException e) {
			disconnect(true);
			throw e;
		}
			
		log("Acquiring current GPIO config");
		NurGPIOConfig[] cfg = null;
		
		try { cfg = nurApi.getGPIOConfigure(); }
		catch (Exception ex) {
			disconnect(true);
			throw ex;
		}
		
		log("Current GPIO config:");
		logGpio(cfg);
		
		log("Current antenna mask: " + nurApi.getSetupAntennaMask());
		
		if (fourAntennas) {
			
			log("Setting GPIOs for 4 antennas");
			
			cfg[0].type = NurApi.GPIO_TYPE_ANTCTL2;
			cfg[0].edge = NurApi.GPIO_EDGE_FALLING;
			cfg[0].enabled = true;
			
			cfg[1].type = NurApi.GPIO_TYPE_ANTCTL1;
			cfg[1].edge = NurApi.GPIO_EDGE_FALLING;
			cfg[1].enabled = true;
			
			cfg[2].type = NurApi.GPIO_TYPE_RFIDON;
			cfg[2].edge = NurApi.GPIO_EDGE_RISING;
			cfg[2].enabled = true;
			
			cfg[3].type = NurApi.GPIO_TYPE_RFIDON;
			cfg[3].edge = NurApi.GPIO_EDGE_RISING;
			cfg[3].enabled = true;
			
			cfg[4].type = NurApi.GPIO_TYPE_INPUT;
			cfg[4].edge = NurApi.GPIO_EDGE_FALLING;
			cfg[4].enabled = true;
			
			log("Setting new GPIO config:");
			nurApi.setGPIOConfigure(cfg);
			logGpio(cfg);
			
			int mask = NurApi.ANTENNAMASK_1 | NurApi.ANTENNAMASK_2 | NurApi.ANTENNAMASK_3 | NurApi.ANTENNAMASK_4;
			log("Setting antenna mask for 4 antennas: " + mask);
			nurApi.setSetupAntennaMask(mask);
		}
		
		else {
			
			log("Setting GPIOs for 2 antennas");
			
			cfg[0].type = NurApi.GPIO_TYPE_ANTCTL1;
			cfg[0].edge = NurApi.GPIO_EDGE_FALLING;
			cfg[0].enabled = true;
			
			cfg[1].type = NurApi.GPIO_TYPE_RFIDON;
			cfg[1].edge = NurApi.GPIO_EDGE_RISING;
			cfg[1].enabled = true;
			
			cfg[2].type = NurApi.GPIO_TYPE_RFIDON;
			cfg[2].edge = NurApi.GPIO_EDGE_RISING;
			cfg[2].enabled = true;
			
			cfg[3].type = NurApi.GPIO_TYPE_INPUT;
			cfg[3].edge = NurApi.GPIO_EDGE_FALLING;
			cfg[3].enabled = true;
			
			cfg[4].type = NurApi.GPIO_TYPE_INPUT;
			cfg[4].edge = NurApi.GPIO_EDGE_FALLING;
			cfg[4].enabled = true;
			
			log("Setting new GPIO config:");
			nurApi.setGPIOConfigure(cfg);
			logGpio(cfg);
			
			int mask = NurApi.ANTENNAMASK_1 | NurApi.ANTENNAMASK_2;
			log("Setting antenna mask for 2 antennas: " + mask);
			nurApi.setSetupAntennaMask(mask);
		}
		
		log("Storing config");
		nurApi.storeSetup(NurApi.STORE_ALL);
		
		disconnect(true);
	}
	
	private void displayConfig(String device) throws Exception {
		connect(device);
		try { 
    		checkModuleMode("A");
    		
    		log();
    		log("Current GPIO config:");
    		logGpio(nurApi.getGPIOConfigure());
    
    		log();
    		log("Current antenna mask: " + nurApi.getSetupAntennaMask());
    		
    		log();
    		log("Current module setup:");
    		logObject(NurSetup.class, nurApi.getModuleSetup());
    		
    		log();
    		log("Current IR config:");
    		logObject(NurIRConfig.class, nurApi.getIRConfig());
    		
    		log();
    		log("Current reader info:");
    		logObject(NurRespReaderInfo.class, nurApi.getReaderInfo());
    		
    		log();
    		log("Current device capabilites:");
    		logObject(NurRespDevCaps.class, nurApi.getDeviceCaps());
    		
    		log();
    		log("Current region info:");
    		logObject(NurRespRegionInfo.class, nurApi.getRegionInfo());
    		
    	}
		catch (Exception e) {
			disconnect(true);
			throw e;
		}		
		disconnect(true);
	}

	private void updateBootLoader(String device, File binFile, boolean pretend) throws Exception {
		connect(device);

		try { checkModuleMode("B"); }
		catch (IllegalStateException e) {
			disconnect(true);
			throw e;
		}

		if (pretend)
			log("Pretending boot loader update");
		else {
			log("Installing boot loader update");
			this.nurApi.programBootloaderFile(binFile.getAbsolutePath());
		}
		disconnect(true);	
	}

	private void connect(String device) throws Exception {
		for (int i = 0; i < RETRY_CONNECT; i++) {
			try {
				log("Creating serial port: " + device);
				this.serialPort = new SerialPort(device, device, 0);

				log("Creating transport");
				this.transport = new NativeSerialTransport(serialPort,
						NativeSerialTransport.BAUDRATE_115200);

				log("Creating api");
				this.nurApi = new NurApi(transport);

				log("Adding listener");
				this.nurApi.setListener(this);

				log("Connecting");
				this.nurApi.connect();

				if (this.nurApi.getMode().equals("A")) {
					log("Running in application mode");
					log("Current firmware version: "
							+ this.nurApi.getReaderInfo().swVersion);
				}

				else if (this.nurApi.getMode().equals("B")) {
					log("Running in boot loader mode");
					log("Current boot loader version: "
							+ this.nurApi.getReaderInfo().swVersion);
				}
				break;
			} catch (Exception ex) {
				Thread.sleep(WAIT_BERFORE_RETRY);
				log("Connection failed. Retry: " + (i + 1) + "/"
						+ RETRY_CONNECT);
			}
		}
	}

	private void disconnect(boolean cleanup) throws Exception {
		log("Disconnecting");
		this.nurApi.disconnect();

		// log("Waiting for disconnected event");
		// waitingForSignal = true;
		// while (waitingForSignal)
		// semaphore.acquire();

		if (cleanup)
			this.cleanup(true); // <== this happens approx. 20 secs later.
	}

	private void cleanup(boolean dispose) {
		log("Cleaning up");
		this.nurApi.setListener(null);
		if (dispose)
			this.nurApi.dispose();
		this.nurApi = null;
		this.transport = null;
		this.serialPort = null;
	}
	
	private void log() {
		System.out.println();
	}
	
	private void log(String message) {
		System.out.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
				.format(new Date()) + ": " + message);
	}
	
	private void logGpio(NurGPIOConfig[] cfg) {
		StringBuilder str = new StringBuilder();
		int index = 0;
		for (NurGPIOConfig c : cfg) {
			str.setLength(0);
			str.append("gpio[").append(index++).append("]=[type=").append(c.type).append(", egde=").append(c.edge).append(", available=").append(c.available)
					.append(", action=").append(c.action).append(", enabled=").append(c.enabled).append("]");
			log(str.toString());
		}
	}
	
	private void logObject(Class<?> clazz, Object object) {
		logObject(null, clazz, object);
	}
	
	private void logObject(String prefix, Class<?> clazz, Object object) {
		for (Field field : clazz.getFields()) {
			try {
				if (!(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()))) {
    				Class<?> type = field.getType();
    				Object value = field.get(object);
    				if (type.getName().startsWith("com.nordicid")) {
    					logObject(prefix != null ? prefix + "." + field.getName() + "." : field.getName() + ".", type, value);
    				} else {
    					String str;
    					if (type.isArray()) {
    						if (value instanceof int[])
    							str = Arrays.toString((int[]) value);
    						else if (value instanceof boolean[])
    							str = Arrays.toString((boolean[]) value);
    						else if (value instanceof byte[])
    							str = Arrays.toString((byte[]) value);
    						else if (value instanceof short[])
    							str = Arrays.toString((short[]) value);
    						else if (value instanceof double[])
    							str = Arrays.toString((double[]) value);
    						else if (value instanceof float[])
    							str = Arrays.toString((float[]) value);
    						else if (value instanceof long[])
    							str = Arrays.toString((long[]) value);
    						else if (value instanceof char[])
    							str = Arrays.toString((char[]) value);
    						else
    							str = Arrays.toString((Object[]) value);
    					} else {
    						str = Objects.toString(value);
    					}
    					log((prefix != null ? prefix : "") + field.getName() + ": " + str);
    				}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	@Override
	public void disconnectedEvent() {
		// this.waitingForSignal = false;
		// this.semaphore.release();
	}

	@Override
	public void programmingProgressEvent(NurEventProgrammingProgress arg0) {
		System.out.print(".");
		if (arg0.currentPage + 1 == arg0.totalPages)
			System.out.println();
	}

	@Override
	public void IOChangeEvent(NurEventIOChange arg0) {
	}

	@Override
	public void bootEvent(String arg0) {
	}

	@Override
	public void clientConnectedEvent(NurEventClientInfo arg0) {
	}

	@Override
	public void clientDisconnectedEvent(NurEventClientInfo arg0) {
	}

	@Override
	public void connectedEvent() {
	}

	@Override
	public void debugMessageEvent(String arg0) {
	}

	@Override
	public void deviceSearchEvent(NurEventDeviceInfo arg0) {
	}

	@Override
	public void epcEnumEvent(NurEventEpcEnum arg0) {
	}

	@Override
	public void frequencyHopEvent(NurEventFrequencyHop arg0) {
	}

	@Override
	public void inventoryExtendedStreamEvent(NurEventInventory arg0) {
	}

	@Override
	public void inventoryStreamEvent(NurEventInventory arg0) {
	}

	@Override
	public void logEvent(int arg0, String arg1) {
	}

	@Override
	public void nxpEasAlarmEvent(NurEventNxpAlarm arg0) {
	}

	@Override
	public void traceTagEvent(NurEventTraceTag arg0) {
	}

	@Override
	public void triggeredReadEvent(NurEventTriggeredRead arg0) {
	}

	@Override
	public void autotuneEvent(NurEventAutotune arg0) {
	}

	@Override
	public void tagTrackingChangeEvent(NurEventTagTrackingChange arg0) {
		
	}

	@Override
	public void tagTrackingScanEvent(NurEventTagTrackingData arg0) {
		
	}
}