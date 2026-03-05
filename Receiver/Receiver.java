import java.io.*;
import java.net.*;
import java.util.*;

public class Receiver {

    //network and protocol config variables
    static InetAddress senderIP;
    static int senderAckPort;
    static int dataPort;
    static int RN;

    static DatagramSocket dataSocket;
    static DatagramSocket ackSocket;

    static int ackCounter = 0;

    public static void main(String[] args) throws Exception {

        senderIP = InetAddress.getByName(args[0]);
        senderAckPort = Integer.parseInt(args[1]);
        dataPort = Integer.parseInt(args[2]);
        String outputFile = args[3];
        RN = Integer.parseInt(args[4]);

        dataSocket = new DatagramSocket(dataPort);
        ackSocket = new DatagramSocket();

        FileOutputStream fos = new FileOutputStream(outputFile);

        int expectedSeq = 1;

        while (true) { //waiting for packets and then process them

            DSPacket packet = receivePacket();//block and wait for udp packets

            if (packet.getType() == DSPacket.TYPE_SOT) { //if the sender is beginning a file
                sendAck(0);//ack
                System.out.println("SOT received");

            } else if (packet.getType() == DSPacket.TYPE_DATA) { //acc content in file
                int seq = packet.getSeqNum();

                if (seq == expectedSeq) { //if packet sqn number matches what were waiting for, save data to file
                    fos.write(packet.getPayload());
                    sendAck(seq);//then ack i
                    expectedSeq = (expectedSeq + 1) % 128;//go to next sqn number

                } else { //if out of order, remove it and tell sender the last succesful number
                    sendAck((expectedSeq - 1 + 128) % 128);
                }

            } else if (packet.getType() == DSPacket.TYPE_EOT) {//ending transfer
                sendAck(packet.getSeqNum());
                break;
            }
        }

        fos.close();
        dataSocket.close();
        ackSocket.close();

        System.out.println("File received.");
    }

    //listens on the dataport for a raw udp packet, converts to dsppacket obj
    static DSPacket receivePacket() throws Exception {
        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);

        dataSocket.receive(dp); //blocking program until packet arrives

        return new DSPacket(dp.getData());
    }

    //sends an ack back to the sender, uses chasoengine to simulate a lossy network by dropping acks
    static void sendAck(int seq) throws Exception {

        ackCounter++;

        //prenteind network inst viable
        if (ChaosEngine.shouldDrop(ackCounter, RN)) {
            System.out.println("Dropping ACK " + seq);
            return;//exits without acc sending the packet
        }

        //creating ack packet
        DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, seq, null);
        byte[] raw = ack.toBytes();

        //sending over network via udp
        DatagramPacket dp = new DatagramPacket(raw, raw.length, senderIP, senderAckPort);
        ackSocket.send(dp);
    }
}