package au.com.jcloud;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;

// Require rxtx.jar file
import gnu.io.CommPortIdentifier;
import gnu.io.PortInUseException;
import gnu.io.SerialPort;
import gnu.io.UnsupportedCommOperationException;

/**
 * A program to use the Dangerous Prototypes Bus Pirate to query the TMP006
 * temperature sensor. Tested with Bus Pirate V3B running firmware V5.9.
 * 
 * Requires RXTX Java IO library.
 * 
 * See this blog post for more information:
 * http://jdesbonnet.blogspot.com/2011/06/interfacing-tmp006-ir-temperature.html
 * 
 * Version 0.1, 27 June 2011.
 *
 * TODO: no register write (eg for configuration change) implemented
 * yet. Calibration constant embedded in code (see S0 in
 * calculateObjectTemperature())
 * 
 * TODO: Remove System.err.println() debugging
 * 
 * 
 * @author Joe Desbonnet, jdesbonnet@gmail.com
 * 
 */

public class TMP006 {

	public static final int I2C_START = 0x02;
	public static final int I2C_STOP = 0x03;
	public static final int I2C_READ_BYTE = 0x04;
	public static final int I2C_ACK = 0x06;
	public static final int I2C_NAK = 0x07;

	private String sioDevName;
	private SerialPort sioPort;
	private OutputStream sioOut;
	private InputStream sioIn;

	private void setDeviceName(String sioDevName) throws IOException {
		this.sioDevName = sioDevName;

		Enumeration<CommPortIdentifier> ports = CommPortIdentifier.getPortIdentifiers();
		while (ports.hasMoreElements()) {
			CommPortIdentifier cpi = ports.nextElement();
			if (cpi.getPortType() == CommPortIdentifier.PORT_SERIAL && cpi.getName().equals(this.sioDevName)) {
				try {
					sioPort = (SerialPort) cpi.open("SIOReader", 2000);
				} catch (PortInUseException e) {
					e.printStackTrace();
				}
			}
		}

		if (sioPort == null) {
			System.err.println("error: serial port " + sioDevName + " not found.");
			return;
		}

		/*
		 * Set serial port
		 */

		try {
			sioPort.setSerialPortParams(115200, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
			sioPort.setFlowControlMode(SerialPort.FLOWCONTROL_NONE);
		} catch (UnsupportedCommOperationException e) {
			e.printStackTrace();
		}

		try {
			sioOut = sioPort.getOutputStream();
			sioIn = sioPort.getInputStream();
		} catch (IOException e) {
			e.printStackTrace();
		}

		try {
			sioPort.enableReceiveTimeout(200);
		} catch (UnsupportedCommOperationException e) {
			e.printStackTrace();
		}

	}

	/**
	 * Set the Bus Pirate in I2C mode.
	 * 
	 * @throws IOException
	 */
	public void enterI2CMode() throws IOException {
		int i;

		// First test to see if already in I2C mode
		clearInputStream();
		sioOut.write(0x01);
		sioOut.flush();

		if (sioIn.read() == 'I' && sioIn.read() == '2' && sioIn.read() == 'C' && sioIn.read() == '1') {
			// Already in I2C mode
			System.err.println("Already in I2C mode I2C");
			return;
		}

		System.err.println("Not already in I2C mode. Attempting to enter.");

		// Send commands to Bus Pirate to first enter BigBang mode and then
		// I2C binary mode. Documentation recommends the following:
		// (quote)
		// The Bus Pirate user terminal could be stuck in a configuration menu 
		// when your program attempts to enter binary mode. One way to ensure 
		// that you're at the command line is to send <enter> at least 10 times, 
		// and then send '#' to reset. Next, send 0×00 to the command line 20+ 
		// times until you get the BBIOx version string.
		// (end quote)

		// Back to command line
		System.err.println("RETURN x 10");
		for (i = 0; i < 10; i++) {
			sioOut.write(13); // RETURN
		}
		sioOut.flush();
		clearInputStream();

		// Reset
		System.err.println("RESET");
		sioOut.write('#');
		sioOut.flush();
		clearInputStream();

		// Enter BigBang mode
		System.err.println("Enter BBIO");
		for (i = 0; i < 20; i++) {
			sioOut.write(0);
		}
		sioOut.flush();

		while (!(sioIn.read() == 'B' && sioIn.read() == 'B' && sioIn.read() == 'I' && sioIn.read() == 'O')) {
			// empty loop
		}
		System.err.println("BBIO" + (char) sioIn.read());

		// Enter I2C mode
		System.err.println("Enter I2C mode");
		sioOut.write(0x02);
		sioOut.flush();
		clearInputStream();

		// Get I2C version
		System.err.println("Get I2C version");
		sioOut.write(0x01);
		sioOut.flush();
		while (!(sioIn.read() == 'I' && sioIn.read() == '2' && sioIn.read() == 'C')) {
			// empty loop
			System.err.print(".");
			System.err.flush();
		}
		System.err.println("I2C" + (char) sioIn.read());
	}

	/**
	 * Send Bus Pirate command to apply power to the 3.3V line
	 * 
	 * @throws IOException
	 */
	public void powerOn() throws IOException {
		// 0100wxyz Configure peripherals w=power, x=pullups, y=AUX, z=CS
		// Write 0b0100 1000
		System.err.println("POWER ON");
		sioOut.write(0x48);

		//011000xx - Set I2C speed, 3=~400kHz, 2=~100kHz, 1=~50kHz, 0=~5kHz (updated in v4.2 firmware)
		System.err.println("SPEED 50kHz");
		sioOut.write(0x61);
		System.err.println("response=" + sioIn.read());

	}

	/**
	 * Send I2C sequence to read a TMP006 register.
	 * 
	 * @param registerAddress
	 * @return
	 * @throws IOException
	 */
	public int readRegister(int registerAddress) throws IOException {

		clearInputStream();

		// Test I2C mode
		sioOut.write(0x01);
		sioOut.flush();

		if (!(sioIn.read() == 'I' && sioIn.read() == '2' && sioIn.read() == 'C' && sioIn.read() == '1')) {
			throw new IOException("Not in I2C mode");
		}

		System.err.print("START");
		sioOut.write(I2C_START);
		sioOut.flush();
		System.err.println(" response=" + sioIn.read());

		System.err.print("Writing 0x80");
		sioOut.write(0x10); // write one byte
		System.err.print(" response=" + sioIn.read());

		sioOut.write(0x80);
		sioOut.flush();
		System.err.println(sioIn.read() == 0 ? " ACK" : " NAK");

		System.err.print("Writing 0x" + Integer.toHexString(registerAddress));
		sioOut.write(0x10); // write one byte
		System.err.print(" response=" + sioIn.read());

		sioOut.write(registerAddress);
		sioOut.flush();
		System.err.println(sioIn.read() == 0 ? " ACK" : " NAK");

		/*
		System.err.print ("Writing 0x80 $regAddr :");
		sioOut.write (0x11); // write two bytes
		System.err.print (" response=" + sioIn.read());
		
		sioOut.write (0x80); sioOut.flush();
		System.err.print ( sioIn.read() == 0 ? " ACK" : " NAK");
		sioOut.write (registerAddress); sioOut.flush();
		System.err.print ( sioIn.read() == 0 ? " ACK" : " NAK");
		*/

		clearInputStream();

		System.err.print("START");
		sioOut.write(I2C_START);
		sioOut.flush();
		System.err.println(" response=" + sioIn.read());

		System.err.print("Writing 0x81");
		sioOut.write(0x10); // write one byte
		System.err.print(" response=" + sioIn.read());

		sioOut.write(0x81);
		sioOut.flush();
		System.err.println(sioIn.read() == 0 ? " ACK" : " NAK");

		int value;
		int data;

		System.err.print("READ");
		sioOut.write(I2C_READ_BYTE);
		sioOut.flush();
		data = sioIn.read() & 0xff;
		System.err.print(" data=" + Integer.toHexString(data));
		System.err.print(" ACK");
		sioOut.write(I2C_ACK);
		System.err.println(" response=" + sioIn.read());
		value = data << 8;

		System.err.print("READ");
		sioOut.write(I2C_READ_BYTE);
		sioOut.flush();
		data = sioIn.read() & 0xff;
		System.err.print(" data=" + Integer.toHexString(data));
		System.err.print(" NAK");
		sioOut.write(I2C_NAK);
		sioOut.flush();
		System.err.println(" response=" + sioIn.read());
		value |= data;

		sioOut.write(I2C_STOP);
		sioOut.flush();

		System.err.println("value=0x" + Integer.toHexString(value));

		return value;
	}

	public void clearInputStream() throws IOException {
		System.err.println("clearing...");
		int c;
		while ((c = sioIn.read()) != -1) {
			System.err.print((char) c + " ");
		}
	}

	/**
	 * Convert TMP006 register values into an object temperature.
	 * Reference TMP006 data sheet (SBOS518) and TMP006 Users Guide (SBOU107).
	 * 
	 * @param to Sensor voltage register value (16 bit unsigned)
	 * @param ta Die temperature register value (16 bit unsigned)
	 * @return
	 */
	public static double calculateObjectTemperature(int to, int ta) {

		// Constants from TMP006 Users Guide (SBOU107), page 10.
		double a1 = 1.75e-3;
		double a2 = -1.678e-5;
		double b0 = -2.94e-5;
		double b1 = -5.7e-7;
		double b2 = 4.63e-9;
		double c2 = 13.4;
		double Tref = 298.15;

		// Calibration factor - this will vary from device to device.
		// Between 5e-14 and 7e-14. Lifting this value from the 
		// Windows evaluation software. It's not clear if this 
		// value was a result of individual calibration. It might 
		// explain why the software was distributed on a USB stick!
		double S0 = 6.4e-14;

		// Convert 16 bit unsigned register values to 16 bit signed.
		if (to > 0x7fff) {
			to -= 0x10000;
		}
		if (ta > 0x7fff) {
			ta -= 0x10000;
		}

		// Die temperature in K (SBOS518, page 10)
		double Tdie = 273.15 + (double) (ta >> 2) / 32;

		// Sensor voltage register value to voltage (SBOS518, page 8)
		double Vobj = to * 156.25e-9;

		//
		// Given Vobj and Tdie, calculate object temperature. This is explained
		// TMP006 Users Guide (SBOU17) page 10.
		//

		double Tdmr = (Tdie - Tref);

		// Equation 1
		// Sensitivity of the thermopile sensor vs Tdie
		double S = S0 * (1 + a1 * (Tdie - Tref) + a2 * (Tdie - Tref) * (Tdie - Tref));

		// Equation 2
		// Vos an offset voltage that arises because of the slight self-heating of the TMP006,
		// caused by the non-zero thermal resistance of the package and the small operational 
		// power dissipation (1 mW) in the device:
		double Vos = b0 + b1 * (Tdie - Tref) + b2 * (Tdie - Tref) * (Tdie - Tref);

		// Equation 3 
		// Models the Seebeck coefficients of the thermopile and how these coefficients change over
		// temperature.
		double fVobj = (Vobj - Vos) + c2 * (Vobj - Vos) * (Vobj - Vos);

		// Equation 4
		// Relates the radiant transfer of IR energy between the target object and the TMP006 and the
		// conducted heat in the thermopile in the TMP006.
		double Tobj = Math.sqrt(Math.sqrt(Tdie * Tdie * Tdie * Tdie + (fVobj / S)));

		return Tobj;
	}

	public static void main(String[] arg) throws IOException, Exception {
		TMP006 tmp006 = new TMP006();

		tmp006.setDeviceName(arg[0]);
		tmp006.powerOn();

		tmp006.enterI2CMode();

		// Test by reading manuf register
		int manufacturerId = tmp006.readRegister(0xFE);
		if (manufacturerId != 0x5449) {
			System.err.println("Error: expecting 0x5449 in register 0xFE (manufacturer ID) but got "
					+ Integer.toHexString(manufacturerId));
			return;
		}

		int to, ta;
		double Tobj;
		while (true) {
			to = tmp006.readRegister(0x00);
			ta = tmp006.readRegister(0x01);

			Tobj = calculateObjectTemperature(to, ta);
			System.out.println(System.currentTimeMillis()
					+ " " + to + " " + ta
					+ " " + ((double) (ta >> 2) / 32)
					+ " " + (Tobj - 273.15));

			//Thread.sleep(5000);
		}

	}
}
