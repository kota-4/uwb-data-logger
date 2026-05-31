package application;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

public class SerialDataProcessor implements SerialPortDataListener {

    private Writer writer;
    private String format;

    public SerialDataProcessor(File file, String format) throws IOException {
        this.writer = new FileWriter(file);
        this.format = format;

        // Write headers for CSV or JSON
        if ("csv".equals(format)) {
            writer.write("saddr,aaddr,taddr,rng_rng,fsl,rsl,rng_raw,rpc,CIR\n");
        } else if ("json".equals(format)) {
            writer.write("{ \"data\": [\n");
        }
    }

    @Override
    public int getListeningEvents() {
        return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
    }

    @Override
    public void serialEvent(SerialPortEvent event) {
        if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
            return;
        }

        byte[] newData = new byte[event.getSerialPort().bytesAvailable()];
        event.getSerialPort().readBytes(newData, newData.length);

        try {
            processReceivedData(newData);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void processReceivedData(byte[] data) throws IOException {
        long saddr = bytesToLong(data, 0);
        long aaddr = bytesToLong(data, 8);
        long taddr = bytesToLong(data, 16);
        int rng_rng = bytesToInt(data, 24);
        int fsl = bytesToInt(data, 28);
        int rsl = bytesToInt(data, 32);
        int rng_raw = bytesToInt(data, 36);
        int rpc = bytesToInt(data, 40);
        String cir = bytesToHex(data, 44, data.length - 44);

        if ("csv".equals(format)) {
            writer.write(String.format("%d,%d,%d,%d,%d,%d,%d,%d,%s\n", saddr, aaddr, taddr, rng_rng, fsl, rsl, rng_raw, rpc, cir));
        } else if ("json".equals(format)) {
            writer.write(String.format("{\"saddr\": %d, \"aaddr\": %d, \"taddr\": %d, \"rng_rng\": %d, \"fsl\": %d, \"rsl\": %d, \"rng_raw\": %d, \"rpc\": %d, \"CIR\": \"%s\"},\n",
                    saddr, aaddr, taddr, rng_rng, fsl, rsl, rng_raw, rpc, cir));
        }
    }

    private long bytesToLong(byte[] bytes, int start) {
        return ((long) bytes[start] & 0xFF) |
               ((long) bytes[start + 1] & 0xFF) << 8 |
               ((long) bytes[start + 2] & 0xFF) << 16 |
               ((long) bytes[start + 3] & 0xFF) << 24 |
               ((long) bytes[start + 4] & 0xFF) << 32 |
               ((long) bytes[start + 5] & 0xFF) << 40 |
               ((long) bytes[start + 6] & 0xFF) << 48 |
               ((long) bytes[start + 7] & 0xFF) << 56;
    }

    private int bytesToInt(byte[] bytes, int start) {
        return (bytes[start] & 0xFF) |
               (bytes[start + 1] & 0xFF) << 8 |
               (bytes[start + 2] & 0xFF) << 16 |
               (bytes[start + 3] & 0xFF) << 24;
    }

    private String bytesToHex(byte[] bytes, int start, int length) {
        StringBuilder hexString = new StringBuilder();
        for (int i = start; i < start + length; i += 8) {
            int realPart = bytesToInt(bytes, i);
            int imaginaryPart = bytesToInt(bytes, i + 4);
            hexString.append(String.format("(%d, %d)", realPart, imaginaryPart));
        }
        return hexString.toString();
    }

    public void closeWriter() {
        try {
            if ("json".equals(format)) {
                writer.write("]}\n");
            }
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
