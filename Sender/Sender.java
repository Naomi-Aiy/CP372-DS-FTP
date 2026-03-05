import java.io.*;
import java.net.*;
import java.util.*;

public class Sender {

    static final int MAX_PAYLOAD = DSPacket.MAX_PAYLOAD_SIZE;

    //config ariables
    static InetAddress receiverIP;
    static int receiverPort;
    static int senderAckPort;
    static int timeout;
    static int windowSize = 1;

    static DatagramSocket sendSocket;
    static DatagramSocket ackSocket;

    static long startTime;

    public static void main(String[] args) throws Exception {

        if (args.length < 5) {
            System.out.println("Usage: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
            return;
        }

        //init variables
        receiverIP = InetAddress.getByName(args[0]);
        receiverPort = Integer.parseInt(args[1]);
        senderAckPort = Integer.parseInt(args[2]);
        String fileName = args[3];
        timeout = Integer.parseInt(args[4]);

        if (args.length == 6) {
            windowSize = Integer.parseInt(args[5]);
        }

        //setup sockets
        sendSocket = new DatagramSocket();
        ackSocket = new DatagramSocket(senderAckPort);
        ackSocket.setSoTimeout(timeout);//setting timeout so receive method doesnt block forever if a packet is lost

        startTime = System.nanoTime();

        handshake(); //creating connection

        if (windowSize == 1) { //transger a file based on its window size
            stopAndWait(fileName);//send one pakcet wait for 1
        } else {
            goBackN(fileName);//send a window off packets all at once
        }

        teardown(); //close connection

        long endTime = System.nanoTime();
        double seconds = (endTime - startTime) / 1e9;
        System.out.println("Total Transmission Time: " + seconds + " seconds");

        sendSocket.close();
        ackSocket.close();
    }

    //sends sot, waits for ack 0, if not retries if ack isnt here within timeout period
    static void handshake() throws Exception {
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        sendPacket(sot);

        while (true) {

            try {
                DSPacket ack = receivePacket();
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == 0) {
                    System.out.println("Handshake complete");
                    return;
                }

            } catch (SocketTimeoutException e) {
                sendPacket(sot); //resend
            }
        }
    }

    //reads, then sends, and stops everything until correct ack arrives
    static void stopAndWait(String fileName) throws Exception {
        File file = new File(fileName);
        if (file.length() == 0) {
            return;
        }

        FileInputStream fis = new FileInputStream(file);
        int seq = 1;
        byte[] buffer = new byte[MAX_PAYLOAD];
        int read;

        while ((read = fis.read(buffer)) != -1) {
            byte[] data = Arrays.copyOf(buffer, read);
            DSPacket packet = new DSPacket(DSPacket.TYPE_DATA, seq, data);

            while (true) {
                sendPacket(packet);
                try {
                    DSPacket ack = receivePacket();
                    //checking if ack matches to the pckt we sent
                    if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == seq) {
                        System.out.println("ACK received: " + seq);
                        break; //move to next chunk
                    }

                } catch (SocketTimeoutException e) {
                    System.out.println("Timeout -> Resend seq " + seq);
                }
            }
            seq = (seq + 1) % 128;
        }
        fis.close();
    }

    //sending multiple packeets no waiting for accs immediately
    static void goBackN(String fileName) throws Exception {
        List<DSPacket> packets = createPackets(fileName);
        //chaso engine shuffling to test receiver
        packets = ChaosEngine.permutePackets(packets);

        int base = 0;
        int nextSeq = 0;
        int timeoutCount = 0;

        while (base < packets.size()) {
            //sending pckets as long as were within the windowsize
            while (nextSeq < base + windowSize && nextSeq < packets.size()) {
                DSPacket packet = packets.get(nextSeq);
                sendPacket(packet);
                System.out.println("Sent packet " + packet.getSeqNum());
                nextSeq++;
            }

            try {
                DSPacket ack = receivePacket();
                int ackSeq = ack.getSeqNum();
                System.out.println("ACK received: " + ackSeq);

                //moving past ack packet
                base = ackSeq + 1;
                timeoutCount = 0;//reset timeout

            } catch (SocketTimeoutException e) {
                timeoutCount++;

                if (timeoutCount >= 3) {
                    System.out.println("Unable to transfer file.");
                    System.exit(0);
                }

                System.out.println("Timeout -> Resending window");

                //resetting next sqqn to base and resnsidng the whole windw 
                for (int i = base; i < nextSeq; i++) {
                    sendPacket(packets.get(i));
                }
            }
        }
    }

    //helper to read file and turn it into list of packets b4 sending
    static List<DSPacket> createPackets(String fileName) throws Exception {
        List<DSPacket> packets = new ArrayList<>();
        FileInputStream fis = new FileInputStream(fileName);

        byte[] buffer = new byte[MAX_PAYLOAD];
        int seq = 1;
        int read;

        while ((read = fis.read(buffer)) != -1) {
            byte[] data = Arrays.copyOf(buffer, read);
            packets.add(new DSPacket(DSPacket.TYPE_DATA, seq, data));
            seq = (seq + 1) % 128;
        }

        fis.close();
        return packets;
    }

    //sends eot and waits for final ack
    static void teardown() throws Exception {
        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, 0, null);
        sendPacket(eot);

        while (true) {

            try {
                DSPacket ack = receivePacket();

                if (ack.getType() == DSPacket.TYPE_ACK) {
                    System.out.println("Connection closed.");
                    return;
                }

            } catch (SocketTimeoutException e) {
                sendPacket(eot);
            }
        }
    }

    //udp send/receive helpers
    static void sendPacket(DSPacket packet) throws Exception {
        byte[] raw = packet.toBytes();
        DatagramPacket dp = new DatagramPacket(raw, raw.length, receiverIP, receiverPort);
        sendSocket.send(dp);
    }

    static DSPacket receivePacket() throws Exception {
        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        ackSocket.receive(dp);

        return new DSPacket(dp.getData());
    }
}