import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;

public class SAWSender {
    private int seq;
    private static final int TIMEOUT = 100;

    public SAWSender() {
        this.seq = 0;
    }

    /*
     * Estabelece a conexão com o host especificado, realizando o handshake de três vias (SYN, SYN+ACK, ACK)
     */ 
    public void establishConnection(String host, int port) {
        // Monta o pacote SYN e calcula o CRC32 antes de enviar
        SrtpPacket synPacket = PacketFactory.createSynPacket(64);
        synPacket.calculateCrc32();
        byte[] packetBytes = synPacket.toBytes();

        try (DatagramSocket socket = new DatagramSocket()) {
            // Monta o datagrama com base no pacote convertido para bytes
            socket.setSoTimeout(TIMEOUT);
            InetAddress address = InetAddress.getByName(host);
            DatagramPacket datagram = new DatagramPacket(packetBytes, packetBytes.length, address, port);
            byte[] receiveBuffer = new byte[64];

            while (true) {
                // Envia SYN
                socket.send(datagram);

                try {
                    // Aguarda o SYN+ACK
                    DatagramPacket response = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    socket.receive(response);

                    SrtpPacket responsePacket = SrtpPacket.fromBytes(Arrays.copyOf(response.getData(), response.getLength()));
                    // Se é um pacote válido e é um SYN+ACK, sai do loop
                    if (responsePacket != null && responsePacket.isSyn() && responsePacket.isAck()) {
                        break;
                    }
                } catch (SocketTimeoutException timeoutException) {
                    // Timeout expirado: retransmite o SYN.
                }
            }

            // Envia o ACK final para completar o handshake
            SrtpPacket ackPacket = PacketFactory.createAckPacket();
            ackPacket.calculateCrc32();
            byte[] ackBytes = ackPacket.toBytes();
            DatagramPacket ackDatagram = new DatagramPacket(ackBytes, ackBytes.length, address, port);
            socket.send(ackDatagram);
            System.out.println("Enviado ACK final");
        } catch (Exception exception) {
            throw new RuntimeException("Falha ao enviar o pacote SYN via UDP", exception);
        }
    }
    
}
