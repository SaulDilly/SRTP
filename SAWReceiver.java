import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.util.Arrays;

public class SAWReceiver {

    private int seq;
    private static final int TIMEOUT = 100;

    public SAWReceiver() {
        this.seq = 0;
    }
    
    /*
     * Escuta conexões de entrada na porta especificada, realizando o handshake de três vias (SYN, SYN+ACK, ACK)
     */ 
    public void listenConnection(int port) {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            socket.setSoTimeout(TIMEOUT);
            byte[] receiveBuffer = new byte[64];

            while (true) {
                // Escuta pacote de SYN
                DatagramPacket incoming = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                Log.writeLine("Aguardando conexão do sender...");
                socket.receive(incoming);
                
                // Se estiver inválido ou não for um SYN, ignora e continua escutando
                SrtpPacket synPacket = SrtpPacket.fromBytes(Arrays.copyOf(incoming.getData(), incoming.getLength()));
                if (synPacket == null || !synPacket.isSyn()) {
                    Log.writeLine("Não recebi um pacote válido.");
                    continue;
                }

                // Cria o pacote SYN+ACK, calcula o CRC32 e monta o datagrama para resposta
                SrtpPacket synAckPacket = PacketFactory.createSynAckPacket(64);
                synAckPacket.calculateCrc32();
                byte[] synAckBytes = synAckPacket.toBytes();
                // Determina o endereço de envio do SYN+ACK com base no pacote recebido
                DatagramPacket synAckDatagram = new DatagramPacket(
                        synAckBytes,
                        synAckBytes.length,
                        incoming.getAddress(),
                        incoming.getPort());

                // Estado intermediário: aguardando ACK final ou o início da transmissão
                while (true) {
                    // Realiza o envio do SYN+ACK
                    Log.writeLine("Enviando pacote SYN+ACK...");
                    socket.send(synAckDatagram);

                    // Aguarda o ACK do sender
                    try {
                        DatagramPacket ackDatagram = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                        Log.writeLine("Aguardando pacote ACK...");
                        socket.receive(ackDatagram);

                        // Trata o pacote recebido
                        SrtpPacket ackPacket = SrtpPacket.fromBytes(Arrays.copyOf(ackDatagram.getData(), ackDatagram.getLength()));
                        if (ackPacket != null && ackPacket.isAck() && !ackPacket.isSyn()) {
                            Log.writeLine("Recebi um pacote válido de ACK.");
                            return;
                        }
                        
                        // Se é pacote válido e é o início da transmissão, considera a conexão estabelecida e parte para o tratamento
                        if (isDataStartPacket(ackPacket)) {
                            Log.writeLine("Recebi um pacote inicial de transmissão, considerando como ACK OK.");
                            return;
                        }
                    } catch (SocketTimeoutException timeoutException) {
                        Log.writeLine("Ocorreu timeout.");
                    }
                }
            }
        } catch (Exception exception) {
            throw new RuntimeException("Falha ao receber a conexão via UDP", exception);
        }
    }
    /* 
     * Verifica se o pacote recebido é um pacote de início de transmissão, ou seja, não é SYN, ACK ou NACK
     */
    private boolean isDataStartPacket(SrtpPacket packet) {
        return packet != null
                && !packet.isSyn()
                && !packet.isAck()
                && !packet.isNack();
    }
    
}
