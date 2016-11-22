package au.com.jcloud;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.log4j.Logger;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

import jssc.SerialPortException;

public class BusPirate implements SerialPortDataListener {

	//BusPirate Constants = http://dangerousprototypes.com/docs/Bitbang
	public static final byte BP_RESET = 0x00;
	public static final byte BP_MODE_SPI = 0x01;
	public static final byte BP_MODE_I2C = 0x02;
	public static final byte BP_MODE_UART = 0x03;
	public static final byte BP_RESET_HARD = 0x15;

	//I2C Constants = http://dangerousprototypes.com/docs/I2C_(binary)
	public static final byte I2C_VERSION_CHECK = 0x01;
	public static final byte I2C_START = 0x02;
	public static final byte I2C_STOP = 0x03;
	public static final byte I2C_READ_BYTE = 0x04;
	public static final byte I2C_ACK = 0x06;
	public static final byte I2C_NAK = 0x07;
	public static final byte I2C_POWER_ON = 0x48;
	public static final byte I2C_POWER_OFF = 0x40;
	public static final byte I2C_FREQ_5HZ = 0x60;
	public static final byte I2C_FREQ_50HZ = 0x61;
	public static final byte I2C_FREQ_100HZ = 0x62;
	public static final byte I2C_FREQ_400HZ = 0x63;

	public static final String RESPONSE_BBIO1 = "BBIO1";
	public static final String RESPONSE_I2C1 = "I2C1";

	// SRF10 - http://www.robotshop.com/en/devantech-srf10-ultrasonic-range-finder.html - http://www.robot-electronics.co.uk/htm/srf10tech.htm
	// [ 0xe0 0x00 0x51 %:68 [ 0xe1 r:3 ]
	public static final int SRF10_WRITE_ADDRESS_DEFAULT = 0xE0;
	public static final int SRF10_READ_ADDRESS_DEFAULT = 0xE1;
	public static final int SRF10_RANGE_IN_INCHES = 0x50;
	public static final int SRF10_RANGE_IN_CM = 0x51;
	public static final int SRF10_RANGE_IN_MS = 0x52;
	public static final int[] SRF10_ADDRESS_CHANGE_SEQUENCE = new int[] { 0xA0, 0xAA, 0xA5 };

	private static final Logger LOG = Logger.getLogger(BusPirate.class);

	private SerialPort _port;

	@Override
	public void serialEvent(SerialPortEvent serialEvent) {
		LOG.info("serialEvent occurred=" + serialEvent);
		if (serialEvent.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
			return;
		}
		try {
			String data = null;
			if (_port.bytesAvailable() > 0) {
				LOG.info("read length=" + _port.bytesAvailable());
				byte[] newData = new byte[_port.bytesAvailable()];
				int numRead = _port.readBytes(newData, newData.length);
				data = new String(newData);
				System.out.println("READ: (" + numRead + " bytes) " + data);
			}
		} catch (Exception e) {
			LOG.error("serialEvent: " + e, e);
		}
	}

	@Override
	public int getListeningEvents() {
		return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
	}

	public SerialPort setupCommPort(String sioDevName, boolean useListener) throws IOException {

		SerialPort port = null;
		SerialPort[] ports = SerialPort.getCommPorts();
		for (SerialPort comPort : ports) {
			LOG.info("port=" + comPort.getSystemPortName() + " desc=" + comPort.getDescriptivePortName());
			if (comPort.getSystemPortName().contains(sioDevName)) {
				try {
					port = comPort;
					port.openPort();
					break;
				} catch (Exception e) {
					LOG.error(e, e);
				}
			}
		}

		if (port == null) {
			throw new IOException("error: serial port " + sioDevName + " not found.");
		}

		/*
		 * Setup serial port
		 */
		int rate = 115200;
		int databits = 8;
		int stopbits = SerialPort.ONE_STOP_BIT;
		int parity = SerialPort.NO_PARITY;

		port.setComPortParameters(rate, databits, stopbits, parity);
		port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
		LOG.info("Connected to " + port.getSystemPortName());

		if (useListener) {
			port.addDataListener(this); // Lets try BLOCKING MODE FIRST			
		}
		else {
			InputStream sioIn = port.getInputStream();
			OutputStream sioOut = port.getOutputStream();

			if (sioIn == null) {
				throw new IOException("error: serial port " + sioDevName + " busy. Cannot create inputStream.");
			}
			if (sioOut == null) {
				throw new IOException("error: serial port " + sioDevName + " busy. Cannot create outputStream.");
			}

			port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 200, 0);
		}

		_port = port;
		return port;
	}

	/**
	 * Set the Bus Pirate in Bitbang mode.
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws SerialPortException
	 */
	public void enterBitbangMode(SerialPort port) throws IOException, InterruptedException {
		int i;

		InputStream sioIn = port.getInputStream();
		OutputStream sioOut = port.getOutputStream();

		// First test to see if already in I2C mode
		clearInputStream(port);

		if (verifyI2CMode(sioIn, sioOut)) {
			// Already in I2C mode
			LOG.debug("Already in I2C mode I2C");
			return;
		}

		LOG.debug("Not already in I2C mode. Attempting to enter.");

		// Send commands to Bus Pirate to first enter BitBang mode and then
		// I2C binary mode. Documentation recommends the following:
		// (quote)
		// The Bus Pirate user terminal could be stuck in a configuration menu 
		// when your program attempts to enter binary mode. One way to ensure 
		// that you’re at the command line is to send <enter> at least 10 times, 
		// and then send ‘#’ to reset. Next, send 0×00 to the command line 20+ 
		// times until you get the BBIOx version string.
		// (end quote)

		// Back to command line
		LOG.debug("RETURN x 10");
		for (i = 0; i < 10; i++) {
			sioOut.write(13 & 0xff); // RETURN
		}
		sioOut.flush();
		clearInputStream(port);

		// Reset
		LOG.debug("RESET");
		writeAndFlush(sioOut, '#');
		clearInputStream(port);

		// Enter BigBang mode
		LOG.debug("Enter BBIO");
		for (i = 0; i < 20; i++) {
			sioOut.write(BP_RESET & 0xff);
		}
		sioOut.flush();

		int count = 0;
		String data = "";
		while (!data.equals(RESPONSE_BBIO1)) {
			if (port.bytesAvailable() > 0) {
				byte[] newData = new byte[port.bytesAvailable()];
				int numRead = port.readBytes(newData, newData.length);
				data = new String(newData);
				LOG.debug("READ: (" + numRead + " bytes) " + data);
			}
			// empty loop
			LOG.debug(".");
			System.err.flush();
			Thread.sleep(100);
			if (++count % 10 == 0) {
				writeAndFlush(sioOut, BP_RESET);
			}
		}
		LOG.debug("In BBIO1 now");
		Thread.sleep(100);
		clearInputStream(port);
	}

	public void enterI2CMode(SerialPort port) throws IOException, InterruptedException {
		enterBitbangMode(port);

		OutputStream sioOut = port.getOutputStream();

		// Enter I2C mode
		LOG.debug("Enter I2C mode");
		writeAndFlush(sioOut, BP_MODE_I2C);
		clearInputStream(port);

		// Get I2C version
		LOG.debug("Get I2C version");
		writeAndFlush(sioOut, I2C_VERSION_CHECK);

		int count = 0;
		String i2c = "";
		while (!i2c.equals(RESPONSE_I2C1)) {
			if (port.bytesAvailable() > 0) {
				byte[] newData = new byte[port.bytesAvailable()];
				int numRead = port.readBytes(newData, newData.length);
				i2c = new String(newData);
				LOG.debug("READ: (" + numRead + " bytes) " + i2c);
			}
			// empty loop
			LOG.debug(".");
			System.err.flush();
			Thread.sleep(100);
			if (++count % 10 == 0) {
				writeAndFlush(sioOut, I2C_VERSION_CHECK);
			}
		}
		LOG.debug("In I2C now");
	}

	/**
	 * Send Bus Pirate command to apply power to the 3.3V line
	 * 
	 * @throws IOException
	 * @throws SerialPortException
	 */
	public void powerOn(SerialPort port) throws IOException {
		InputStream sioIn = port.getInputStream();
		OutputStream sioOut = port.getOutputStream();

		// 0100wxyz – Configure peripherals w=power, x=pullups, y=AUX, z=CS
		// Write 0b0100 1000
		LOG.debug("POWER ON");
		sioOut.write(I2C_POWER_ON & 0xff);

		//011000xx - Set I2C speed, 3=~400kHz, 2=~100kHz, 1=~50kHz, 0=~5kHz (updated in v4.2 firmware)
		LOG.debug("SPEED 100kHz");
		sioOut.write(I2C_FREQ_100HZ & 0xff);
		LOG.debug("response=" + sioIn.read());
	}

	/**
	 * Send I2C sequence to read a SRF10 distance sensor.
	 * 
	 * @param registerAddress
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws SerialPortException
	 */
	public int readSRF10(SerialPort port) throws IOException, InterruptedException {
		int writePosition = 0x00;
		int writeData = SRF10_RANGE_IN_CM;
		int[] readOutput = new int[3];
		int delay = 65;
		writeAndReadI2C(port, SRF10_WRITE_ADDRESS_DEFAULT, SRF10_READ_ADDRESS_DEFAULT, writePosition, writeData, readOutput, delay);

		int result;
		result = readOutput[1] << 8;
		result |= readOutput[2];
		return result;
	}

	/**
	 * Send I2C sequence to read a BMP180 distance sensor.
	 * 
	 * [ 0xEE 0xF4 0x2E %:5 [ 0xEF r:4 ]
	 * 
	 * @param registerAddress
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws SerialPortException
	 */
	public int readBMP180(SerialPort port) throws IOException, InterruptedException {
		int writePosition = 0xF4;
		int writeData = 0x2E;
		int[] readOutput = new int[4];
		int BMP180_WRITE_ADDRESS_DEFAULT = 0xEE;
		int BMP180_READ_ADDRESS_DEFAULT = 0xEF;
		int delay = 5;
		writeAndReadI2C(port, BMP180_WRITE_ADDRESS_DEFAULT, BMP180_READ_ADDRESS_DEFAULT, writePosition, writeData, readOutput, delay);

		int result;
//		result = readOutput[2] << 8;
		result = readOutput[3];
		return result;
	}

	/**
	 * Send I2C sequence (registerPosition, writeData) to writeAddress, and then read readAddress into the output data.
	 * 
	 * @param port
	 * @param writeAddress
	 * @param readAddress
	 * @param writePosition
	 * @param writeData
	 * @param readOutput
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws SerialPortException
	 */
	public void writeAndReadI2C(SerialPort port, int writeAddress, int readAddress, int writePosition, int writeData, int[] readOutput, int delay) throws IOException, InterruptedException {
		InputStream sioIn = port.getInputStream();
		OutputStream sioOut = port.getOutputStream();

		writeAndFlush(sioOut, I2C_START);
		LOG.debug("I2C START BIT " + (sioIn.read() == 0 ? "ACK" : "NAK"));

		writeBytes(sioIn, sioOut, new int[] { writeAddress, writePosition, writeData });

		Thread.sleep(delay); // Delay >65ms

		clearInputStream(port);

		writeAndFlush(sioOut, I2C_START);
		LOG.debug("I2C START BIT " + (sioIn.read() == 0 ? "ACK" : "NAK"));

		writeByte(sioIn, sioOut, readAddress);

		readBytes(sioIn, sioOut, readOutput);
		LOG.debug("readOutput=" + asHex(readOutput));

		writeAndFlush(sioOut, I2C_STOP);
	}

	private boolean verifyI2CMode(InputStream sioIn, OutputStream sioOut) throws IOException {
		// Test I2C mode
		writeAndFlush(sioOut, I2C_VERSION_CHECK);
		if (!(sioIn.read() == 'I' && sioIn.read() == '2' && sioIn.read() == 'C' && sioIn.read() == '1')) {
			return false;
		}
		return true;
	}

	private void writeAndFlush(OutputStream sioOut, byte data) throws IOException {
		sioOut.write(data & 0xff);
		sioOut.flush();
	}

	private void writeAndFlush(OutputStream sioOut, char data) throws IOException {
		sioOut.write(data & 0xff);
		sioOut.flush();
	}

	private void writeAndFlush(OutputStream sioOut, int data) throws IOException {
		sioOut.write(data & 0xff);
		sioOut.flush();
	}

	private void writeByte(InputStream sioIn, OutputStream sioOut, int data) throws IOException {
		sioOut.write(0x10); // write one byte
		sioOut.write(data);
		sioOut.flush();
		LOG.debug("WRITE: " + asHex(data) + " " + (sioIn.read() == 0 ? "ACK" : "NAK"));
	}

	private void writeBytes(InputStream sioIn, OutputStream sioOut, int[] dataArray) throws IOException {
		int len = dataArray.length;
		if (len > 16) {
			throw new IOException("write length>16 not supported!");
		}
		int lenValue = 0x10 + (len - 1);
		sioOut.write(lenValue); // write three byte. Note 0x10=1byte, 0x11=2bytes, etc
		for (int i = 0; i < len; i++) {
			int data = dataArray[i];
			sioOut.write(data);
			LOG.debug("WRITE: " + asHex(data) + " " + (sioIn.read() == 0 ? "ACK" : "NAK"));
		}
		sioOut.flush();
	}

	private void readBytes(InputStream sioIn, OutputStream sioOut, int[] output) throws IOException {
		if (output.length > 0) {
			for (int i = 0; i < output.length; i++) {
				if (i == output.length - 1) {
					output[i] = readByte(sioIn, sioOut, true); // Last one
				}
				else {
					output[i] = readByte(sioIn, sioOut, false);
				}
			}
		}
	}

	private int readByte(InputStream sioIn, OutputStream sioOut, boolean isEnd) throws IOException {
		int data;
		writeAndFlush(sioOut, I2C_READ_BYTE);
		int response = sioIn.read() & 0xff;
		LOG.debug("READ: response=" + asHex(response) + " " + (isEnd ? "NAK" : "ACK"));
		if (isEnd) {
			sioOut.write(I2C_NAK);
		}
		else {
			sioOut.write(I2C_ACK);
		}
		data = sioIn.read();
		LOG.debug("  data=" + asHex(data));
		return data;
	}

	public void clearInputStream(SerialPort port) throws IOException, InterruptedException {
		LOG.debug("clearing...");
		int i = 0;
		while (++i < 5) {
			if (port.bytesAvailable() > 0) {
				byte[] newData = new byte[port.bytesAvailable()];
				int numRead = port.readBytes(newData, newData.length);
				String data = new String(newData);
				LOG.debug("CLEAR: (" + numRead + " bytes) " + data);
				Thread.sleep(20);
			}
		}
	}

	private static String asHex(int value) {
		return "0x" + String.format("%02X", value);
	}

	private static String asHex(int[] valueArray) {
		String result = "";
		for (int value : valueArray) {
			if (result.length() > 0) {
				result += " ";
			}
			result += "0x" + String.format("%02X", value);
		}
		return result;
	}

	public static void main(String[] arg) throws IOException, Exception {
		BusPirate pirate = new BusPirate();
		SerialPort port = null;
		try {
			String portName = "ttyUSB"; // Linux = "/dev/ttyUSB0";
			if (System.getProperty("os.name").toLowerCase().startsWith("win")) {
				portName = "COM"; // Windows = "COM4"
			}
			port = pirate.setupCommPort(portName, false); // (arg[0], false);
			if (port == null) {
				throw new Exception("Port " + portName + " not found. Cannot continue");
			}
			pirate.enterI2CMode(port);
			pirate.powerOn(port);

			// Test by reading manuf register
			int dist = 07;
			int temp;
			int count = 0;

			pirate.clearInputStream(port);

			if (!pirate.verifyI2CMode(port.getInputStream(), port.getOutputStream())) {
				throw new IOException("Not in I2C mode");
			}

			while (dist > 06 && ++count < 100) {
				dist = pirate.readSRF10(port);
				LOG.info("dist=" + dist);
//				temp = pirate.readBMP180(port);
//				LOG.info("temp=" + temp);
			}
		} catch (Exception e) {
			LOG.error(e, e);
		} finally {
			if (pirate != null && port != null) {
				if (port.isOpen()) {
					port.closePort();
				}
			}
		}
	}
}
